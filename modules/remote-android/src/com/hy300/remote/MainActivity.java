package com.hy300.remote;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends Activity
        implements RemoteTransport.Listener, BluetoothTransport.Listener {

    private static final int REQ_BT_PERMS = 3001;
    private static final int BG_OBSIDIAN = Color.parseColor("#0A0B0E");

    private WebView webView;
    private RemoteTransport wifiTransport;
    private BluetoothTransport bleTransport;
    private OtaUpdater otaUpdater;
    private boolean installReceiverRegistered = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean pageLoaded = false;

    // OTA cached state for UI replay
    private volatile JSONObject lastOtaCheck = null;

    // Unified mirrored state for instant getInitialState()
    private volatile String activeTransport = "wifi"; // "wifi" | "bluetooth"
    private volatile String connectionState = "disconnected";
    private volatile String bleMode = "ble"; // "ble" | "rfcomm"
    private volatile String deviceName = "HY300Pro";
    private volatile boolean isAwake = true;
    private volatile int volume = 10;
    private volatile int maxVolume = 20;
    private volatile boolean isMuted = false;
    private volatile String resolution = "1920x1080";
    private volatile boolean isDaemonActive = false;
    private volatile int lastRttMs = -1;
    private volatile boolean pendingBleScanAfterPerm = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Window w = getWindow();
        w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        w.setStatusBarColor(BG_OBSIDIAN);
        w.setNavigationBarColor(BG_OBSIDIAN);

        if (Build.VERSION.SDK_INT >= 30) {
            w.getDecorView().setSystemUiVisibility(0);
        }

        wifiTransport = new RemoteTransport(this, this);
        bleTransport = new BluetoothTransport(this, this);
        otaUpdater = new OtaUpdater(this);
        activeTransport = wifiTransport.getPreferredTransport();

        // Register dynamic PackageInstaller result receiver
        IntentFilter otaFilter = new IntentFilter(OtaUpdater.ACTION_INSTALL_RESULT);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(otaInstallReceiver, otaFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(otaInstallReceiver, otaFilter);
        }
        installReceiverRegistered = true;

        webView = new WebView(this);
        webView.setBackgroundColor(BG_OBSIDIAN);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        webView.setVerticalScrollBarEnabled(false);
        webView.setHorizontalScrollBarEnabled(false);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                pageLoaded = true;
                pushConnectionEvent(null);
                pushBleStatus(bleTransport.getStatusText(), bleTransport.isScanning(), bleTransport.hasPermissions());
                if (lastOtaCheck != null) {
                    pushEventToJs(lastOtaCheck);
                }
            }
        });
        webView.setWebChromeClient(new WebChromeClient());
        webView.addJavascriptInterface(new RemoteBridge(), "NativeRemote");

        setContentView(webView);
        webView.loadUrl("file:///android_asset/index.html");

        // Auto-connect preferred transport
        if ("bluetooth".equals(activeTransport)
                && bleTransport.hasPermissions()
                && wifiTransport.getSavedBleAddress() != null) {
            bleTransport.connect(wifiTransport.getSavedBleAddress());
        } else {
            activeTransport = "wifi";
            wifiTransport.connect(wifiTransport.getHost(), RemoteTransport.DEFAULT_TCP_PORT, RemoteTransport.DEFAULT_HTTP_PORT);
        }

        // Check GitHub Releases for OTA update 2s after startup
        mainHandler.postDelayed(this::runOtaCheck, 2000);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if ("wifi".equals(activeTransport)) {
            wifiTransport.setForeground(true);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        wifiTransport.setForeground(false);
        wifiTransport.stopDiscovery();
        bleTransport.stopScan();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (installReceiverRegistered) {
            try { unregisterReceiver(otaInstallReceiver); } catch (Exception ignored) {}
            installReceiverRegistered = false;
        }
        wifiTransport.shutdown();
        bleTransport.shutdown();
        if (otaUpdater != null) otaUpdater.shutdown();
        if (webView != null) {
            webView.destroy();
        }
    }

    // --- GitHub OTA Self-Update ---

    private void runOtaCheck() {
        if (otaUpdater == null) return;
        otaUpdater.checkForUpdate(result -> {
            lastOtaCheck = result;
            pushEventToJs(result);
        });
    }

    private void pushOtaInstallEvent(String stage, int percent, String message) {
        try {
            JSONObject ev = new JSONObject();
            ev.put("event", "ota_install");
            ev.put("stage", stage);
            ev.put("percent", percent);
            ev.put("message", message != null ? message : "");
            pushEventToJs(ev);
        } catch (Exception ignored) {}
    }

    private final BroadcastReceiver otaInstallReceiver = new BroadcastReceiver() {
        @Override
        @SuppressWarnings("deprecation")
        public void onReceive(Context context, Intent intent) {
            if (intent == null || !OtaUpdater.ACTION_INSTALL_RESULT.equals(intent.getAction())) return;
            int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);

            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirmIntent = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirmIntent != null) {
                    confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try {
                        startActivity(confirmIntent);
                        pushOtaInstallEvent("prompting", 100, "Confirme a instalação na tela do Android");
                    } catch (Exception e) {
                        pushOtaInstallEvent("error", 0, "Não foi possível abrir o instalador: " + e.getMessage());
                    }
                }
            } else if (status == PackageInstaller.STATUS_SUCCESS) {
                performHaptic("success");
                pushOtaInstallEvent("success", 100, "Atualização instalada com sucesso!");
            } else if (status == PackageInstaller.STATUS_FAILURE_ABORTED) {
                pushOtaInstallEvent("aborted", 0, "Instalação cancelada");
            } else {
                pushOtaInstallEvent("error", 0,
                        (msg != null && !msg.isEmpty()) ? ("Erro na instalação: " + msg) : "Falha ao instalar atualização");
            }
        }
    };

    // --- Hardware Volume Button Interception ---

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            if (event.getRepeatCount() == 0) {
                sendKeyInternal("vol_up", "down");
                performHaptic("light");
                emitHardwareVolumeEvent("vol_up");
            }
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event.getRepeatCount() == 0) {
                sendKeyInternal("vol_down", "down");
                performHaptic("light");
                emitHardwareVolumeEvent("vol_down");
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            sendKeyInternal("vol_up", "up");
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            sendKeyInternal("vol_down", "up");
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    private void emitHardwareVolumeEvent(String key) {
        try {
            JSONObject ev = new JSONObject();
            ev.put("event", "hw_volume");
            ev.put("key", key);
            pushEventToJs(ev);
        } catch (Exception ignored) {}
    }

    // --- Runtime Permissions ---

    private void requestBtPermissionsInternal() {
        pendingBleScanAfterPerm = true;
        if (Build.VERSION.SDK_INT >= 31) {
            requestPermissions(new String[]{
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT
            }, REQ_BT_PERMS);
        } else {
            requestPermissions(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION
            }, REQ_BT_PERMS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_BT_PERMS) {
            boolean granted = true;
            if (grantResults.length == 0) {
                granted = false;
            } else {
                for (int r : grantResults) {
                    if (r != PackageManager.PERMISSION_GRANTED) {
                        granted = false;
                        break;
                    }
                }
            }
            pushBleStatus(bleTransport.getStatusText(), false, granted);
            if (granted && pendingBleScanAfterPerm) {
                pendingBleScanAfterPerm = false;
                bleTransport.startScan();
            }
        }
    }

    // --- Unified Command Router ---

    private void sendPayload(JSONObject payload) {
        String raw = payload.toString();
        if ("bluetooth".equals(activeTransport)) {
            bleTransport.sendRawJson(raw);
        } else {
            wifiTransport.sendRawJson(raw);
        }
    }

    private void sendKeyInternal(String key, String action) {
        try {
            JSONObject p = new JSONObject();
            p.put("t", "key");
            p.put("k", key);
            p.put("a", (action == null || action.isEmpty()) ? "tap" : action);
            sendPayload(p);
        } catch (Exception ignored) {}
    }

    // --- Incoming Protocol Handling (Unified Wi-Fi & BLE) ---

    private void handleProtocolMessage(JSONObject msg) {
        String type = msg.optString("t", "");
        try {
            if ("state".equals(type)) {
                String dev = msg.optString("device", "");
                if (!dev.isEmpty()) deviceName = dev;
                if (msg.has("awake")) isAwake = msg.optBoolean("awake", isAwake);
                if (msg.has("vol")) volume = msg.optInt("vol", volume);
                if (msg.has("maxVol")) {
                    int mv = msg.optInt("maxVol", maxVolume);
                    if (mv > 0) maxVolume = mv;
                }
                if (msg.has("muted")) isMuted = msg.optBoolean("muted", isMuted);
                String res = msg.optString("res", "");
                if (!res.isEmpty()) resolution = res.replace("Override size: ", "");
                if (msg.has("daemon")) isDaemonActive = msg.optBoolean("daemon", isDaemonActive);

                JSONObject ev = new JSONObject();
                ev.put("event", "state");
                ev.put("device", deviceName);
                ev.put("awake", isAwake);
                ev.put("vol", volume);
                ev.put("maxVol", maxVolume);
                ev.put("muted", isMuted);
                ev.put("res", resolution);
                ev.put("daemon", isDaemonActive);
                pushEventToJs(ev);

            } else if ("pong".equals(type)) {
                long ts = msg.optLong("ts", 0);
                if (ts > 0) {
                    int rtt = (int) Math.max(1, System.currentTimeMillis() - ts);
                    lastRttMs = rtt;
                    JSONObject ev = new JSONObject();
                    ev.put("event", "rtt");
                    ev.put("ms", rtt);
                    pushEventToJs(ev);
                }

            } else if ("apps".equals(type)) {
                JSONArray items = msg.optJSONArray("items");
                if (items != null && items.length() > 0) {
                    wifiTransport.saveCachedDockAppsJson(items.toString());
                    JSONObject ev = new JSONObject();
                    ev.put("event", "apps");
                    ev.put("items", items);
                    pushEventToJs(ev);
                }

            } else if ("bg_killed".equals(type)) {
                int count = msg.optInt("count", 0);
                performHaptic("success");
                JSONObject ev = new JSONObject();
                ev.put("event", "bg_killed");
                ev.put("count", count);
                pushEventToJs(ev);
            }
        } catch (Exception ignored) {}
    }

    // --- RemoteTransport.Listener ---

    @Override
    public void onWifiConnectionState(String state, String errorMessage) {
        if (!"wifi".equals(activeTransport)) return;
        this.connectionState = state;
        if (!"connected".equals(state)) {
            this.lastRttMs = -1;
        }
        pushConnectionEvent(errorMessage);
    }

    @Override
    public void onWifiJsonMessage(JSONObject msg) {
        if (!"wifi".equals(activeTransport)) return;
        handleProtocolMessage(msg);
    }

    @Override
    public void onWifiDiscovered(JSONObject device) {
        try {
            JSONObject ev = new JSONObject();
            ev.put("event", "wifi_found");
            ev.put("device", device);
            pushEventToJs(ev);
        } catch (Exception ignored) {}
    }

    @Override
    public void onWifiScanState(boolean scanning) {
        try {
            JSONObject ev = new JSONObject();
            ev.put("event", "wifi_scan");
            ev.put("scanning", scanning);
            pushEventToJs(ev);
        } catch (Exception ignored) {}
    }

    // --- BluetoothTransport.Listener ---

    @Override
    public void onBleConnectionState(String state, String name, String address, String mode, String error) {
        if (!"bluetooth".equals(activeTransport)) return;
        this.connectionState = state;
        this.bleMode = mode != null ? mode : "ble";
        if (name != null && !name.isEmpty()) this.deviceName = name;
        if (!"connected".equals(state)) {
            this.lastRttMs = -1;
        }
        pushConnectionEvent(error);
    }

    @Override
    public void onBleJsonMessage(JSONObject msg) {
        if (!"bluetooth".equals(activeTransport)) return;
        handleProtocolMessage(msg);
    }

    @Override
    public void onBleDeviceFound(JSONObject device) {
        try {
            JSONObject ev = new JSONObject();
            ev.put("event", "ble_found");
            ev.put("device", device);
            pushEventToJs(ev);
        } catch (Exception ignored) {}
    }

    @Override
    public void onBleStatusChanged(String status, boolean scanning, boolean hasPermission) {
        pushBleStatus(status, scanning, hasPermission);
    }

    // --- Push Events to WebView ---

    private void pushConnectionEvent(String error) {
        try {
            JSONObject ev = new JSONObject();
            ev.put("event", "connection");
            ev.put("state", connectionState);
            ev.put("transport", activeTransport);
            ev.put("bleMode", bleMode);
            ev.put("host", wifiTransport.getHost());
            ev.put("tcpPort", wifiTransport.getTcpPort());
            ev.put("httpPort", wifiTransport.getHttpPort());
            ev.put("bleAddress", bleTransport.getConnectedAddress() != null ? bleTransport.getConnectedAddress() : "");
            ev.put("device", deviceName);
            ev.put("rtt", lastRttMs);
            if (error != null) ev.put("error", error);
            pushEventToJs(ev);
        } catch (Exception ignored) {}
    }

    private void pushBleStatus(String status, boolean scanning, boolean hasPermission) {
        try {
            JSONObject ev = new JSONObject();
            ev.put("event", "ble_status");
            ev.put("status", status);
            ev.put("scanning", scanning);
            ev.put("hasPermission", hasPermission);
            pushEventToJs(ev);
        } catch (Exception ignored) {}
    }

    private void pushEventToJs(JSONObject ev) {
        if (!pageLoaded || webView == null) return;
        final String js = "window.onRemoteEvent && window.onRemoteEvent(" + ev.toString() + ");";
        mainHandler.post(() -> {
            if (webView != null) {
                webView.evaluateJavascript(js, null);
            }
        });
    }

    // --- Tactile Haptics Engine ---

    private void performHaptic(String style) {
        try {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null || !v.hasVibrator()) return;
            String s = style == null ? "light" : style;
            if (Build.VERSION.SDK_INT >= 26) {
                switch (s) {
                    case "selection":
                        v.vibrate(VibrationEffect.createOneShot(8, 90));
                        break;
                    case "medium":
                        v.vibrate(VibrationEffect.createOneShot(18, 175));
                        break;
                    case "heavy":
                        v.vibrate(VibrationEffect.createOneShot(28, 240));
                        break;
                    case "rigid":
                        v.vibrate(VibrationEffect.createOneShot(12, 220));
                        break;
                    case "success":
                        v.vibrate(VibrationEffect.createWaveform(new long[]{0, 14, 48, 22}, new int[]{0, 150, 0, 220}, -1));
                        break;
                    case "light":
                    default:
                        v.vibrate(VibrationEffect.createOneShot(12, 130));
                        break;
                }
            } else {
                long ms = "heavy".equals(s) ? 26 : ("medium".equals(s) ? 18 : 11);
                v.vibrate(ms);
            }
        } catch (Exception ignored) {}
    }

    // --- @JavascriptInterface Bridge ---

    public class RemoteBridge {

        @JavascriptInterface
        public void sendKey(String key, String action) {
            sendKeyInternal(key, action);
        }

        @JavascriptInterface
        public void sendText(String text) {
            if (text == null || text.isEmpty()) return;
            try {
                JSONObject p = new JSONObject();
                p.put("t", "text");
                p.put("v", text);
                sendPayload(p);
            } catch (Exception ignored) {}
        }

        @JavascriptInterface
        public void setVolume(int vol) {
            int clamped = Math.max(0, Math.min(maxVolume, vol));
            volume = clamped;
            isMuted = clamped == 0;
            try {
                JSONObject p = new JSONObject();
                p.put("t", "vol");
                p.put("v", clamped);
                sendPayload(p);
            } catch (Exception ignored) {}
        }

        @JavascriptInterface
        public void sendPower(String action) {
            try {
                JSONObject p = new JSONObject();
                p.put("t", "power");
                p.put("a", (action == null || action.isEmpty()) ? "toggle" : action);
                sendPayload(p);
            } catch (Exception ignored) {}
        }

        @JavascriptInterface
        public void launchApp(String id) {
            if (id == null || id.isEmpty()) return;
            try {
                JSONObject p = new JSONObject();
                p.put("t", "app");
                p.put("id", id);
                sendPayload(p);
            } catch (Exception ignored) {}
        }

        @JavascriptInterface
        public void setResolution(String preset) {
            if (preset == null || preset.isEmpty()) return;
            try {
                JSONObject p = new JSONObject();
                p.put("t", "res");
                p.put("p", preset);
                sendPayload(p);
            } catch (Exception ignored) {}
        }

        @JavascriptInterface
        public void resetKeystone() {
            try {
                JSONObject p = new JSONObject();
                p.put("t", "keystone");
                p.put("a", "reset");
                sendPayload(p);
            } catch (Exception ignored) {}
        }

        @JavascriptInterface
        public void requestApps() {
            try {
                JSONObject p = new JSONObject();
                p.put("t", "apps");
                sendPayload(p);
            } catch (Exception ignored) {}
            if ("wifi".equals(activeTransport)) {
                wifiTransport.fetchDockAppsHttp();
            }
        }

        @JavascriptInterface
        public void killBackgroundApps() {
            try {
                JSONObject p = new JSONObject();
                p.put("t", "kill_bg");
                sendPayload(p);
            } catch (Exception ignored) {}
        }

        @JavascriptInterface
        public void connectWifi(String host) {
            bleTransport.disconnect();
            activeTransport = "wifi";
            wifiTransport.savePreferredTransport("wifi");
            wifiTransport.connect(host, RemoteTransport.DEFAULT_TCP_PORT, RemoteTransport.DEFAULT_HTTP_PORT);
        }

        @JavascriptInterface
        public void reconnect() {
            if ("bluetooth".equals(activeTransport)) {
                String addr = bleTransport.getConnectedAddress();
                if (addr == null) addr = wifiTransport.getSavedBleAddress();
                if (addr != null && !addr.isEmpty()) {
                    bleTransport.connect(addr);
                } else {
                    bleTransport.startScan();
                }
            } else {
                wifiTransport.reconnectNow();
            }
        }

        @JavascriptInterface
        public void startWifiDiscovery() {
            wifiTransport.startDiscovery();
        }

        @JavascriptInterface
        public void stopWifiDiscovery() {
            wifiTransport.stopDiscovery();
        }

        @JavascriptInterface
        public void startBluetoothScan() {
            if (!bleTransport.hasPermissions()) {
                mainHandler.post(MainActivity.this::requestBtPermissionsInternal);
                return;
            }
            bleTransport.startScan();
        }

        @JavascriptInterface
        public void stopBluetoothScan() {
            bleTransport.stopScan();
        }

        @JavascriptInterface
        public void connectBluetooth(String address) {
            if (address == null || address.trim().isEmpty()) return;
            if (!bleTransport.hasPermissions()) {
                mainHandler.post(MainActivity.this::requestBtPermissionsInternal);
                return;
            }
            wifiTransport.disconnect();
            activeTransport = "bluetooth";
            wifiTransport.savePreferredTransport("bluetooth");
            wifiTransport.saveBleAddress(address.trim());
            bleTransport.connect(address.trim());
        }

        @JavascriptInterface
        public void requestBluetoothPermissions() {
            mainHandler.post(MainActivity.this::requestBtPermissionsInternal);
        }

        @JavascriptInterface
        public void haptic(String style) {
            performHaptic(style);
        }

        @JavascriptInterface
        public void checkForUpdate() {
            runOtaCheck();
        }

        @JavascriptInterface
        public void installUpdate(String apkUrl) {
            if (otaUpdater == null) return;
            mainHandler.post(() -> otaUpdater.downloadAndInstall(
                    MainActivity.this,
                    apkUrl,
                    (stage, percent, message) -> pushOtaInstallEvent(stage, percent, message)
            ));
        }

        @JavascriptInterface
        public void openExternalUrl(String url) {
            if (url == null || url.trim().isEmpty()) return;
            mainHandler.post(() -> {
                try {
                    Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url.trim()));
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                } catch (Exception ignored) {}
            });
        }

        @JavascriptInterface
        public String getInitialState() {
            try {
                JSONObject s = new JSONObject();
                s.put("transport", activeTransport);
                s.put("bleMode", bleMode);
                s.put("connection", connectionState);
                s.put("host", wifiTransport.getHost());
                s.put("tcpPort", wifiTransport.getTcpPort());
                s.put("httpPort", wifiTransport.getHttpPort());
                s.put("bleAddress", bleTransport.getConnectedAddress() != null ? bleTransport.getConnectedAddress() : "");
                s.put("device", deviceName);
                s.put("awake", isAwake);
                s.put("vol", volume);
                s.put("maxVol", maxVolume);
                s.put("muted", isMuted);
                s.put("res", resolution);
                s.put("daemon", isDaemonActive);
                s.put("rtt", lastRttMs);
                s.put("hasBtPermission", bleTransport.hasPermissions());
                s.put("bleStatus", bleTransport.getStatusText());
                s.put("dockApps", new JSONArray(wifiTransport.getCachedDockAppsJson()));
                s.put("appVersion", otaUpdater != null ? otaUpdater.getCurrentVersionName() : "1.0.6");
                s.put("appBuild", otaUpdater != null ? otaUpdater.getCurrentVersionCode() : 6);
                s.put("fallbackApkUrl", OtaUpdater.FALLBACK_APK_URL);
                return s.toString();
            } catch (Exception e) {
                return "{}";
            }
        }
    }
}
