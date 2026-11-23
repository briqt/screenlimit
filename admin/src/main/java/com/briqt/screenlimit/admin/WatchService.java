package com.briqt.screenlimit.admin;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 后台实时审批提醒服务。
 * 通过 SSE 长连接（{@code GET /api/admin/events}）秒级接收新解锁申请并弹出短信级强提醒通知，
 * 支持通知栏一键批准/拒绝、切网/亮屏秒重连、老版本服务端 404 自动降级轮询。
 */
public class WatchService extends Service {
    private static final int ONGOING_ID = 1;
    private static final int TEST_NOTIF_ID = 2;

    public static final String CH_ONGOING = "watch";
    public static final String CH_ALERT = "approval_alert_v2";
    private static final String LEGACY_CH_ALERT = "watch-alert";

    private static final long[] BACKOFF_MS = {2_000L, 5_000L, 10_000L, 20_000L};

    public interface SnapshotListener {
        void onSnapshot(JSONArray reqs, int pendingCount);
        default void onUnauthorized() {}
    }

    static volatile WatchService INSTANCE;
    private static volatile SnapshotListener snapshotListener;

    private final Handler h = new Handler(Looper.getMainLooper());
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Object waitLock = new Object();
    private boolean reconnectNow;
    private Thread sseThread;
    private volatile HttpURLConnection activeConn;
    private volatile boolean streamConnected;
    private volatile long lastAliveElapsed;
    private volatile long connectAttemptAt;
    private volatile long lastNetworkHandle;

    private ConnectivityManager cm;
    private boolean netCallbackRegistered;
    private boolean screenReceiverRegistered;

    public static void setSnapshotListener(SnapshotListener l) {
        snapshotListener = l;
    }

    public static void sync(Context c) {
        Context app = c.getApplicationContext();
        ensureChannels(app);
        if (Session.watch(app) && !Session.token(app).isEmpty()) {
            AdminReceiver.scheduleWatchdog(app, AdminReceiver.WATCHDOG_INTERVAL_MS);
            WatchService ws = INSTANCE;
            if (ws != null) {
                ws.checkHealthAndHeal();
            } else {
                startServiceSafe(app);
            }
        } else {
            AdminReceiver.cancelWatchdog(app);
            try {
                app.stopService(new Intent(app, WatchService.class));
            } catch (Exception ignored) {}
        }
    }

    static void startServiceSafe(Context c) {
        try {
            c.startForegroundService(new Intent(c, WatchService.class));
        } catch (Exception ignored) {}
    }

    /** 初始化并分层双通知渠道；清理旧版低优先级提醒渠道。 */
    public static void ensureChannels(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        nm.deleteNotificationChannel(LEGACY_CH_ALERT);

        NotificationChannel low = new NotificationChannel(
                CH_ONGOING, "后台连接保持", NotificationManager.IMPORTANCE_LOW);
        low.setDescription("维持与管理服务器的实时连接；单独关闭此项不影响新申请弹窗");
        low.setShowBadge(false);
        low.enableLights(false);
        low.enableVibration(false);
        low.setSound(null, null);
        low.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
        nm.createNotificationChannel(low);

        NotificationChannel alert = new NotificationChannel(
                CH_ALERT, "新的授权申请", NotificationManager.IMPORTANCE_HIGH);
        alert.setDescription("收到新的解锁申请时横幅弹窗并响铃震动");
        alert.setShowBadge(true);
        alert.enableVibration(true);
        alert.setVibrationPattern(new long[]{0, 220, 120, 220});
        alert.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        Uri soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        alert.setSound(soundUri, attrs);
        nm.createNotificationChannel(alert);
    }

    private final ConnectivityManager.NetworkCallback netCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(Network network) {
            long handle = network.getNetworkHandle();
            long prev = lastNetworkHandle;
            lastNetworkHandle = handle;
            if (prev == 0L) {
                if (!streamConnected) kickReconnect(false);
            } else if (prev != handle) {
                kickReconnect(true);
            }
        }

