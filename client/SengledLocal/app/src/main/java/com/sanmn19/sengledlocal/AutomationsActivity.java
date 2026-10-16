package com.sanmn19.sengledlocal;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

public class AutomationsActivity extends Activity {
    private LinearLayout container;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        State.get().init(this);
        setContentView(R.layout.activity_automations);
        container = findViewById(R.id.automContainer);
        findViewById(R.id.btnAddAuto).setOnClickListener(v ->
                startActivity(new android.content.Intent(this, AutomationFormActivity.class)));
        findViewById(R.id.btnSetLocation).setOnClickListener(v -> setServerLocation());
        reload();
    }

    private void setServerLocation() {
        if (android.os.Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.ACCESS_COARSE_LOCATION}, 110);
            return;
        }
        acquireAndUploadLocation();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        if (code == 110 && results.length > 0 && results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            acquireAndUploadLocation();
        } else {
            android.widget.Toast.makeText(this,
                    "Location permission needed for sunrise/sunset", android.widget.Toast.LENGTH_LONG).show();
        }
    }

    private void acquireAndUploadLocation() {
        try {
            android.location.LocationManager lm =
                    (android.location.LocationManager) getSystemService(LOCATION_SERVICE);
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                lm.getCurrentLocation(android.location.LocationManager.NETWORK_PROVIDER, null,
                        getMainExecutor(), loc -> {
                            if (loc != null) {
                                uploadLocation(loc.getLatitude(), loc.getLongitude());
                            } else {
                                android.widget.Toast.makeText(this, "No location available yet — open Maps once and retry",
                                        android.widget.Toast.LENGTH_LONG).show();
                            }
                        });
            } else {
                android.location.Location loc = lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER);
                if (loc == null) loc = lm.getLastKnownLocation(android.location.LocationManager.PASSIVE_PROVIDER);
                if (loc != null) {
                    uploadLocation(loc.getLatitude(), loc.getLongitude());
                } else {
                    android.widget.Toast.makeText(this, "No cached location — try again outdoors/after a minute",
                            android.widget.Toast.LENGTH_LONG).show();
                }
            }
        } catch (Exception e) {
            android.widget.Toast.makeText(this, "location failed: " + e.getMessage(), android.widget.Toast.LENGTH_LONG).show();
        }
    }

    private void uploadLocation(double lat, double lon) {
        // round to 2 decimals (~1km grid): city-level precision, less personal data transmitted
        double rl = Math.round(lat * 100.0) / 100.0;
        double ro = Math.round(lon * 100.0) / 100.0;
        State.get().io.execute(() -> {
            try {
                org.json.JSONObject body = new org.json.JSONObject();
                body.put("lat", rl);
                body.put("lon", ro);
                String tz = java.util.TimeZone.getDefault().getID();
                body.put("tz", tz);
                Api.post("/api/location", body);
                runOnUiThread(() -> {
                    android.widget.Toast.makeText(this,
                            "Location set: " + rl + ", " + ro + " (" + tz + ")", android.widget.Toast.LENGTH_LONG).show();
                    reload();
                });
            } catch (Exception e) {
                runOnUiThread(() -> android.widget.Toast.makeText(this,
                        "failed: " + e.getMessage(), android.widget.Toast.LENGTH_LONG).show());
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        State.get().io.execute(() -> {
            try {
                JSONObject data = Api.get("/api/automations");
                runOnUiThread(() -> render(data));
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this,
                        "Server unreachable: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void render(JSONObject data) {
        container.removeAllViews();
        org.json.JSONArray items = data.optJSONArray("automations");
        org.json.JSONObject loc = data.optJSONObject("location");
        if (loc == null || !loc.has("lat")) {
            TextView warn = new TextView(this);
            warn.setText("Set your location on the server (POST /api/location) to use sunrise/sunset automations.");
            warn.setTextColor(getResources().getColor(R.color.sengled_text_dim));
            warn.setPadding(8, 12, 8, 12);
            container.addView(warn);
        }
        if (items == null || items.length() == 0) {
            TextView t = new TextView(this);
            t.setText("No automations yet — tap + New automation");
            t.setTextColor(getResources().getColor(R.color.sengled_text_dim));
            t.setPadding(8, 12, 8, 12);
            container.addView(t);
            return;
        }
        for (int i = 0; i < items.length(); i++) {
            JSONObject e = items.optJSONObject(i);
            if (e == null) continue;
            addRow(e);
        }
    }

    private void addRow(JSONObject e) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.card_dark);
        card.setElevation(2f);
        int pad = Math.round(14f * getResources().getDisplayMetrics().density);
        card.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = 8;
        card.setLayoutParams(lp);

        // text column + switch on ONE line, switch right-aligned & centered
        LinearLayout headRow = new LinearLayout(this);
        headRow.setOrientation(LinearLayout.HORIZONTAL);
        headRow.setGravity(Gravity.CENTER_VERTICAL);
        headRow.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView label = new TextView(this);
        label.setText(e.optString("label"));
        label.setTextColor(getResources().getColor(R.color.sengled_text));
        label.setTextSize(17);
        label.setTypeface(null, android.graphics.Typeface.BOLD);
        textCol.addView(label);

        TextView summary = new TextView(this);
        summary.setText(summarize(e));
        summary.setTextColor(getResources().getColor(R.color.sengled_text_dim));
        summary.setTextSize(13);
        textCol.addView(summary);

        headRow.addView(textCol);

        Switch sw = new Switch(this);
        sw.setScaleX(0.77f);
        sw.setScaleY(0.77f);
        sw.setChecked(e.optBoolean("enabled"));
        PowerSwitchStyle.apply(sw, e.optBoolean("enabled"));
        LinearLayout.LayoutParams swLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        swLp.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        swLp.setMarginEnd(Math.round(6f * getResources().getDisplayMetrics().density));
        sw.setLayoutParams(swLp);
        headRow.addView(sw);
        card.addView(headRow);
        sw.setOnCheckedChangeListener((b, checked) -> {
            if (!b.isPressed()) return;
            PowerSwitchStyle.apply(sw, checked);
            State.get().io.execute(() -> {
                try {
                    Api.post("/api/automations/" + e.optString("id") + "/enabled",
                            new JSONObject().put("on", checked));
                } catch (Exception ex) {
                    runOnUiThread(() -> Toast.makeText(this, "failed", Toast.LENGTH_SHORT).show());
                }
            });
        });
        TextView delete = new TextView(this);
        delete.setText("Delete");
        delete.setTextColor(getResources().getColor(R.color.sengled_red));
        delete.setTextSize(13);
        delete.setPadding(0, 8, 0, 0);
        delete.setOnClickListener(v -> new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog)
                .setTitle("Delete '" + e.optString("label") + "'?")
                .setPositiveButton("Delete", (d, w) -> State.get().io.execute(() -> {
                    try {
                        Api.delete("/api/automations/" + e.optString("id"));
                        runOnUiThread(this::reload);
                    } catch (Exception ex) {
                        runOnUiThread(() -> Toast.makeText(this, "failed", Toast.LENGTH_SHORT).show());
                    }
                }))
                .setNegativeButton(android.R.string.cancel, null)
                .show());
        card.addView(delete);

        container.addView(card);
    }

    private String summarize(JSONObject e) {
        JSONObject trig = e.optJSONObject("trigger");
        StringBuilder s = new StringBuilder();
        if (trig != null) {
            String kind = trig.optString("type");
            if ("daily".equals(kind)) {
                s.append("Daily at ").append(trig.optString("time")).append(" · ");
            } else if ("interval".equals(kind)) {
                s.append("Every ").append(trig.optInt("minutes", 60)).append(" min · ");
            } else {
                s.append(kind.substring(0, 1).toUpperCase()).append(kind.substring(1));
                s.append(" (").append(trig.optLong("offsetMinutes", 0)).append("min) · ");
            }
        }
        org.json.JSONArray acts = e.optJSONArray("actions");
        if (acts != null) {
            for (int i = 0; i < acts.length(); i++) {
                JSONObject a = acts.optJSONObject(i);
                s.append(a == null ? "?" : describeAction(a));
                if (i < acts.length() - 1) s.append(", ");
            }
        }
        int next = e.optInt("next_run_minutes", -1);
        if (next >= 0) {
            s.append("\nTrigger in ").append(next / 60).append("h ").append(next % 60).append("m");
        } else if (!"interval".equals(trig == null ? "" : trig.optString("type"))) {
            s.append("\nTrigger time unknown (set server location)");
        }
        return s.toString();
    }

    private String describeAction(JSONObject a) {
        org.json.JSONArray macs = a.optJSONArray("macs");
        int cnt = macs == null ? 0 : macs.length();
        String action = a.optString("action");
        String mode = a.optString("mode", "set");
        if ("power".equals(action)) {
            boolean on = a.optBoolean("value", false);
            return (on ? "ON" : "OFF") + " (" + cnt + " bulbs)";
        }
        String val = a.opt("value") == null ? "" : a.opt("value").toString();
        return action + (mode.equals("offset") ? " by " : " = ") + val
                + " (" + cnt + " bulbs)";
    }
}
