package com.briqt.screenlimit.admin;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.os.SystemClock;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 管理端内部广播入口：
 * 1) 15 分钟低频巡检闹钟（{@link #ACTION_WATCHDOG}），兜底确认 {@link WatchService} 存活且 SSE 连接未僵死；
 * 2) 强提醒通知栏快捷按钮「批准」（{@link #ACTION_APPROVE}）与「拒绝」（{@link #ACTION_DENY}）。
 */
public class AdminReceiver extends BroadcastReceiver {
    static final String ACTION_WATCHDOG = "com.briqt.screenlimit.admin.WATCHDOG";
    static final String ACTION_APPROVE = "com.briqt.screenlimit.admin.APPROVE";
    static final String ACTION_DENY = "com.briqt.screenlimit.admin.DENY";

    static final long WATCHDOG_INTERVAL_MS = 15 * 60_000L;

    private static final Set<String> IN_FLIGHT = ConcurrentHashMap.newKeySet();

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        String action = intent.getAction();
        Context appCtx = context.getApplicationContext();

        if (ACTION_WATCHDOG.equals(action)) {
            if (!Session.watch(appCtx) || Session.token(appCtx).isEmpty()) {
                cancelWatchdog(appCtx);
                return;
            }
            scheduleWatchdog(appCtx, WATCHDOG_INTERVAL_MS);
            WatchService ws = WatchService.INSTANCE;
            if (ws == null) {
                WatchService.startServiceSafe(appCtx);
            } else {
                ws.checkHealthAndHeal();
            }
            return;
        }

        boolean approve = ACTION_APPROVE.equals(action);
        if (!approve && !ACTION_DENY.equals(action)) return;

        final String reqId = intent.getStringExtra("req_id");
        final String appName = intent.getStringExtra("app");
        if (reqId == null || reqId.isEmpty()) return;

        if (approve) {
            String pin = Session.savedPin(appCtx);
            if (pin.length() != 6) {
                WatchService.notifyActionError(appCtx, reqId, appName, "本机未保存 6 位解锁密码，点此打开输入密码批准");
                return;
            }
            handleDecisionAsync(appCtx, reqId, appName, true, pin);
        } else {
            handleDecisionAsync(appCtx, reqId, appName, false, null);
        }
    }

    private void handleDecisionAsync(Context appCtx, String reqId, String appName, boolean approve, String pin) {
        if (!IN_FLIGHT.add(reqId)) return;
        final PendingResult pending = goAsync();
        final PowerManager.WakeLock wl = WatchService.newShortWakeLock(appCtx, "screenlimit:admin-action");
        if (wl != null) wl.acquire(10_000L);

        new Thread(() -> {
            try {
                if (approve) {
                    Api.approve(appCtx, reqId, pin);
                } else {
                    Api.deny(appCtx, reqId);
                }
                WatchService.onRequestHandled(appCtx, reqId);
                WatchService.refreshSnapshotsAsync(appCtx);
            } catch (Api.Err e) {
                if (e.code == 404 || e.code == 409) {
                    WatchService.onRequestHandled(appCtx, reqId);
                    WatchService.refreshSnapshotsAsync(appCtx);
                } else {
                    String msg = e.getMessage();
                    if (msg == null || msg.isEmpty()) msg = approve ? "批准未完成" : "拒绝未完成";
                    WatchService.notifyActionError(appCtx, reqId, appName, msg + "，点此打开限时管理处理");
                }
            } catch (Throwable t) {
                WatchService.notifyActionError(appCtx, reqId, appName, "网络异常，点此打开限时管理处理");
            } finally {
                IN_FLIGHT.remove(reqId);
                WatchService.safeRelease(wl);
                pending.finish();
            }
        }, approve ? "admin-notif-approve" : "admin-notif-deny").start();
    }

    static void scheduleWatchdog(Context c, long delayMs) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            if (am != null) {
                am.setAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        SystemClock.elapsedRealtime() + delayMs,
                        watchdogPi(c));
            }
        } catch (Exception ignored) {}
    }

    static void cancelWatchdog(Context c) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            if (am != null) am.cancel(watchdogPi(c));
        } catch (Exception ignored) {}
    }

    private static PendingIntent watchdogPi(Context c) {
        Intent i = new Intent(c, AdminReceiver.class).setAction(ACTION_WATCHDOG);
        return PendingIntent.getBroadcast(c, 1, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
}