        @Override
        public void onLost(Network network) {
            if (network.getNetworkHandle() == lastNetworkHandle) {
                lastNetworkHandle = -1L;
                abortActiveConn();
            }
        }
    };

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String act = intent.getAction();
            if (Intent.ACTION_SCREEN_ON.equals(act) || Intent.ACTION_USER_PRESENT.equals(act)) {
                checkHealthAndHeal();
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        INSTANCE = this;
        ensureChannels(this);

        cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm != null) {
            try {
                cm.registerDefaultNetworkCallback(netCallback);
                netCallbackRegistered = true;
            } catch (Exception ignored) {}
        }

        IntentFilter sf = new IntentFilter(Intent.ACTION_SCREEN_ON);
        sf.addAction(Intent.ACTION_USER_PRESENT);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(screenReceiver, sf, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(screenReceiver, sf);
            }
            screenReceiverRegistered = true;
        } catch (Exception ignored) {}
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 铁律：常驻通知仅在 startForeground 时发送这一次，运行期间绝不重复 notify(ONGOING_ID, ...)
        Notification n = ongoing();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(ONGOING_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(ONGOING_ID, n);
        }

        if (!Session.watch(this) || Session.token(this).isEmpty()) {
            AdminReceiver.cancelWatchdog(this);
            stopMe();
            return START_NOT_STICKY;
        }

        AdminReceiver.scheduleWatchdog(this, AdminReceiver.WATCHDOG_INTERVAL_MS);
        startLoopIfNeeded();
        return START_STICKY;
    }

    @Override
    public void onTimeout(int startId, int fgsType) {
        if (Session.watch(this) && !Session.token(this).isEmpty()) {
            AdminReceiver.scheduleWatchdog(this, 5_000L);
        }
        stopMe();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        if (Session.watch(this) && !Session.token(this).isEmpty()) {
            AdminReceiver.scheduleWatchdog(this, 2_000L);
            startServiceSafe(this);
        }
    }

    @Override
    public void onDestroy() {
        if (INSTANCE == this) INSTANCE = null;
        running.set(false);
        kickReconnect(true);
        if (netCallbackRegistered && cm != null) {
            try { cm.unregisterNetworkCallback(netCallback); } catch (Exception ignored) {}
            netCallbackRegistered = false;
        }
        if (screenReceiverRegistered) {
            try { unregisterReceiver(screenReceiver); } catch (Exception ignored) {}
            screenReceiverRegistered = false;
        }
        h.removeCallbacksAndMessages(null);
        if (Session.watch(this) && !Session.token(this).isEmpty()) {
            AdminReceiver.scheduleWatchdog(this, 15_000L);
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startLoopIfNeeded() {
        if (running.compareAndSet(false, true)) {
            sseThread = new Thread(this::sseLoop, "admin-sse");
            sseThread.start();
        } else {
            checkHealthAndHeal();
        }
    }

    /** 亮屏或巡检时确认连接健康：未连上或距上次收到数据超 55 秒则打断重连。 */
    void checkHealthAndHeal() {
        long now = SystemClock.elapsedRealtime();
        if (!streamConnected) {
            boolean connectingFresh = connectAttemptAt > 0L && (now - connectAttemptAt) < 10_000L;
            kickReconnect(!connectingFresh);
            return;
        }
        if (lastAliveElapsed == 0L || (now - lastAliveElapsed) > 55_000L) {
            kickReconnect(true);
        }
    }

    void kickReconnect(boolean abortCurrent) {
        synchronized (waitLock) {
            reconnectNow = true;
            waitLock.notifyAll();
        }
        if (abortCurrent) {
            abortActiveConn();
        }
    }

    private void abortActiveConn() {
        final HttpURLConnection c = activeConn;
        if (c != null) {
            new Thread(() -> {
                try { c.disconnect(); } catch (Throwable ignored) {}
            }, "sse-abort").start();
        }
    }

    private void sseLoop() {
        int backoffIdx = 0;
        while (running.get()) {
            if (!Session.watch(this) || Session.token(this).isEmpty()) {
                h.post(this::stopMe);
                return;
            }
            synchronized (waitLock) {
                reconnectNow = false;
            }
            HttpURLConnection conn = null;
            long connStart = SystemClock.elapsedRealtime();
            connectAttemptAt = connStart;
            long sleepMs = -1L;
            try {
                conn = Api.openEvents(this);
                activeConn = conn;
                if (!running.get()) {
                    conn.disconnect();
                    break;
                }
                int code = conn.getResponseCode();
                if (code == 200) {
                    streamConnected = true;
                    lastAliveElapsed = SystemClock.elapsedRealtime();
                    try (BufferedReader br = new BufferedReader(
                            new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while (running.get() && (line = br.readLine()) != null) {
                            long now = SystemClock.elapsedRealtime();
                            lastAliveElapsed = now;
                            if (now - connStart >= 15_000L) {
                                backoffIdx = 0;
                            }
                            if (line.startsWith(":")) {
                                continue;
                            }
                            if (line.startsWith("data:")) {
                                String payload = line.substring(5).trim();
                                if (!payload.isEmpty()) {
                                    dispatchPayloadWithWakeLock(payload);
                                }
                            }
                        }
                    } finally {
                        streamConnected = false;
                    }
                    if (SystemClock.elapsedRealtime() - connStart >= 15_000L) {
                        backoffIdx = 0;
                    }
                } else if (code == 401) {
                    h.post(this::handleUnauthorized);
                    return;
                } else if (code == 404) {
                    // 兼容尚未升级的老服务端：降级调一次 Api.requests 处理快照并休眠 30 秒重试
                    conn.disconnect();
                    activeConn = null;
                    conn = null;
                    try {
                        JSONObject j = Api.requests(this);
                        lastAliveElapsed = SystemClock.elapsedRealtime();
                        dispatchReqsWithWakeLock(j.optJSONArray("reqs"));
                        backoffIdx = 0;
                        sleepMs = 30_000L;
                    } catch (Api.Err e) {
                        if (e.code == 401) {
                            h.post(this::handleUnauthorized);
                            return;
                        }
                    }
                }
            } catch (Api.Err e) {
                if (e.code == 401) {
                    h.post(this::handleUnauthorized);
                    return;
                }
            } catch (Throwable ignored) {
                // 网络中断、读超时或主动 disconnect 打断
            } finally {
                streamConnected = false;
                if (activeConn == conn) activeConn = null;
                if (conn != null) {
                    try { conn.disconnect(); } catch (Throwable ignored) {}
                }
            }

            if (!running.get()) break;
            if (sleepMs < 0L) {
                sleepMs = BACKOFF_MS[Math.min(backoffIdx, BACKOFF_MS.length - 1)];
                backoffIdx = Math.min(backoffIdx + 1, BACKOFF_MS.length - 1);
            }
            synchronized (waitLock) {
                if (!running.get()) break;
                if (reconnectNow) {
                    reconnectNow = false;
                    backoffIdx = 0;
                    continue;
                }
                try {
                    waitLock.wait(sleepMs);
                } catch (InterruptedException ignored) {}
                if (reconnectNow) {
                    reconnectNow = false;
                    backoffIdx = 0;
                }
            }
        }
    }

    private void dispatchPayloadWithWakeLock(String payload) {
        PowerManager.WakeLock wl = newShortWakeLock(this, "screenlimit:admin-sse");
        if (wl != null) wl.acquire(5_000L);
        boolean handedOff = false;
        try {
            final JSONArray reqs = parseSnapshotReqs(payload);
            if (reqs != null) {
                handedOff = h.post(() -> {
                    try {
                        onSnapshot(reqs);
                    } finally {
                        safeRelease(wl);
                    }
                });
            }
        } catch (Exception ignored) {
        } finally {
            if (!handedOff) safeRelease(wl);
        }
    }

    private void dispatchReqsWithWakeLock(JSONArray reqs) {
        PowerManager.WakeLock wl = newShortWakeLock(this, "screenlimit:admin-poll");
        if (wl != null) wl.acquire(5_000L);
        final JSONArray arr = reqs == null ? new JSONArray() : reqs;
        boolean handedOff = false;
        try {
            handedOff = h.post(() -> {
                try {
                    onSnapshot(arr);
                } finally {
                    safeRelease(wl);
                }
            });
        } finally {
            if (!handedOff) safeRelease(wl);
        }
    }

    private static JSONArray parseSnapshotReqs(String payload) throws Exception {
        String s = payload.trim();
        if (s.isEmpty()) return null;
        if (s.startsWith("[")) return new JSONArray(s);
        JSONObject j = new JSONObject(s);
        if (j.has("reqs")) {
            JSONArray arr = j.optJSONArray("reqs");
            return arr != null ? arr : new JSONArray();
        }
        return null;
    }

    /** 处理全量快照：新 pending 立即强提醒，已处理/已过期的旧通知立即撤销，并实时回调前台页面。 */
    private void onSnapshot(JSONArray reqs) {
        if (reqs == null) reqs = new JSONArray();
        Set<String> notified = Session.notifiedIds(this);
        Set<String> currentPending = new HashSet<>();
        List<JSONObject> fresh = new ArrayList<>();
        int pendingCount = 0;

        for (int i = 0; i < reqs.length(); i++) {
            JSONObject o = reqs.optJSONObject(i);
            if (o == null || !"pending".equals(o.optString("state"))) continue;
            String id = o.optString("id");
            if (id.isEmpty()) continue;
            pendingCount++;
            currentPending.add(id);
            if (!notified.contains(id)) {
                fresh.add(o);
            }
        }

        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        boolean changed = false;
        for (String oldId : new HashSet<>(notified)) {
            if (!currentPending.contains(oldId)) {
                notified.remove(oldId);
                changed = true;
                if (nm != null) nm.cancel(notifIdFor(oldId));
            }
        }

        for (JSONObject o : fresh) {
            String id = o.optString("id");
            notifyNew(o, pendingCount);
            notified.add(id);
            changed = true;
        }

        if (changed) {
            Session.saveNotifiedIds(this, notified);
        }

        SnapshotListener l = snapshotListener;
        if (l != null) {
            l.onSnapshot(reqs, pendingCount);
        }
    }

    private void notifyNew(JSONObject o, int pendingCount) {
        String reqId = o.optString("id");
        String app = o.optString("app", "").trim();
        if (app.isEmpty()) app = o.optString("pkg", "某应用");

        boolean hasPin = Session.savedPin(this).length() == 6;
        String title = "解锁申请 · " + app;
        String body = hasPin
                ? "下拉可直接一键批准或拒绝，或点击查看详情"
                : "点击打开限时管理批准或拒绝";

        PendingIntent contentPi = openApp(this, reqId);
        PendingIntent approvePi = hasPin
                ? decisionBroadcast(this, AdminReceiver.ACTION_APPROVE, reqId, app,
                        70_000 + Math.floorMod(reqId.hashCode(), 50_000))
                : contentPi;
        PendingIntent denyPi = decisionBroadcast(this, AdminReceiver.ACTION_DENY, reqId, app,
                130_000 + Math.floorMod(reqId.hashCode(), 50_000));

        Notification n = new Notification.Builder(this, CH_ALERT)
                .setSmallIcon(R.drawable.ic_shield)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setGroup("alert_" + reqId)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .setShowWhen(true)
                .setWhen(System.currentTimeMillis())
                .setNumber(Math.max(1, pendingCount))
                .setContentIntent(contentPi)
                .addAction(new Notification.Action.Builder((Icon) null, "批准", approvePi).build())
                .addAction(new Notification.Action.Builder((Icon) null, "拒绝", denyPi).build())
                .build();

        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(notifIdFor(reqId), n);
    }

    /** 发送一条真实的 {@link #CH_ALERT} 强提醒测试通知，验证横幅、铃声与锁屏亮出效果。 */
    public static void sendTestAlert(Context c) {
        Context app = c.getApplicationContext();
        ensureChannels(app);
        String title = "解锁申请 · 浏览器（申请 15 分钟）";
        String body = "收到此横幅与提示音即代表强提醒正常（测试通知，点击可关闭）";
        Notification n = new Notification.Builder(app, CH_ALERT)
                .setSmallIcon(R.drawable.ic_shield)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setGroup("alert_test")
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .setShowWhen(true)
                .setWhen(System.currentTimeMillis())
                .setNumber(1)
                .setContentIntent(openApp(app, null))
                .build();
        NotificationManager nm = (NotificationManager) app.getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(TEST_NOTIF_ID, n);
    }

    /** 延迟发送测试强提醒，期间持短时唤醒锁保证立即按电源键锁屏后也能准时弹出。 */
    public static void scheduleTestAlert(Context c, long delayMs) {
        final Context app = c.getApplicationContext();
        final PowerManager.WakeLock wl = newShortWakeLock(app, "screenlimit:admin-test");
        if (wl != null) wl.acquire(delayMs + 2_000L);
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                sendTestAlert(app);
            } finally {
                safeRelease(wl);
            }
        }, delayMs);
    }

    /** 通知栏快捷审批失败时弹出可点击提示，引导进入应用内处理。 */
    static void notifyActionError(Context c, String reqId, String appName, String msg) {
        Context app = c.getApplicationContext();
        ensureChannels(app);
        String title = "未能完成审批" + (appName == null || appName.isEmpty() ? "" : (" · " + appName));
        Notification n = new Notification.Builder(app, CH_ALERT)
                .setSmallIcon(R.drawable.ic_shield)
                .setContentTitle(title)
                .setContentText(msg)
                .setStyle(new Notification.BigTextStyle().bigText(msg))
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .setShowWhen(true)
                .setWhen(System.currentTimeMillis())
                .setContentIntent(openApp(app, reqId))
                .build();
        NotificationManager nm = (NotificationManager) app.getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(notifIdFor(reqId), n);
    }

    /** 批准或拒绝成功后立即移除已通知记录并撤销对应通知栏条目。 */
    public static void onRequestHandled(Context c, String id) {
        if (id == null || id.isEmpty()) return;
        Context app = c.getApplicationContext();
        Set<String> notified = Session.notifiedIds(app);
        if (notified.remove(id)) {
            Session.saveNotifiedIds(app, notified);
        }
        NotificationManager nm = (NotificationManager) app.getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(notifIdFor(id));
    }

    /** 根据当前列表同步清理所有已不再处于 pending 状态的残留通知。 */
    public static void pruneHandledNotifications(Context c, JSONArray reqs) {
        if (reqs == null) return;
        Context app = c.getApplicationContext();
        Set<String> pendingIds = new HashSet<>();
        for (int i = 0; i < reqs.length(); i++) {
            JSONObject o = reqs.optJSONObject(i);
            if (o != null && "pending".equals(o.optString("state"))) {
                String id = o.optString("id");
                if (!id.isEmpty()) pendingIds.add(id);
            }
        }
        Set<String> notified = Session.notifiedIds(app);
        NotificationManager nm = (NotificationManager) app.getSystemService(NOTIFICATION_SERVICE);
        boolean changed = false;
        for (String oldId : new HashSet<>(notified)) {
            if (!pendingIds.contains(oldId)) {
                notified.remove(oldId);
                changed = true;
                if (nm != null) nm.cancel(notifIdFor(oldId));
            }
        }
        if (changed) {
            Session.saveNotifiedIds(app, notified);
        }
    }

    /** 通知栏操作完成后异步拉一次最新列表，确保非 SSE 环境或前台页面同步刷新。 */
    static void refreshSnapshotsAsync(Context c) {
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            try {
                JSONObject j = Api.requests(app);
                final JSONArray reqs = j.optJSONArray("reqs");
                final int pendingCount = j.optInt("pending");
                new Handler(Looper.getMainLooper()).post(() -> {
                    WatchService ws = INSTANCE;
                    if (ws != null) {
                        ws.onSnapshot(reqs);
                    } else {
                        pruneHandledNotifications(app, reqs);
                        SnapshotListener l = snapshotListener;
                        if (l != null) {
                            l.onSnapshot(reqs == null ? new JSONArray() : reqs, pendingCount);
                        }
                    }
                });
            } catch (Exception ignored) {}
        }, "admin-refresh").start();
    }

    static int notifIdFor(String reqId) {
        return 1000 + Math.floorMod(reqId == null ? 0 : reqId.hashCode(), 8000);
    }

    private void handleUnauthorized() {
        Session.clearToken(this);
        Session.setWatch(this, false);
        AdminReceiver.cancelWatchdog(this);
        SnapshotListener l = snapshotListener;
        if (l != null) l.onUnauthorized();
        stopMe();
    }

    private Notification ongoing() {
        // 原生 Notification.Builder 等价于 NotificationCompat.setSilent(true) 的静默配置
        return new Notification.Builder(this, CH_ONGOING)
                .setSmallIcon(R.drawable.ic_shield)
                .setContentTitle("限时管理")
                .setContentText("后台连接保持中，有新申请将立即提醒")
                .setGroup("silent_ongoing")
                .setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY)
                .setSound((Uri) null)
                .setVibrate(null)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_SECRET)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setContentIntent(openApp(this, null))
                .build();
    }

    private static PendingIntent openApp(Context c, String reqId) {
        Intent i = new Intent(c, AdminActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra("tab", "req");
        int reqCode = 1;
        if (reqId != null && !reqId.isEmpty()) {
            i.putExtra("req_id", reqId);
            reqCode = 10_000 + Math.floorMod(reqId.hashCode(), 50_000);
        }
        return PendingIntent.getActivity(c, reqCode, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static PendingIntent decisionBroadcast(Context c, String action, String reqId, String app, int reqCode) {
        Intent i = new Intent(c, AdminReceiver.class)
                .setAction(action)
                .putExtra("req_id", reqId)
                .putExtra("app", app);
        return PendingIntent.getBroadcast(c, reqCode, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static PowerManager.WakeLock newShortWakeLock(Context c, String tag) {
        try {
            PowerManager pm = (PowerManager) c.getSystemService(POWER_SERVICE);
            if (pm == null) return null;
            PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag);
            wl.setReferenceCounted(false);
            return wl;
        } catch (Exception e) {
            return null;
        }
    }

    static void safeRelease(PowerManager.WakeLock wl) {
        if (wl == null) return;
        try {
            if (wl.isHeld()) wl.release();
        } catch (Throwable ignored) {}
    }

    private void stopMe() {
        running.set(false);
        kickReconnect(true);
        h.removeCallbacksAndMessages(null);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }
}
