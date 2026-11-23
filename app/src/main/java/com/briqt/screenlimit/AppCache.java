package com.briqt.screenlimit;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;

/**
 * 应用分类：
 * - 硬安全（桌面/系统UI/输入法/设置/安装器/权限控制器/本应用）：永不拦截，不出现在列表
 * - 基础工具（系统自带的电话短信相机等）：可一键加入白名单
 * - 其他应用（含浏览器/影音/游戏/商店等系统应用和全部第三方应用）：纳入名单管理
 */
public final class AppCache {
    private final Context appCtx;
    private final PackageManager pm;
    private volatile Set<String> launchable = new HashSet<>();
    private volatile Set<String> homePkgs = new HashSet<>();
    private volatile Set<String> imePkgs = new HashSet<>();
    private final Map<String, Boolean> system = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, String> labels = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, Drawable> icons = new java.util.concurrent.ConcurrentHashMap<>();
    /** 负向探测节流：对不在 launchable 里的陌生包名，3 秒内最多触发一次 rebuild，既保证新装应用秒级发现，又避免对无图标后台服务频繁重扫。 */
    private final Map<String, Long> unlaunchableCheckedAt = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile Runnable onChange;

    /**
     * 硬安全特征词。**只对系统应用生效**——见 {@link #isHardSafe}。
     * 原因：这里是子串匹配，若对第三方应用也生效，任何包名带 "launcher" 的第三方应用
     * （游戏启动器、第三方桌面壳）就会永久免疫拦截，等于在 default-deny 上开了个洞。
     */
    private static final String[] HARD_SAFE = {
            "launcher", "systemui", "inputmethod", "com.android.settings",
            "packageinstaller", "permissioncontroller", "printspooler"
    };

    /** 本应用自身：任何情况下不拦自己，否则改规则的入口就没了。 */
    private static final String SELF = "com.briqt.screenlimit";

    /** 基础工具特征：包名包含任一即候选。天气/录音/日历/计算器这类系统自带工具就落在这里。 */
    private static final String[] TOOL_TOKENS = {
            "dialer", "phone", "contacts", "mms", "sms",
            "camera", "gallery", "photos", "filemanager", "documentsui",
            "calculator", "deskclock", "clock", "calendar",
            "recorder", "memo", "note", "compass", "weather", "scanner",
            "soundrecorder", "flashlight"
    };

    /**
     * 厂商管家/清理类：**绝不进一键白名单**，默认禁止。
     * `com.coloros.phonemanager` 本来会被 "phone" 这个词误判成基础工具，v0.12.3 起单独排除——
     * 一键清理后台就在那里面，被管者进得去就能把守护清掉，比多数被限应用危险得多。
     * 管理者自己要用（开自启动、看耗电）时，从设置页把它临时放行或走应急使用。
     */
    private static final String[] MANAGER_TOKENS = {
            "phonemanager", "safecenter", "securitycenter", "guardelf", "cleaner", "optimizer"
    };

    /** 娱乐/内容/商店特征：命中则排除出一键白名单（优先级高于 TOOL_TOKENS）。 */
    private static final String[] FUN_TOKENS = {
            "browser", "video", "music", "game", "theme", "book", "reader",
            "comic", "wallpaper", "community", "market", "store", "play",
            "movie", "player", "tv", "news", "cast", "karaoke", "live",
            "shortvideo", "assistant", "wallet", "pay", "aimemory"
    };

    AppCache(Context c) {
        this.appCtx = c.getApplicationContext();
        this.pm = this.appCtx.getPackageManager();
        rebuild(this.appCtx);
    }

    void setOnChangeListener(Runnable r) {
        this.onChange = r;
    }

