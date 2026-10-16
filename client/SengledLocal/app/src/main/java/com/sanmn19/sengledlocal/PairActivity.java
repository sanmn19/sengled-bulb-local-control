package com.sanmn19.sengledlocal;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.Bundle;
import android.os.PatternMatcher;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PairActivity extends Activity {
    private static final String TAG = "PairActivity";
    private static final String BULB_IP = "192.168.8.1";
    private static final int BULB_PORT = 9080;
    private static final String KEY_STR = "MTlCaWppbmdTaGFuZ2hhaVdpU2VuZ2xlZEZpMjBBQUJBU0U2NA==";

    private TextView tvStatus;
    private ProgressBar progress;
    private LinearLayout netList;
    private EditText edPassword;
    private Button btnProceed;
    private String chosenSsid = "";
    private String bulbMac = "";
    private android.net.Network wifiNetwork;
    private ConnectivityManager.NetworkCallback networkCallback;
    private final android.os.Handler uiHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean retryScheduled = false;
    private boolean manualRequested = false;
    private boolean wifiPanelOpened = false;
    private boolean processBound = false;
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_pair);
        tvStatus = findViewById(R.id.tvStatus);
        tvStatus.setTextColor(getResources().getColor(R.color.sengled_text));
        progress = findViewById(R.id.progress);
        netList = findViewById(R.id.netList);
        edPassword = findViewById(R.id.edPassword);
        btnProceed = findViewById(R.id.btnProceed);

        if (android.os.Build.VERSION.SDK_INT >= 23
                && !android.provider.Settings.System.canWrite(this)) {
            status("Android requires one-time approval: 'Modify system settings' — opening the panel; grant it, then restart pairing");
            startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    android.net.Uri.parse("package:" + getPackageName())));
            return;
        }
        android.net.wifi.WifiManager wm =
                (android.net.wifi.WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null && !wm.isWifiEnabled()) {
            wifiPanelOpened = true;
            status("Wi-Fi is off — opening the Wi-Fi panel; turn it on, then come back.");
            startActivity(new Intent(android.provider.Settings.Panel.ACTION_WIFI));
            return;
        }
        status("Scanning for the bulb's Wi-Fi...");
        requestBulbNetwork();
        findViewById(R.id.btnManual).setOnClickListener(v -> {
            manualRequested = true;
            uiHandler.removeCallbacksAndMessages(null);
            retryScheduled = false;
            status("Opening Wi-Fi settings — connect to the bulb's hotspot, then come back.");
            startActivity(new Intent(android.provider.Settings.ACTION_WIFI_SETTINGS));
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (wifiPanelOpened) {
            wifiPanelOpened = false;
            progress.setVisibility(View.VISIBLE);
            status("Wi-Fi back on — scanning for the bulb's Wi-Fi...");
            requestBulbNetwork();
            return;
        }
        if (manualRequested) {
            manualRequested = false;
            progress.setVisibility(View.VISIBLE);
            status("Trying the bulb handshake on the current Wi-Fi...");
            io.execute(() -> runPairing(null));
        }
    }

    private void status(String s) {
        runOnUiThread(() -> {
            tvStatus.setText(s);
            Log.d(TAG, s);
        });
    }

    private void requestBulbNetwork() {
        try {
            retryScheduled = false;
            progress.setVisibility(View.VISIBLE);
            status("Scanning for the bulb's Wi-Fi...");
            if (networkCallback != null) {
                try {
                    ((ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE))
                            .unregisterNetworkCallback(networkCallback);
                } catch (Exception ignored) {
                }
                networkCallback = null;
            }
            WifiNetworkSpecifier spec = new WifiNetworkSpecifier.Builder()
                    .setSsidPattern(new PatternMatcher("Sengled", PatternMatcher.PATTERN_PREFIX))
                    .build();
            NetworkRequest req = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .setNetworkSpecifier(spec)
                    .build();
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    wifiNetwork = network;
                    status("Connected to bulb AP — talking to bulb...");
                    io.execute(() -> runPairing(network));
                }

                @Override
                public void onUnavailable() {
                    status("No bulb hotspot found yet — keep retrying every 3s");
                    if (!retryScheduled) {
                        retryScheduled = true;
                        uiHandler.postDelayed(() -> requestBulbNetwork(), 3000);
                    }
                }
            };
            cm.requestNetwork(req, networkCallback);
        } catch (Exception e) {
            status("Wi-Fi switch failed: " + e.getMessage());
        }
    }

    private void runPairing(android.net.Network network) {
        try {
            DatagramSocket sock = new DatagramSocket();
            sock.setSoTimeout(5000);
            if (network != null) {
                boolean bound = false;
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    try {
                        network.bindSocket(sock);
                        bound = true;
                    } catch (Exception bindError) {
                        // OneUI denies bindSocket on specifier networks (EPERM):
                        // fall back to routing the whole process through that network
                        try {
                            bound = ConnectivityManager.setProcessDefaultNetwork(network);
                            processBound = bound;
                        } catch (Exception e2) {
                            bound = false;
                        }
                    }
                }
                if (!bound) {
                    sock.close();
                    status("Automatic network binding refused — use Manual mode at the bottom.");
                    return;
                }
            }

            status("[1/5] handshake...");
            JSONObject start = new JSONObject()
                    .put("name", "startConfigRequest")
                    .put("totalStep", 1).put("curStep", 1)
                    .put("payload", new JSONObject().put("protocol", 1));
            JSONObject resp = roundtrip(sock, start);
            bulbMac = resp.getJSONObject("payload").optString("mac", "");
            if (bulbMac.length() != 17) {
                status("Handshake failed: " + resp);
                return;
            }
            status("Bulb found: " + bulbMac);

            status("[2/5] asking bulb to scan Wi-Fi networks...");
            sock.send(jsonUdp(new JSONObject()
                    .put("name", "scanWifiRequest")
                    .put("totalStep", 1).put("curStep", 1)
                    .put("payload", new JSONObject())));
            Thread.sleep(5000);

            JSONObject apReq = new JSONObject()
                    .put("name", "getAPListRequest")
                    .put("totalStep", 1).put("curStep", 1)
                    .put("payload", new JSONObject());
            JSONArray routers = null;
            for (int attempt = 0; attempt < 3 && (routers == null || routers.length() == 0); attempt++) {
                try {
                    resp = roundtrip(sock, apReq);
                    routers = resp.getJSONObject("payload").optJSONArray("routers");
                } catch (java.net.SocketTimeoutException t) {
                    routers = null;
                }
                if ((routers == null || routers.length() == 0) && attempt < 2) {
                    status("scan still in progress... re-requesting the AP list");
                    Thread.sleep(1500);
                }
            }
            final JSONArray routerList = routers;
            if (routerList == null || routerList.length() == 0) {
                status("No networks found by the bulb.");
                return;
            }
            status("[3/5] pick your Wi-Fi network:");
            runOnUiThread(() -> {
                progress.setVisibility(View.GONE);
                netList.removeAllViews();
                boolean anyShown = false;
                for (int i = 0; i < routerList.length(); i++) {
                    JSONObject r = routerList.optJSONObject(i);
                    if (r == null) continue;
                    String ssid = r.optString("ssid", "");
                    // the bulb's own hotspot can never be its uplink — hide it
                    if (ssid.toLowerCase(java.util.Locale.ROOT).startsWith("sengled")) continue;
                    if (ssid.isEmpty()) continue;
                    anyShown = true;
                    Button b = new Button(PairActivity.this);
                    b.setText(ssid);
                    b.setOnClickListener(v -> {
                        chosenSsid = ssid;
                        edPassword.setVisibility(View.VISIBLE);
                        btnProceed.setVisibility(View.VISIBLE);
                        status("Selected " + ssid + " — enter password and send.");
                    });
                    netList.addView(b);
                }
                if (!anyShown) {
                    netList.removeAllViews();
                    TextView note = new TextView(PairActivity.this);
                    note.setText("The bulb only sees its own hotspot near you — move it closer to your router and rescan.");
                    note.setTextSize(14);
                    note.setTextColor(0xFF7E7E7E);
                    netList.addView(note);
                    btnProceed.setVisibility(View.GONE);
                    edPassword.setVisibility(View.GONE);
                    status("No jo networks found — rescan by pressing Pair again.");
                }
                btnProceed.setOnClickListener(v -> {
                    String pw = edPassword.getText().toString();
                    if (pw.isEmpty()) {
                        Toast.makeText(this, "enter password", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    btnProceed.setEnabled(false);
                    io.execute(() -> sendConfig(sock, pw));
                });
            });
        } catch (java.net.SocketTimeoutException e) {
            status("Bulb not answering handshake yet — retrying every 3s...");
            uiHandler.postDelayed(() -> {
                if (wifiNetwork != null) {
                    io.execute(() -> runPairing(wifiNetwork));
                }
            }, 3000);
        } catch (Exception e) {
            status("Pairing error: " + e.getMessage());
        }
    }

    private void sendConfig(DatagramSocket sock, String password) {
        try {
            status("[4/5] sending encrypted config...");
            // The bulb must reach the server over the home LAN — use the fixed
            // LAN address, never the app's current (e.g. Tailscale) host.
            String pairHost = State.get().activeHost().isEmpty() ? "192.168.1.50" : State.get().activeHost();
            JSONObject params = new JSONObject()
                    .put("name", "setParamsRequest")
                    .put("totalStep", 1).put("curStep", 1)
                    .put("payload", new JSONObject()
                            .put("userID", "618")
                            .put("appServerDomain",
                                    "http://" + pairHost + ":" + State.get().port
                                            + "/life2/device/accessCloud.json")
                            .put("jbalancerDomain",
                                    "http://" + pairHost + ":" + State.get().port
                                            + "/jbalancer/new/bimqtt")
                            .put("timeZone", java.util.TimeZone.getDefault().getID())
                            .put("routerInfo", new JSONObject()
                                    .put("ssid", chosenSsid)
                                    .put("password", password)));
            sock.send(encrypt(params));

            status("[5/5] configuring finished — bulb is joining Wi-Fi...");
            try {
                JSONObject end = new JSONObject()
                        .put("name", "endConfigRequest")
                        .put("totalStep", 1).put("curStep", 1)
                        .put("payload", new JSONObject());
                sock.send(jsonUdp(end));
            } catch (Exception ignored) {
            }

            Thread.sleep(3000);
            releaseNetwork();
            if (processBound) {
                try {
                    ConnectivityManager.setProcessDefaultNetwork(null);
                } catch (Exception ignored) {
                }
                processBound = false;
            }
            registerWithServer(bulbMac);
        } catch (Exception e) {
            status("Send failed: " + e.getMessage());
        }
    }

    private void registerWithServer(String mac) {
        State.get().io.execute(() -> {
            try {
                Api.post("/api/bulbs/register", new JSONObject().put("mac", mac));
                runOnUiThread(() -> {
                    Toast.makeText(this, "Paired " + mac, Toast.LENGTH_LONG).show();
                    finish();
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this,
                        "Paired, but register failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void releaseNetwork() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (networkCallback != null) {
                cm.unregisterNetworkCallback(networkCallback);
                networkCallback = null;
            }
        } catch (Exception ignored) {
        }
    }

    private DatagramPacket jsonUdp(JSONObject o) throws Exception {
        return jsonUdp(o.toString());
    }

    private DatagramPacket jsonUdp(String s) throws Exception {
        byte[] data = s.getBytes("UTF-8");
        return new DatagramPacket(data, data.length,
                InetAddress.getByName(BULB_IP), BULB_PORT);
    }

    private DatagramPacket encrypt(JSONObject o) throws Exception {
        String text = o.toString();
        byte[] enc = rc4(text.getBytes("UTF-8"), KEY_STR.getBytes("UTF-8"));
        String b64 = android.util.Base64.encodeToString(enc, android.util.Base64.NO_WRAP);
        return jsonUdp(b64);
    }

    private static byte[] rc4(byte[] data, byte[] key) {
        int[] s = new int[256];
        for (int i = 0; i < 256; i++) s[i] = i;
        int j = 0;
        for (int i = 0; i < 256; i++) {
            j = (j + s[i] + (key[i % key.length] & 0xFF)) % 256;
            int t = s[i];
            s[i] = s[j];
            s[j] = t;
        }
        byte[] out = new byte[data.length];
        int i = 0;
        j = 0;
        for (int k = 0; k < data.length; k++) {
            i = (i + 1) % 256;
            j = (j + s[i]) % 256;
            int t = s[i];
            s[i] = s[j];
            s[j] = t;
            int k2 = s[(s[i] + s[j]) % 256];
            out[k] = (byte) (data[k] ^ (k2 & 0xFF));
        }
        return out;
    }

    private JSONObject roundtrip(DatagramSocket sock, JSONObject payload) throws Exception {
        sock.send(jsonUdp(payload));
        byte[] buf = new byte[4096];
        DatagramPacket p = new DatagramPacket(buf, buf.length);
        sock.receive(p);
        String text = new String(p.getData(), p.getOffset(), p.getLength(), "UTF-8");
        try {
            return new JSONObject(text);
        } catch (Exception e) {
            byte[] dec = rc4(android.util.Base64.decode(text, android.util.Base64.NO_WRAP),
                    KEY_STR.getBytes("UTF-8"));
            return new JSONObject(new String(dec, "UTF-8"));
        }
    }
}
