package com.sanmn19.sengledlocal;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class AutomationFormActivity extends Activity {
    private Spinner spTrigger;
    private Spinner spAction;
    private CheckBox cbDelta;
    private EditText edParam;
    private EditText edValue;
    private TextView tvSliderLabel;
    private SeekBar sbActValue;
    private SeekBar sbDelta;
    private Switch swActPower;
    private LinearLayout actionList;
    private LinearLayout targetList;
    private View colorSwatch;
    private int[] colorValue = {255, 255, 255};
    private final List<JSONObject> pendingActions = new ArrayList<>();

    private static final String[] TRIGGER_LABELS =
            {"Every day at a clock time", "At sunset", "At sunrise", "Every N minutes"};
    private static final String[] ACTION_LABELS =
            {"Power", "Brightness", "White temperature", "Color"};

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        State.get().init(this);
        setContentView(R.layout.activity_automation_form);
        actionList = findViewById(R.id.actionList);
        targetList = findViewById(R.id.targetList);
        edParam = findViewById(R.id.edTriggerParam);
        edValue = findViewById(R.id.edValue);
        tvSliderLabel = findViewById(R.id.tvSliderLabel);
        sbActValue = findViewById(R.id.sbActValue);
        sbDelta = findViewById(R.id.sbDelta);
        sbDelta.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar s, int progress, boolean fromUser) {
                updateSliderLabel();
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar s) {}
            @Override public void onStopTrackingTouch(android.widget.SeekBar s) {}
        });
        swActPower = findViewById(R.id.swActPower);
        cbDelta = findViewById(R.id.cbDelta);
        colorSwatch = findViewById(R.id.colorSwatch);
        cbDelta.setTextColor(getResources().getColor(R.color.sengled_text));
        cbDelta.setButtonTintList(ColorStateList.valueOf(0xFF34C759));

        spTrigger = findViewById(R.id.spTrigger);
        spAction = findViewById(R.id.spAction);
        styleSpinner(spTrigger, TRIGGER_LABELS, 0);
        styleSpinner(spAction, ACTION_LABELS, 0);
        android.widget.AdapterView.OnItemSelectedListener listener = listener();
        spTrigger.setOnItemSelectedListener(listener);
        spAction.setOnItemSelectedListener(listener);
        cbDelta.setOnCheckedChangeListener((c, checked) -> updateValueWidgets());
        colorSwatch.setOnClickListener(v -> openSwatchPicker());
        spActValueInit();
        swActPower.setOnCheckedChangeListener((c, checked) -> {
            PowerSwitchStyle.apply(swActPower, checked, 0xFF34C759);
        });
        swActPower.setChecked(true);
        swActPower.setText("");
        PowerSwitchStyle.apply(swActPower, true, 0xFF34C759);
        updateParamHint();
        updateValueWidgets();
        fetchSunInfo();

        findViewById(R.id.btnAddAction).setOnClickListener(v -> addAction());
        findViewById(R.id.btnSaveAuto).setOnClickListener(v -> save());
        loadBulbs();
    }

    private void spActValueInit() {
        sbActValue.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                updateSliderLabel();
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        });
    }

    private android.widget.AdapterView.OnItemSelectedListener listener() {
        return new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int pos, long id) {
                updateParamHint();
                updateValueWidgets();
                refreshSunLine();
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        };
    }

    private void styleSpinner(Spinner sp, String[] items, int sel) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                R.layout.spinner_item_white, items);
        adapter.setDropDownViewResource(R.layout.spinner_item_white);
        sp.setAdapter(adapter);
        sp.setSelection(sel);
    }

    private boolean actionIsPower() {
        return "Power".equals(ACTION_LABELS[spAction.getSelectedItemPosition()]);
    }

    private void updateParamHint() {
        String t = TRIGGER_LABELS[spTrigger.getSelectedItemPosition()];
        if (t.startsWith("Every day")) {
            edParam.setHint("Tap to pick the clock time");
            edParam.setFocusable(false);
            edParam.setClickable(true);
            edParam.setOnClickListener(v -> showTimePicker());
        } else {
            edParam.setOnClickListener(null);
            edParam.setClickable(false);
            edParam.setFocusableInTouchMode(true);
            edParam.setFocusable(true);
            if (t.startsWith("Every N")) {
                edParam.setHint("minutes: 30");
            } else {
                edParam.setHint("minutes after (negative = before): e.g. -20");
            }
        }
    }

    private void showTimePicker() {
        int hour = 22, minute = 0;
        String cur = edParam.getText().toString().trim();
        try {
            String[] parts = cur.split(":");
            if (parts.length == 2) {
                hour = Integer.parseInt(parts[0]);
                minute = Integer.parseInt(parts[1]);
                if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
                    hour = 22;
                    minute = 0;
                }
            }
        } catch (Exception ignored) {
        }
        new android.app.TimePickerDialog(this,
                (picker, selH, selM) -> edParam.setText(String.format("%02d:%02d", selH, selM)),
                hour, minute, true).show();
    }

    /** Shows the right input for the picked action: toggle / slider / text. */
    private void updateValueWidgets() {
        boolean delta = cbDelta.isChecked();
        swActPower.setVisibility(View.GONE);
        sbActValue.setVisibility(View.GONE);
        tvSliderLabel.setVisibility(View.GONE);
        edValue.setVisibility(View.GONE);
        String a = ACTION_LABELS[spAction.getSelectedItemPosition()];
        boolean power = a.equals("Power");
        boolean swatchNeedsHide = true;
        cbDelta.setVisibility(power ? View.GONE : View.VISIBLE);
        sbDelta.setVisibility(View.GONE);
        edValue.setVisibility(View.GONE);
        colorSwatch.setVisibility(View.GONE);
        sbActValue.setVisibility(View.GONE);
        tvSliderLabel.setVisibility(View.GONE);
        swActPower.setVisibility(View.GONE);
        if (power) {
            swActPower.setVisibility(View.VISIBLE);
            return;
        }
        if (delta) {
            if (a.equals("Color")) {
                edValue.setVisibility(View.VISIBLE);
                edValue.setHint("RGB delta: 10:-20:5");
            } else {
                sbDelta.setVisibility(View.VISIBLE);
                tvSliderLabel.setVisibility(View.VISIBLE);
                updateSliderLabel();
            }
            return;
        }
        if (a.equals("Color")) {
            colorSwatch.setVisibility(View.VISIBLE);
            paintSwatch();
            swatchNeedsHide = false;
            return;
        }
        sbActValue.setVisibility(View.VISIBLE);
        tvSliderLabel.setVisibility(View.VISIBLE);
        if (a.equals("White temperature")) {
            sbActValue.setProgress(40);
        } else {
            sbActValue.setProgress(50);
        }
        updateSliderLabel();
    }

    private void updateSliderLabel() {
        String a = ACTION_LABELS[spAction.getSelectedItemPosition()];
        if (sbDelta.isShown()) {
            int d = sbDelta.getProgress() - 100;
            tvSliderLabel.setText((a.equals("White temperature") ? "Temperature delta: " : "Brightness delta: ")
                    + (d > 0 ? "+" : "") + d);
            return;
        }
        tvSliderLabel.setText((a.equals("White temperature") ? "White temperature: " : "Brightness: ")
                + sbActValue.getProgress());
    }

    private void paintSwatch() {
        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        int d = Math.round(4f * getResources().getDisplayMetrics().density);
        bg.setCornerRadius(d);
        bg.setColor(android.graphics.Color.rgb(colorValue[0], colorValue[1], colorValue[2]));
        bg.setStroke(Math.round(2f * getResources().getDisplayMetrics().density), android.graphics.Color.WHITE);
        colorSwatch.setBackground(bg);
    }

    private void openSwatchPicker() {
        ColorPickerView big = new ColorPickerView(this);
        int size = Math.round(280f * getResources().getDisplayMetrics().density);
        big.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        float[] hsv = new float[3];
        android.graphics.Color.RGBToHSV(colorValue[0], colorValue[1], colorValue[2], hsv);
        big.setHSV(hsv[0], hsv[1]);
        big.setListener((r, g, bl) -> {
            colorValue = new int[]{r, g, bl};
            paintSwatch();
        });
        LinearLayout holder = new LinearLayout(this);
        holder.setOrientation(LinearLayout.VERTICAL);
        holder.setGravity(Gravity.CENTER);
        holder.addView(big);
        int pad = Math.round(12f * getResources().getDisplayMetrics().density);
        holder.setPadding(pad, pad, pad, pad);
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog)
                .setTitle("Pick color")
                .setView(holder)
                .setPositiveButton("Done", null)
                .show();
    }

    private void fetchSunInfo() {
        TextView tvSunInfo = findViewById(R.id.tvSunInfo);
        State.get().io.execute(() -> {
            try {
                JSONObject sun = Api.get("/api/sun");
                runOnUiThread(() -> {
                    String rise = sun.optString("sunrise", "?");
                    String set = sun.optString("sunset", "?");
                    StringBuilder sb = new StringBuilder();
                    sb.append("Today: sunrise ").append(rise).append(" · sunset ").append(set);
                    long mins = minutesUntil(set);
                    if (mins >= 0) {
                        sb.append(" — sunset in ").append(mins / 60).append("h ")
                                .append(mins % 60).append("m");
                    }
                    tvSunInfo.setText(sb.toString());
                });
            } catch (Exception e) {
                runOnUiThread(() -> tvSunInfo.setText(
                        "Sun times unavailable — set your location (POST /api/location) on the server"));
            }
        });
    }

    private void refreshSunLine() {
        TextView tvSunInfo = findViewById(R.id.tvSunInfo);
        String t = TRIGGER_LABELS[spTrigger.getSelectedItemPosition()];
        if (t.startsWith("At sunset") || t.startsWith("At sunrise")) {
            tvSunInfo.setVisibility(View.VISIBLE);
            fetchSunInfo();
        } else {
            tvSunInfo.setVisibility(View.GONE);
        }
    }

    private long minutesUntil(String hhmm) {
        try {
            String[] parts = hhmm.split(":");
            int th = Integer.parseInt(parts[0]);
            int tm = Integer.parseInt(parts[1]);
            java.util.Calendar cal = java.util.Calendar.getInstance();
            int nowMin = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE);
            int target = th * 60 + tm;
            long delta = target - nowMin;
            if (delta < 0) delta += 24 * 60;
            return delta;
        } catch (Exception e) {
            return -1;
        }
    }

    private void addAction() {
        try {
            String a = ACTION_LABELS[spAction.getSelectedItemPosition()];
            boolean delta = cbDelta.isChecked();
            if ((a.equals("Turn ON") || a.equals("Turn OFF")) && delta) {
                throw new Exception("Delta does not apply to on/off");
            }
            JSONObject def = new JSONObject();
            def.put("macs", new JSONArray());   // resolved at save from checkboxes
            if (a.equals("Power")) {
                def.put("action", "power");
                def.put("mode", "set");
                def.put("value", swActPower.isChecked());
                rejectConflict(def);
            } else {
                String action = a.equals("Brightness") ? "brightness"
                        : a.equals("White temperature") ? "colortemp" : "color";
                def.put("action", action);
                def.put("mode", delta ? "offset" : "set");
                rejectConflict(def);
                if (delta) {
                    String v = edValue.getText().toString().trim();
                    if (action.equals("color")) {
                        String[] parts = v.split(":");
                        if (parts.length != 3) throw new Exception("color delta needs dr:dg:db");
                        int dr = Integer.parseInt(parts[0].trim());
                        int dg = Integer.parseInt(parts[1].trim());
                        int db = Integer.parseInt(parts[2].trim());
                        // client-side bound validation: per-channel |d| <= 255
                        if (Math.abs(dr) > 255 || Math.abs(dg) > 255 || Math.abs(db) > 255) {
                            throw new Exception("color delta channels must be within -255..255");
                        }
                        JSONObject c = new JSONObject();
                        c.put("dr", dr);
                        c.put("dg", dg);
                        c.put("db", db);
                        def.put("value", c);
                    } else {
                        int deltaVal = sbDelta.getProgress() - 100;   // -100..100
                        if (deltaVal < -100 || deltaVal > 100) {
                            throw new Exception("delta must be -100..100");
                        }
                        def.put("value", deltaVal);
                    }
                } else {
                    if (action.equals("color")) {
                        JSONObject c = new JSONObject();
                        c.put("r", colorValue[0]);
                        c.put("g", colorValue[1]);
                        c.put("b", colorValue[2]);
                        def.put("value", c);
                    } else {
                        def.put("value", sbActValue.getProgress());
                    }
                }
            }
            pendingActions.add(def);
            addActionChip(def);
            edValue.setText("");
        } catch (Exception e) {
            Toast.makeText(this, "check inputs: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /** Reject duplicate actions and physically conflicting light-mode actions. */
    private void rejectConflict(JSONObject def) throws Exception {
        String action = def.optString("action");
        for (JSONObject existing : pendingActions) {
            String ex = existing.optString("action");
            if (ex.equals(action)) {
                throw new Exception("conflicting/duplicate: '" + actionName(action)
                        + "' already in this automation");
            }
            if ((action.equals("color") && ex.equals("colortemp"))
                    || (action.equals("colortemp") && ex.equals("color"))) {
                throw new Exception("color and white temperature conflict - a bulb runs one mode at a time");
            }
        }
    }

    private String actionName(String action) {
        if (action.equals("power")) return "Power";
        if (action.equals("brightness")) return "Brightness";
        if (action.equals("colortemp")) return "White temperature";
        return "Color";
    }

    private void addActionChip(JSONObject def) {
        LinearLayout chip = new LinearLayout(this);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setBackgroundResource(R.drawable.card_dark);
        int pad = Math.round(10f * getResources().getDisplayMetrics().density);
        chip.setPadding(pad, Math.round(6f * getResources().getDisplayMetrics().density), pad, Math.round(6f * getResources().getDisplayMetrics().density));

        TextView tv = new TextView(this);
        tv.setText(describe(def));
        tv.setTextColor(getResources().getColor(R.color.sengled_text));
        tv.setTextSize(14);
        tv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        chip.addView(tv);

        TextView x = new TextView(this);
        x.setText("✕");
        x.setTextColor(getResources().getColor(R.color.sengled_red));
        x.setTextSize(16);
        x.setPadding(16, 0, 8, 0);
        x.setOnClickListener(v -> {
            pendingActions.remove(def);
            actionList.removeView(chip);
        });
        chip.addView(x);

        int m = Math.round(8f * getResources().getDisplayMetrics().density);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = m;
        chip.setLayoutParams(lp);
        actionList.addView(chip);
    }

    private String describe(JSONObject def) {
        try {
            String action = def.optString("action");
            String mode = def.optString("mode");
            String word = mode.equals("offset") ? "adjust " : "set ";
            if (action.equals("power")) {
                return def.optBoolean("value") ? "Turn ON" : "Turn OFF";
            }
            String val = def.opt("value") == null ? "" : def.opt("value").toString();
            if (action.equals("colortemp")) return word + "temperature " + val;
            if (action.equals("brightness")) return word + "brightness " + val;
            return word + "color " + val;
        } catch (Exception e) {
            return "?";
        }
    }

    private void loadBulbs() {
        State.get().io.execute(() -> {
            try {
                JSONObject data = Api.get("/api/bulbs");
                runOnUiThread(() -> {
                    targetList.removeAllViews();
                    org.json.JSONObject bulbs = data.optJSONObject("bulbs");
                    if (bulbs != null) {
                        for (java.util.Iterator<String> it = bulbs.keys(); it.hasNext(); ) {
                            String mac = it.next();
                            String name = bulbs.optJSONObject(mac).optString("name", mac);
                            addTargetRow(mac, name);
                        }
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Server unreachable", Toast.LENGTH_LONG).show());
            }
        });
    }

    private void addTargetRow(String mac, String name) {
        CheckBox cb = new CheckBox(this);
        cb.setText(name);
        cb.setContentDescription("bulb " + mac);
        cb.setTextColor(getResources().getColor(R.color.sengled_text));
        cb.setTextSize(15);
        cb.setButtonTintList(ColorStateList.valueOf(0xFF34C759));
        cb.setTag(mac);
        cb.setChecked(false);   // nothing selected until the user ticks
        targetList.addView(cb);
    }

    private void save() {
        EditText edLabel = findViewById(R.id.edLabel);
        try {
            String paramText = edParam.getText().toString().trim();
            String triggerKind = TRIGGER_LABELS[spTrigger.getSelectedItemPosition()];
            JSONObject trigger = new JSONObject();
            if (triggerKind.startsWith("Every day")) {
                if (!paramText.matches("\\d{1,2}:\\d{2}") || paramText.isEmpty()) {
                    throw new Exception("tap the time field to pick a clock time");
                }
                trigger.put("type", "daily");
                trigger.put("time", paramText);
            } else if (triggerKind.startsWith("Every N")) {
                trigger.put("type", "interval");
                trigger.put("minutes", Integer.parseInt(paramText));
            } else if (triggerKind.startsWith("At sunset")) {
                trigger.put("type", "sunset");
                trigger.put("offsetMinutes", paramText.isEmpty() ? 0 : Integer.parseInt(paramText));
            } else {
                trigger.put("type", "sunrise");
                trigger.put("offsetMinutes", paramText.isEmpty() ? 0 : Integer.parseInt(paramText));
            }

            if (pendingActions.isEmpty()) {
                throw new Exception("add at least one action");
            }
            JSONArray macs = new JSONArray();
            for (int i = 0; i < targetList.getChildCount(); i++) {
                View c = targetList.getChildAt(i);
                if (c instanceof CheckBox && ((CheckBox) c).isChecked()) {
                    macs.put((String) c.getTag());
                }
            }
            if (macs.length() == 0) throw new Exception("pick at least one bulb");

            JSONArray actions = new JSONArray();
            for (JSONObject def : pendingActions) {
                def.put("macs", macs);
                actions.put(def);
            }

            JSONObject payload = new JSONObject();
            String label = edLabel.getText().toString().trim();
            if (!label.isEmpty()) payload.put("label", label);   // server defaults otherwise
            payload.put("trigger", trigger);
            payload.put("actions", actions);

            State.get().io.execute(() -> {
                try {
                    Api.post("/api/automations", payload);
                    runOnUiThread(() -> {
                        Toast.makeText(this, "Automation created", Toast.LENGTH_SHORT).show();
                        finish();
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> Toast.makeText(this, "failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
                }
            });
        } catch (Exception e) {
            Toast.makeText(this, "check your inputs: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
