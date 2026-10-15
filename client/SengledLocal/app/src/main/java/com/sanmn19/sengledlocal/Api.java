package com.sanmn19.sengledlocal;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class Api {
    private static String serverUrl() {
        return "http://" + State.get().activeHost() + ":" + State.get().port;
    }

    private static JSONObject withFailover(ThrowingCall call) throws Exception {
        Exception last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                JSONObject result = call.run();
                State.get().markReachable(State.get().activeHost());
                return result;
            } catch (Exception e) {
                last = e;
                String next = State.get().failover();
                if (attempt == 0 && next != null) {
                    continue;
                }
                break;
            }
        }
        throw last;
    }

    private interface ThrowingCall {
        JSONObject run() throws Exception;
    }

    public static JSONObject get(String path) throws Exception {
        return withFailover(() -> simple("GET", path, null, 5000));
    }

    public static JSONObject getLong(String path) throws Exception {
        return withFailover(() -> simple("GET", path, null, 40000));
    }

    public static JSONObject post(String path, JSONObject payload) throws Exception {
        return withFailover(() -> simple("POST", path, payload, 5000));
    }

    public static JSONObject delete(String path) throws Exception {
        return withFailover(() -> simple("DELETE", path, null, 5000));
    }

    private static JSONObject simple(String method, String path, JSONObject payload, int readTimeoutMs) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(serverUrl() + path).openConnection();
        c.setConnectTimeout(4000);
        c.setReadTimeout(readTimeoutMs);
        c.setRequestMethod(method);
        if (payload != null) {
            c.setDoOutput(true);
            byte[] data = payload.toString().getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(data.length);
            c.setRequestProperty("Content-Type", "application/json");
            OutputStream os = c.getOutputStream();
            os.write(data);
            os.flush();
            os.close();
        }
        int code = c.getResponseCode();
        String body = read(c);
        c.disconnect();
        if (code != 200) throw new Exception("HTTP " + code + ": " + body);
        return new JSONObject(body);
    }

    private static String read(HttpURLConnection c) throws Exception {
        InputStream is = c.getResponseCode() < 400 ? c.getInputStream() : c.getErrorStream();
        if (is == null) return "";
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) > 0) out.write(buf, 0, n);
        is.close();
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    public static JSONArray getArray(JSONObject o, String key) {
        try {
            return o.getJSONArray(key);
        } catch (Exception e) {
            return new JSONArray();
        }
    }
}
