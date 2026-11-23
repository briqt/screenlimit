package com.briqt.screenlimit;

import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * 远程配置（需求 F9）。
 *
 * <p><b>服务端说了算</b>：只要服务端的 {@code version} 比本地已应用的新，就**无条件**落地，
 * 设备侧不做二次确认——这是用户明确选的取舍。代价也写在明处：服务端一旦被攻破，
 * 推一条 {@code master=false} 就能把限时整个关掉。
 *
 * <p>字段是「缺省即不动」的语义：JSON 里没有的键保持本地原样。这样可以只推时段而不碰名单，
 * 避免一次误操作把整份名单冲掉。
 */
final class RemoteConfig {

    private static final String TAG = "ScreenLimit";
    /** 拉取间隔（毫秒）。按时间分频而不按 tick 数：tick 间隔在时段外会放慢。 */
    static final long PULL_EVERY_MS = 60_000L;
    /** 应用列表上报间隔：变化不频繁，10 分钟一次足够。 */
    static final long REPORT_EVERY_MS = 10 * 60_000L;

    private RemoteConfig() {}

    static long appliedVersion(Context c) {
        return Rules.sp(c).getLong("cfg_version", 0L);
    }

    /**
     * 应用一份远程配置。
     * @return 真的应用了返回 true（版本更新且落地成功）
     */
    static boolean apply(Context c, JSONObject j) {
        if (j == null) return false;
        long ver = j.optLong("version", 0L);
        if (ver <= 0 || ver <= appliedVersion(c)) return false;

        StringBuilder changed = new StringBuilder();

        if (j.has("master")) {
            Rules.setMaster(c, j.optBoolean("master"));
            changed.append(" master=").append(j.optBoolean("master"));
        }
        if (j.has("winStart") || j.has("winEnd")) {
            int s = j.optInt("winStart", Rules.winStart(c));
            int e = j.optInt("winEnd", Rules.winEnd(c));
            Rules.setWindow(c, s, e);
            changed.append(" win=").append(s).append('-').append(e);
        }
        if (j.has("guardSettings")) {
            Rules.setGuardSettings(c, j.optBoolean("guardSettings"));
            changed.append(" guard=").append(j.optBoolean("guardSettings"));
        }
        // hideIcon：v0.12.1 起不再支持隐藏桌面图标（见下方 ensureIconVisible），老服务端下发也忽略。
        if (j.has("coolUseMin") || j.has("coolIntervalMin")) {
            int u = j.optInt("coolUseMin", Rules.coolUseMin(c));
            int iv = j.optInt("coolIntervalMin", Rules.coolIntervalMin(c));
            Rules.setCoolParams(c, u, iv);
            changed.append(" cool=").append(u).append('/').append(iv);
        }
        if (j.has("whitelist")) {
            Set<String> wl = new HashSet<>();
            JSONArray a = j.optJSONArray("whitelist");
            for (int i = 0; a != null && i < a.length(); i++) wl.add(a.optString(i));
            Rules.sp(c).edit().putStringSet("whitelist", wl).apply();
            changed.append(" whitelist=").append(wl.size());
        }
        if (j.has("gray")) {
            JSONObject g = j.optJSONObject("gray");
            Set<String> gray = new HashSet<>();
            android.content.SharedPreferences.Editor ed = Rules.sp(c).edit();
            if (g != null) {
                for (Iterator<String> it = g.keys(); it.hasNext(); ) {
                    String pkg = it.next();
                    int q = g.optInt(pkg, 30);
                    gray.add(pkg);
                    ed.putInt("g_" + pkg, Math.max(1, q));
                }
            }
            ed.putStringSet("graylist", gray).apply();
            changed.append(" gray=").append(gray.size());
        }

        Rules.sp(c).edit().putLong("cfg_version", ver).apply();
        Log.i(TAG, "远程配置已应用 version=" + ver + " ·" + changed);
        return true;
    }

