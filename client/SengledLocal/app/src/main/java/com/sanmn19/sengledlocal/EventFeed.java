package com.sanmn19.sengledlocal;

import org.json.JSONObject;

/**
 * Long-poll listener for server-side bulb events: each echo the server sees
 * bumps its event version, this feed wakes within ~1s and triggers the UI
 * refresh with the fresh attribute snapshot. Reconnects with backoff.
 */
public class EventFeed extends Thread {
    private volatile boolean alive = false;
    private final Runnable onChange;

    public EventFeed(Runnable onChange) {
        this.onChange = onChange;
        setDaemon(true);
        setName("sengled-events");
    }

    public void startFeed() {
        alive = true;
        start();
    }

    public void stopFeed() {
        alive = false;
        interrupt();
    }

    @Override
    public void run() {
        while (alive) {
            try {
                com.sanmn19.sengledlocal.State s = com.sanmn19.sengledlocal.State.get();
                JSONObject e = Api.getLong("/api/events?since=" + s.eventsVersion);
                s.eventsVersion = e.optLong("version", s.eventsVersion);
                if (e.optBoolean("changed")) {
                    onChange.run();
                }
            } catch (Exception ex) {
                if (alive) {
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException ie) {
                        break;
                    }
                }
            }
        }
    }
}
