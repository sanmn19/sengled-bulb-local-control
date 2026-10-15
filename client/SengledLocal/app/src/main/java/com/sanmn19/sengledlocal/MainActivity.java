package com.sanmn19.sengledlocal;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private LinearLayout container;
    private EventFeed feed;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        State.get().init(this);
        setContentView(R.layout.activity_main);
        container = findViewById(R.id.roomsContainer);
        findViewById(R.id.btnRefresh).setOnClickListener(v -> deepRefresh());
        findViewById(R.id.btnSettings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        findViewById(R.id.btnAutomations).setOnClickListener(v ->
                startActivity(new Intent(this, AutomationsActivity.class)));
        findViewById(R.id.btnPair).setOnClickListener(v -> showAddChoices());
        feed = new EventFeed(() -> runOnUiThread(MainActivity.this::reload));
        feed.startFeed();
        reload();
    }

    @Override
    protected void onDestroy() {
        if (feed != null) {
            feed.stopFeed();
        }
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    /**
     * Deep refresh: asks the server to query each bulb directly (MQTT status +
     * UDP brightness probe), then renders the freshest state.
     */
    private void deepRefresh() {
        State.get().io.execute(() -> {
            try {
                Api.post("/api/refresh", new org.json.JSONObject());
            } catch (Exception ignored) {
            }
            try {
                JSONObject rooms = Api.get("/api/rooms");
                JSONObject bulbs = Api.get("/api/bulbs");
                runOnUiThread(() -> render(rooms, bulbs));
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this,
                        "Server unreachable: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void reload() {
        State.get().io.execute(() -> {
            try {
                JSONObject rooms = Api.get("/api/rooms");
                JSONObject bulbs = Api.get("/api/bulbs");
                runOnUiThread(() -> render(rooms, bulbs));
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this,
                        "Server unreachable: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void render(JSONObject rooms, JSONObject bulbs) {
        container.removeAllViews();
        JSONArray roomList = rooms.optJSONArray("rooms");
        JSONObject bulbMap = bulbs.optJSONObject("bulbs");

        List<String> shownMacs = new ArrayList<>();
        if (roomList != null) {
            for (int i = 0; i < roomList.length(); i++) {
                JSONObject room = roomList.optJSONObject(i);
                if (room == null) continue;
                String name = room.optString("name");
                JSONArray macs = room.optJSONArray("bulbs");
                boolean allOn = true;
                int bulbCount = 0;
                if (macs != null) {
                    for (int j = 0; j < macs.length(); j++) {
                        if (macs.optString(j, "").isEmpty()) continue;
                        bulbCount++;
                        if (!bulbPower(macs.optString(j, ""), bulbMap)) {
                            allOn = false;
                        }
                    }
                }
                if (bulbCount == 0) {
                    allOn = false;
                }
                addRoomHeader(name, allOn, macs);
                if (macs != null) {
                    for (int j = 0; j < macs.length(); j++) {
                        String mac = macs.optString(j, "");
                        addBulbRow(mac, bulbMap);
                        shownMacs.add(mac);
                    }
                }
            }
        }
        unassignedHeader(getString(R.string.unassigned));
        if (bulbMap != null) {
            for (java.util.Iterator<String> it = bulbMap.keys(); it.hasNext(); ) {
                String mac = it.next();
                if (!shownMacs.contains(mac)) {
                    addBulbRow(mac, bulbMap);
                }
            }
        }
        if (container.getChildCount() == 0) {
            TextView empty = new TextView(this);
            empty.setText("No bulbs yet — tap Pair new bulb");
            empty.setTextColor(getResources().getColor(R.color.sengled_text_dim));
            empty.setPadding(24, 24, 24, 24);
            container.addView(empty);
        }
    }

    private void unassignedHeader(String name) {
        TextView t = new TextView(this);
        t.setText(name);
        t.setTextSize(16);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setTextColor(getResources().getColor(R.color.sengled_text_dim));
        t.setPadding(8, 24, 8, 22);
        container.addView(t);
    }

    private void addRoomHeader(String name, boolean anyOn, JSONArray macs) {
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(8, 24, 16, 22);
        head.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView t = new TextView(this);
        t.setText(name);
        t.setTextSize(18);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setTextColor(getResources().getColor(R.color.sengled_text));
        t.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(t);

        Switch roomSwitch = new Switch(this);
        roomSwitch.setScaleX(0.77f);
        roomSwitch.setScaleY(0.77f);
        roomSwitch.setChecked(anyOn);
        PowerSwitchStyle.apply(roomSwitch, anyOn);
        LinearLayout.LayoutParams roomSwParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        roomSwParams.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        roomSwParams.setMarginEnd(Math.round(3f * getResources().getDisplayMetrics().density));
        roomSwitch.setLayoutParams(roomSwParams);
        roomSwitch.setOnCheckedChangeListener((b, checked) -> {
            if (b.isPressed()) {
                PowerSwitchStyle.apply(roomSwitch, checked);
                State.get().io.execute(() -> {
                    try {
                        Api.post("/api/rooms/" + java.net.URLEncoder.encode(name, "UTF-8") + "/power",
                                new org.json.JSONObject().put("on", checked));
                        if (macs != null) {
                            for (int k = 0; k < macs.length(); k++) {
                                State.get().rememberPower(macs.optString(k, ""), checked);
                            }
                        }
                        // reflect room-level change after echoes land
                        new android.os.Handler(android.os.Looper.getMainLooper())
                                .postDelayed(() -> runOnUiThread(MainActivity.this::reload), 1300);
                    } catch (Exception e) {
                        runOnUiThread(() -> Toast.makeText(this, "room power failed", Toast.LENGTH_SHORT).show());
                    }
                });
            }
        });
        head.addView(roomSwitch);
        container.addView(head);
    }

    /** current best-known power state of a bulb (server echo first, cache fallback) */
    private boolean bulbPower(String mac, JSONObject bulbMap) {
        JSONObject info = bulbMap == null ? null : bulbMap.optJSONObject(mac);
        org.json.JSONObject attrs = info == null ? null : info.optJSONObject("attributes");
        if (attrs != null && attrs.has("switch")) {
            boolean s = "1".equals(attrs.optString("switch", "1"));
            State.get().rememberPower(mac, s);
            return s;
        }
        return State.get().lastPower(mac);
    }

    private void addBulbRow(String mac, JSONObject bulbMap) {
        JSONObject info = bulbMap == null ? new JSONObject() : bulbMap.optJSONObject(mac);
        String display = info == null ? mac : info.optString("name", mac);
        String ip = info == null ? "" : info.optString("ip", "");

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.card_dark);
        row.setElevation(2f);
        row.setPadding(16, 18, 16, 18);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = 8;
        row.setLayoutParams(lp);

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView name = new TextView(this);
        name.setText(display);
        name.setTextColor(getResources().getColor(R.color.sengled_text));
        name.setTextSize(18);
        textCol.addView(name);

        TextView sub = new TextView(this);
        sub.setText(mac + (ip.isEmpty() ? "" : "  ·  " + ip));
        sub.setTextSize(12);
        sub.setTextColor(getResources().getColor(R.color.sengled_text_dim));
        textCol.addView(sub);
        row.addView(textCol);

        Switch sw = new Switch(this);
        sw.setScaleX(0.77f);
        sw.setScaleY(0.77f);
        LinearLayout.LayoutParams swParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        swParams.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        int pxOffset = Math.round(3f * getResources().getDisplayMetrics().density);
        swParams.setMarginEnd(pxOffset);
        // Trust the bulb's own reported state first (server stores its status
        // echoes), fall back to the app's remembered state.
        boolean powerState;
        org.json.JSONObject attrs = info == null ? null : info.optJSONObject("attributes");
        if (attrs != null && attrs.has("switch")) {
            powerState = "1".equals(attrs.optString("switch", "1"));
        } else {
            powerState = State.get().lastPower(mac);
        }
        State.get().rememberPower(mac, powerState);
        sw.setChecked(powerState);
        PowerSwitchStyle.apply(sw, powerState);
        sw.setOnCheckedChangeListener((b, checked) -> {
            PowerSwitchStyle.apply(sw, checked);
            State.get().rememberPower(mac, checked);
            State.get().io.execute(() -> {
                try {
                    JSONObject body = new JSONObject();
                    body.put("on", checked);
                    Api.post("/api/bulbs/" + mac + "/power", body);
                    // wait for the async echo confirmation, THEN refresh so the
                    // switch state reflects the bulb's actual reported state
                    new android.os.Handler(android.os.Looper.getMainLooper())
                            .postDelayed(() -> runOnUiThread(MainActivity.this::reload), 1300);
                } catch (Exception e) {
                    runOnUiThread(() -> Toast.makeText(this, "command failed", Toast.LENGTH_SHORT).show());
                }
            });
        });
        sw.setLayoutParams(swParams);
        row.addView(sw);

        row.setOnClickListener(v -> {
            Intent i = new Intent(this, DetailActivity.class);
            i.putExtra("mac", mac);
            i.putExtra("name", display);
            startActivity(i);
        });
        row.setOnLongClickListener(v -> {
            showBulbMenu(mac, display);
            return true;
        });
        container.addView(row);
    }

    private void showAddChoices() {
        new AlertDialog.Builder(this)
                .setTitle("Add")
                .setItems(new String[]{"Add room", "Pair new bulb"}, (d, which) -> {
                    if (which == 0) addRoomDialog();
                    else startActivity(new Intent(this, PairActivity.class));
                })
                .show();
    }

    private void addRoomDialog() {
        EditText ed = new EditText(this);
        ed.setHint("Room name");
        new AlertDialog.Builder(this)
                .setTitle("Add room")
                .setView(ed)
                .setPositiveButton(R.string.save, (d, w) -> {
                    String name = ed.getText().toString().trim();
                    if (name.isEmpty()) return;
                    State.get().io.execute(() -> {
                        try {
                            Api.post("/api/rooms", new org.json.JSONObject().put("name", name));
                            runOnUiThread(this::reload);
                        } catch (Exception e) {
                            runOnUiThread(() -> Toast.makeText(this, "failed", Toast.LENGTH_SHORT).show());
                        }
                    });
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showBulbMenu(String mac, String display) {
        String[] options = {getString(R.string.rename), getString(R.string.move_to_room)};
        new AlertDialog.Builder(this)
                .setTitle(display)
                .setItems(options, (d, which) -> {
                    if (which == 0) renameBulb(mac, display);
                    else moveBulb(mac);
                })
                .show();
    }

    private void renameBulb(String mac, String current) {
        EditText ed = new EditText(this);
        ed.setText(current);
        new AlertDialog.Builder(this)
                .setTitle(R.string.rename)
                .setView(ed)
                .setPositiveButton(R.string.save, (d, w) -> State.get().io.execute(() -> {
                    try {
                        Api.post("/api/bulbs/" + mac + "/name",
                                new JSONObject().put("name", ed.getText().toString().trim()));
                        runOnUiThread(this::reload);
                    } catch (Exception e) {
                        runOnUiThread(() -> Toast.makeText(this, "failed", Toast.LENGTH_SHORT).show());
                    }
                }))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void moveBulb(String mac) {
        State.get().io.execute(() -> {
            try {
                JSONObject rooms = Api.get("/api/rooms");
                JSONArray arr = rooms.optJSONArray("rooms");
                List<String> names = new ArrayList<>();
                names.add(getString(R.string.unassigned));
                if (arr != null) for (int i = 0; i < arr.length(); i++) {
                    names.add(arr.getJSONObject(i).optString("name"));
                }
                runOnUiThread(() -> new AlertDialog.Builder(this)
                        .setTitle(R.string.move_to_room)
                        .setItems(names.toArray(new String[0]), (d, which) -> State.get().io.execute(() -> {
                            try {
                                if (which == 0) {
                                    runOnUiThread(this::reload);
                                    return;
                                }
                                String room = names.get(which);
                                Api.post("/api/rooms/" + java.net.URLEncoder.encode(room, "UTF-8")
                                        + "/assign", new JSONObject().put("mac", mac));
                                runOnUiThread(this::reload);
                            } catch (Exception e) {
                                runOnUiThread(() -> Toast.makeText(this, "failed", Toast.LENGTH_SHORT).show());
                            }
                        }))
                        .show());
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "failed", Toast.LENGTH_SHORT).show());
            }
        });
    }
}
