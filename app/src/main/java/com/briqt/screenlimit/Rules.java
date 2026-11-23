package com.briqt.screenlimit;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

/** 规则、用量、授权的全部持久化状态。实时读取，改完立即生效。 */
public final class Rules {
    static final String PREFS = "limits";

    static final int PASSWORD_GRANT_MIN = 30;

    // ---------- 冷却式应急使用（v0.9.0 取代原「临时使用 3 分钟 × 每日 5 次」）----------
    // 旧模型的问题：每天 5 次一次性配发，白天一口气用光，晚上真要用时一次都不剩。
    // 改成回血制——用一次进冷却，冷却满自动恢复，全天分布更均匀，也不用管跨天重置。
    //
    // **冷却是整机全局的，不是每应用**：按应用算等于应用越多额度越多，
    // 挨个应用点一遍就能连续用下去，这条后门就形同虚设了。
    static final int DEF_COOL_USE_MIN = 3;        // 每次可用分钟
    static final int DEF_COOL_INTERVAL_MIN = 50;  // 两次之间的冷却间隔（全局）

    private Rules() {}

    static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---------- 可信时钟 ----------
    // 判定如果直接信 System.currentTimeMillis()，被管者把系统时间拨到时段外就全放开了。
    // 所以以 elapsedRealtime（开机以来的单调时钟，改系统时间不影响）+ 一次性锚点为准：
    // 锚点 = 首次运行/开机后第一拍时的「系统时间 − elapsed」，之后系统时间怎么改都不影响判定。
    // 重启后 elapsed 归零，靠 anchor_elapsed（每分钟刷新）判出重启并以开机时的系统时间重新锚定。

    private static volatile long anchor = Long.MIN_VALUE;
    private static long anchorTouchedAt;

    private static void ensureAnchor(Context c) {
        if (anchor != Long.MIN_VALUE) return;
        synchronized (Rules.class) {
            if (anchor != Long.MIN_VALUE) return;
            SharedPreferences p = sp(c);
            long a = p.getLong("clock_anchor", 0L);
            long el = p.getLong("clock_anchor_elapsed", -1L);
            long e = SystemClock.elapsedRealtime();
            if (a == 0L || el < 0 || e < el) {
                a = System.currentTimeMillis() - e;
                p.edit().putLong("clock_anchor", a).putLong("clock_anchor_elapsed", e).apply();
            }
            anchor = a;
        }
    }

    /** 可信的当前 epoch 毫秒。全部判定与授权到期都用它，不用 System.currentTimeMillis()。 */
    static long now(Context c) {
        ensureAnchor(c);
        long e = SystemClock.elapsedRealtime();
        if (e - anchorTouchedAt > 60_000L) {
            anchorTouchedAt = e;
            sp(c).edit().putLong("clock_anchor_elapsed", e).apply();
        }
        return e + anchor;
    }

    /** 没有 Context 的调用方（拦截页倒计时）用这个；锚点还没建时退回系统时间。 */
    static long nowCached() {
        return anchor == Long.MIN_VALUE ? System.currentTimeMillis()
                : SystemClock.elapsedRealtime() + anchor;
    }

    /** 系统时钟相对可信时钟的偏差（毫秒）。正数＝系统时间被拨快。 */
    static long clockDrift(Context c) { return System.currentTimeMillis() - now(c); }

    /** 管理者确认系统时间是对的：以当前系统时间重新锚定。 */
    static void reanchorClock(Context c) {
        synchronized (Rules.class) {
            long e = SystemClock.elapsedRealtime();
            long a = System.currentTimeMillis() - e;
            sp(c).edit().putLong("clock_anchor", a).putLong("clock_anchor_elapsed", e).apply();
            anchor = a;
        }
    }

