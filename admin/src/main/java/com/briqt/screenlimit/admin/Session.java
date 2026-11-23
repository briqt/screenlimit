package com.briqt.screenlimit.admin;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

/** 管理端自己的登录态。和守护端的 SharedPreferences 不是同一份，也不是同一个包。 */
final class Session {
    private static final String FILE = "admin";

    private Session() {}

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static String base(Context c) {
        String s = sp(c).getString("base", "");
        return normalize(s == null ? "" : s);
    }

    static void setBase(Context c, String raw) {
        sp(c).edit().putString("base", normalize(raw)).apply();
    }

    /** 补上协议、去掉末尾斜杠。空串保持空串，让调用方自己报「没填地址」。 */
    static String normalize(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.isEmpty()) return "";
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "https://" + s;
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    static String token(Context c) {
        String t = sp(c).getString("token", "");
        return t == null ? "" : t;
    }

    static void setToken(Context c, String token) {
        sp(c).edit().putString("token", token == null ? "" : token).apply();
    }

    static void clearToken(Context c) {
        sp(c).edit().remove("token").apply();
    }

    static String user(Context c) {
        String u = sp(c).getString("user", "");
        return u == null ? "" : u;
    }

    static void setUser(Context c, String user) {
        sp(c).edit().putString("user", user == null ? "" : user).apply();
    }

    static boolean watch(Context c) {
        return sp(c).getBoolean("watch", false);
    }

    static void setWatch(Context c, boolean on) {
        sp(c).edit().putBoolean("watch", on).apply();
    }

    /** 管理端本机记住的 6 位受控端解锁密码（仅存管理端本地，方便一键秒批）。 */
    static String savedPin(Context c) {
        String p = sp(c).getString("saved_pin", "");
        return p == null ? "" : p;
    }

    static void setSavedPin(Context c, String pin) {
        sp(c).edit().putString("saved_pin", pin == null ? "" : pin.trim()).apply();
    }

    /**
     * 已经发过通知的 pending 申请 ID 集合。
     * 服务重启或切网重连收到全量快照时：未在此集合的 pending 立即提醒（不漏首单），
     * 已在此集合的不重复响铃，不再处于 pending 的移出集合并撤销旧通知。
     */
    static synchronized Set<String> notifiedIds(Context c) {
        Set<String> raw = sp(c).getStringSet("notified_ids", null);
        return raw == null ? new HashSet<>() : new HashSet<>(raw);
    }

    static synchronized void saveNotifiedIds(Context c, Set<String> ids) {
        sp(c).edit().putStringSet("notified_ids",
                ids == null ? new HashSet<>() : new HashSet<>(ids)).apply();
    }
}
