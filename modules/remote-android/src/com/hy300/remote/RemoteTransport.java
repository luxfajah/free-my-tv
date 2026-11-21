package com.hy300.remote;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Persistent low-latency Wi-Fi TCP NDJSON transport (:8091, TCP_NODELAY)
 * with pipelined send queue, 5s ping/pong RTT heartbeat, exponential-backoff reconnect,
 * HTTP /api/apps fallback, and dual NsdManager (_hy300remote._tcp.) + UDP (:8092) discovery.
 */
public class RemoteTransport {
    public static final int DEFAULT_TCP_PORT = 8091;
    public static final int DEFAULT_HTTP_PORT = 8090;
    public static final int UDP_DISCOVERY_PORT = 8092;
    public static final String DEFAULT_HOST = "192.168.1.7";
    private static final String NSD_SERVICE_TYPE = "_hy300remote._tcp.";

    private static final String PREFS_NAME = "hy300_remote_prefs";
    private static final String KEY_HOST = "last_host";
    private static final String KEY_TRANSPORT = "preferred_transport";
    private static final String KEY_BLE_ADDR = "last_ble_address";
    private static final String KEY_DOCK_APPS = "dock_apps_json";

    public interface Listener {
        void onWifiConnectionState(String state, String errorMessage);
        void onWifiJsonMessage(JSONObject msg);
        void onWifiDiscovered(JSONObject device);
        void onWifiScanState(boolean scanning);
    }

    private final Context appContext;
    private final SharedPreferences prefs;
    private final Listener listener;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService ioPool = Executors.newCachedThreadPool();
    private final LinkedBlockingQueue<String> sendQueue = new LinkedBlockingQueue<>(256);
    private final AtomicInteger connectionGen = new AtomicInteger(0);

    private volatile String host;
    private volatile int tcpPort = DEFAULT_TCP_PORT;
    private volatile int httpPort = DEFAULT_HTTP_PORT;
    private volatile boolean active = false;
    private volatile boolean connected = false;
    private volatile boolean inForeground = true;
    private volatile int reconnectAttempt = 0;

    private volatile Socket socket;
    private volatile ScheduledFuture<?> heartbeatFuture;
    private volatile ScheduledFuture<?> reconnectFuture;

    // Discovery state
    private NsdManager nsdManager;
    private NsdManager.DiscoveryListener nsdDiscoveryListener;
    private WifiManager.MulticastLock multicastLock;
    private volatile boolean isScanningWifi = false;
    private volatile ScheduledFuture<?> scanStopFuture;

    public RemoteTransport(Context context, Listener listener) {
        this.appContext = context.getApplicationContext();
        this.prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.listener = listener;
        this.host = getSavedHost();
    }

    // --- SharedPreferences Persistence ---

    public String getSavedHost() {
        String h = prefs.getString(KEY_HOST, DEFAULT_HOST);
        return (h == null || h.trim().isEmpty()) ? DEFAULT_HOST : h.trim();
    }

    public void saveHost(String newHost) {
        if (newHost == null) return;
        String trimmed = newHost.trim();
        if (!trimmed.isEmpty()) {
            this.host = trimmed;
            prefs.edit().putString(KEY_HOST, trimmed).apply();
        }
    }

    public String getPreferredTransport() {
        String t = prefs.getString(KEY_TRANSPORT, "wifi");
        return "bluetooth".equals(t) ? "bluetooth" : "wifi";
    }

    public void savePreferredTransport(String transport) {
        prefs.edit().putString(KEY_TRANSPORT, "bluetooth".equals(transport) ? "bluetooth" : "wifi").apply();
    }

    public String getSavedBleAddress() {
        return prefs.getString(KEY_BLE_ADDR, null);
    }

    public void saveBleAddress(String address) {
        if (address != null && !address.trim().isEmpty()) {
            prefs.edit().putString(KEY_BLE_ADDR, address.trim()).apply();
        }
    }

    public String getCachedDockAppsJson() {
        return prefs.getString(KEY_DOCK_APPS, "[]");
    }

    public void saveCachedDockAppsJson(String jsonArrayStr) {
        if (jsonArrayStr != null && jsonArrayStr.startsWith("[")) {
            prefs.edit().putString(KEY_DOCK_APPS, jsonArrayStr).apply();
        }
    }

    public String getHost() {
        return host;
    }

    public int getTcpPort() {
        return tcpPort;
    }

