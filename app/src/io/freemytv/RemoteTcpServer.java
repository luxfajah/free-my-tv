package io.freemytv;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.PowerManager;
import android.util.DisplayMetrics;
import android.view.KeyEvent;
import android.view.WindowManager;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Native low-latency remote control server for iOS (Network.framework):
 * - Port 8091: persistent TCP NDJSON protocol with TCP_NODELAY, heartbeat, and live state push
 * - mDNS / Bonjour (_hy300remote._tcp.) via Android NsdManager
 * - Port 8092: UDP broadcast discovery fallback ("HY300_DISCOVER")
 */
final class RemoteTcpServer {
    static final int TCP_PORT = 8091;
    static final int UDP_PORT = 8092;
    static final String MDNS_TYPE = "_hy300remote._tcp.";
    private static final String DISCOVER_TOKEN = "HY300_DISCOVER";
    private static final int CLIENT_IDLE_TIMEOUT_MS = 25_000;

    private static RemoteTcpServer instance;

    private volatile Activity activity;
    private final Context appContext;
    private ServerSocket tcpSocket;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final CopyOnWriteArrayList<ClientSession> clients = new CopyOnWriteArrayList<>();
    private final ExecutorService broadcastExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "RemoteTCP-Broadcast");
        t.setDaemon(true);
        return t;
    });

    private DatagramSocket udpSocket;
    private NsdManager nsdManager;
    private NsdManager.RegistrationListener nsdListener;
    private BroadcastReceiver stateReceiver;
    private BroadcastReceiver packageReceiver;

    interface ReplySink {
        void send(String jsonLine);
    }

    private static final class ClientSession {
        final Socket socket;
        final OutputStream out;
        final Object writeLock = new Object();

        ClientSession(Socket socket) throws IOException {
            this.socket = socket;
            this.out = socket.getOutputStream();
        }

        boolean sendLine(String jsonLine) {
            byte[] bytes = (jsonLine + "\n").getBytes(StandardCharsets.UTF_8);
            synchronized (writeLock) {
                try {
                    out.write(bytes);
                    out.flush();
                    return true;
                } catch (IOException e) {
                    return false;
                }
            }
        }

        void close() {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    private RemoteTcpServer(Activity activity) {
        this.activity = activity;
        this.appContext = activity.getApplicationContext();

        // Only bind TCP 8091 in-app as fallback if LMK-immune SystemRemoteDaemon doesn't already own it
        try {
            ServerSocket ss = new ServerSocket();
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(TCP_PORT), 50);
            this.tcpSocket = ss;
            Thread acceptThread = new Thread(this::tcpAcceptLoop, "RemoteTCP-Accept");
            acceptThread.setDaemon(true);
            acceptThread.start();
            AppLog.w("RemoteTcpServer fallback TCP listening on :" + TCP_PORT);
        } catch (IOException e) {
            AppLog.w("RemoteTcpServer: TCP " + TCP_PORT + " already served by LMK-immune SystemRemoteDaemon");
        }

        Thread udpThread = new Thread(this::udpDiscoverLoop, "RemoteUDP-Discover");
        udpThread.setDaemon(true);
        udpThread.start();

        registerNsd();
        registerStateReceiver();
        registerPackageReceiver();
        RemoteBleServer.start(appContext);
    }

    static synchronized RemoteTcpServer start(Activity activity) {
        if (instance != null && instance.running.get()) {
            instance.activity = activity;
            RemoteBleServer.start(instance.appContext);
            return instance;
        }
        try {
            instance = new RemoteTcpServer(activity);
        } catch (Exception e) {
            AppLog.w("RemoteTcpServer start error: " + e.getMessage());
        }
        return instance;
    }

    static RemoteTcpServer get() {
        return instance;
    }

    private void tcpAcceptLoop() {
        while (running.get() && !tcpSocket.isClosed()) {
            try {
                Socket socket = tcpSocket.accept();
                socket.setTcpNoDelay(true);
                socket.setKeepAlive(true);
                socket.setSoTimeout(CLIENT_IDLE_TIMEOUT_MS);
                Thread t = new Thread(() -> handleClient(socket), "RemoteTCP-Client");
                t.setDaemon(true);
                t.start();
            } catch (Exception e) {
                if (!running.get()) break;
            }
        }
    }

    private void handleClient(Socket socket) {
        ClientSession session = null;
        try {
            session = new ClientSession(socket);
            clients.add(session);
            // Push initial state and dock apps list immediately upon connect
            session.sendLine(buildStateJson(appContext).toString());
            session.sendLine(HomeApps.getDockAppsForRemote(appContext).toString());

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while (running.get() && (line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                handleCommand(line, session::sendLine);
            }
        } catch (Exception ignored) {
        } finally {
            if (session != null) {
                clients.remove(session);
                session.close();
            } else {
                try { socket.close(); } catch (Exception ignored) {}
            }
            // Safety: release any held keys on client disconnect
            RemoteDaemonBridge.releaseAll();
        }
    }

    void handleCommand(String raw, ReplySink replySink) {
        try {
            JSONObject msg = new JSONObject(raw);
            String type = msg.optString("t", "");
            switch (type) {
                case "ping": {
                    JSONObject pong = new JSONObject();
                    pong.put("t", "pong");
                    pong.put("ts", msg.optLong("ts", System.currentTimeMillis()));
                    if (replySink != null) replySink.send(pong.toString());
                    break;
                }
                case "state": {
                    if (replySink != null) replySink.send(buildStateJson(appContext).toString());
                    break;
                }
                case "apps": {
                    if (replySink != null) replySink.send(HomeApps.getDockAppsForRemote(appContext).toString());
                    break;
                }
                case "kill_bg": {
                    broadcastExecutor.execute(() -> {
                        int count = RemoteDaemonBridge.killBackgroundApps();
                        JSONObject res = new JSONObject();
                        try {
                            res.put("t", "bg_killed");
                            res.put("ok", count >= 0);
                            res.put("count", Math.max(0, count));
                        } catch (Exception ignored) {}
                        if (replySink != null) replySink.send(res.toString());
                    });
                    break;
                }
                case "key": {
                    int keyCode = msg.optInt("kc", 0);
                    if (keyCode == 0) {
                        keyCode = WebRemoteServer.mapKey(msg.optString("k", ""));
                    }
                    if (keyCode == 0) break;
                    String action = msg.optString("a", "tap").toLowerCase();
                    switch (action) {
                        case "down":
                            if (!RemoteDaemonBridge.sendKeyDown(keyCode)) {
                                WebRemoteServer.fallbackInAppKey(keyCode);
                            }
                            break;
                        case "up":
                            RemoteDaemonBridge.sendKeyUp(keyCode);
                            break;
                        default:
                            if (!RemoteDaemonBridge.sendKeyTap(keyCode)) {
                                WebRemoteServer.fallbackInAppKey(keyCode);
                            }
                            break;
                    }
                    break;
                }
                case "text": {
                    String text = msg.optString("v", "");
                    if (!text.isEmpty()) {
                        RemoteDaemonBridge.sendText(text);
                    }
                    break;
                }
                case "vol": {
                    int target = msg.optInt("v", -1);
                    if (target >= 0) {
                        AudioManager am = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
                        if (am != null) {
                            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                            int clamped = Math.max(0, Math.min(max, target));
                            am.setStreamVolume(AudioManager.STREAM_MUSIC, clamped, AudioManager.FLAG_SHOW_UI);
                            broadcastState();
                        }
                    }
                    break;
                }
                case "power": {
                    String action = msg.optString("a", "toggle").toLowerCase();
                    int kc;
                    if ("wake".equals(action) || "wakeup".equals(action)) kc = KeyEvent.KEYCODE_WAKEUP;
                    else if ("standby".equals(action) || "sleep".equals(action)) kc = KeyEvent.KEYCODE_SLEEP;
                    else kc = KeyEvent.KEYCODE_POWER;
                    RemoteDaemonBridge.sendKeyTap(kc);
                    break;
                }
                case "app": {
                    String id = msg.optString("id", "");
                    if (!id.isEmpty()) {
                        RemoteDaemonBridge.sendKeyTap(KeyEvent.KEYCODE_WAKEUP);
                        WebRemoteServer.launchApp(activity, id);
                    }
                    break;
                }
                case "res": {
                    String preset = msg.optString("p", "720p");
                    WebRemoteServer.applyResolution(preset);
                    scheduleDelayedStatePush(700);
                    break;
                }
                case "keystone": {
                    if ("reset".equalsIgnoreCase(msg.optString("a", "reset"))) {
                        WebRemoteServer.resetKeystone();
                    }
                    break;
                }
                case "open_url": {
                    String url = msg.optString("url", msg.optString("u", ""));
                    if (!url.isEmpty()) {
                        ProjectorHardware.openUrl(appContext, url);
                    }
                    break;
                }
                default:
                    break;
            }
        } catch (Exception ignored) {
        }
    }

    private void udpDiscoverLoop() {
        try {
            udpSocket = new DatagramSocket(null);
            udpSocket.setReuseAddress(true);
            udpSocket.setBroadcast(true);
            udpSocket.bind(new InetSocketAddress(UDP_PORT));
            byte[] buf = new byte[256];
            while (running.get() && !udpSocket.isClosed()) {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                udpSocket.receive(packet);
                String payload = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8).trim();
                if (payload.contains(DISCOVER_TOKEN)) {
                    JSONObject reply = new JSONObject();
                    reply.put("device", deviceName());
                    reply.put("ip", WebRemoteServer.getLocalIpAddress(appContext));
                    reply.put("tcp", TCP_PORT);
                    reply.put("http", WebRemoteServer.PORT);
                    reply.put("ver", 2);
                    byte[] respBytes = reply.toString().getBytes(StandardCharsets.UTF_8);
                    DatagramPacket resp = new DatagramPacket(respBytes, respBytes.length, packet.getAddress(), packet.getPort());
                    udpSocket.send(resp);
                }
            }
        } catch (Exception e) {
            AppLog.w("RemoteUDP discover loop ended: " + e.getMessage());
        }
    }

    private void registerNsd() {
        try {
            nsdManager = (NsdManager) appContext.getSystemService(Context.NSD_SERVICE);
            if (nsdManager == null) return;
            NsdServiceInfo info = new NsdServiceInfo();
            info.setServiceName(deviceName());
            info.setServiceType(MDNS_TYPE);
            info.setPort(TCP_PORT);
            info.setAttribute("ver", "2");
            info.setAttribute("http", String.valueOf(WebRemoteServer.PORT));
            info.setAttribute("model", deviceName());

            nsdListener = new NsdManager.RegistrationListener() {
                @Override public void onServiceRegistered(NsdServiceInfo serviceInfo) {
                    AppLog.w("NSD registered: " + serviceInfo.getServiceName() + " " + MDNS_TYPE + " port " + TCP_PORT);
                }
                @Override public void onRegistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
                    AppLog.w("NSD registration failed: " + errorCode);
                }
                @Override public void onServiceUnregistered(NsdServiceInfo serviceInfo) {}
                @Override public void onUnregistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {}
            };
            nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, nsdListener);
        } catch (Exception e) {
            AppLog.w("NSD register error: " + e.getMessage());
        }
    }

    private void registerStateReceiver() {
        try {
            IntentFilter filter = new IntentFilter();
            filter.addAction("android.media.VOLUME_CHANGED_ACTION");
            filter.addAction("android.media.STREAM_MUTE_CHANGED_ACTION");
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            stateReceiver = new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    broadcastState();
                }
            };
            appContext.registerReceiver(stateReceiver, filter);
        } catch (Exception e) {
            AppLog.w("StateReceiver register error: " + e.getMessage());
        }
    }

    private void registerPackageReceiver() {
        try {
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_PACKAGE_ADDED);
            filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
            filter.addAction(Intent.ACTION_PACKAGE_REPLACED);
            filter.addDataScheme("package");
            packageReceiver = new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    if (intent != null && intent.getData() != null) {
                        HomeApps.invalidateRemoteIconCache(intent.getData().getSchemeSpecificPart());
                    }
                    RemoteDaemonBridge.syncCacheAsync(appContext);
                    broadcastApps();
                }
            };
            appContext.registerReceiver(packageReceiver, filter);
        } catch (Exception e) {
            AppLog.w("PackageReceiver register error: " + e.getMessage());
        }
    }

    static void notifyAppsChanged() {
        RemoteTcpServer srv = instance;
        if (srv != null) {
            RemoteDaemonBridge.syncCacheAsync(srv.appContext);
            srv.broadcastApps();
        }
    }

    void broadcastApps() {
        if (clients.isEmpty()) return;
        broadcastExecutor.execute(() -> {
            if (clients.isEmpty()) return;
            String line = HomeApps.getDockAppsForRemote(appContext).toString();
            for (ClientSession c : clients) {
                if (!c.sendLine(line)) {
                    clients.remove(c);
                    c.close();
                }
            }
        });
    }

    private void scheduleDelayedStatePush(long delayMs) {
        Thread t = new Thread(() -> {
            try { Thread.sleep(delayMs); } catch (InterruptedException ignored) {}
            broadcastState();
        }, "RemoteTCP-DelayedPush");
        t.setDaemon(true);
        t.start();
    }

    void broadcastState() {
        RemoteBleServer ble = RemoteBleServer.get();
        boolean hasBle = ble != null && ble.hasConnectedClients();
        if (clients.isEmpty() && !hasBle) return;
        broadcastExecutor.execute(() -> {
            RemoteBleServer b = RemoteBleServer.get();
            boolean bleActive = b != null && b.hasConnectedClients();
            if (clients.isEmpty() && !bleActive) return;
            String line = buildStateJson(appContext).toString();
            for (ClientSession c : clients) {
                if (!c.sendLine(line)) {
                    clients.remove(c);
                    c.close();
                }
            }
            if (bleActive) {
                b.broadcastState(line);
            }
        });
    }

    static JSONObject buildStateJson(Context context) {
        JSONObject obj = new JSONObject();
        try {
            obj.put("t", "state");
            obj.put("online", true);
            obj.put("device", deviceName());
            obj.put("ip", WebRemoteServer.getLocalIpAddress(context));
            obj.put("tcpPort", TCP_PORT);
            obj.put("httpPort", WebRemoteServer.PORT);

            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            obj.put("awake", pm == null || pm.isInteractive());

            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                obj.put("vol", am.getStreamVolume(AudioManager.STREAM_MUSIC));
                obj.put("maxVol", am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
                obj.put("muted", am.isStreamMute(AudioManager.STREAM_MUSIC));
            } else {
                obj.put("vol", 0);
                obj.put("maxVol", 20);
                obj.put("muted", false);
            }

            obj.put("res", getCurrentResolution(context));
            obj.put("daemon", RemoteDaemonBridge.isDaemonAlive());
        } catch (Exception ignored) {}
        return obj;
    }

    private static String getCurrentResolution(Context context) {
        try {
            WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            if (wm != null && wm.getDefaultDisplay() != null) {
                DisplayMetrics dm = new DisplayMetrics();
                wm.getDefaultDisplay().getRealMetrics(dm);
                if (dm.widthPixels > 0 && dm.heightPixels > 0) {
                    return dm.widthPixels + "x" + dm.heightPixels;
                }
            }
        } catch (Exception ignored) {}
        return "1920x1080";
    }

    private static String deviceName() {
        String model = Build.MODEL;
        return (model != null && !model.trim().isEmpty()) ? model.trim() : "HY300Pro";
    }
}
