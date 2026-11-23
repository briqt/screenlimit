package com.briqt.screenlimit.admin;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 管理端 HTTP。只打 {@code /api/admin/*}，Bearer JWT，不碰网页表单和 CSRF。
 * 密码只出现在请求体里，不写日志。
 */
final class Api {
    static final class Err extends Exception {
        final int code;

        Err(int code, String msg) {
            super(msg);
            this.code = code;
        }
    }

    private Api() {}

    static String login(String base, String user, String password) throws Err {
        JSONObject body = new JSONObject();
        try {
            body.put("user", user);
            body.put("password", password);
        } catch (Exception e) {
            throw new Err(0, "请求有误");
        }
        JSONObject out = call(base, null, "POST", "/api/admin/login", body.toString());
        String tok = out.optString("token", "");
        if (tok.isEmpty()) throw new Err(0, "服务器没有返回登录凭证");
        return tok;
    }

    static JSONObject requests(Context c) throws Err {
        return call(Session.base(c), Session.token(c), "GET", "/api/admin/requests", null);
    }

    static void approve(Context c, String id, String password) throws Err {
        JSONObject body = new JSONObject();
        try {
            body.put("id", id);
            body.put("password", password);
        } catch (Exception e) {
            throw new Err(0, "请求有误");
        }
        call(Session.base(c), Session.token(c), "POST", "/api/admin/approve", body.toString());
    }

    static void deny(Context c, String id) throws Err {
        JSONObject body = new JSONObject();
        try {
            body.put("id", id);
        } catch (Exception e) {
            throw new Err(0, "请求有误");
        }
        call(Session.base(c), Session.token(c), "POST", "/api/admin/deny", body.toString());
    }

    static JSONObject config(Context c) throws Err {
        return call(Session.base(c), Session.token(c), "GET", "/api/admin/config", null);
    }

    static JSONObject saveConfig(Context c, JSONObject body) throws Err {
        return call(Session.base(c), Session.token(c), "POST", "/api/admin/config", body.toString());
    }

    /**
     * 打开 {@code GET /api/admin/events} SSE 长连接。
     * 仅完成请求头与超时配置后即返回连接对象，供调用方保存引用以便随时从外部 {@code disconnect()} 打断阻塞读。
     */
    static HttpURLConnection openEvents(Context c) throws Err {
        String base = Session.base(c);
        String token = Session.token(c);
        if (base.isEmpty()) throw new Err(0, "还没有填写服务器地址");
        if (token.isEmpty()) throw new Err(401, "未登录");
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(base + "/api/admin/events").openConnection();
            conn.setConnectTimeout(12_000);
            conn.setReadTimeout(90_000);
            conn.setUseCaches(false);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "text/event-stream");
            conn.setRequestProperty("Cache-Control", "no-cache");
            conn.setRequestProperty("Authorization", "Bearer " + token);
            return conn;
        } catch (Exception e) {
            throw new Err(0, e.getMessage() == null ? "网络失败" : e.getMessage());
        }
    }

    private static JSONObject call(String base, String token, String method, String path, String json) throws Err {
        if (base == null || base.isEmpty()) throw new Err(0, "还没有填写服务器地址");
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(base + path).openConnection();
            conn.setConnectTimeout(12_000);
            conn.setReadTimeout(20_000);
            conn.setRequestMethod(method);
            conn.setRequestProperty("Accept", "application/json");
            if (token != null && !token.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + token);
            }
            if (json != null) {
                byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(bytes);
                }
            }
            int code = conn.getResponseCode();
            InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String text = slurp(in);
            if (code >= 400) {
                String msg = text;
                try {
                    JSONObject j = new JSONObject(text);
                    if (j.has("error")) msg = j.optString("error");
                } catch (Exception ignored) {}
                if (msg == null || msg.trim().isEmpty()) msg = "HTTP " + code;
                throw new Err(code, msg.trim());
            }
            if (text.trim().isEmpty()) return new JSONObject();
            return new JSONObject(text);
        } catch (Err e) {
            throw e;
        } catch (Exception e) {
            throw new Err(0, e.getMessage() == null ? "网络失败" : e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String slurp(InputStream in) throws Exception {
        if (in == null) return "";
        try (InputStream input = in) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = input.read(buf)) >= 0) bos.write(buf, 0, n);
            return bos.toString(StandardCharsets.UTF_8);
        }
    }
}