    static LocalDateTime localNow(Context c) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(now(c)), ZoneId.systemDefault());
    }

    static String today(Context c) { return localNow(c).toLocalDate().toString(); }

    // ---------- 主开关 / 时间窗 ----------

    static boolean master(Context c) { return sp(c).getBoolean("master", false); }

    static void setMaster(Context c, boolean on) { sp(c).edit().putBoolean("master", on).apply(); }

    /** 分钟数 0..1440；end<=start 表示跨午夜。默认全天。 */
    static int winStart(Context c) { return sp(c).getInt("win_start", 0); }
    static int winEnd(Context c) { return sp(c).getInt("win_end", 1440); }

    static void setWindow(Context c, int start, int end) {
        sp(c).edit().putInt("win_start", start).putInt("win_end", end).apply();
    }

    static int nowMinute(Context c) { return localNow(c).toLocalTime().toSecondOfDay() / 60; }

    static boolean inWindow(Context c) {
        int now = nowMinute(c);
        int s = winStart(c), e = winEnd(c);
        if (e <= s) return now >= s || now < e;
        return now >= s && now < e;
    }

    /** 0:00–24:00 或 start==end 都等价于全天限制。 */
    static boolean allDay(Context c) {
        int s = winStart(c), e = winEnd(c);
        return (s == 0 && e >= 1440) || s == e;
    }

    static String fmtClock(int min) {
        min = ((min % 1440) + 1440) % 1440;
        return String.format(java.util.Locale.US, "%02d:%02d", min / 60, min % 60);
    }

    /** 「22:00–07:00」这样的时段文案；全天返回「全天」。 */
    static String windowText(Context c) {
        if (allDay(c)) return "全天";
        int s = winStart(c), e = winEnd(c);
        return fmtClock(s) + "–" + (e >= 1440 ? "24:00" : fmtClock(e));
    }

    /** 距离下一次时段开始还有几分钟；已在时段内或全天返回 -1。 */
    static int minutesToWindowStart(Context c) {
        if (allDay(c) || inWindow(c)) return -1;
        int now = nowMinute(c);
        int s = winStart(c);
        return s >= now ? s - now : s + 1440 - now;
    }

    /** 距离本次时段结束还有几分钟；不在时段内返回 -1，全天返回 Integer.MAX_VALUE。 */
    static int minutesToWindowEnd(Context c) {
        if (!inWindow(c)) return -1;
        if (allDay(c)) return Integer.MAX_VALUE;
        int now = nowMinute(c);
        int e = winEnd(c) % 1440;
        return e > now ? e - now : e + 1440 - now;
    }

    /**
     * 被拦应用什么时候能再用，给拦截页写在原因后面。
     * 灰名单配额用完：取「时段结束」和「0 点配额重置」中先到的那个。
     * 默认禁止：时段结束；全天限制则没有恢复时刻。
     */
    static String releaseText(Context c, boolean gray) {
        int toEnd = minutesToWindowEnd(c);
        int toMidnight = minutesToMidnight(c);
        if (gray) {
            if (toEnd < 0 || toMidnight <= toEnd) return "0 点后恢复";
            return fmtClock(winEnd(c)) + " 时段结束后恢复";
        }
        if (toEnd == Integer.MAX_VALUE) return "全天限制";
        if (toEnd < 0) return "";
        return fmtClock(winEnd(c)) + " 后恢复";
    }

    // ---------- 白名单 / 灰名单 ----------

    static Set<String> whitelist(Context c) {
        return new HashSet<>(sp(c).getStringSet("whitelist", new HashSet<>()));
    }

    static boolean isWhitelisted(Context c, String pkg) {
        return sp(c).getStringSet("whitelist", new HashSet<>()).contains(pkg);
    }

    static void setWhitelisted(Context c, String pkg, boolean yes) {
        Set<String> s = whitelist(c);
        if (yes) { s.add(pkg); } else { s.remove(pkg); }
        sp(c).edit().putStringSet("whitelist", s).apply();
    }

    /** 灰名单集合（有每日配额）。 */
    static Set<String> graylist(Context c) {
        return new HashSet<>(sp(c).getStringSet("graylist", new HashSet<>()));
    }

    static boolean isGray(Context c, String pkg) {
        return sp(c).getStringSet("graylist", new HashSet<>()).contains(pkg);
    }

    static void setGray(Context c, String pkg, Integer minutes) {
        SharedPreferences.Editor ed = sp(c).edit();
        Set<String> s = graylist(c);
        if (minutes == null) {
            s.remove(pkg);
            ed.remove("g_" + pkg);
        } else {
            s.add(pkg);
            ed.putInt("g_" + pkg, Math.max(1, minutes));
        }
        ed.putStringSet("graylist", s).apply();
    }

    static int grayQuota(Context c, String pkg) {
        return sp(c).getInt("g_" + pkg, 30);
    }

    // ---------- 用量 ----------

    static int usedSecondsToday(Context c, String pkg) {
        return sp(c).getInt("u_" + today(c) + "_" + pkg, 0);
    }

    static void setUsedToday(Context c, String pkg, int seconds) {
        sp(c).edit().putInt("u_" + today(c) + "_" + pkg, Math.max(0, seconds)).apply();
    }

    // ---------- 今日使用统计（与配额计时分开）----------
    // 配额只在「主开关开 + 时段内」计时（u_ 键），统计则全天亮屏都记（s_ 键），
    // 不然守护页那张「今日使用」在时段外永远是空的。两者口径不同，不能共用一个键。

    static int statSecondsToday(Context c, String pkg) {
        return sp(c).getInt("s_" + today(c) + "_" + pkg, 0);
    }

    static void setStatToday(Context c, String pkg, int seconds) {
        sp(c).edit().putInt("s_" + today(c) + "_" + pkg, Math.max(0, seconds)).apply();
    }

    /** 今日所有有统计的包 → 秒数。 */
    static java.util.Map<String, Integer> statsToday(Context c) {
        String prefix = "s_" + today(c) + "_";
        java.util.Map<String, Integer> out = new java.util.HashMap<>();
        for (java.util.Map.Entry<String, ?> e : sp(c).getAll().entrySet()) {
            if (e.getKey().startsWith(prefix) && e.getValue() instanceof Integer) {
                out.put(e.getKey().substring(prefix.length()), (Integer) e.getValue());
            }
        }
        return out;
    }

    /** 跨天时清掉非今日的用量/统计/隔日授权键，别让 prefs 无限长，同时兑现「授权次日 0 点清零」。 */
    static void pruneOldDays(Context c) {
        String today = today(c);
        String u = "u_" + today + "_", s = "s_" + today + "_";
        SharedPreferences p = sp(c);
        SharedPreferences.Editor ed = p.edit();
        int n = 0;
        for (java.util.Map.Entry<String, ?> e : p.getAll().entrySet()) {
            String k = e.getKey();
            boolean dated = k.startsWith("u_") || k.startsWith("s_") || k.startsWith("t_");
            if (dated && !k.startsWith(u) && !k.startsWith(s)) {
                ed.remove(k);
                n++;
            } else if (k.startsWith("grant_day_") && !today.equals(e.getValue())) {
                ed.remove(k);
                n++;
            } else if (k.startsWith("grant_sec_day_") && !today.equals(e.getValue())) {
                String pkg = k.substring("grant_sec_day_".length());
                ed.remove(k).remove("grant_sec_" + pkg);
                n++;
            } else if (k.startsWith("grant_until_")) {
                long until = e.getValue() instanceof Long ? (Long) e.getValue() : 0L;
                if (until <= now(c)) {
                    ed.remove(k);
                    n++;
                }
            }
        }
        if (n > 0) ed.apply();
    }

    // ---------- 冷却式应急使用 & 按应用前台累计时长授权 ----------

    /** 每次应急可用分钟数（可被远程配置覆盖）。 */
    static int coolUseMin(Context c) { return sp(c).getInt("cool_use_min", DEF_COOL_USE_MIN); }

    /** 冷却间隔分钟数（可被远程配置覆盖）。 */
    static int coolIntervalMin(Context c) {
        return sp(c).getInt("cool_interval_min", DEF_COOL_INTERVAL_MIN);
    }

    static void setCoolParams(Context c, int useMin, int intervalMin) {
        sp(c).edit()
                .putInt("cool_use_min", Math.max(1, useMin))
                .putInt("cool_interval_min", Math.max(1, intervalMin))
                .apply();
    }

    /** 下一次可以应急使用的 epoch 毫秒（**全局一份**）；已到点则返回 0。 */
    static long coolReadyAt(Context c) {
        long at = sp(c).getLong("cool_until", 0L);
        return at > now(c) ? at : 0L;
    }

    static boolean coolReady(Context c) { return coolReadyAt(c) == 0L; }

    /**
     * 应急使用一次：授予当前应用 {@link #coolUseMin} 分钟的**前台可用时长**（打开才扣减，次日清零），
     * 并把下次可用时间推到 **本次开始 + 冷却间隔**（全局自然时间冷却）。
     * @return 授权后该应用的剩余可用秒数；仍在冷却中返回 0。
     */
    static long grantCool(Context c, String pkg) {
        if (!coolReady(c)) return 0;
        long now = now(c);
        sp(c).edit().putLong("cool_until", now + coolIntervalMin(c) * 60_000L).apply();
        return writeGrant(c, pkg, coolUseMin(c));
    }

    /** 授权时长选项。TODAY 是哨兵值，落地时标记当天无限放行（次日 0 点清零）。 */
    static final int GRANT_TODAY = -1;

    /** 距离次日 00:00 还有多少分钟（向上取整，至少 1）。 */
    static int minutesToMidnight(Context c) {
        LocalTime now = localNow(c).toLocalTime();
        int left = (24 * 60) - (now.toSecondOfDay() / 60);
        return Math.max(1, left);
    }

    /** 迁移旧版绝对时间戳 grant_until_<pkg> 到按应用前台秒数余量，升级不丢正在生效的授权。 */
    private static void migrateLegacyGrant(Context c, String pkg) {
        if (pkg == null) return;
        SharedPreferences p = sp(c);
        String legacyKey = "grant_until_" + pkg;
        if (!p.contains(legacyKey)) return;
        long until = p.getLong(legacyKey, 0L);
        SharedPreferences.Editor ed = p.edit().remove(legacyKey);
        long leftMs = until - now(c);
        String today = today(c);
        if (leftMs > 2 * 3600_000L) {
            ed.putString("grant_day_" + pkg, today);
        } else if (leftMs > 0) {
            int sec = (int) Math.min(leftMs / 1000L, 12 * 3600L);
            ed.putInt("grant_sec_" + pkg, sec).putString("grant_sec_day_" + pkg, today);
        }
        ed.apply();
    }

    /**
     * 写入单应用临时授权：
     * - {@link #GRANT_TODAY}（或 >=720 分钟）：今日 24:00 前不再限制该应用；
     * - 其余正整数分钟：累加到该应用今日的**前台可用秒数余量**（仅在该应用前台亮屏使用时按秒扣减，不打开不消耗，次日 0 点清零）。
     * @return 授权后该应用的剩余可用秒数。
     */
    static long writeGrant(Context c, String pkg, int minutes) {
        migrateLegacyGrant(c, pkg);
        String today = today(c);
        SharedPreferences p = sp(c);
        SharedPreferences.Editor ed = p.edit().remove("grant_until_" + pkg);
        if (minutes == GRANT_TODAY || minutes >= 720) {
            ed.putString("grant_day_" + pkg, today)
                    .remove("grant_sec_" + pkg)
                    .remove("grant_sec_day_" + pkg)
                    .apply();
            return minutesToMidnight(c) * 60L;
        }
        int cur = today.equals(p.getString("grant_sec_day_" + pkg, ""))
                ? Math.max(0, p.getInt("grant_sec_" + pkg, 0)) : 0;
        int next = Math.min(12 * 3600, cur + Math.max(1, minutes) * 60);
        ed.putInt("grant_sec_" + pkg, next)
                .putString("grant_sec_day_" + pkg, today)
                .remove("grant_day_" + pkg)
                .apply();
        return next;
    }

    /**
     * 按选定时长授权。{@link #GRANT_TODAY} → 今日全天放行；其余按该应用前台累计使用分钟数。
     * @return 授权后该应用的剩余可用秒数。
     */
    static long grantFor(Context c, String pkg, int minutesOrToday) {
        return writeGrant(c, pkg, minutesOrToday);
    }

    /** 是否处于「今日不再限制」授权中。 */
    static boolean grantTodayActive(Context c, String pkg) {
        if (pkg == null) return false;
        migrateLegacyGrant(c, pkg);
        return today(c).equals(sp(c).getString("grant_day_" + pkg, ""));
    }

    /**
     * 该应用今日剩余的临时授权可用秒数（仅前台使用时扣减；切后台或锁屏不扣）。
     * 若为「今日不再限制」，返回距离次日 00:00 的秒数。
     */
    static int grantRemainingSeconds(Context c, String pkg) {
        if (pkg == null) return 0;
        migrateLegacyGrant(c, pkg);
        if (grantTodayActive(c, pkg)) return minutesToMidnight(c) * 60;
        SharedPreferences p = sp(c);
        if (!today(c).equals(p.getString("grant_sec_day_" + pkg, ""))) return 0;
        return Math.max(0, p.getInt("grant_sec_" + pkg, 0));
    }

    /** 消耗该应用 {@code stepSec} 秒的临时授权余量，返回扣减后的剩余秒数。 */
    static int consumeGrantSeconds(Context c, String pkg, int stepSec) {
        if (pkg == null) return 0;
        if (grantTodayActive(c, pkg)) return minutesToMidnight(c) * 60;
        int rem = grantRemainingSeconds(c, pkg);
        if (rem <= 0) return 0;
        int next = Math.max(0, rem - Math.max(0, stepSec));
        SharedPreferences.Editor ed = sp(c).edit();
        if (next <= 0) {
            ed.remove("grant_sec_" + pkg).remove("grant_sec_day_" + pkg);
        } else {
            ed.putInt("grant_sec_" + pkg, next).putString("grant_sec_day_" + pkg, today(c));
        }
        ed.apply();
        return next;
    }

    /** 主动提前结束某个应用的临时授权，立即恢复受控。 */
    static void revokeGrant(Context c, String pkg) {
        if (pkg == null) return;
        sp(c).edit()
                .remove("grant_day_" + pkg)
                .remove("grant_sec_" + pkg)
                .remove("grant_sec_day_" + pkg)
                .remove("grant_until_" + pkg)
                .apply();
    }

    /**
     * 当前所有仍在生效的临时授权（包名 → 剩余可用秒数；若为「今日不限」则值为 {@link #GRANT_TODAY}）。
     */
    static java.util.Map<String, Integer> activeGrants(Context c) {
        String today = today(c);
        SharedPreferences p = sp(c);
        java.util.Map<String, Integer> out = new java.util.LinkedHashMap<>();
        for (java.util.Map.Entry<String, ?> e : p.getAll().entrySet()) {
            String k = e.getKey();
            if (k.startsWith("grant_day_") && today.equals(e.getValue())) {
                out.put(k.substring("grant_day_".length()), GRANT_TODAY);
            } else if (k.startsWith("grant_sec_day_") && today.equals(e.getValue())) {
                String pkg = k.substring("grant_sec_day_".length());
                if (out.containsKey(pkg)) continue;
                int sec = p.getInt("grant_sec_" + pkg, 0);
                if (sec > 0) out.put(pkg, sec);
            }
        }
        return out;
    }

    static long grantUntil(Context c, String pkg) {
        int sec = grantRemainingSeconds(c, pkg);
        return sec > 0 ? now(c) + sec * 1000L : 0L;
    }

    static boolean grantActive(Context c, String pkg) {
        return grantTodayActive(c, pkg) || grantRemainingSeconds(c, pkg) > 0;
    }

    // ---------- 密码 ----------

    static boolean hasPassword(Context c) { return sp(c).contains("pw_salt"); }

    static void setPassword(Context c, String pin6) {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        String s = Base64.getEncoder().encodeToString(salt);
        sp(c).edit().putString("pw_salt", s).putString("pw_hash", sha256(s + pin6)).apply();
    }

    static boolean checkPassword(Context c, String pin6) {
        String salt = sp(c).getString("pw_salt", null);
        String hash = sp(c).getString("pw_hash", null);
        if (salt == null || hash == null || pin6 == null) return false;
        return hash.equals(sha256(salt + pin6));
    }

    // ---------- 密码试错限速 ----------
    // 6 位数字只有一百万种，不限速的话被管者闲下来一个个试，总能试出来（设置页门、拦截页都能试）。
    // 每错 5 次锁一段时间，每多一轮翻倍：30 秒、1 分、2 分……封顶 32 分钟。按可信时钟算，改系统时间解不了锁；
    // 计数落在 prefs 里，杀进程、重启都不清零，输对一次才清。

    static final int PW_FREE_TRIES = 5;

    /** 还要锁多久（毫秒）；0 表示现在可以输。 */
    static long pwLockLeft(Context c) {
        long until = sp(c).getLong("pw_lock_until", 0L);
        long n = now(c);
        return until > n ? until - n : 0L;
    }

    /** 本轮还能错几次。 */
    static int pwTriesLeft(Context c) {
        return PW_FREE_TRIES - sp(c).getInt("pw_fail", 0) % PW_FREE_TRIES;
    }

    /** 带限速的校验：锁定中一律 false 且不计次；输对清零，输错计次、满一轮即上锁。 */
    static boolean tryPassword(Context c, String pin6) {
        if (pwLockLeft(c) > 0) return false;
        if (checkPassword(c, pin6)) {
            sp(c).edit().remove("pw_fail").remove("pw_lock_until").apply();
            return true;
        }
        int fail = sp(c).getInt("pw_fail", 0) + 1;
        SharedPreferences.Editor ed = sp(c).edit().putInt("pw_fail", fail);
        if (fail % PW_FREE_TRIES == 0) {
            int round = Math.min(fail / PW_FREE_TRIES - 1, 6);
            ed.putLong("pw_lock_until", now(c) + (30_000L << round));
        }
        ed.apply();
        return false;
    }

    /** 输错之后给人看的一句话：还剩几次，或者要等多久。 */
    static String pwFailText(Context c) {
        long left = pwLockLeft(c);
        if (left > 0) {
            long s = (left + 999) / 1000;
            return "错误次数过多，请 " + (s >= 60 ? (s + 59) / 60 + " 分钟" : s + " 秒") + "后再试";
        }
        return "密码错误，还可以试 " + pwTriesLeft(c) + " 次";
    }

    private static String sha256(String in) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(in.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ---------- 服务器 ----------

    static String serverAddr(Context c) { return sp(c).getString("server", ""); }

    static void setServerAddr(Context c, String addr) {
        sp(c).edit().putString("server", addr == null ? "" : addr.trim()).apply();
    }

    /**
     * 规范化成可直接拼路径的 base URL。
     * 既接受老式的 `10.0.0.1:8931`（补 http://），也接受 `https://sl.example.com`
     * ——服务端上了公网 Caddy 之后必须能走 https，否则密码是明文过公网的。
     */
    static String serverBase(Context c) {
        String a = serverAddr(c);
        if (a.isEmpty()) return "";
        if (!a.startsWith("http://") && !a.startsWith("https://")) a = "http://" + a;
        while (a.endsWith("/")) a = a.substring(0, a.length() - 1);
        return a;
    }

    /** 设备令牌：设备侧调用服务端 API 的凭据，服务端比对不上直接 401。 */
    static String deviceToken(Context c) { return sp(c).getString("dev_token", ""); }

    static void setDeviceToken(Context c, String t) {
        sp(c).edit().putString("dev_token", t == null ? "" : t.trim()).apply();
    }

    // ---------- 防护开关 ----------

    /** 看守系统设置：限时期间拦截卸载弹窗 / 无障碍设置页 / 应用信息页，防止用户绕过。 */
    static boolean guardSettings(Context c) { return sp(c).getBoolean("guard_settings", false); }

    static void setGuardSettings(Context c, boolean on) {
        sp(c).edit().putBoolean("guard_settings", on).apply();
    }

    /**
     * 隐藏后台：把设置页的任务从最近任务里摘掉（`AppTask.setExcludeFromRecents`）。
     * 默认关——藏起来就没法在最近任务里给它加锁，而加锁是 ColorOS 上最管用的保活。
     * 想两样都要的用法是：先加锁，再打开这个开关。
     */
    static boolean hideRecents(Context c) { return sp(c).getBoolean("hide_recents", false); }

    static void setHideRecents(Context c, boolean on) {
        sp(c).edit().putBoolean("hide_recents", on).apply();
    }

}
