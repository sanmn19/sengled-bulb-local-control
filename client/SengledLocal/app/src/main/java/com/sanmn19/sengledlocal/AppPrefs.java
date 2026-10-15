package com.sanmn19.sengledlocal;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

/** Per-bulb client cache — survives server outages so the UI shows last known state. */
public class AppPrefs {
    private static SharedPreferences p(Context ctx) {
        return ctx.getSharedPreferences("sengled_local", Context.MODE_PRIVATE);
    }

    public static int brightness(Context ctx, String mac, int def) {
        return p(ctx).getInt("br_" + mac, def);
    }

    public static void saveBrightness(Context ctx, String mac, int v) {
        p(ctx).edit().putInt("br_" + mac, v).apply();
    }

    public static int warmth(Context ctx, String mac, int def) {
        return p(ctx).getInt("wt_" + mac, def);
    }

    public static void saveWarmth(Context ctx, String mac, int w) {
        p(ctx).edit().putInt("wt_" + mac, w).apply();
    }

    /** Returns {r,g,b} or null if never set. */
    public static int[] color(Context ctx, String mac) {
        String raw = p(ctx).getString("col_" + mac, null);
        if (raw == null) return null;
        try {
            JSONObject o = new JSONObject(raw);
            return new int[]{o.optInt("r"), o.optInt("g"), o.optInt("b")};
        } catch (Exception e) {
            return null;
        }
    }

    public static void saveColor(Context ctx, String mac, int r, int g, int b) {
        try {
            JSONObject o = new JSONObject();
            o.put("r", r);
            o.put("g", g);
            o.put("b", b);
            p(ctx).edit().putString("col_" + mac, o.toString()).apply();
        } catch (Exception ignored) {
        }
    }
}
