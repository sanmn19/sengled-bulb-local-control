package com.sanmn19.sengledlocal;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class State {
    private static State instance;

    public static final String DEFAULT_LOCAL = ""      // your rig's LAN IP (set in Settings; used for pairing URLs);
    public static final String DEFAULT_TAILSCALE = ""  // your rig's Tailscale IP (set in Settings);

    public int port = 57542;
    private int activeCandidate = 0;
    private List<String> candidates = new ArrayList<>();
    private SharedPreferences prefs;
    public final ExecutorService io = Executors.newSingleThreadExecutor();
    public volatile long eventsVersion = 0;

    public static State get() {
        if (instance == null) instance = new State();
        return instance;
    }

    private void rebuildCandidates(String saved) {
        candidates.clear();
        if (saved != null && !saved.isEmpty()) candidates.add(saved);
        for (String f : new String[]{DEFAULT_LOCAL, DEFAULT_TAILSCALE}) {
            if (!candidates.contains(f)) candidates.add(f);
        }
        activeCandidate = 0;
    }

    public void init(Context ctx) {
        if (prefs == null) {
            prefs = ctx.getSharedPreferences("sengled_local", Context.MODE_PRIVATE);
            rebuildCandidates(prefs.getString("host", DEFAULT_LOCAL));
            port = prefs.getInt("port", port);
        }
    }

    public String activeHost() {
        try {
            return candidates.get(activeCandidate);
        } catch (Exception e) {
            return DEFAULT_LOCAL;
        }
    }

    /** Advance to next candidate; persists it as sticky so it is tried first next time. */
    public String failover() {
        if (activeCandidate < candidates.size() - 1) {
            activeCandidate++;
            return activeHost();
        }
        return null;
    }

    public void markReachable(String host) {
        activeCandidate = candidates.indexOf(host);
        if (activeCandidate < 0) {
            candidates.add(0, host);
            activeCandidate = 0;
        }
        prefs.edit().putString("host", host).apply();
    }

    public int activePort() {
        return port;
    }

    public void saveServer(String h, int p) {
        port = p;
        prefs.edit().putInt("port", p).apply();
        try {
            JSONObject cache = new JSONObject(prefs.getString("bulbs_cache", "{\"bulbs\":{}}"));
        } catch (Exception ignored) {
        }
        prefs.edit().putString("host", h).apply();
        rebuildCandidates(h);
    }

    public void setPort(int p) {
        port = p;
        prefs.edit().putInt("port", p).apply();
    }

    public void cacheBulbs(String json) {
        prefs.edit().putString("bulbs_cache", json).apply();
    }

    public String cachedBulbs() {
        return prefs.getString("bulbs_cache", null);
    }

    public void cacheRooms(String json) {
        prefs.edit().putString("rooms_cache", json).apply();
    }

    public String cachedRooms() {
        return prefs.getString("rooms_cache", null);
    }

    public boolean lastPower(String mac) {
        try {
            JSONObject obj = new JSONObject(prefs.getString("power", "{}"));
            if (obj.has(mac)) return obj.getJSONObject(mac).optBoolean("on", false);
        } catch (Exception ignored) {
        }
        return true;
    }

    public void rememberPower(String mac, boolean on) {
        try {
            JSONObject obj = new JSONObject(prefs.getString("power", "{}"));
            JSONObject s = new JSONObject();
            s.put("on", on);
            obj.put(mac, s);
            prefs.edit().putString("power", obj.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    public int brightness(String mac, int def) {
        return prefs.getInt("br_" + mac, def);
    }

    public void rememberBrightness(String mac, int v) {
        prefs.edit().putInt("br_" + mac, v).apply();
    }
}
