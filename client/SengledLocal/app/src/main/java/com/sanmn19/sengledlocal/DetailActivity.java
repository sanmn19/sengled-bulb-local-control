package com.sanmn19.sengledlocal;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;

public class DetailActivity extends Activity {
    private String mac;
    private String originalName;
    private boolean powerOn = false;
    private SeekBar sbBrightness;
    private final CommandBus bus = new CommandBus();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        State.get().init(this);
        bus.setUi(this);
        setContentView(R.layout.activity_detail);
        mac = getIntent().getStringExtra("mac");
        originalName = getIntent().getStringExtra("name");
        if (originalName != null) setTitle(originalName);

        ImageView ivPower = findViewById(R.id.ivPower);
        sbBrightness = findViewById(R.id.sbBrightness);
        SeekBar sbTemp = findViewById(R.id.sbTemp);
        EditText edName = findViewById(R.id.edName);
        View colorButton = findViewById(R.id.colorButton);
        View colorMini = findViewById(R.id.colorMini);

        // Power — 220dp light-bulb button: lit = on, dim = off
        powerOn = State.get().lastPower(mac);
        applyBulbIcon(ivPower);
        ivPower.setOnClickListener(v -> {
            powerOn = !powerOn;
            State.get().rememberPower(mac, powerOn);
            applyBulbIcon(ivPower);
            bus.immediate(mac, "power", jput(new org.json.JSONObject(), "on", powerOn));
        });

