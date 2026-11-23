package com.briqt.screenlimit.admin;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;

/**
 * 审批提醒。网页只在打开时每 5 秒刷新；手机管理端可以在后台继续看。
 * 默认关。打开后是一条低优先级常驻通知，有新的 pending 才再响一条。
 */
public class WatchService extends Service {
    private static final int ONGOING_ID = 1;
    private static final String CH_ONGOING = "watch";
    private static final String CH_ALERT = "watch-alert";

    private final Handler h = new Handler(Looper.getMainLooper());
    private final Set<String> seen = new HashSet<>();
    private boolean primed;
    private boolean polling;

    public static void sync(Context c) {
        Intent i = new Intent(c, WatchService.class);
        if (Session.watch(c) && !Session.token(c).isEmpty()) {
            c.startForegroundService(i);
        } else {
            c.stopService(i);
        }
    }

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!Session.watch(WatchService.this) || Session.token(WatchService.this).isEmpty()) {
                stopMe();
                return;
            }
            new Thread(() -> {
                try {
                    JSONObject j = Api.requests(WatchService.this);
                    JSONArray reqs = j.optJSONArray("reqs");
                    h.post(() -> onList(reqs));
                } catch (Api.Err e) {
                    h.post(() -> onErr(e));
                }
            }, "admin-watch").start();
            h.postDelayed(this, 30_000);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel low = new NotificationChannel(CH_ONGOING, "审批值守", NotificationManager.IMPORTANCE_LOW);
        low.setShowBadge(false);
        nm.createNotificationChannel(low);
        NotificationChannel alert = new NotificationChannel(CH_ALERT, "新的授权申请", NotificationManager.IMPORTANCE_DEFAULT);
        nm.createNotificationChannel(alert);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        Notification n = ongoing("正在等候新的授权申请");
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(ONGOING_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(ONGOING_ID, n);
        }
        if (!polling) {
            polling = true;
            h.post(poll);
        }
        return START_STICKY;
    }

    private void onList(JSONArray reqs) {
        if (reqs == null) reqs = new JSONArray();
        Set<String> now = new HashSet<>();
        int pending = 0;
        for (int i = 0; i < reqs.length(); i++) {
            JSONObject o = reqs.optJSONObject(i);
            if (o == null || !"pending".equals(o.optString("state"))) continue;
            pending++;
            String id = o.optString("id");
            now.add(id);
            if (primed && !seen.contains(id)) notifyNew(o);
        }
        seen.clear();
        seen.addAll(now);
        primed = true;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(ONGOING_ID, ongoing(pending == 0 ? "没有待处理的申请" : ("待处理 " + pending + " 条")));
    }

    private void notifyNew(JSONObject o) {
        String title = o.optString("app", "新的授权申请");
        String text = o.optString("pkg", "");
        Notification n = new Notification.Builder(this, CH_ALERT)
                .setSmallIcon(R.drawable.ic_shield)
                .setContentTitle(title)
                .setContentText(text.isEmpty() ? "打开限时管理批准或拒绝" : text)
                .setAutoCancel(true)
                .setContentIntent(openApp())
                .build();
        int id = 100 + Math.floorMod(o.optString("id").hashCode(), 400);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(id, n);
    }

    private void onErr(Api.Err e) {
        if (e.code == 401) {
            Session.clearToken(this);
            Session.setWatch(this, false);
            stopMe();
        }
    }

    private Notification ongoing(String text) {
        return new Notification.Builder(this, CH_ONGOING)
                .setSmallIcon(R.drawable.ic_shield)
                .setContentTitle("限时管理")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openApp())
                .build();
    }

    private PendingIntent openApp() {
        Intent i = new Intent(this, AdminActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra("tab", "req");
        return PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private void stopMe() {
        h.removeCallbacksAndMessages(null);
        polling = false;
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override public void onDestroy() {
        h.removeCallbacksAndMessages(null);
        polling = false;
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