    synchronized void rebuild(Context c) {
        Context ctx = c != null ? c.getApplicationContext() : appCtx;
        Set<String> newLaunchable = new HashSet<>();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(main, PackageManager.MATCH_ALL)) {
            if (ri.activityInfo != null && ri.activityInfo.packageName != null) {
                newLaunchable.add(ri.activityInfo.packageName);
            }
        }
        Set<String> newHome = new HashSet<>();
        Intent homeIntent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        for (ResolveInfo ri : pm.queryIntentActivities(homeIntent, PackageManager.MATCH_ALL)) {
            if (ri.activityInfo != null && ri.activityInfo.packageName != null) {
                newHome.add(ri.activityInfo.packageName);
            }
        }
        // 输入法问系统要，不靠包名里有没有 "inputmethod"（搜狗/百度等都不含这个词）
        Set<String> newIme = new HashSet<>();
        try {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager)
                            ctx.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                for (android.view.inputmethod.InputMethodInfo i : imm.getInputMethodList()) {
                    newIme.add(i.getPackageName());
                }
            }
        } catch (Throwable ignored) {}

        boolean changed = !newLaunchable.equals(this.launchable);
        this.launchable = newLaunchable;
        this.homePkgs = newHome;
        this.imePkgs = newIme;
        if (changed) {
            Runnable cb = this.onChange;
            if (cb != null) cb.run();
        }
    }

    boolean isSystem(String pkg) {
        Boolean v = system.get(pkg);
        if (v == null) {
            try {
                ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                v = (ai.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
            } catch (PackageManager.NameNotFoundException e) {
                v = Boolean.FALSE;
            }
            system.put(pkg, v);
        }
        return v;
    }

    boolean isHardSafe(String pkg) {
        if (pkg == null) return true;
        if (SELF.equals(pkg)) return true;
        if (homePkgs.contains(pkg)) return true;      // CATEGORY_HOME 实测出来的桌面
        if (imePkgs.contains(pkg)) return true;       // 系统登记的输入法，不靠包名猜
        if (!isSystem(pkg)) return false;             // 第三方一律不吃下面的子串匹配
        String l = pkg.toLowerCase();
        for (String t : HARD_SAFE) if (l.contains(t)) return true;
        return false;
    }

    /** 是不是桌面（CATEGORY_HOME 实测出来的）。防拆要用：卸载确认弹窗是桌面自己弹的。 */
    boolean isHome(String pkg) { return pkg != null && homePkgs.contains(pkg); }

    /**
     * 纳入限时管理（有桌面图标、非硬安全）。
     * 若遇到尚未在 {@link #launchable} 里的非硬安全包名（例如刚安装的应用、ColorOS 拦截了 PACKAGE_ADDED 广播），
     * 立即按需触发一次 {@link #rebuild}，确保新安装应用打开的第一拍即被识别、统计并按 default-deny 拦截。
     */
    boolean isManageable(String pkg) {
        if (pkg == null || isHardSafe(pkg)) return false;
        if (!launchable.contains(pkg)) {
            long now = android.os.SystemClock.elapsedRealtime();
            Long last = unlaunchableCheckedAt.get(pkg);
            if (last == null || now - last > 3_000L) {
                unlaunchableCheckedAt.put(pkg, now);
                rebuild(appCtx);
            }
        }
        return launchable.contains(pkg) && !isHardSafe(pkg);
    }

    /** 系统基础工具：系统应用 + 工具特征 + 不含娱乐特征 + 不是厂商管家。 */
    boolean isBasicTool(String pkg) {
        if (!isSystem(pkg) || !isManageable(pkg)) return false;
        if (isManagerApp(pkg)) return false;
        String l = pkg.toLowerCase();
        boolean tool = false;
        for (String t : TOOL_TOKENS) if (l.contains(t)) { tool = true; break; }
        if (!tool) return false;
        for (String t : FUN_TOKENS) if (l.contains(t)) return false;
        return true;
    }

    /** 厂商管家/清理类应用。 */
    boolean isManagerApp(String pkg) {
        if (pkg == null || !isSystem(pkg)) return false;
        String l = pkg.toLowerCase();
        for (String t : MANAGER_TOKENS) if (l.contains(t)) return true;
        return false;
    }

    /** 应用图标，失败返回 null（调用方负责留白）。列表反复重建，故带缓存。 */
    Drawable icon(String pkg) {
        if (pkg == null) return null;
        Drawable cached = icons.get(pkg);
        if (cached != null) return cached;
        try {
            Drawable d = pm.getApplicationIcon(pkg);
            if (d != null) icons.put(pkg, d);
            return d;
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    String label(String pkg) {
        String l = labels.get(pkg);
        if (l != null) return l;
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            l = pm.getApplicationLabel(ai).toString();
        } catch (PackageManager.NameNotFoundException e) {
            l = pkg;
        }
        labels.put(pkg, l);
        return l;
    }

    static class App {
        final String pkg;
        final String label;
        final boolean system;
        final boolean basicTool;
        App(String pkg, String label, boolean system, boolean basicTool) {
            this.pkg = pkg; this.label = label; this.system = system; this.basicTool = basicTool;
        }
    }

    /**
     * 全部可管理应用：第三方常用应用排最前，其次非工具类系统应用，系统基础工具排最后。
     * 每次调用均重新扫描 PackageManager，保证新安装/卸载的应用在设置页与服务端上报中永不漏项。
     */
    List<App> listManageable(Context c) {
        rebuild(c);
        List<App> out = new ArrayList<>();
        for (String pkg : new HashSet<>(launchable)) {
            if (!isManageable(pkg)) continue;
            out.add(new App(pkg, label(pkg), isSystem(pkg), isBasicTool(pkg)));
        }
        out.sort((a, b) -> {
            if (a.basicTool != b.basicTool) return a.basicTool ? 1 : -1;
            if (a.system != b.system) return a.system ? 1 : -1;
            return a.label.compareToIgnoreCase(b.label);
        });
        return out;
    }
}
