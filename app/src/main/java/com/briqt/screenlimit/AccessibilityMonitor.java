package com.briqt.screenlimit;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

import java.util.HashSet;
import java.util.Set;

public class AccessibilityMonitor extends AccessibilityService {
    private static final String TAG = "ScreenLimit";
    static volatile AccessibilityMonitor INSTANCE;
    static volatile String currentPkg;
    static volatile long eventAt;

    private final Set<String> launchable = new HashSet<>();

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        INSTANCE = this;
        rebuildLaunchable();
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        // 0：系统不攒批、窗口一变立刻送来。原先的 100ms 实测就是被拦应用多露的那 100ms。
        info.notificationTimeout = 0;
        setServiceInfo(info);
        try {
            startForegroundService(new Intent(this, MonitorService.class));
        } catch (Exception ignored) {}
        Log.i(TAG, "accessibility connected");
    }

    private void rebuildLaunchable() {
        launchable.clear();
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo ri : getPackageManager().queryIntentActivities(i, PackageManager.MATCH_ALL)) {
            launchable.add(ri.activityInfo.packageName);
        }
        Intent h = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        for (ResolveInfo ri : getPackageManager().queryIntentActivities(h, PackageManager.MATCH_ALL)) {
            launchable.add(ri.activityInfo.packageName);
        }
        launchable.add(getPackageName());
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        try {
            if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
            CharSequence p = event.getPackageName();
            if (p == null) return;
            String pkg = p.toString();

            // 防拆：先于前台判定跑。这些页面本身多半落在「硬安全」的系统设置/安装器里，
            // 常规判定链根本不会拦它们，必须在这里单独看守。
            CharSequence cn = event.getClassName();
            if (Tamper.guardAndKick(this, pkg, cn == null ? null : cn.toString())) return;
            // 只采纳真实应用/桌面的事件；忽略输入法、系统弹窗等（否则会伪装成前台导致撤防）
            if (!launchable.contains(pkg)) {
                rebuildLaunchable(); // 兼容新安装应用
                if (!launchable.contains(pkg)) return;
            }
            if (pkg.equals(getPackageName())) return;
            currentPkg = pkg;
            eventAt = System.currentTimeMillis();
            MonitorService.notifyForeground(pkg);
        } catch (Throwable t) {
            Log.e(TAG, "event", t);
        }
    }

    @Override
    public void onInterrupt() {}

    @Override
    public boolean onUnbind(Intent intent) {
        INSTANCE = null;
        return super.onUnbind(intent);
    }

    void goHome() {
        performGlobalAction(GLOBAL_ACTION_HOME);
    }
}
