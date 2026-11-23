package com.briqt.screenlimit;

import android.accessibilityservice.AccessibilityService;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * 防拆：限时期间看守那些「用来绕过限时」的系统页面。
 *
 * <p>为什么必须单独做：卸载弹窗在 packageinstaller、无障碍设置和应用信息页都在
 * com.android.settings，这些包在 {@link AppCache#isHardSafe} 里是**永远放行**的
 * （不放行就会把自己锁死，见需求 N5）。所以常规判定链碰不到它们，只能靠无障碍
 * 读当前窗口文本，分辨出「进的是哪个子页」再单独弹回去。
 *
 * <p>手段是全局动作（回桌面；只有被限应用的信息页先退一层），而不是遮挡层——系统设置里弹遮挡层
 * 容易和厂商的安全检测对撞（ColorOS 实测会因为遮挡层行为关掉无障碍）。动作之后 600ms 回头复查，
 * 还在原页面就再回一次桌面。
 *
 * <p><b>固有局限</b>：这套东西依赖无障碍。无障碍一旦被关（ColorOS 安全中心会自动关），
 * 防拆同时失效。它提高的是绕过的摩擦，不是不可绕过——真正不可绕过需要 Device Owner。
 */
final class Tamper {

    private static final String TAG = "ScreenLimit";

    /** 同一个页面别反复弹，两次动作至少隔这么久，避免和系统动画打架形成抖动。 */
    private static final long MIN_INTERVAL_MS = 500;   // 太长就成了"刚被弹走立刻再进"的空窗
    private static long lastKickAt;

    /** 连续尝试的升级策略：短时间内反复撞同一类页面，就从「退一层」升级成「回桌面」。 */
    private static final long STREAK_WINDOW_MS = 8000;
    private static final int STREAK_TO_HOME = 2;
    private static String lastReason;
    private static int streak;

    /** 读窗口树的上限，防止在超长列表页上把主线程拖住。 */
    private static final int MAX_NODES = 400;

    private Tamper() {}

    /**
     * @return true 表示这是个受看守的敏感页面，已经弹回去了，调用方应当中止后续处理。
     */
    static boolean guardAndKick(AccessibilityService svc, String pkg, String cls) {
        if (!Rules.guardSettings(svc) || !Rules.master(svc)) return false;
        if (pkg == null || pkg.equals(svc.getPackageName())) return false;

        String lower = pkg.toLowerCase();
        String clsLower = cls == null ? "" : cls.toLowerCase();

        // 卸载入口按 **Activity 类名** 认，不按包名：AOSP 走 packageinstaller，
        // 而 ColorOS 走 com.oplus.appdetail/...UninstallPackageActivity（真机实测），
        // 按包名列白名单永远追不全厂商。
        boolean installer = clsLower.contains("uninstall")
                || lower.contains("packageinstaller");
        boolean settings = lower.contains("settings") || lower.contains("securitycenter")
                || lower.contains("safecenter") || lower.contains("phonemanager")
                || lower.contains("appdetail");
        // 桌面也要看：ColorOS 上"长按图标 → 卸载"的确认弹窗是 **桌面自己** 弹的
        // （`com.android.launcher`，标题「要卸载"xxx"吗？」），类名里也没有 uninstall，
        // 只按包名/类名认的话这条路整条漏掉（v0.12.3 真机实测漏了）。
        boolean launcher = MonitorService.homePkg(pkg);
        if (!installer && !settings && !launcher) return false;

        long now = System.currentTimeMillis();
        if (now - lastKickAt < MIN_INTERVAL_MS) return false;

        String reason = classify(svc, installer, clsLower);
        if (reason == null) return false;

        // 退一层往往只回到设置的上一页，人还在设置里；同一类页面反复撞就直接送回桌面。
        if (reason.equals(lastReason) && now - lastKickAt < STREAK_WINDOW_MS) streak++;
        else streak = 1;
        lastReason = reason;
        lastKickAt = now;

        // 权限页、无障碍页、卸载确认：一步到位回桌面。「退一层」得等系统退栈动画，还可能落空，
        // 那几百毫秒足够手快的人点下开关（用户实测反馈）。只有应用信息页这类「误入也常见」的才先退一层。
        boolean home = streak >= STREAK_TO_HOME || !reason.contains("被限应用的设置");
        Log.i(TAG, "防拆：拦截 " + pkg + "（" + reason + "）第 " + streak + " 次 → "
                + (home ? "回桌面" : "退一层"));
        svc.performGlobalAction(home
                ? AccessibilityService.GLOBAL_ACTION_HOME
                : AccessibilityService.GLOBAL_ACTION_BACK);
        OverlayGuard.toastGuard(svc.getApplicationContext(), reason);
        verifyLeft(svc, pkg);
        return true;
    }

    private static final android.os.Handler UI = new android.os.Handler(android.os.Looper.getMainLooper());

    /**
     * 「退一层」可能落空：从通知点进来时，返回键先被正在收起的通知栏吃掉，页面纹丝不动，
     * 也不会再来新的窗口事件让我们升级（真机实测：悬浮窗权限页就这么留住了）。
     * 所以过一会儿回头看一眼，还在原来那个包里就直接回桌面。
     */
    private static void verifyLeft(AccessibilityService svc, String pkg) {
        UI.postDelayed(() -> {
            try {
                AccessibilityNodeInfo r = svc.getRootInActiveWindow();
                CharSequence now = r == null ? null : r.getPackageName();
                if (r != null) r.recycle();
                if (now != null && pkg.equals(now.toString())) {
                    Log.i(TAG, "防拆：还没离开 " + pkg + " → 再回桌面");
                    svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
                }
            } catch (Throwable t) {
                Log.w(TAG, "verifyLeft", t);
            }
        }, 600);
    }

    /** @return 命中的原因文案；null 表示这是普通页面，不拦。 */
    private static String classify(AccessibilityService svc, boolean installer, String cls) {
        if (installer) return "限时期间不允许卸载应用";
        AccessibilityNodeInfo root = svc.getRootInActiveWindow();
        if (root == null) return null;
        StringBuilder sb = new StringBuilder(512);
        try {
            collect(root, sb, 0);
        } finally {
            root.recycle();
        }
        String t = sb.toString();

        // ① 卸载确认：类名/包名已经表明是卸载入口，文本只作辅助，不作必要条件
        if (installer) return "限时期间不允许卸载应用";
        // ①' 桌面弹的卸载确认 / 长按图标的气泡菜单。按文案认，因为包名和类名都只是"桌面"。
        //     用词挑得死一点，避免把商店、管家里普通带"卸载"字样的页面也拦了。
        if (has(t, "要卸载") || has(t, "确定要卸载") || has(t, "卸载此应用")
                || (has(t, "卸载") && has(t, "彻底删除"))
                || (has(t, "卸载") && has(t, "应用信息") && has(t, "分享"))) {
            return "限时期间不允许卸载应用";
        }
        // ② 无障碍设置：关掉它就等于废掉防拆和加速判定
        if (has(t, "无障碍") || has(t, "辅助功能") || has(t, "accessibility")) {
            return "限时期间不允许改无障碍设置";
        }
        // ②'' 悬浮窗 / 使用情况访问权限页：收回任何一个，拦截就整个失效。
        //      生效期间系统常驻「xx 正在其他应用的上层显示内容」通知，点一下就直达这一页，必须看住。
        //      要求页面里出现本应用名，只看本应用那一行/那一页，别的应用的权限照常能改。
        String me = selfLabel(svc);
        if (me != null && has(t, me)
                && (has(t, "悬浮窗") || has(t, "上层显示") || has(t, "显示在其他应用")
                    || has(t, "使用情况访问") || has(t, "使用记录访问") || has(t, "查看使用情况")
                    || has(t, "display over other apps") || has(t, "usage access"))) {
            return "限时期间不允许改本应用权限";
        }
        // ②' 日期时间设置：判定虽然走可信时钟（改了系统时间也拦得住），但改时区仍会挪动时段，
        //     而且让人在这页折腾本身就没意义。要「自动设置」类字样同时出现才认，避免误伤带日期字样的普通页面。
        if ((has(t, "日期和时间") || has(t, "日期与时间") || has(t, "date & time") || has(t, "date and time"))
                && (has(t, "自动") || has(t, "时区") || has(t, "automatic") || has(t, "time zone"))) {
            return "限时期间不允许改系统时间";
        }
        // ③④ 应用信息页。两个信号：Activity 类名像详情页，或页面同时有「卸载/强行停止」。
        boolean detailCls = cls.contains("installedappdetails") || cls.contains("appinfodashboard")
                || cls.contains("applicationdetails") || cls.contains("appdetail");
        boolean detailText = (has(t, "卸载") || has(t, "uninstall"))
                && (has(t, "强行停止") || has(t, "结束运行") || has(t, "停止运行")
                    || has(t, "force stop") || has(t, "存储占用") || has(t, "权限管理"));
        if (detailCls || detailText) {
            // 本应用：ColorOS 详情页只显示应用名、不显示包名，两个都试
            String self = svc.getPackageName();
            String selfLabel = selfLabel(svc);
            if (has(t, self) || (selfLabel != null && has(t, selfLabel))) {
                return "限时期间不允许改本应用设置";
            }
            if (mentionsManagedApp(svc, t)) return "限时期间不允许改被限应用的设置";
            Log.i(TAG, "防拆：详情页但认不出是哪个应用，放行（cls=" + cls + "）");
        }
        return null;
    }

    /** 页面里是否提到了某个「当前处于受限态」的应用。包名和应用名都试——ColorOS 只给后者。 */
    private static boolean mentionsManagedApp(AccessibilityService svc, String pageText) {
        MonitorService m = MonitorService.INSTANCE;
        if (m == null) return false;
        for (String pkg : m.managedPkgsSnapshot()) {
            if (pageText.contains(pkg)) return true;
        }
        for (String label : m.managedLabelsSnapshot()) {
            if (pageText.contains(label)) return true;
        }
        return false;
    }

    private static String selfLabel(AccessibilityService svc) {
        try {
            return svc.getString(R.string.app_name).toLowerCase();
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean has(String haystack, String needle) {
        return haystack.contains(needle.toLowerCase());
    }

    /** 把窗口树里的可见文本与 content-desc 拼成一个小写串，够做关键词判定就行。 */
    private static void collect(AccessibilityNodeInfo n, StringBuilder sb, int depth) {
        if (n == null || depth > 24 || sb.length() > 8192) return;
        CharSequence t = n.getText();
        if (t != null) sb.append(t.toString().toLowerCase()).append('\n');
        CharSequence d = n.getContentDescription();
        if (d != null) sb.append(d.toString().toLowerCase()).append('\n');
        CharSequence v = n.getViewIdResourceName();
        if (v != null) sb.append(v.toString().toLowerCase()).append('\n');
        int cnt = Math.min(n.getChildCount(), MAX_NODES);
        for (int i = 0; i < cnt; i++) {
            AccessibilityNodeInfo c = n.getChild(i);
            if (c == null) continue;
            collect(c, sb, depth + 1);
            c.recycle();
        }
    }
}
