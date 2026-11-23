package com.briqt.screenlimit;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class MonitorService extends Service {
    private static final String TAG = "ScreenLimit";
    static final int NOTIF_ID = 1;
    private static final int NOTIF_REMOTE = 4;
    private static final int NOTIF_PREWARN = 5;
    private static final int NOTIF_CLOCK = 6;
    private static final String CH_MONITOR = "monitor";
    private static final String CH_EVENTS = "events";
    static volatile MonitorService INSTANCE;

    private AppCache apps;
    private UsageStatsManager usm;
    private PowerManager pm;
    private final Handler h = new Handler(Looper.getMainLooper());
    private String date;
    private final Map<String, Integer> usedCache = new HashMap<>();
    private String blockedPkg;
    private String lastFg;
    /** 上一拍 tick 的时刻（elapsedRealtime）；本拍计时步长按实际间隔算（进程被冻结过也不少记）。 */
    private long tickAt;
    /** 远程配置上次拉取 / 应用列表上次上报的时刻（elapsedRealtime）。按时间分频，不按 tick 数——tick 间隔会变。 */
    private long cfgPulledAt, appsReportedAt;
    /** 常驻通知上一句文案，没变就不重发。 */
    private String lastStatusText = "";
    /** 今天已经提醒过「配额快用完」的包。跨天清空。 */
    private final Set<String> quotaWarned = new HashSet<>();
    /** 已经提醒过「授权即将到期」的授权（pkg + 到期时刻），同一次授权只提醒一次。 */
    private final Set<String> grantWarned = new HashSet<>();
    /** 今天已经发过「限制即将开始」预告。 */
    private String preWarnedDate;
    /** 上次提醒系统时间被改时的偏差，偏差没再变就不重复提醒。 */
    private long clockDriftNotified;

    // 远程申请轮询
    private String remoteId, remotePkg;
    /** 本次远程申请的时长（分钟，或 Rules.GRANT_TODAY）。批准回来后按它授权。 */
    private int remoteMinutes = Rules.PASSWORD_GRANT_MIN;
    private long remoteStartedAt;
    private boolean remoteCanceled;
    private final Runnable remotePoll = new Runnable() {
        @Override public void run() { pollRemote(); }
    };

    /**
     * 生效中 2 秒一拍（N3）；主开关关或时段外放慢到 10 秒省电；息屏时完全暂停轮询（零唤醒），由 SCREEN_ON 立刻唤醒。
     */
    private static final long TICK_ACTIVE_MS = 2000, TICK_IDLE_MS = 10_000;
    /** 两次「退桌面」之间至少隔多久。被拦应用赖在前台时每拍都会想退一次，别连发。 */
    private static final long KICK_HOME_GAP_MS = 800;
    private long kickedHomeAt;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!pm.isInteractive()) {
                // 息屏后完全停止定时轮询与哨兵窗，消除息屏网络/CPU 唤醒（防止被 ColorOS 电池管家判定高耗电强杀）
                tickAt = 0;
                OverlayGuard.ensureSentinel(MonitorService.this, false);
                return;
            }
            try { onTick(); } catch (Throwable t) { Log.e(TAG, "tick", t); }
            if (!pm.isInteractive()) return;
            boolean active = Rules.master(MonitorService.this) && Rules.inWindow(MonitorService.this);
            // 时段开始前 1 分钟内也走快拍，保证 22:00 一到就能拦
            int toStart = active ? -1 : Rules.minutesToWindowStart(MonitorService.this);
            boolean fast = active || (toStart >= 0 && toStart <= 1);
            h.postDelayed(this, fast ? TICK_ACTIVE_MS : TICK_IDLE_MS);
        }
    };

    /**
     * 装/卸应用后立刻重建应用缓存。
     * 不做这件事的后果很严重：{@code AppCache.launchable} 只在服务启动时建一次，
     * 新装的应用不在集合里 → {@code isManageable()} 为 false → 判定链第二条直接放行，
     * 于是"新装应用天然落在禁止态"这条 default-deny 的立身之本就失效了（需求 R4 / 用例 A6）。
     */
    private final android.content.BroadcastReceiver pkgChange = new android.content.BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (apps != null) apps.rebuild(getApplicationContext());
            scheduleReportApps();
            Log.i(TAG, "app list rebuilt: " + i.getAction());
        }
    };

    /** 息屏时彻底停拍；亮屏/解锁立刻补一拍并重置步长锚点，别把息屏时间算进配额或授权里。 */
    private final android.content.BroadcastReceiver screenOn = new android.content.BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            String act = i == null ? "" : i.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(act)) {
                h.removeCallbacks(tick);
                tickAt = 0;
                OverlayGuard.ensureSentinel(MonitorService.this, false);
                return;
            }
            tickAt = android.os.SystemClock.elapsedRealtime();
            requestUsagePoll(0);
            h.removeCallbacks(tick);
            h.post(tick);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        RemoteConfig.ensureIconVisible(this);   // 旧版隐藏图标的遗留状态：开机起服务时就自愈
        seedFirstRun();
        apps = new AppCache(getApplicationContext());
        apps.setOnChangeListener(this::scheduleReportApps);
        android.content.IntentFilter f = new android.content.IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_REPLACED);
        f.addDataScheme("package");
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(pkgChange, f, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(pkgChange, f);
        }
        android.content.IntentFilter sf = new android.content.IntentFilter(Intent.ACTION_SCREEN_ON);
        sf.addAction(Intent.ACTION_SCREEN_OFF);
        sf.addAction(Intent.ACTION_USER_PRESENT);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenOn, sf, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(screenOn, sf);
        }
        usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        date = Rules.today(this);
        startInForeground();
        INSTANCE = this;
        Rules.sp(this).registerOnSharedPreferenceChangeListener(cfgListener);
        startUsageTracker();   // 只起线程；查询由 tick 驱动
        h.post(tick);
    }

    /**
     * 增量预置基础工具：每个工具只在首次被识别时自动加入白名单；
     * 用户后来手动取消勾选的，不会因重启/升级被重新加回。
     */
    private void seedFirstRun() {
        AppCache c = new AppCache(getApplicationContext());
        dropSeededManagers(c);
        Set<String> seen = new HashSet<>(Rules.sp(this).getStringSet("seed_seen", new HashSet<>()));
        Set<String> wl = Rules.whitelist(this);
        int added = 0;
        for (AppCache.App a : c.listManageable(this)) {
            if (!a.basicTool) continue;
            if (seen.add(a.pkg) && !wl.contains(a.pkg)) {
                wl.add(a.pkg);
                added++;
            }
        }
        Rules.sp(this).edit()
                .putStringSet("seed_seen", seen)
                .putStringSet("whitelist", wl)
                .putBoolean("seeded_v1", true)
                .apply();
        if (added > 0) Log.i(TAG, "seeded " + added + " basic tools");
    }

    /**
     * 一次性迁移：v0.12.3 之前「手机管家」被 "phone" 这个词误判成基础工具，首次运行时自动进了白名单。
     * 现在它归为厂商管家（默认禁止），把当初**自动**加进去的那一份摘掉。
     * 只摘 `seed_seen` 里的——用户后来自己手动放行的不动，那是他的决定。
     */
    private void dropSeededManagers(AppCache c) {
        if (Rules.sp(this).getBoolean("mig_manager_v1", false)) return;
        Set<String> seen = Rules.sp(this).getStringSet("seed_seen", new HashSet<>());
        Set<String> wl = Rules.whitelist(this);
        int dropped = 0;
        for (String pkg : new HashSet<>(wl)) {
            if (c.isManagerApp(pkg) && seen.contains(pkg) && wl.remove(pkg)) dropped++;
        }
        Rules.sp(this).edit()
                .putStringSet("whitelist", wl)
                .putBoolean("mig_manager_v1", true)
                .apply();
        if (dropped > 0) Log.i(TAG, "迁移：从白名单摘掉 " + dropped + " 个厂商管家类应用");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Watchdog.scheduleDefault(this);
        return START_STICKY;
    }

    /**
     * 最近任务里被划掉：ColorOS 不把这当"系统回收"，START_STICKY 不补，服务就真没了。
     * 这里立刻自己起回来——卡片可以被划走，守护不能停。
     */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        Watchdog.schedule(this, 2_000L);
        try {
            startForegroundService(new Intent(this, MonitorService.class));
        } catch (Exception ignored) {}
    }

    @Override
    public void onDestroy() {
        Watchdog.schedule(this, 30_000L);   // 死了就让看门狗早点来看一眼
        INSTANCE = null;
        try { Rules.sp(this).unregisterOnSharedPreferenceChangeListener(cfgListener); } catch (Exception ignored) {}
        h.removeCallbacks(reportDebounce);
        try { unregisterReceiver(pkgChange); } catch (Exception ignored) {}
        try { unregisterReceiver(screenOn); } catch (Exception ignored) {}
        h.removeCallbacksAndMessages(null);
        if (usThread != null) usThread.quitSafely();
        OverlayGuard.hide();
        OverlayGuard.ensureSentinel(this, false);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent i) { return null; }

    /** 给 Tamper 用：这个包是不是桌面。服务没起来时返回 false（那时也没在限时）。 */
    static boolean homePkg(String pkg) {
        MonitorService m = INSTANCE;
        return m != null && m.apps != null && m.apps.isHome(pkg);
    }

    private void startInForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(CH_MONITOR, "屏幕限时守护", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("限时监控常驻通知，文案会跟着当前状态变");
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
        NotificationChannel ev = new NotificationChannel(CH_EVENTS, "限时提醒", NotificationManager.IMPORTANCE_DEFAULT);
        ev.setDescription("限制即将开始、配额快用完、授权快到期、远程申请有结果时提醒一次");
        nm.createNotificationChannel(ev);

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, ongoing("正在启动"), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF_ID, ongoing("正在启动"));
        }
    }

    private Notification ongoing(String text) {
        return new Notification.Builder(this, CH_MONITOR)
                .setContentTitle("屏幕限时守护")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_shield)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openSettings())
                .build();
    }

    private void refreshStatusNotif(String pkg, int decision) {
        String text;
        if (!Rules.master(this)) text = "限时未开启";
        else if (!Rules.inWindow(this)) text = "当前不在限制时段";
        else if (decision == BLOCK && pkg != null) text = "正在限制「" + apps.label(pkg) + "」";
        else if (pkg != null && apps.isManageable(pkg) && !Rules.isWhitelisted(this, pkg)
                && Rules.isGray(this, pkg) && usedSeconds(pkg) < Rules.grayQuota(this, pkg) * 60) {
            int left = Math.max(0, Rules.grayQuota(this, pkg) * 60 - usedSeconds(pkg));
            text = "「" + apps.label(pkg) + "」今日配额还剩 " + ((left + 59) / 60) + " 分钟";
        }
        else if (pkg != null && apps.isManageable(pkg) && !Rules.isWhitelisted(this, pkg)
                && Rules.grantActive(this, pkg)) {
            if (Rules.grantTodayActive(this, pkg)) {
                text = "「" + apps.label(pkg) + "」今日已授权不限";
            } else {
                int leftSec = Rules.grantRemainingSeconds(this, pkg);
                text = "「" + apps.label(pkg) + "」可用授权还剩 " + fmtSecShort(leftSec);
            }
        } else {
            // 当前不在受控应用里，若后台存有未用完的按时长授权，提示用户「未打开不消耗」
            String banked = bankedGrantSummary();
            text = banked != null ? ("守护中 · " + banked) : "守护中";
        }
        if (text.equals(lastStatusText)) return;
        lastStatusText = text;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(NOTIF_ID, ongoing(text));
    }

    private String bankedGrantSummary() {
        java.util.Map<String, Integer> map = Rules.activeGrants(this);
        for (java.util.Map.Entry<String, Integer> e : map.entrySet()) {
            int sec = e.getValue();
            if (sec > 0) {
                return "「" + apps.label(e.getKey()) + "」存有 " + fmtSecShort(sec) + "授权（未消耗）";
            }
        }
        return null;
    }

    private static String fmtSecShort(int sec) {
        if (sec <= 0) return "0 秒";
        if (sec < 60) return sec + " 秒";
        int m = (sec + 59) / 60;
        return m + " 分钟";
    }

    private void postEvent(int id, String title, String text) {
        postEvent(id, title, text, openSettings());
    }

    private void postEvent(int id, String title, String text, PendingIntent tap) {
        Notification n = new Notification.Builder(this, CH_EVENTS)
                .setSmallIcon(R.drawable.ic_shield)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(tap)
                .build();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(id, n);
    }

    private PendingIntent openSettings() {
        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private PendingIntent openApp(String pkg) {
        Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) return openSettings();
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(this, 1, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** 事件通知 id：按包名散开，避免两个应用互相覆盖。 */
    private static int notifId(String pkg) {
        return 20 + Math.floorMod(pkg == null ? 0 : pkg.hashCode(), 400);
    }

    // ---------- 判定 ----------

    private static final int ALLOW = 0, BLOCK = 1;

    private int decide(String pkg) {
        if (pkg == null) return ALLOW;
        if (pkg.equals(getPackageName()) || apps.isHardSafe(pkg)) return ALLOW;
        if (!apps.isManageable(pkg)) return ALLOW;
        if (!Rules.master(this) || !Rules.inWindow(this)) return ALLOW;
        if (Rules.isWhitelisted(this, pkg)) return ALLOW;
        if (Rules.grantActive(this, pkg)) return ALLOW;
        if (Rules.isGray(this, pkg)) {
            return usedSeconds(pkg) >= (long) Rules.grayQuota(this, pkg) * 60 ? BLOCK : ALLOW;
        }
        return BLOCK; // 默认禁止
    }

    private void onTick() {
        // 只在生效（及开始前 1 分钟）时挂哨兵：它会让系统常驻一条"正在其他应用上层显示"的通知
        int toStart = Rules.minutesToWindowStart(this);
        OverlayGuard.ensureSentinel(this, Rules.master(this)
                && (Rules.inWindow(this) || (toStart >= 0 && toStart <= 1)));
        String now = Rules.today(this);
        if (!now.equals(date)) {
            date = now;
            usedCache.clear();
            quotaWarned.clear();
            grantWarned.clear();
            OverlayGuard.hide();
            blockedPkg = null;
            Rules.pruneOldDays(this);
        }

        checkClock();
        syncRemote();
        preWarnWindow();

        String pkg = foregroundPkg();
        requestUsagePoll(0);   // 结果下一拍用；有变化它会自己催一拍
        long elapsedNow = android.os.SystemClock.elapsedRealtime();
        // 这一拍与上一拍的间隔，即本拍的计时步长
        int stepSec = tickAt > 0 ? (int) Math.min(Math.max((elapsedNow - tickAt) / 1000, 1), 15) : 2;
        tickAt = elapsedNow;
        if (pkg == null) return; // 判定不出前台时维持现状，不撤防

        int d = decide(pkg);
        boolean interactive = pm.isInteractive();
        if (d == ALLOW) {
            // 今日使用统计：亮屏 + 可管理应用即记，与主开关/时段无关（守护页那张卡靠它）
            if (interactive && apps.isManageable(pkg)) {
                Rules.setStatToday(this, pkg, Rules.statSecondsToday(this, pkg) + stepSec);
            }
            // 受控应用在功能生效且亮屏时：先消耗灰名单每日配额（若有剩余），配额用尽或默认禁止则消耗临时授权秒数
            if (interactive && Rules.master(this) && Rules.inWindow(this)
                    && apps.isManageable(pkg) && !Rules.isWhitelisted(this, pkg)) {
                boolean isGray = Rules.isGray(this, pkg);
                int quotaSec = isGray ? Rules.grayQuota(this, pkg) * 60 : 0;
                int curUsed = isGray ? usedSeconds(pkg) : 0;
                if (isGray && curUsed < quotaSec) {
                    int v = curUsed + stepSec;
                    usedCache.put(pkg, v);
                    Rules.setUsedToday(this, pkg, v);
                    int left = quotaSec - v;
                    if (left > 0 && left <= 120 && quotaWarned.add(pkg)) {
                        postEvent(notifId(pkg), "配额快用完了",
                                apps.label(pkg) + " 今日配额还剩不到 2 分钟");
                    }
                } else if (Rules.grantActive(this, pkg)) {
                    int leftSec = Rules.consumeGrantSeconds(this, pkg, stepSec);
                    if (!Rules.grantTodayActive(this, pkg)) {
                        String warnKey = pkg + "@" + date;
                        if (leftSec > 60) {
                            grantWarned.remove(warnKey);
                        } else if (leftSec > 0 && grantWarned.add(warnKey)) {
                            postEvent(notifId(pkg), "授权即将到期",
                                    apps.label(pkg) + " 可用授权还剩不到 1 分钟");
                        }
                        if (leftSec <= 0) {
                            showBlock(pkg);
                            OverlayGuard.applyCooldown(Rules.coolReadyAt(this),
                                    Rules.coolUseMin(this), Rules.coolIntervalMin(this));
                            lastFg = pkg;
                            refreshStatusNotif(pkg, BLOCK);
                            return;
                        }
                    }
                }
            }
            // 拦截时已经退回桌面，拦截页就盖在桌面上：此时前台是桌面（放行），但拦截页得留着，
            // 等人自己点「返回桌面」或解锁。被拦应用本身已经放行了（时段结束/授权到手）才撤。
            if (OverlayGuard.isAttached()) {
                String shown = OverlayGuard.shownPkg();
                boolean keep = apps.isHome(pkg) && shown != null && decide(shown) == BLOCK;
                if (!keep) {
                    OverlayGuard.hide();
                    blockedPkg = null;
                }
            }
        } else {
            showBlock(pkg);
            // 倒计时靠 tick 推动；遮挡层本身是静态 View，不自己走表
            OverlayGuard.applyCooldown(Rules.coolReadyAt(this),
                    Rules.coolUseMin(this), Rules.coolIntervalMin(this));
        }
        lastFg = pkg;
        refreshStatusNotif(pkg, d);
    }

    /** 时段开始前 5 分钟预告一次，正在用的应用到点会被盖住，让人有个准备。 */
    private void preWarnWindow() {
        if (!Rules.master(this)) return;
        int m = Rules.minutesToWindowStart(this);
        if (m < 0 || m > 5) return;
        String today = Rules.today(this);
        if (today.equals(preWarnedDate)) return;
        preWarnedDate = today;
        postEvent(NOTIF_PREWARN, "限制将在 " + Math.max(1, m) + " 分钟后开始",
                Rules.fmtClock(Rules.winStart(this)) + " 起，名单外的应用将被限制");
    }

    /**
     * 系统时钟被改了就说一声。判定本身走可信时钟（Rules.now），改时间不影响限时，
     * 但管理者应该知道有人在试。偏差 2 分钟以内当作正常的网络校时，不提。
     */
    private void checkClock() {
        long drift = Rules.clockDrift(this);
        if (Math.abs(drift) < 120_000) { clockDriftNotified = 0; return; }
        if (Math.abs(drift - clockDriftNotified) < 60_000) return;
        clockDriftNotified = drift;
        long mins = Math.abs(drift) / 60_000;
        Log.w(TAG, "系统时间与可信时钟偏差 " + drift + "ms");
        postEvent(NOTIF_CLOCK, "系统时间被修改",
                "比守护时钟" + (drift > 0 ? "快" : "慢") + " " + mins + " 分钟，限时仍按真实时间执行");
    }

    /**
     * 分频跑远程同步：拉配置（约 60s）与上报应用列表（约 10min）。
     * 都扔到子线程——网络别卡住判定主循环。
     */
    private void syncRemote() {
        if (Rules.serverBase(this).isEmpty() || Rules.deviceToken(this).isEmpty()) return;
        long e = android.os.SystemClock.elapsedRealtime();

        if (e - cfgPulledAt >= RemoteConfig.PULL_EVERY_MS) {
            cfgPulledAt = e;
            new Thread(() -> {
                org.json.JSONObject j = RemoteClient.fetchConfig(MonitorService.this);
                // 落库要回主线程：设置页可能正在读同一份 prefs
                h.post(() -> RemoteConfig.apply(MonitorService.this, j));
            }, "cfg-pull").start();
        }
        if (e - appsReportedAt >= RemoteConfig.REPORT_EVERY_MS) {
            appsReportedAt = e;
            reportApps();
        }
    }

    /** 上报一次应用名单（子线程）。周期上报与"本地一变即补报"共用。 */
    private void reportApps() {
        appsReportedAt = android.os.SystemClock.elapsedRealtime();
        new Thread(() -> {
            try {
                RemoteClient.postApps(MonitorService.this,
                        RemoteConfig.appsPayload(MonitorService.this, apps.listManageable(this)));
            } catch (Throwable t) {
                Log.w(TAG, "postApps", t);
            }
        }, "apps-report").start();
    }

    /** 这些键变了意味着"规则配置变了"，要补报给服务端（统计 s_ / 用量 u_ 等键不在这里）。 */
    private static final String[] CFG_KEYS = {
            "master", "win_start", "win_end", "whitelist", "graylist", "guard_settings",
            "cool_use_min", "cool_interval_min",
            "cfg_version"   // 应用了一版远程配置但各项恰好没变时，也得告诉服务端"已应用"
    };
    private static boolean isCfgKey(String k) {
        if (k == null) return false;
        if (k.startsWith("g_")) return true;   // 灰名单配额
        for (String c : CFG_KEYS) if (c.equals(k)) return true;
        return false;
    }

    private final Runnable reportDebounce = this::reportApps;

    /** 防抖 3 秒后向服务端上报最新应用列表与配置。 */
    private void scheduleReportApps() {
        if (Rules.serverBase(this).isEmpty() || Rules.deviceToken(this).isEmpty()) return;
        h.removeCallbacks(reportDebounce);
        h.postDelayed(reportDebounce, 3_000L);
    }

    /** 监听本地配置变化：防抖 3 秒后补报，服务端/管理端看到的就是最新状态。 */
    private final android.content.SharedPreferences.OnSharedPreferenceChangeListener cfgListener =
            (sp, key) -> {
                if (!isCfgKey(key)) return;
                scheduleReportApps();
            };

    private void showBlock(String pkg) {
        boolean already = OverlayGuard.isShown(pkg);
        blockedPkg = pkg;
        if (already) {
            // 拦截页还在、被拦应用却又回到了前台（自己拉起 / 从最近任务点回来）：再退一次桌面，
            // 不然它在拦截页底下接着自动播放
            kickHome();
            return;
        }
        // 「授权即将到期 / 配额快用完」这时已经兑现，别让过期提醒还挂在通知栏
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(notifId(pkg));

        String label = apps.label(pkg);
        boolean gray = Rules.isGray(this, pkg);
        String reasonBadge;
        if (gray) {
            reasonBadge = "今日配额 " + Rules.grayQuota(this, pkg) + " 分钟已用尽";
        } else if (Rules.allDay(this)) {
            reasonBadge = "受限应用 · 全天管控";
        } else {
            reasonBadge = "受限时段 " + Rules.windowText(this);
        }
        int statSec = Rules.statSecondsToday(this, pkg);
        String usedTodayText = statSec < 60 ? "不到 1 分钟" : ((statSec / 60) + " 分钟");
        String releaseHintText = Rules.releaseText(this, gray);
        if (releaseHintText == null || releaseHintText.isEmpty()) releaseHintText = "明日 00:00";

        boolean waitingThisPkg = !remoteCanceled && remoteId != null && pkg.equals(remotePkg);
        if (waitingThisPkg) {
            // 切出审批完又切回该应用：立即触发一次轮询，已批则秒解
            h.removeCallbacks(remotePoll);
            h.post(remotePoll);
        }

        // 没配密码 / 没配服务器的出口直接不显示，别让人点进去才发现走不通
        OverlayGuard.showBlock(getApplicationContext(), pkg, apps.icon(pkg), label, reasonBadge,
                usedTodayText, releaseHintText,
                Rules.coolReadyAt(this), Rules.coolUseMin(this),
                Rules.coolIntervalMin(this),
                Rules.hasPassword(this),
                !Rules.serverBase(this).isEmpty() && !Rules.deviceToken(this).isEmpty(),
                waitingThisPkg, remoteMinutes,
                actions);
        // 先盖、再在底下退桌面：拦截页只挡画面，被拦应用（视频类）得退到后台才会停止自动播放。
        // 顺序不能反——先退桌面再盖，中间那一下被拦应用的画面是露着、能点的。
        kickHome();
    }

    /** 退回桌面。优先走无障碍的 GLOBAL_ACTION_HOME，无障碍被关时发 HOME intent（有悬浮窗权限，后台可启动）。 */
    private void kickHome() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - kickedHomeAt < KICK_HOME_GAP_MS) return;
        kickedHomeAt = now;
        requestUsagePoll(500);   // 退桌面后桌面往往不发无障碍事件，靠这一次尽快认出「已在桌面」
        AccessibilityMonitor a = AccessibilityMonitor.INSTANCE;
        if (a != null) {
            a.goHome();
            return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) {}
    }

    /** 解锁后把人送回刚才被拦的应用——拦的时候退了桌面，不送回去就得自己再找一遍图标。 */
    private void reopen(String pkg) {
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
            if (i == null) return;
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(i);
        } catch (Exception e) {
            Log.w(TAG, "reopen " + pkg, e);
        }
    }

    /** 解锁成功的统一收尾：撤拦截页、送回应用。 */
    private void unblocked(String pkg) {
        OverlayGuard.hide();
        blockedPkg = null;
        reopen(pkg);
    }

    private final OverlayGuard.Actions actions = new OverlayGuard.Actions() {
        @Override public void onTempUse() {
            String pkg = OverlayGuard.shownPkg();
            if (pkg == null) return;
            long sec = Rules.grantCool(MonitorService.this, pkg);
            if (sec > 0) unblocked(pkg);
        }

        @Override public void onDismiss() {
            // 远程申请若还在等，不取消：批准了会发通知，点通知直接进应用
            OverlayGuard.hide();
            blockedPkg = null;
        }

        @Override public void onPasswordSubmit(String pin, int minutes) {
            String pkg = OverlayGuard.shownPkg();
            if (pkg == null) return;
            if (!Rules.hasPassword(MonitorService.this)) {
                OverlayGuard.setPasswordError("尚未设置密码");
                return;
            }
            if (Rules.pwLockLeft(MonitorService.this) > 0) {
                OverlayGuard.setPasswordError(Rules.pwFailText(MonitorService.this));
                return;
            }
            if (pin != null && pin.length() == 6 && Rules.tryPassword(MonitorService.this, pin)) {
                Rules.grantFor(MonitorService.this, pkg, minutes);
                Log.i(TAG, "密码解锁 " + pkg + " · " + OverlayGuard.durText(minutes));
                unblocked(pkg);
            } else {
                OverlayGuard.setPasswordError(Rules.pwFailText(MonitorService.this));
            }
        }

        @Override public void onRemoteRequest(int minutes) {
            final String pkg = OverlayGuard.shownPkg();
            if (pkg == null) return;
            final String base = Rules.serverBase(MonitorService.this);
            final String tok = Rules.deviceToken(MonitorService.this);
            if (base.isEmpty()) {
                OverlayGuard.setWaitText("未设置服务器地址（在应用设置中填写）");
                return;
            }
            if (tok.isEmpty()) {
                OverlayGuard.setWaitText("未设置设备令牌（在应用设置中填写）");
                return;
            }
            final String prevId = remoteId;
            remotePkg = pkg;
            remoteMinutes = minutes;
            remoteId = RemoteClient.newRequestId();
            remoteStartedAt = android.os.SystemClock.elapsedRealtime();
            remoteCanceled = false;
            final String reqId = remoteId;
            final String label = apps.label(pkg) + "（申请 " + OverlayGuard.durText(minutes) + "）";
            new Thread(() -> {
                if (prevId != null) {
                    RemoteClient.cancel(base, tok, prevId);
                }
                String err = RemoteClient.postRequest(MonitorService.this, reqId, pkg, label);
                // 统一回主线程：setWaitText 会碰 View，postRequest 失败时尤其容易踩到
                h.post(() -> {
                    if (!reqId.equals(remoteId)) return;   // 期间已取消/换了一单
                    if (err != null) OverlayGuard.setWaitText(err);
                    else {
                        OverlayGuard.setWaitText("等待管理员批准中（每 5 秒自动检查）…");
                        h.removeCallbacks(remotePoll);
                        h.post(remotePoll);
                    }
                });
            }, "remote-request").start();
        }

        @Override public void onRemotePollNow() {
            if (remoteId == null || remoteCanceled) {
                OverlayGuard.setWaitText("当前没有待审批的申请，请返回重新发起");
                return;
            }
            h.removeCallbacks(remotePoll);
            h.post(remotePoll);
        }

        @Override public void onRemoteCancel() {
            remoteCanceled = true;
            h.removeCallbacks(remotePoll);
            if (remoteId != null) {
                final String id = remoteId;
                final String base = Rules.serverBase(MonitorService.this);
                final String tok = Rules.deviceToken(MonitorService.this);
                new Thread(() -> RemoteClient.cancel(base, tok, id), "remote-cancel").start();
            }
            remoteId = null;
        }
    };

    private static final long REMOTE_TIMEOUT_MS = 15 * 60_000L;

    private void pollRemote() {
        if (remoteCanceled || remoteId == null) return;
        if (remoteStartedAt > 0 && android.os.SystemClock.elapsedRealtime() - remoteStartedAt > REMOTE_TIMEOUT_MS) {
            remoteId = null;
            OverlayGuard.setWaitText("申请已超时（超过 15 分钟未处理），请返回重新发起");
            return;
        }
        final String id = remoteId, pkg = remotePkg;
        final int mins = remoteMinutes;
        final String base = Rules.serverBase(this);
        final String tok = Rules.deviceToken(this);
        new Thread(() -> {
            String r = RemoteClient.poll(base, tok, id);
            h.post(() -> {
                if (remoteCanceled || !id.equals(remoteId)) return;
                if (r == null) {
                    h.postDelayed(remotePoll, 5000);
                    return;
                }
                if ("expired".equals(r)) {
                    remoteId = null;
                    OverlayGuard.setWaitText("申请已过期或被替代，请返回重新发起");
                    return;
                }
                if ("denied".equals(r)) {
                    remoteId = null;
                    OverlayGuard.setWaitText("申请已被管理员拒绝");
                    postEvent(NOTIF_REMOTE, "远程申请被拒绝", apps.label(pkg));
                    return;
                }
                // 收到密码
                if (Rules.hasPassword(this) && Rules.checkPassword(this, r)) {
                    Rules.grantFor(this, pkg, mins);
                    Log.i(TAG, "远程解锁 " + pkg + " · " + OverlayGuard.durText(mins));
                    if (pkg.equals(OverlayGuard.shownPkg())) {
                        unblocked(pkg);
                        postEvent(NOTIF_REMOTE, "远程申请已通过",
                                apps.label(pkg) + " · " + OverlayGuard.durText(mins));
                    } else {
                        // 拦截页早被关掉了：通知点一下直接进应用
                        postEvent(NOTIF_REMOTE, "远程申请已通过",
                                apps.label(pkg) + " · " + OverlayGuard.durText(mins) + " · 点此打开",
                                openApp(pkg));
                    }
                } else {
                    OverlayGuard.setWaitText("审批填写的解锁密码不正确，已取消");
                    postEvent(NOTIF_REMOTE, "远程密码不正确", "申请已取消");
                }
                remoteId = null;
            });
        }, "remote-poll").start();
    }

    /**
     * 当前「处于受限态」的可管理应用包名快照，供防拆判断某个应用信息页值不值得拦。
     * 只含真会被拦的（默认禁止 / 配额已用完），白名单和授权生效中的不算。
     */
    java.util.Set<String> managedPkgsSnapshot() {
        java.util.Set<String> out = new HashSet<>();
        AppCache c = apps;
        if (c == null) return out;
        for (AppCache.App a : c.listManageable(this)) {
            if (Rules.isWhitelisted(this, a.pkg) || Rules.grantActive(this, a.pkg)) continue;
            out.add(a.pkg);
        }
        return out;
    }

    /**
     * 同上，但取**应用名**（小写）。ColorOS 的应用详情页根本不显示包名，只显示应用名，
     * 光靠包名匹配会全部落空（真机实测）。名字短于 2 个字的丢掉，避免误伤。
     */
    java.util.Set<String> managedLabelsSnapshot() {
        java.util.Set<String> out = new HashSet<>();
        AppCache c = apps;
        if (c == null) return out;
        for (AppCache.App a : c.listManageable(this)) {
            if (Rules.isWhitelisted(this, a.pkg) || Rules.grantActive(this, a.pkg)) continue;
            if (a.label != null && a.label.length() >= 2) out.add(a.label.toLowerCase());
        }
        return out;
    }

    private int usedSeconds(String pkg) {
        Integer v = usedCache.get(pkg);
        if (v == null) {
            v = Rules.usedSecondsToday(this, pkg);
            usedCache.put(pkg, v);
        }
        return v;
    }

    /**
     * 当前前台应用：无障碍事件与 UsageStats 事件**谁新信谁**（两者都是 wall-clock 时间戳）。
     * <ul>
     * <li>无障碍快（切应用的瞬间就到），但会漏：拦截页盖着时退回桌面，桌面不发窗口事件，
     *     只信它就会一直以为被拦应用还在前台——人点了「返回桌面」，下一拍又被盖回去（真机实测）。</li>
     * <li>UsageStats 不漏，但查询是跨进程调用，**会被卡住**：ColorOS 上别的应用冷启动时，
     *     queryEvents 在主线程上卡过 3.1 秒（真机抓到的栈），无障碍回调排在它后面一起被堵，
     *     冷启动的被禁应用就能完整露 3 秒。所以它挪到 {@link #usThread} 上跑，这里只读结果。</li>
     * </ul>
     */
    private String foregroundPkg() {
        long aAt = AccessibilityMonitor.eventAt;
        String a = AccessibilityMonitor.currentPkg;
        String u = usPkg;
        long uAt = usAt;
        String p;
        if (a != null && (u == null || aAt >= uAt)) p = a;
        else p = u;
        if (p != null) lastFg = p;
        return lastFg;
    }

    // ---------- UsageStats 前台跟踪（独立线程） ----------
    // 这里**采纳自身包名**：ACTIVITY_RESUMED 只会由 MainActivity 触发，遮挡层是 window、不是 activity，
    // 不会伪造这个事件。不采纳的话，从通知点进设置页时前台仍被判成上一个被拦应用，
    // 一改规则遮挡层就盖到设置页上（真机实测）。

    private android.os.HandlerThread usThread;
    private Handler uh;
    /** UsageStats 看到的最近一次切到前台的包与时刻；只在 usThread 上写。 */
    private volatile String usPkg;
    private volatile long usAt;

    private void startUsageTracker() {
        usThread = new android.os.HandlerThread("fg-usage");
        usThread.start();
        uh = new Handler(usThread.getLooper());
        uh.post(() -> {
            try { backfillTodayStats(); } catch (Throwable t) { Log.w(TAG, "backfill stats", t); }
        });
    }

    /**
     * 服务启动时从系统 UsageEvents 回补今日前台使用时长。
     * 解决三类漏计：1) 服务尚未启动/被强杀期间的使用；2) 新装应用首次被发现前的当日已用时长；3) 升级覆盖安装前的空白。
     * 仅当系统精确事件累计时长大于本地已记录的 s_YYYY-MM-DD_<pkg> 时才向上回补，绝不倒扣。
     */
    private void backfillTodayStats() {
        if (usm == null || apps == null) return;
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0);
        cal.set(java.util.Calendar.MINUTE, 0);
        cal.set(java.util.Calendar.SECOND, 0);
        cal.set(java.util.Calendar.MILLISECOND, 0);
        long startOfDay = cal.getTimeInMillis();
        long now = System.currentTimeMillis();
        if (now <= startOfDay) return;

        UsageEvents ev = usm.queryEvents(startOfDay, now);
        if (ev == null) return;
        UsageEvents.Event e = new UsageEvents.Event();
        Map<String, Long> totalMs = new HashMap<>();
        String curPkg = null;
        String curCls = null;
        long curStart = 0;
        final long MAX_SINGLE_SESSION_MS = 45 * 60_000L;

        while (ev.hasNextEvent()) {
            ev.getNextEvent(e);
            int ty = e.getEventType();
            long ts = e.getTimeStamp();
            if (ty == UsageEvents.Event.MOVE_TO_FOREGROUND
                    || ty == UsageEvents.Event.ACTIVITY_RESUMED) {
                if (curPkg != null && ts > curStart) {
                    long d = Math.min(ts - curStart, MAX_SINGLE_SESSION_MS);
                    totalMs.put(curPkg, totalMs.getOrDefault(curPkg, 0L) + d);
                }
                curPkg = e.getPackageName();
                curCls = e.getClassName();
                curStart = ts;
            } else if (ty == UsageEvents.Event.MOVE_TO_BACKGROUND
                    || ty == UsageEvents.Event.ACTIVITY_PAUSED
                    || ty == UsageEvents.Event.ACTIVITY_STOPPED) {
                if (curPkg != null && curPkg.equals(e.getPackageName())
                        && (curCls == null || curCls.equals(e.getClassName()))) {
                    if (ts > curStart) {
                        long d = Math.min(ts - curStart, MAX_SINGLE_SESSION_MS);
                        totalMs.put(curPkg, totalMs.getOrDefault(curPkg, 0L) + d);
                    }
                    curPkg = null;
                    curCls = null;
                    curStart = 0;
                }
            } else if (ty == UsageEvents.Event.SCREEN_NON_INTERACTIVE
                    || ty == UsageEvents.Event.KEYGUARD_SHOWN
                    || ty == UsageEvents.Event.DEVICE_SHUTDOWN) {
                if (curPkg != null && ts > curStart) {
                    long d = Math.min(ts - curStart, MAX_SINGLE_SESSION_MS);
                    totalMs.put(curPkg, totalMs.getOrDefault(curPkg, 0L) + d);
                }
                curPkg = null;
                curCls = null;
                curStart = 0;
            }
        }
        if (curPkg != null && now > curStart && pm != null && pm.isInteractive()) {
            long d = Math.min(now - curStart, MAX_SINGLE_SESSION_MS);
            totalMs.put(curPkg, totalMs.getOrDefault(curPkg, 0L) + d);
        }

        boolean updated = false;
        for (Map.Entry<String, Long> en : totalMs.entrySet()) {
            String pkg = en.getKey();
            int sec = (int) (en.getValue() / 1000L);
            if (sec < 30 || !apps.isManageable(pkg)) continue;
            int cur = Rules.statSecondsToday(this, pkg);
            if (sec > cur + 15) {
                Rules.setStatToday(this, pkg, sec);
                Log.i(TAG, "backfilled today stat for " + pkg + ": " + cur + "s -> " + sec + "s");
                updated = true;
            }
        }
        if (updated) scheduleReportApps();
    }

    /** 不自己排周期：每拍 tick 顺手请求一次（见 {@link #requestUsagePoll}），退桌面后再补一次。 */
    private final Runnable usPoll = () -> {
        try { pollUsage(); } catch (Throwable t) { Log.w(TAG, "usage poll", t); }
    };

    private void requestUsagePoll(long delayMs) {
        if (uh == null) return;
        uh.removeCallbacks(usPoll);
        uh.postDelayed(usPoll, delayMs);
    }

    /**
     * 增量查询：从上次看到的那条事件起查到现在（首次回溯 10 分钟，覆盖「服务重启时人已经在被禁应用里停了很久」）。
     * 不用固定窗口——固定 15 秒窗口里没新事件就会退回旧值；进程被冻结一段时间后窗口也会滑过真正的切换。
     */
    private void pollUsage() {
        long now = System.currentTimeMillis();
        long from = usAt > 0 ? usAt : now - 10 * 60_000L;
        UsageEvents ev = usm.queryEvents(from, now + 1);
        UsageEvents.Event e = new UsageEvents.Event();
        String last = null;
        long lastAt = 0;
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e);
            int ty = e.getEventType();
            if (ty == UsageEvents.Event.MOVE_TO_FOREGROUND
                    || ty == UsageEvents.Event.ACTIVITY_RESUMED) {
                last = e.getPackageName();
                lastAt = e.getTimeStamp();
            }
        }
        if (last == null || (last.equals(usPkg) && lastAt == usAt)) return;
        boolean changed = !last.equals(usPkg);
        usPkg = last;
        usAt = lastAt;
        final String fg = last;
        if (changed) h.post(() -> notifyForeground(fg));
    }

    /** 无障碍即时通知（仅用于加速，判定仍走 tick 单一来源）。 */
    static void notifyForeground(String pkg) {
        MonitorService s = INSTANCE;
        if (s != null) s.h.removeCallbacks(s.tick);
        if (s != null) s.h.post(s.tick);
    }
}
