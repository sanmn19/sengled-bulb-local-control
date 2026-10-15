package com.sanmn19.sengledlocal;

import android.app.Activity;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

public class AutomationFormActivity extends Activity {
    private LinearLayout targetList;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        State.get().init(this);
        setContentView(R.layout.activity_automation_form);
        targetList = findViewById(R.id.targetList);

        EditText edParam = findViewById(R.id.edTriggerParam);
        View.OnFocusChangeListener fcl = (v, hasFocus) -> updateParamHint(edParam);
        findViewById(R.id.rbDaily).setOnClickListener(v -> updateParamHint(edParam));
        findViewById(R.id.rbSunset).setOnClickListener(v -> updateParamHint(edParam));
        findViewById(R.id.rbSunrise).setOnClickListener(v -> updateParamHint(edParam));
        findViewById(R.id.rbInterval).setOnClickListener(v -> updateParamHint(edParam));
        findViewById(R.id.rbPowerOn).setOnClickListener(v -> updateValueHint());
        findViewById(R.id.rbPowerOff).setOnClickListener(v -> updateValueHint());
        findViewById(R.id.rbBrightness).setOnClickListener(v -> updateValueHint());
        findViewById(R.id.rbTemp).setOnClickListener(v -> updateValueHint());
        findViewById(R.id.rbColor).setOnClickListener(v -> updateValueHint());
        findViewById(R.id.rbSet).setOnClickListener(v -> updateValueHint());
        findViewById(R.id.rbOffset).setOnClickListener(v -> updateValueHint());
        updateParamHint(edParam);
        updateValueHint();

        findViewById(R.id.btnSaveAuto).setOnClickListener(v -> save());
        loadBulbs();
    }

    private boolean isTrigger(int id) {
        return ((android.widget.RadioButton) findViewById(id)).isChecked();
    }

    private void updateParamHint(EditText edParam) {
        if (isTrigger(R.id.rbDaily)) {
            edParam.setHint("clock time: 22:00");
        } else if (isTrigger(R.id.rbInterval)) {
            edParam.setHint("minutes: 30");
        } else if (isTrigger(R.id.rbSunrise) || isTrigger(R.id.rbSunset)) {
            edParam.setHint("minutes after (negative = before): e.g. -20");
        }
    }

    private void updateValueHint() {
        EditText ed = findViewById(R.id.edValue);
        boolean isColor = isTrigger(R.id.rbColor);
        boolean offset = isTrigger(R.id.rbOffset);
        if (isColor) {
            ed.setHint(offset ? "RGB adjust: 10:-20:5" : "RGB set: 255:100:0");
        } else if (isTrigger(R.id.rbPowerOn) || isTrigger(R.id.rbPowerOff)) {
            ed.setHint("(no value needed)");
        } else {
            ed.setHint(offset ? "adjust by: -30 (negative dims/warms)" : "value: 0-100");
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
        cb.setText(name + "  (" + mac + ")");
        cb.setTextColor(getResources().getColor(R.color.sengled_text));
        cb.setTextSize(15);
        cb.setTag(mac);
        cb.setChecked(true);
        targetList.addView(cb);
    }

    private void save() {
        EditText edLabel = findViewById(R.id.edLabel);
        EditText edParam = findViewById(R.id.edTriggerParam);
        EditText edValue = findViewById(R.id.edValue);
        try {
            org.json.JSONObject trigger = new org.json.JSONObject();
            String paramText = edParam.getText().toString().trim();
            if (isTrigger(R.id.rbDaily)) {
                trigger.put("type", "daily");
                trigger.put("time", paramText);
            } else if (isTrigger(R.id.rbInterval)) {
                trigger.put("type", "interval");
                trigger.put("minutes", Integer.parseInt(paramText));
            } else if (isTrigger(R.id.rbSunset)) {
                trigger.put("type", "sunset");
                trigger.put("offsetMinutes", paramText.isEmpty() ? 0 : Integer.parseInt(paramText));
            } else {
                trigger.put("type", "sunrise");
                trigger.put("offsetMinutes", paramText.isEmpty() ? 0 : Integer.parseInt(paramText));
            }

            org.json.JSONObject value = new org.json.JSONObject();
            String valText = edValue.getText().toString().trim();
            boolean powerOn = isTrigger(R.id.rbPowerOn);
            boolean powerAction = powerOn || isTrigger(R.id.rbPowerOff);
            String action = powerAction ? "power"
                    : isTrigger(R.id.rbBrightness) ? "brightness"
                    : isTrigger(R.id.rbTemp) ? "colortemp" : "color";
            boolean offset = isTrigger(R.id.rbOffset);

            Object actionValue = null;
            if (!powerAction) {
                if (action.equals("color")) {
                    String[] parts = valText.split(":");
                    if (parts.length != 3) throw new Exception("color needs r:g:b");
                    if (offset) {
                        value.put("dr", part(parts[0]));
                        value.put("dg", part(parts[1]));
                        value.put("db", part(parts[2]));
                    } else {
                        value.put("r", part(parts[0]));
                        value.put("g", part(parts[1]));
                        value.put("b", part(parts[2]));
                    }
                } else {
                    if (valText.isEmpty()) throw new Exception("value required");
                    actionValue = Integer.parseInt(valText);
                }
            } else {
                actionValue = powerOn;
            }

            org.json.JSONArray macs = new org.json.JSONArray();
            for (int i = 0; i < targetList.getChildCount(); i++) {
                View c = targetList.getChildAt(i);
                if (c instanceof CheckBox && ((CheckBox) c).isChecked()) {
                    macs.put((String) c.getTag());
                }
            }
            if (macs.length() == 0) throw new Exception("pick at least one bulb");

            org.json.JSONObject actionDef = new org.json.JSONObject();
            org.json.JSONArray actions = new org.json.JSONArray();
            if (!powerAction) {
                actionDef.put("action", action);
                actionDef.put("mode", offset ? "offset" : "set");
                actionDef.put("macs", macs);
                if (actionValue != null) {
                    actionDef.put("value", actionValue);
                } else {
                    actionDef.put("value", value);
                }
            } else {
                actionDef.put("action", "power");
                actionDef.put("mode", "set");
                actionDef.put("macs", macs);
                actionDef.put("value", true);
            }
            actions.put(actionDef);

            org.json.JSONObject payload = new org.json.JSONObject();
            payload.put("label", edLabel.getText().toString().trim());
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

    private int part(String s) {
        return Math.max(0, Math.min(255, Integer.parseInt(s.trim())));
    }
}