    public int getHttpPort() {
        return httpPort;
    }

    public boolean isConnected() {
        return connected;
    }

    // --- Connection Lifecycle ---

    public synchronized void connect(String targetHost, int targetTcpPort, int targetHttpPort) {
        if (targetHost != null && !targetHost.trim().isEmpty()) {
            saveHost(targetHost);
        }
        this.tcpPort = targetTcpPort > 0 ? targetTcpPort : DEFAULT_TCP_PORT;
        this.httpPort = targetHttpPort > 0 ? targetHttpPort : DEFAULT_HTTP_PORT;
        this.active = true;
        this.reconnectAttempt = 0;
        openSocket();
    }

    public synchronized void reconnectNow() {
        this.active = true;
        this.reconnectAttempt = 0;
        openSocket();
    }

    public synchronized void disconnect() {
        this.active = false;
        teardownSocket();
        listener.onWifiConnectionState("disconnected", null);
    }

    public void setForeground(boolean fg) {
        this.inForeground = fg;
        if (!active) return;
        if (fg) {
            if (!connected) {
                reconnectNow();
            } else {
                sendPing();
                sendRawJson("{\"t\":\"state\"}");
                startHeartbeat();
            }
        } else {
            stopHeartbeat();
        }
    }

    private synchronized void teardownSocket() {
        connectionGen.incrementAndGet();
        connected = false;
        stopHeartbeat();
        if (reconnectFuture != null) {
            reconnectFuture.cancel(false);
            reconnectFuture = null;
        }
        sendQueue.clear();
        Socket s = socket;
        socket = null;
        if (s != null) {
            try { s.close(); } catch (Exception ignored) {}
        }
    }