    /**
     * 保证桌面图标在。v0.12.0 有过"隐藏桌面图标"开关，OPPO 实测它是个陷阱：
     * ColorOS 桌面会留一个点了只跳应用信息页的幽灵图标（看着像没隐藏），真入口只剩常驻通知；
     * 服务一被杀就谁也进不去，而 adb `pm enable` 改不了别的应用的组件状态，外部救不回来。
     * 防绕过改由"进设置页必须输密码"承担，图标一律保持可见——老版本藏过的这里给恢复回来。
     */
    static void ensureIconVisible(Context c) {
        try {
            android.content.ComponentName alias =
                    new android.content.ComponentName(c, c.getPackageName() + ".LauncherAlias");
            PackageManager pm = c.getPackageManager();
            if (pm.getComponentEnabledSetting(alias)
                    == PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                pm.setComponentEnabledSetting(alias,
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                        PackageManager.DONT_KILL_APP);
                Log.i(TAG, "桌面图标已恢复（旧版隐藏开关的遗留状态）");
            }
        } catch (Exception e) {
            Log.w(TAG, "恢复图标失败", e);
        }
    }

    /**
     * 手机此刻实际生效的规则，随应用名单一起上报。服务端配置页以它为初值和 diff 基准，
     * 下发时只带与它不同的项——否则管理端看到的是自己上次下发的旧值，一保存就把手机上的改动冲掉。
     */
    static JSONObject devState(Context c) {
        try {
            return new JSONObject()
                    .put("master", Rules.master(c))
                    .put("winStart", Rules.winStart(c))
                    .put("winEnd", Rules.winEnd(c))
                    .put("guardSettings", Rules.guardSettings(c))
                    .put("coolUseMin", Rules.coolUseMin(c))
                    .put("coolIntervalMin", Rules.coolIntervalMin(c))
                    .put("applied", appliedVersion(c));
        } catch (Exception e) {
            return null;
        }
    }

    /** 把当前可管理应用与它们的三态、今日用量打包上报，供服务端与管理员端配置页按优先级渲染。 */
    static JSONArray appsPayload(Context c, List<AppCache.App> apps) {
        java.util.List<AppCache.App> sorted = new java.util.ArrayList<>(apps);
        final java.util.Map<String, Integer> todayStats = Rules.statsToday(c);
        sorted.sort((a, b) -> {
            boolean ta = Rules.grantActive(c, a.pkg);
            boolean tb = Rules.grantActive(c, b.pkg);
            if (ta != tb) return ta ? -1 : 1;
            int ma = todayStats.getOrDefault(a.pkg, 0) / 60;
            int mb = todayStats.getOrDefault(b.pkg, 0) / 60;
            if ((ma > 0) != (mb > 0)) return ma > 0 ? -1 : 1;
            if (ma != mb) return mb - ma;
            boolean ga = Rules.isGray(c, a.pkg);
            boolean gb = Rules.isGray(c, b.pkg);
            if (ga != gb) return ga ? -1 : 1;
            if (a.basicTool != b.basicTool) return a.basicTool ? 1 : -1;
            if (a.system != b.system) return a.system ? 1 : -1;
            return a.label.compareToIgnoreCase(b.label);
        });
        JSONArray arr = new JSONArray();
        for (AppCache.App a : sorted) {
            try {
                String st = Rules.isWhitelisted(c, a.pkg) ? "white"
                        : Rules.isGray(c, a.pkg) ? "gray" : "deny";
                int usedMin = todayStats.getOrDefault(a.pkg, 0) / 60;
                JSONObject o = new JSONObject()
                        .put("pkg", a.pkg)
                        .put("label", a.label)
                        .put("state", st);
                if ("gray".equals(st)) o.put("quota", Rules.grayQuota(c, a.pkg));
                if (usedMin > 0) o.put("usedMin", usedMin);
                arr.put(o);
            } catch (Exception ignored) {}
        }
        return arr;
    }
}
