package com.sanmn19.sengledlocal;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.widget.Switch;

/**
 * Shared power-switch styling: GREEN track when on, GREY when off, WHITE
 * outline on both track and thumb so the control reads against any background.
 */
public class PowerSwitchStyle {
    private static final int GREEN = 0xFFD52B1E; // Sengled brand red for ON
    private static final int GREY = 0xFF9E9E9E;

    public static void apply(Switch sw, boolean on) {
        apply(sw, on, GREEN);
    }

    public static void apply(Switch sw, boolean on, int onColor) {
        int accent = on ? onColor : GREY;

        GradientDrawable track = new GradientDrawable();
        track.setCornerRadius(dp(sw, 15));
        track.setColor(accent);
        track.setStroke(dp(sw, 2), Color.WHITE);
        track.setSize(dp(sw, 58), dp(sw, 30));

        GradientDrawable thumb = new GradientDrawable();
        thumb.setShape(GradientDrawable.OVAL);
        thumb.setColor(Color.WHITE);
        thumb.setStroke(dp(sw, 2), accent);
        thumb.setSize(dp(sw, 26), dp(sw, 26));

        sw.setTrackDrawable(track);
        sw.setThumbDrawable(thumb);
        int pad = dp(sw, 4);
        sw.setPadding(pad, pad, pad, pad);
        sw.requestLayout();
        sw.invalidate();
    }

    private static int dp(Switch sw, float v) {
        return Math.round(v * sw.getResources().getDisplayMetrics().density);
    }
}
