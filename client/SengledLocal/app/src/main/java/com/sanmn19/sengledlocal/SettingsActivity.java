package com.sanmn19.sengledlocal;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

public class SettingsActivity extends Activity {
    private LinearLayout roomsList;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        State.get().init(this);
        setContentView(R.layout.activity_settings);
        roomsList = findViewById(R.id.roomsList);

        EditText edHost = findViewById(R.id.edHost);
        EditText edPort = findViewById(R.id.edPort);
        edHost.setText(State.get().activeHost());
        edPort.setText(String.valueOf(State.get().port));
        findViewById(R.id.btnSave).setOnClickListener(v -> {
            try {
                String h = edHost.getText().toString().trim();
                int p = Integer.parseInt(edPort.getText().toString().trim());
                State.get().saveServer(h, p);
                Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "Bad address", Toast.LENGTH_SHORT).show();
            }
        });

        findViewById(R.id.btnAddRoom).setOnClickListener(v -> {
            EditText ed = findViewById(R.id.edRoomName);
            String name = ed.getText().toString().trim();
            if (name.isEmpty()) return;
            ed.setText("");
            State.get().io.execute(() -> {
                try {
                    Api.post("/api/rooms", new JSONObject().put("name", name));
                    runOnUiThread(this::reloadRooms);
                } catch (Exception e) {
                    runOnUiThread(() -> Toast.makeText(this, "failed", Toast.LENGTH_SHORT).show());
                }
            });
        });
        reloadRooms();
    }

    private void reloadRooms() {
        State.get().io.execute(() -> {
            try {
                JSONObject rooms = Api.get("/api/rooms");
                runOnUiThread(() -> renderRooms(rooms));
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Server unreachable", Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void renderRooms(JSONObject rooms) {
        roomsList.removeAllViews();
        JSONArray arr = rooms.optJSONArray("rooms");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject r = arr.optJSONObject(i);
                if (r == null) continue;
                addRoomRow(r.optString("name"),
                        r.optJSONArray("bulbs") == null ? 0 : r.optJSONArray("bulbs").length());
            }
        }
        if (roomsList.getChildCount() == 0) {
            TextView t = new TextView(this);
            t.setText("(no rooms yet)");
            t.setTextColor(getResources().getColor(R.color.sengled_text_dim));
            t.setPadding(8, 12, 8, 12);
            roomsList.addView(t);
        }
    }

    private void addRoomRow(String name, int bulbCount) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(8, 12, 8, 12);

        TextView label = new TextView(this);
        label.setText(name + " (" + bulbCount + ")");
        label.setTextSize(16);
        label.setTextColor(getResources().getColor(R.color.sengled_text));
        label.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(label);

        Button del = new Button(this);
        del.setText("✕");
        del.setOnClickListener(v -> new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog)
                .setTitle("Delete room '" + name + "'?")
                .setPositiveButton("Delete", (d, w) -> State.get().io.execute(() -> {
                    try {
                        Api.delete("/api/rooms/" + java.net.URLEncoder.encode(name, "UTF-8"));
                        runOnUiThread(this::reloadRooms);
                    } catch (Exception e) {
                        runOnUiThread(() -> Toast.makeText(this, "failed", Toast.LENGTH_SHORT).show());
                    }
                }))
                .setNegativeButton(android.R.string.cancel, null)
                .show());
        row.addView(del);
        roomsList.addView(row);
    }
}