    private synchronized void openSocket() {
        teardownSocket();
        if (!active) return;

        final int gen = connectionGen.incrementAndGet();
        final String targetHost = this.host;
        final int targetPort = this.tcpPort;
        listener.onWifiConnectionState(reconnectAttempt > 0 ? "reconnecting" : "connecting", null);

        ioPool.execute(() -> {
            Socket s = null;
            try {
                s = new Socket();
                s.setTcpNoDelay(true);
                s.setKeepAlive(true);
                s.connect(new InetSocketAddress(targetHost, targetPort), 4000);

                if (gen != connectionGen.get() || !active) {
                    try { s.close(); } catch (Exception ignored) {}
                    return;
                }

                socket = s;
                connected = true;
                reconnectAttempt = 0;
                listener.onWifiConnectionState("connected", null);

                // Start dedicated pipelined writer thread
                final Socket activeSock = s;
                ioPool.execute(() -> writerLoop(activeSock, gen));

                // Request initial state, dock apps, and RTT ping
                sendRawJson("{\"t\":\"state\"}");
                sendRawJson("{\"t\":\"apps\"}");
                sendPing();
                startHeartbeat();
                fetchDockAppsHttp();

                // Blocking reader loop for NDJSON lines
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(activeSock.getInputStream(), StandardCharsets.UTF_8), 8192);
                String line;
                while (gen == connectionGen.get() && (line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty()) continue;
                    try {
                        JSONObject json = new JSONObject(trimmed);
                        listener.onWifiJsonMessage(json);
                    } catch (Exception ignored) {}
                }

                // Stream closed cleanly by peer
                if (gen == connectionGen.get()) {
                    handleConnectionDrop(gen, "Conexão encerrada pelo projetor");
                }
            } catch (Exception e) {
                if (s != null) {
                    try { s.close(); } catch (Exception ignored) {}
                }
                if (gen == connectionGen.get()) {
                    handleConnectionDrop(gen, e.getMessage());
                }
            }
        });
    }

    private void writerLoop(Socket activeSock, int gen) {
        try {
            BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(activeSock.getOutputStream(), StandardCharsets.UTF_8), 4096);
            while (gen == connectionGen.get() && active && connected) {
                String payload = sendQueue.poll(1, TimeUnit.SECONDS);
                if (payload == null) continue;
                writer.write(payload);
                writer.write('\n');
                // Drain any queued commands in one flush for ultra-low-latency burst pipelining
                String next;
                while ((next = sendQueue.poll()) != null) {
                    writer.write(next);
                    writer.write('\n');
                }
                writer.flush();
            }
        } catch (Exception e) {
            if (gen == connectionGen.get()) {
                handleConnectionDrop(gen, e.getMessage());
            }
        }
    }

    private synchronized void handleConnectionDrop(int gen, String error) {
        if (gen != connectionGen.get()) return;
        connected = false;
        stopHeartbeat();
        Socket s = socket;
        socket = null;
        if (s != null) {
            try { s.close(); } catch (Exception ignored) {}
        }
        if (!active) {
            listener.onWifiConnectionState("disconnected", null);
            return;
        }
        listener.onWifiConnectionState("disconnected", error);
        scheduleReconnect();
    }

    private synchronized void scheduleReconnect() {
        if (!active || !inForeground) return;
        if (reconnectFuture != null) {
            reconnectFuture.cancel(false);
        }
        int step = Math.min(reconnectAttempt, 4);
        long delayMs = Math.min(500L * (1L << step), 8000L);
        reconnectAttempt++;
        reconnectFuture = scheduler.schedule(() -> {
            if (active && inForeground && !connected) {
                openSocket();
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    private synchronized void startHeartbeat() {
        stopHeartbeat();
        heartbeatFuture = scheduler.scheduleAtFixedRate(() -> {
            if (active && connected && inForeground) {
                sendPing();
            }
        }, 5, 5, TimeUnit.SECONDS);
    }

    private synchronized void stopHeartbeat() {
        if (heartbeatFuture != null) {
            heartbeatFuture.cancel(false);
            heartbeatFuture = null;
        }
    }

    public void sendPing() {
        try {
            JSONObject ping = new JSONObject();
            ping.put("t", "ping");
            ping.put("ts", System.currentTimeMillis());
            sendRawJson(ping.toString());
        } catch (Exception ignored) {}
    }

    public boolean sendRawJson(String json) {
        if (json == null || json.isEmpty()) return false;
        if (!connected) {
            if (active && inForeground && reconnectFuture == null) {
                reconnectNow();
            }
            return false;
        }
        if (!sendQueue.offer(json)) {
            sendQueue.poll();
            sendQueue.offer(json);
        }
        return true;
    }

    /**
     * Supplements TCP `{"t":"apps"}` by querying http://<host>:8090/api/apps on Wi-Fi.
     */
    public void fetchDockAppsHttp() {
        final String targetHost = this.host;
        final int targetHttp = this.httpPort;
        ioPool.execute(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL("http://" + targetHost + ":" + targetHttp + "/api/apps");
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(3500);
                conn.setReadTimeout(3500);
                conn.setRequestMethod("GET");
                int code = conn.getResponseCode();
                if (code >= 200 && code < 300) {
                    BufferedReader br = new BufferedReader(
                            new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) {
                        sb.append(line);
                    }
                    String body = sb.toString().trim();
                    JSONArray items = null;
                    if (body.startsWith("{")) {
                        JSONObject obj = new JSONObject(body);
                        items = obj.optJSONArray("items");
                    } else if (body.startsWith("[")) {
                        items = new JSONArray(body);
                    }
                    if (items != null && items.length() > 0) {
                        JSONObject synthetic = new JSONObject();
                        synthetic.put("t", "apps");
                        synthetic.put("items", items);
                        listener.onWifiJsonMessage(synthetic);
                    }
                }
            } catch (Exception ignored) {
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    // --- Dual Discovery: NsdManager (_hy300remote._tcp.) + UDP Broadcast (:8092) ---

    public synchronized void startDiscovery() {
        stopDiscovery();
        isScanningWifi = true;
        listener.onWifiScanState(true);

        // 1. NsdManager Bonjour/mDNS discovery
        try {
            WifiManager wm = (WifiManager) appContext.getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                multicastLock = wm.createMulticastLock("hy300_nsd_lock");
                multicastLock.setReferenceCounted(false);
                multicastLock.acquire();
            }
            nsdManager = (NsdManager) appContext.getSystemService(Context.NSD_SERVICE);
            if (nsdManager != null) {
                nsdDiscoveryListener = new NsdManager.DiscoveryListener() {
                    @Override public void onDiscoveryStarted(String regType) {}
                    @Override public void onServiceLost(NsdServiceInfo service) {}
                    @Override public void onDiscoveryStopped(String serviceType) {}
                    @Override public void onStartDiscoveryFailed(String serviceType, int errorCode) {}
                    @Override public void onStopDiscoveryFailed(String serviceType, int errorCode) {}

                    @Override
                    public void onServiceFound(NsdServiceInfo service) {
                        if (service == null || nsdManager == null) return;
                        try {
                            nsdManager.resolveService(service, new NsdManager.ResolveListener() {
                                @Override public void onResolveFailed(NsdServiceInfo info, int errorCode) {}

                                @Override
                                public void onServiceResolved(NsdServiceInfo info) {
                                    InetAddress addr = info.getHost();
                                    if (addr == null) return;
                                    String ip = addr.getHostAddress();
                                    if (ip == null || ip.contains(":")) return;
                                    int port = info.getPort() > 0 ? info.getPort() : DEFAULT_TCP_PORT;
                                    String name = info.getServiceName();
                                    if (name == null || name.isEmpty()) name = "HY300Pro";
                                    emitDiscovered(name, ip, port, DEFAULT_HTTP_PORT, "Bonjour");
                                }
                            });
                        } catch (Exception ignored) {}
                    }
                };
                nsdManager.discoverServices(NSD_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, nsdDiscoveryListener);
            }
        } catch (Exception ignored) {}

        // 2. UDP Broadcast Probe ("HY300_DISCOVER" -> 255.255.255.255:8092)
        ioPool.execute(this::runUdpBroadcastProbe);

        // Auto-stop after 8 seconds
        scanStopFuture = scheduler.schedule(this::stopDiscovery, 8, TimeUnit.SECONDS);
    }

    public synchronized void stopDiscovery() {
        if (scanStopFuture != null) {
            scanStopFuture.cancel(false);
            scanStopFuture = null;
        }
        if (nsdManager != null && nsdDiscoveryListener != null) {
            try {
                nsdManager.stopServiceDiscovery(nsdDiscoveryListener);
            } catch (Exception ignored) {}
            nsdDiscoveryListener = null;
        }
        if (multicastLock != null) {
            try {
                if (multicastLock.isHeld()) multicastLock.release();
            } catch (Exception ignored) {}
            multicastLock = null;
        }
        if (isScanningWifi) {
            isScanningWifi = false;
            listener.onWifiScanState(false);
        }
    }

    private void runUdpBroadcastProbe() {
        DatagramSocket sock = null;
        try {
            sock = new DatagramSocket();
            sock.setBroadcast(true);
            sock.setSoTimeout(1200);

            byte[] payload = "HY300_DISCOVER".getBytes(StandardCharsets.UTF_8);
            InetAddress bcast = InetAddress.getByName("255.255.255.255");
            DatagramPacket out = new DatagramPacket(payload, payload.length, bcast, UDP_DISCOVERY_PORT);

            // Send 2 quick probes for Wi-Fi broadcast reliability
            for (int i = 0; i < 2; i++) {
                sock.send(out);
                try { Thread.sleep(120); } catch (InterruptedException ignored) {}
            }

            byte[] buf = new byte[1024];
            long deadline = System.currentTimeMillis() + 2000;
            while (System.currentTimeMillis() < deadline) {
                DatagramPacket in = new DatagramPacket(buf, buf.length);
                try {
                    sock.receive(in);
                } catch (Exception timeout) {
                    break;
                }
                String text = new String(in.getData(), 0, in.getLength(), StandardCharsets.UTF_8).trim();
                try {
                    JSONObject json = new JSONObject(text);
                    String ip = json.optString("ip", "").trim();
                    if (ip.isEmpty() && in.getAddress() != null) {
                        ip = in.getAddress().getHostAddress();
                    }
                    if (ip == null || ip.isEmpty()) continue;
                    String device = json.optString("device", "HY300Pro");
                    int tcp = json.optInt("tcp", DEFAULT_TCP_PORT);
                    int http = json.optInt("http", DEFAULT_HTTP_PORT);
                    emitDiscovered(device, ip, tcp, http, "UDP");
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {
        } finally {
            if (sock != null) sock.close();
        }
    }

    private void emitDiscovered(String name, String ip, int tcp, int http, String source) {
        try {
            JSONObject obj = new JSONObject();
            obj.put("name", name);
            obj.put("host", ip);
            obj.put("tcpPort", tcp);
            obj.put("httpPort", http);
            obj.put("source", source);
            listener.onWifiDiscovered(obj);
        } catch (Exception ignored) {}
    }

    public void shutdown() {
        stopDiscovery();
        disconnect();
        scheduler.shutdownNow();
        ioPool.shutdownNow();
    }
}
