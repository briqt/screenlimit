package com.briqt.screenlimit.admin;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 开机自启：若已开启审批提醒且处于已登录态，自动恢复后台值守服务与低频巡检闹钟。 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        WatchService.sync(context);
    }
}
