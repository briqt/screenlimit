package com.briqt.screenlimit;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;

/**
 * 看门狗：定期确认 {@link MonitorService} 还活着，死了就拉起来。
 *
 * <p>为什么需要：ColorOS 的内存管理会杀掉「没加锁」的后台应用，前台服务也保不住（R1）。
 * `START_STICKY` 只在系统自己回收时补起，被一键清理 / 手动划掉这类"用户行为"杀掉时不补。
 *
 * <p>使用非精确闹钟而非精确闹钟：精确闹钟在 Android 13+ 要用户单独授权，
 * 而看门狗晚几分钟无所谓——它只是兜底，正常路径是前台服务一直活着。
 * Android 15+ 已收紧 {@code SYSTEM_ALERT_WINDOW} 在无可见悬浮窗时的后台拉起豁免，
 * 此处从广播拉起前台服务主要依赖「忽略电池优化」（{@code REQUEST_IGNORE_BATTERY_OPTIMIZATIONS}）
 * 与处于活跃绑定态的无障碍服务（{@code AccessibilityService}）这两项后台启动豁免。
 */
public class Watchdog extends BroadcastReceiver {

    private static final String TAG = "SL/Watchdog";
    static final String ACTION = "com.briqt.screenlimit.WATCHDOG";
    /** 5 分钟。Doze 里会被系统拉长到 ~9 分钟，够用。 */
    private static final long INTERVAL_MS = 5 * 60_000L;

    @Override
    public void onReceive(Context c, Intent i) {
        if (i == null || !ACTION.equals(i.getAction())) return;
        boolean alive = MonitorService.INSTANCE != null;
        if (!alive) {
            Log.i(TAG, "监控服务不在，拉起来");
            try {
                c.startForegroundService(new Intent(c, MonitorService.class));
            } catch (Exception e) {
                Log.w(TAG, "拉起失败", e);
            }
        }
        schedule(c, INTERVAL_MS);
    }

    /**
     * 排下一次检查。重复调用只会覆盖同一个 PendingIntent，不会堆积。
     * 使用非唤醒型 ELAPSED_REALTIME：息屏深睡时不需要管屏幕、不强行唤醒 CPU（避免被 ColorOS 电池管家判定为后台高频唤醒而强杀），
     * 亮屏瞬间系统会自动派发已到期的非唤醒闹钟。
     */
    static void schedule(Context c, long delayMs) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            am.set(AlarmManager.ELAPSED_REALTIME,
                    SystemClock.elapsedRealtime() + delayMs, pi(c));
        } catch (Exception e) {
            Log.w(TAG, "排看门狗失败", e);
        }
    }

    static void scheduleDefault(Context c) {
        schedule(c, INTERVAL_MS);
    }

    private static PendingIntent pi(Context c) {
        Intent i = new Intent(c, Watchdog.class).setAction(ACTION);
        return PendingIntent.getBroadcast(c, 1, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
}
