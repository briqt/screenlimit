package com.briqt.screenlimit;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        try {
            context.startForegroundService(new Intent(context, MonitorService.class));
        } catch (Exception ignored) {}
        Watchdog.scheduleDefault(context);   // 闹钟不跨重启，开机要重排
    }
}
