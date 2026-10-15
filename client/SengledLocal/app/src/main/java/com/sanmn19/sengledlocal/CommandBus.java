package com.sanmn19.sengledlocal;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Resilient batch command bus:
 *  - merges every change in a slot into ONE update call
 *  - records only the LATEST value per (setting, bulb) while streaming
 *  - if the server connection is broken, queues the latest changes and
 *    reconnects (host failover included) every ~3s, flushing on recovery
 */
public class CommandBus {
    private static final long PERIOD_MS = 400;
    private static final long RECONNECT_MS = 3000;

    private final Map<String, Map<String, JSONObject>> pending = new HashMap<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean scheduled = false;
    private boolean broken = false;
    private android.content.Context ui;
    private long lastToast = 0L;

    private void reportFailure(String message) {
        if (ui == null) return;
        long now = System.currentTimeMillis();
        if (now - lastToast < 3000) return;
        lastToast = now;
        handler.postDelayed(() -> android.widget.Toast.makeText(ui, message, android.widget.Toast.LENGTH_SHORT).show(), 10);
    }

    /** Supplies the activity context used for failure toasts. */
    public void setUi(android.content.Context c) {
        ui = c;
    }

    /** Records the latest value for one action; batched on the slot tick. */
    public void send(String mac, String action, JSONObject body) {
        synchronized (pending) {
            Map<String, JSONObject> batch = pending.get(mac);
            if (batch == null) {
                batch = new HashMap<>();
                pending.put(mac, batch);
            }
            batch.put(action, body);
        }
        schedule();
    }

    /** Sends this bulb's accumulated slot NOW (call when a gesture ends). */
    public void flush(String mac) {
        Map<String, JSONObject> batch;
        synchronized (pending) {
            batch = pending.remove(mac);
        }
        if (batch != null && !batch.isEmpty()) {
            dispatchBatch(mac, batch);
        }
    }

    /** Immediate single-action send (power toggle etc.). */
    public void immediate(String mac, String action, JSONObject body) {
        synchronized (pending) {
            Map<String, JSONObject> batch = pending.get(mac);
            if (batch != null) batch.remove(action);
        }
        dispatchDirect(mac, action, body);
    }

    private void schedule() {
        if (scheduled || broken) return;
        scheduled = true;
        handler.postDelayed(() -> {
            scheduled = false;
            if (broken) return;
            ArrayList<String> macs;
            synchronized (pending) {
                macs = new ArrayList<>(pending.keySet());
            }
            for (String mac : macs) {
                Map<String, JSONObject> batch;
                synchronized (pending) {
                    batch = pending.remove(mac);
                }
                if (batch != null && !batch.isEmpty()) {
                    dispatchBatch(mac, batch);
                }
            }
            synchronized (pending) {
                if (!pending.isEmpty()) schedule();
            }
        }, PERIOD_MS);
    }

    private void connectionLost() {
        if (broken) return;
        broken = true;
        reportFailure("Server unreachable — changes queued, retrying...");
        attemptReconnect();
    }

    private void attemptReconnect() {
        State.get().io.execute(() -> {
            boolean ok = false;
            try {
                Api.get("/api/health");
                ok = true;
            } catch (Exception ignored) {
            }
            final boolean reached = ok;
            handler.post(() -> {
                if (reached) {
                    broken = false;
                    reportFailureOnce("Reconnected — flushing queued changes");
                    flushAll();
                } else {
                    // still down — stays queued; try again
                    handler.postDelayed(this::attemptReconnect, RECONNECT_MS);
                }
            });
        });
    }

    private void reportFailureOnce(String message) {
        if (ui == null) return;
        handler.post(() -> android.widget.Toast.makeText(ui, message, android.widget.Toast.LENGTH_SHORT).show());
    }

    private void flushAll() {
        ArrayList<String> macs;
        synchronized (pending) {
            macs = new ArrayList<>(pending.keySet());
        }
        for (String mac : macs) {
            flush(mac);
        }
    }

    /** Merge the slot's per-action bodies into one update request. */
    private void dispatchBatch(String mac, Map<String, JSONObject> batch) {
        JSONObject merged = mergeBatch(batch);
        if (merged.length() == 0) return;
        State.get().io.execute(() -> {
            try {
                Api.post("/api/bulbs/" + mac + "/update", merged);
            } catch (Exception e) {
                requeue(mac, batch);
                reportFailure("Update failed: " + e.getMessage());
                connectionLost();
            }
        });
    }

    private void dispatchDirect(String mac, String action, JSONObject body) {
        State.get().io.execute(() -> {
            try {
                Api.post("/api/bulbs/" + mac + "/" + action, body);
            } catch (Exception e) {
                Map<String, JSONObject> one = new HashMap<>();
                one.put(action, body);
                requeue(mac, one);
                reportFailure("Command failed: " + e.getMessage());
                connectionLost();
            }
        });
    }

    /** Latest value per action; merges any previous pending entries. */
    private void requeue(String mac, Map<String, JSONObject> batch) {
        synchronized (pending) {
            Map<String, JSONObject> existing = pending.get(mac);
            if (existing == null) {
                existing = new HashMap<>();
                pending.put(mac, existing);
            }
            existing.putAll(batch);
        }
    }

    private JSONObject mergeBatch(Map<String, JSONObject> batch) {
        JSONObject merged = new JSONObject();
        for (Map.Entry<String, JSONObject> e : batch.entrySet()) {
            String action = e.getKey();
            JSONObject body = e.getValue();
            try {
                if ("power".equals(action)) {
                    merged.put("power", body.optBoolean("on", false));
                } else if ("brightness".equals(action)) {
                    merged.put("brightness", body.optInt("value", 0));
                } else if ("colortemp".equals(action)) {
                    merged.put("colortemp", body.optInt("value", 0));
                    if (body.has("brightness")) {
                        merged.put("brightness", body.optInt("brightness", 0));
                    }
                } else if ("color".equals(action)) {
                    JSONObject c = new JSONObject();
                    c.put("r", body.optInt("r", 0));
                    c.put("g", body.optInt("g", 0));
                    c.put("b", body.optInt("b", 0));
                    merged.put("color", c);
                }
            } catch (Exception ignored) {
            }
        }
        return merged;
    }
}