        // Brightness — vertical slider right of the bulb (rotated 270: bottom=0)
        int savedBr = AppPrefs.brightness(this, mac, 50);
        sbBrightness.setProgress(savedBr);
        sbBrightness.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                AppPrefs.saveBrightness(DetailActivity.this, mac, progress);
                if (fromUser) {
                    bus.send(mac, "brightness", jput(new org.json.JSONObject(), "value", progress));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                bus.flush(mac);
            }
        });

        // Temperature — horizontal gradient slider directly above the bulb;
        // bulb axis is 0=warm / 100=cool and the label/pct display is gone.
        int savedWarmth = AppPrefs.warmth(this, mac, 40);
        sbTemp.setProgress(100 - savedWarmth);
        sbTemp.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            private long lastSent = 0;

            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                if (fromUser) {
                    AppPrefs.saveWarmth(DetailActivity.this, mac, 100 - progress);
                    long now = System.currentTimeMillis();
                    if (now - lastSent > 400) {
                        lastSent = now;
                        org.json.JSONObject body = jput(new org.json.JSONObject(), "value", progress);
                        jput(body, "brightness", AppPrefs.brightness(DetailActivity.this, mac, 50));
                        bus.send(mac, "colortemp", body);
                    }
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                bus.flush(mac);
            }
        });

        refreshBrightnessRamp();

        // Color — small wheel-BUTTON opens the full picker popup
        colorButton.setOnClickListener(v -> openColorPopup());
        renderMiniPreview(colorMini);

        // Name syncs through the server
        edName.setText(originalName);
        edName.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) saveName(edName);
        });
        edName.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE
                    || actionId == android.view.inputmethod.EditorInfo.IME_ACTION_UNSPECIFIED) {
                saveName(edName);
                edName.clearFocus();
                android.view.inputmethod.InputMethodManager imm =
                        (android.view.inputmethod.InputMethodManager)
                                getSystemService(android.view.inputmethod.InputMethodManager.class);
                if (imm != null) imm.hideSoftInputFromWindow(edName.getWindowToken(), 0);
            }
            return false;
        });
    }

    private void applyBulbIcon(ImageView ivPower) {
        ivPower.setImageResource(powerOn ? R.drawable.ic_bulb_on : R.drawable.ic_bulb_off);
    }

    private void renderMiniPreview(View colorMini) {
        ColorPickerView mini = (ColorPickerView) colorMini;
        int[] c = AppPrefs.color(this, mac);
        if (c != null) {
            float[] hsv = new float[3];
            Color.RGBToHSV(c[0], c[1], c[2], hsv);
            mini.setHSV(hsv[0], hsv[1]);
        }
        mini.setInteractive(false); // icon only — the button container handles taps
        mini.setClickable(false);
        mini.setFocusable(false);
    }

    private void openColorPopup() {
        ColorPickerView big = new ColorPickerView(this);
        int size = Math.round(280f * getResources().getDisplayMetrics().density);
        big.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        big.setPadding(16, 16, 16, 16);

        FrameLayout holder = new FrameLayout(this);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size);
        lp.gravity = Gravity.CENTER;
        holder.addView(big, lp);
        int pad = Math.round(16f * getResources().getDisplayMetrics().density);
        holder.setPadding(pad, pad, pad, pad);

        // restore the stored color into the popup wheel
        int[] c = AppPrefs.color(this, mac);
        if (c != null) {
            float[] hsv = new float[3];
            Color.RGBToHSV(c[0], c[1], c[2], hsv);
            big.setHSV(hsv[0], hsv[1]);
        }

        big.setListener((r, g, bl) -> {
            AppPrefs.saveColor(DetailActivity.this, mac, r, g, bl);
            refreshBrightnessRamp();
            renderMiniPreview(findViewById(R.id.colorMini));
            org.json.JSONObject body = new org.json.JSONObject();
            jput(body, "r", r);
            jput(body, "g", g);
            jput(body, "b", bl);
            if (big.isDragging()) {
                bus.send(mac, "color", body);
            } else {
                bus.flush(mac);
            }
        });

        new AlertDialog.Builder(this)
                .setTitle("Pick color")
                .setView(holder)
                .setPositiveButton("Done", (d, w) -> bus.flush(mac))
                .show();
    }

    /** Brightness ramp shows faint->bright of the CURRENT color (bottom->top). */
    private void refreshBrightnessRamp() {
        int faint = 0xFF3A3A3A, bright = 0xFFF5F5F5;
        int[] c = AppPrefs.color(this, mac);
        if (c != null) {
            float[] hsv = new float[3];
            Color.RGBToHSV(c[0], c[1], c[2], hsv);
            faint = Color.HSVToColor(new float[]{hsv[0], hsv[1], 0.18f});
            bright = Color.HSVToColor(new float[]{hsv[0], hsv[1], 1f});
        }
        GradientDrawable ramp = new GradientDrawable();
        ramp.setColors(new int[]{faint, bright});
        ramp.setOrientation(GradientDrawable.Orientation.LEFT_RIGHT);
        float d = getResources().getDisplayMetrics().density;
        ramp.setCornerRadius(7f * d);
        ramp.setStroke(Math.round(2f * d), Color.WHITE);
        LayerDrawable ld = new LayerDrawable(new android.graphics.drawable.Drawable[]{
                ramp, new ColorDrawable(Color.TRANSPARENT)});
        ld.setId(0, android.R.id.background);
        ld.setId(1, android.R.id.progress);
        sbBrightness.setProgressDrawable(ld);
        sbBrightness.requestLayout();
        sbBrightness.invalidate();
    }

    private void saveName(EditText ed) {
        String newName = ed.getText().toString()
                .replace("\n", "").replace("\r", "").trim();
        if (newName.isEmpty() || newName.equals(originalName)) return;
        originalName = newName;
        setTitle(newName);
        State.get().io.execute(() -> {
            try {
                Api.post("/api/bulbs/" + mac + "/name", jput(new org.json.JSONObject(), "name", newName));
            } catch (Exception e) {
                runOnUiThread(() -> android.widget.Toast.makeText(this, "rename failed", android.widget.Toast.LENGTH_SHORT).show());
            }
        });
    }

    private static org.json.JSONObject jput(org.json.JSONObject o, String key, Object value) {
        try {
            o.put(key, value);
        } catch (Exception ignored) {
        }
        return o;
    }
}
