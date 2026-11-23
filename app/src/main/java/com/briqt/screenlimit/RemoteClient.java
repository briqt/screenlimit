package com.briqt.screenlimit;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/**
 * 远程授权客户端：HTTP + JSON。
 * 地址既支持老式 `IP:端口`（明文 http），也支持 `https://域名`——服务端上公网后必须走 https。
 * 所有请求都带 `X-Device-Token`；服务端对不上直接 401，避免公网上谁都能往里灌申请。
 */
public final class RemoteClient {
    private static final String TAG = "ScreenLimit";

    interface Callback {
        void onApproved(String password);
        void onDenied();
        void onError(String msg);
    }

    private RemoteClient() {}

    static String newRequestId() {
        byte[] b = new byte[12];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    static String postRequest(Context c, String id, String pkg, String appLabel) {
        String base = Rules.serverBase(c);
        if (base.isEmpty()) return "未设置服务器地址";
        String token = Rules.deviceToken(c);
        if (token.isEmpty()) return "未设置设备令牌（在应用设置中填写）";
        try {
            JSONObject body = new JSONObject();
            body.put("id", id);
            body.put("pkg", pkg);
            body.put("app", appLabel);
            body.put("ts", System.currentTimeMillis());
            int code = http(base + "/api/request", "POST", body.toString(), token);
            if (code == 401) return "设备令牌不正确";
            return code == 200 ? null : ("服务器返回 " + code);
        } catch (Exception e) {
            Log.w(TAG, "postRequest", e);
            return "无法连接服务器：" + e.getMessage();
        }
    }

    /** @return null=仍在等待；"denied"=拒绝；"expired"=超时或已被新申请替代；其他=密码。 */
    static String poll(String base, String token, String id) {
        try {
            String resp = httpGet(base + "/api/poll?id=" + id, token);
            JSONObject j = new JSONObject(resp);
            String state = j.optString("state", "pending");
            if ("approved".equals(state)) return j.optString("password", "");
            if ("denied".equals(state)) return "denied";
            if ("expired".equals(state)) return "expired";
            return null;
        } catch (Exception e) {
            Log.w(TAG, "poll", e);
            return null; // 网络抖动不中断等待
        }
    }

    static void cancel(String base, String token, String id) {
        try {
            JSONObject body = new JSONObject();
            body.put("id", id);
            http(base + "/api/cancel", "POST", body.toString(), token);
        } catch (Exception ignored) {}
    }

    /** 上报可管理应用列表，供服务端配置页渲染三态。 */
    static void postApps(Context c, org.json.JSONArray apps) {
        String base = Rules.serverBase(c), token = Rules.deviceToken(c);
        if (base.isEmpty() || token.isEmpty()) return;
        try {
            JSONObject body = new JSONObject().put("apps", apps);
            JSONObject st = RemoteConfig.devState(c);
            if (st != null) body.put("cfg", st);
            http(base + "/api/apps", "POST", body.toString(), token);
        } catch (Exception e) {
            Log.w(TAG, "postApps", e);
        }
    }

    /** 拉远程配置。@return 原始 JSON，失败返回 null（网络抖动不算错，下拍再试）。 */
    static JSONObject fetchConfig(Context c) {
        String base = Rules.serverBase(c), token = Rules.deviceToken(c);
        if (base.isEmpty() || token.isEmpty()) return null;
        try {
            return new JSONObject(httpGet(base + "/api/config", token));
        } catch (Exception e) {
            Log.w(TAG, "fetchConfig: " + e.getMessage());
            return null;
        }
    }

    private static int http(String urlStr, String method, String body, String token) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        conn.setRequestMethod(method);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("X-Device-Token", token);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        int code = conn.getResponseCode();
        conn.disconnect();
        return code;
    }

    private static String httpGet(String urlStr, String token) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        conn.setRequestProperty("X-Device-Token", token);
        try (InputStream is = conn.getInputStream();
             BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            return sb.toString();
        } finally {
            conn.disconnect();
        }
    }
}
