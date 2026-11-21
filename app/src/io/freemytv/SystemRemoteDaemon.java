package io.freemytv;

import android.hardware.input.InputManager;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.SystemClock;
import android.util.Base64;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * LMK-Immune System-Wide Remote Control Daemon for HY300Pro.
 * Runs under user `shell` (uid 2000) via app_process — completely immune to Android's Low Memory Killer
 * even when heavy apps (Stremio, YouTube, Netflix) kill the home launcher process.
 *
 * Directly serves:
 * - 127.0.0.1:8099 : Loopback bridge for io.freemytv / BLE GATT / RFCOMM
 * - 0.0.0.0:8091   : Persistent TCP NDJSON remote protocol (TCP_NODELAY, <2ms direct InputManager injection)
 * - 0.0.0.0:8092   : UDP broadcast discovery ("HY300_DISCOVER")
 * - 0.0.0.0:8090   : Backwards-compatible HTTP API (/api/status, /api/apps, /api/app-icon/<pkg>, /api/key/*, ...)
 */
public final class SystemRemoteDaemon {
    public static final int PORT = 8099;
    public static final int HTTP_PORT = 8090;
    public static final int TCP_PORT = 8091;
    public static final int UDP_PORT = 8092;

    private static final int INJECT_INPUT_EVENT_MODE_ASYNC = 0;
    private static final long REPEAT_INITIAL_DELAY_MS = 350L;
    private static final long REPEAT_INTERVAL_MS = 65L;
    private static final long STUCK_KEY_WATCHDOG_MS = 10_000L;
    private static final int TCP_CLIENT_IDLE_TIMEOUT_MS = 25_000;
    private static final String DISCOVER_TOKEN = "HY300_DISCOVER";
    private static final int STREAM_MUSIC = 3;

    private static final File CACHE_DIR = new File("/data/local/tmp/freemytv_remote_cache");
    private static final File ICONS_DIR = new File(CACHE_DIR, "icons");
    private static final File DOCK_APPS_FILE = new File(CACHE_DIR, "dock_apps.json");
    private static final File REMOTE_HTML_FILE = new File(CACHE_DIR, "remote.html");

    private static InputManager inputManager;
    private static Method injectMethod;
    private static KeyCharacterMap virtualKeyMap;

    // Binder system service handles (zero-fork sub-ms state reads)
    private static volatile Object audioService;
    private static volatile Method audioGetVolMethod;
    private static volatile Method audioGetMaxVolMethod;
    private static volatile Method audioIsMuteMethod;
    private static volatile Method audioSetVolMethod;
    private static volatile Object powerService;
    private static volatile Method powerIsInteractiveMethod;

    // Cached state & dock data (survives io.freemytv LMK)
    private static volatile String cachedRes = null;
    private static volatile long cachedResAt = 0;
    private static volatile String cachedDockAppsJson = null;
    private static volatile String cachedRemoteHtml = null;
    private static final ConcurrentHashMap<String, byte[]> iconCache = new ConcurrentHashMap<>();

    private static final CopyOnWriteArrayList<TcpClient> tcpClients = new CopyOnWriteArrayList<>();

    private static final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "Daemon-KeyRepeat");
        t.setDaemon(true);
        return t;
    });

    private static final ExecutorService asyncExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "Daemon-Async");
        t.setDaemon(true);
        return t;
    });

    private static final ConcurrentHashMap<Integer, HeldKey> heldKeys = new ConcurrentHashMap<>();

    private static final class HeldKey {
        final int keyCode;
        final long downTime;
        final AtomicInteger repeatCount = new AtomicInteger(0);
        volatile ScheduledFuture<?> repeatTask;
        volatile ScheduledFuture<?> watchdogTask;

        HeldKey(int keyCode, long downTime) {
            this.keyCode = keyCode;
            this.downTime = downTime;
        }

        void cancelTasks() {
            ScheduledFuture<?> r = repeatTask;
            if (r != null) r.cancel(false);
            ScheduledFuture<?> w = watchdogTask;
            if (w != null) w.cancel(false);
        }
    }

    private static final class TcpClient {
        final Socket socket;
        final OutputStream out;
        final Object writeLock = new Object();

        TcpClient(Socket socket) throws IOException {
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

    public static void main(String[] args) {
        System.out.println("SystemRemoteDaemon (LMK-Immune) starting...");
        initInputManager();
        initBinderServices();
        loadDiskCache();
        startVolumeControllerHijacker();

        // Start LMK-immune network listeners directly inside shell daemon
        startDaemonThread(SystemRemoteDaemon::runTcpServer, "Daemon-TCP-8091");
        startDaemonThread(SystemRemoteDaemon::runUdpDiscovery, "Daemon-UDP-8092");
        startDaemonThread(SystemRemoteDaemon::runHttpServer, "Daemon-HTTP-8090");

        // Primary loopback bridge on 127.0.0.1:8099 (keeps main thread alive)
        runLoopbackServer();
    }

    private static void startDaemonThread(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.start();
    }

    private static void initInputManager() {
        try {
            Method getInstance = InputManager.class.getMethod("getInstance");
            inputManager = (InputManager) getInstance.invoke(null);
            injectMethod = InputManager.class.getMethod("injectInputEvent", InputEvent.class, int.class);
            System.out.println("InputManager reflection succeeded.");
        } catch (Throwable t) {
            System.out.println("InputManager reflection note: " + t.getMessage());
        }
        try {
            virtualKeyMap = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD);
        } catch (Throwable t) {
            System.out.println("KeyCharacterMap note: " + t.getMessage());
        }
    }

    private static void initBinderServices() {
        try {
            Class<?> smClass = Class.forName("android.os.ServiceManager");
            Method getService = smClass.getMethod("getService", String.class);

            IBinder audioBinder = (IBinder) getService.invoke(null, "audio");
            if (audioBinder != null) {
                Class<?> stub = Class.forName("android.media.IAudioService$Stub");
                Object svc = stub.getMethod("asInterface", IBinder.class).invoke(null, audioBinder);
                if (svc != null) {
                    audioService = svc;
                    Class<?> cls = svc.getClass();
                    try { audioGetVolMethod = cls.getMethod("getStreamVolume", int.class); } catch (Throwable ignored) {}
                    try { audioGetMaxVolMethod = cls.getMethod("getStreamMaxVolume", int.class); } catch (Throwable ignored) {}
                    try { audioIsMuteMethod = cls.getMethod("isStreamMute", int.class); } catch (Throwable ignored) {}
                    try { audioSetVolMethod = cls.getMethod("setStreamVolume", int.class, int.class, int.class, String.class); } catch (Throwable ignored) {}
                }
            }

            IBinder powerBinder = (IBinder) getService.invoke(null, "power");
            if (powerBinder != null) {
                Class<?> stub = Class.forName("android.os.IPowerManager$Stub");
                Object svc = stub.getMethod("asInterface", IBinder.class).invoke(null, powerBinder);
                if (svc != null) {
                    powerService = svc;
                    try { powerIsInteractiveMethod = svc.getClass().getMethod("isInteractive"); } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable t) {
            System.out.println("Binder services note: " + t.getMessage());
        }
    }

    private static void loadDiskCache() {
        try {
            if (!ICONS_DIR.exists()) ICONS_DIR.mkdirs();
            if (DOCK_APPS_FILE.exists()) {
                byte[] b = readFileBytes(DOCK_APPS_FILE);
                if (b != null && b.length > 2) {
                    cachedDockAppsJson = new String(b, StandardCharsets.UTF_8).trim();
                }
            }
            if (REMOTE_HTML_FILE.exists()) {
                byte[] b = readFileBytes(REMOTE_HTML_FILE);
                if (b != null && b.length > 0) {
                    cachedRemoteHtml = new String(b, StandardCharsets.UTF_8);
                }
            }
        } catch (Throwable ignored) {}
    }

    // =========================================================================
    // 1. Loopback Bridge (127.0.0.1:8099)
    // =========================================================================

    private static void runLoopbackServer() {
        ServerSocket server;
        try {
            server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT), 50);
            System.out.println("SystemRemoteDaemon loopback listening on 127.0.0.1:" + PORT);
        } catch (Throwable t) {
            t.printStackTrace();
            return;
        }

        while (true) {
            try {
                final Socket socket = server.accept();
                socket.setTcpNoDelay(true);
                socket.setKeepAlive(true);
                startDaemonThread(() -> handleLoopbackClient(socket), "Daemon-LoopbackClient");
            } catch (Throwable ignored) {}
        }
    }

    private static void handleLoopbackClient(Socket socket) {
        try (Socket s = socket;
             BufferedReader reader = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
             OutputStream out = s.getOutputStream()) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) continue;

                if ("PING".equals(line)) {
                    out.write("PONG\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    continue;
                }

                if ("KILL_BG".equals(line)) {
                    int count = killBackgroundApps();
                    out.write(("BG_KILLED:" + count + "\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    continue;
                }

                if ("RELEASE_ALL".equals(line)) {
                    releaseAllKeys();
                    continue;
                }

                if ("RELOAD_APPS".equals(line)) {
                    loadDiskCache();
                    broadcastAppsToTcp();
                    continue;
                }

                if (line.startsWith("CACHE_APPS:")) {
                    String json = line.substring("CACHE_APPS:".length()).trim();
                    if (json.startsWith("{")) {
                        cachedDockAppsJson = json;
                        writeFileBytes(DOCK_APPS_FILE, json.getBytes(StandardCharsets.UTF_8));
                        broadcastAppsToTcp();
                    }
                    continue;
                }

                if (line.startsWith("CACHE_ICON:")) {
                    String rest = line.substring("CACHE_ICON:".length());
                    int sep = rest.indexOf(':');
                    if (sep > 0) {
                        String pkg = rest.substring(0, sep).trim();
                        String b64 = rest.substring(sep + 1).trim();
                        if (pkg.matches("^[a-zA-Z0-9._-]+$") && !b64.isEmpty()) {
                            try {
                                byte[] png = Base64.decode(b64, Base64.DEFAULT);
                                if (png != null && png.length > 0) {
                                    iconCache.put(pkg, png);
                                    writeFileBytes(new File(ICONS_DIR, pkg + ".png"), png);
                                }
                            } catch (Throwable ignored) {}
                        }
                    }
                    continue;
                }

                if (line.startsWith("CACHE_HTML:")) {
                    String b64 = line.substring("CACHE_HTML:".length()).trim();
                    if (!b64.isEmpty()) {
                        try {
                            byte[] html = Base64.decode(b64, Base64.DEFAULT);
                            if (html != null && html.length > 0) {
                                cachedRemoteHtml = new String(html, StandardCharsets.UTF_8);
                                writeFileBytes(REMOTE_HTML_FILE, html);
                            }
                        } catch (Throwable ignored) {}
                    }
                    continue;
                }

                if (line.startsWith("DOWN:")) {
                    Integer kc = parseIntOrNull(line.substring(5).trim());
                    if (kc != null) handleKeyDown(kc);
                    continue;
                }

                if (line.startsWith("UP:")) {
                    Integer kc = parseIntOrNull(line.substring(3).trim());
                    if (kc != null) handleKeyUp(kc);
                    continue;
                }

                if (line.startsWith("TAP:")) {
                    Integer kc = parseIntOrNull(line.substring(4).trim());
                    if (kc != null) handleKeyTap(kc);
                    continue;
                }

                if (line.startsWith("TEXT:")) {
                    handleText(line.substring(5));
                    continue;
                }

                if (line.startsWith("CMD:")) {
                    String cmd = line.substring(4);
                    try {
                        Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
                    } catch (Throwable ignored) {}
                    continue;
                }

                Integer kc = parseIntOrNull(line.trim());
                if (kc != null) {
                    handleKeyTap(kc);
                }
            }
        } catch (Throwable ignored) {
        } finally {
            releaseAllKeys();
        }
    }

    // =========================================================================
    // 2. Native Persistent TCP NDJSON Server (0.0.0.0:8091)
    // =========================================================================

    private static void runTcpServer() {
        ServerSocket server = bindWithRetry(TCP_PORT);
        if (server == null) return;
        System.out.println("SystemRemoteDaemon TCP NDJSON listening on 0.0.0.0:" + TCP_PORT);

        while (true) {
            try {
                Socket socket = server.accept();
                socket.setTcpNoDelay(true);
                socket.setKeepAlive(true);
                socket.setSoTimeout(TCP_CLIENT_IDLE_TIMEOUT_MS);
                startDaemonThread(() -> handleTcpClient(socket), "Daemon-TCP-Client");
            } catch (Throwable ignored) {}
        }
    }

    private static void handleTcpClient(Socket socket) {
        TcpClient client = null;
        try {
            client = new TcpClient(socket);
            tcpClients.add(client);
            // Push initial state & dock apps immediately on connect
            client.sendLine(buildStateJson().toString());
            client.sendLine(getDockAppsJson());

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String line;
            final TcpClient finalClient = client;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                handleJsonCommand(line, finalClient);
            }
        } catch (Throwable ignored) {
        } finally {
            if (client != null) {
                tcpClients.remove(client);
                client.close();
            } else {
                try { socket.close(); } catch (Throwable ignored) {}
            }
            releaseAllKeys();
        }
    }

    private static void handleJsonCommand(String raw, TcpClient client) {
        try {
            JSONObject msg = new JSONObject(raw);
            String type = msg.optString("t", "");
            switch (type) {
                case "ping": {
                    JSONObject pong = new JSONObject();
                    pong.put("t", "pong");
                    pong.put("ts", msg.optLong("ts", System.currentTimeMillis()));
                    client.sendLine(pong.toString());
                    break;
                }
                case "state": {
                    client.sendLine(buildStateJson().toString());
                    break;
                }
                case "apps": {
                    client.sendLine(getDockAppsJson());
                    break;
                }
                case "kill_bg": {
                    asyncExecutor.execute(() -> {
                        int count = killBackgroundApps();
                        JSONObject res = new JSONObject();
                        try {
                            res.put("t", "bg_killed");
                            res.put("ok", true);
                            res.put("count", Math.max(0, count));
                        } catch (Exception ignored) {}
                        client.sendLine(res.toString());
                    });
                    break;
                }
                case "key": {
                    int keyCode = msg.optInt("kc", 0);
                    if (keyCode == 0) keyCode = mapKey(msg.optString("k", ""));
                    if (keyCode == 0) break;
                    String action = msg.optString("a", "tap").toLowerCase();
                    if ("down".equals(action)) {
                        handleKeyDown(keyCode);
                    } else if ("up".equals(action)) {
                        handleKeyUp(keyCode);
                    } else {
                        handleKeyTap(keyCode);
                    }
                    if (keyCode == KeyEvent.KEYCODE_VOLUME_UP
                            || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
                            || keyCode == KeyEvent.KEYCODE_VOLUME_MUTE
                            || keyCode == KeyEvent.KEYCODE_POWER
                            || keyCode == KeyEvent.KEYCODE_WAKEUP
                            || keyCode == KeyEvent.KEYCODE_SLEEP) {
                        scheduleStateBroadcast(150);
                    }
                    break;
                }
                case "text": {
                    String text = msg.optString("v", "");
                    if (!text.isEmpty()) handleText(text);
                    break;
                }
                case "vol": {
                    int target = msg.optInt("v", -1);
                    if (target >= 0) {
                        setMusicVolume(target);
                        scheduleStateBroadcast(80);
                    }
                    break;
                }
                case "power": {
                    String action = msg.optString("a", "toggle").toLowerCase();
                    int kc;
                    if ("wake".equals(action) || "wakeup".equals(action)) kc = KeyEvent.KEYCODE_WAKEUP;
                    else if ("standby".equals(action) || "sleep".equals(action)) kc = KeyEvent.KEYCODE_SLEEP;
                    else kc = KeyEvent.KEYCODE_POWER;
                    handleKeyTap(kc);
                    scheduleStateBroadcast(350);
                    break;
                }
                case "app": {
                    String id = msg.optString("id", "");
                    if (!id.isEmpty()) {
                        asyncExecutor.execute(() -> launchAppFromShell(id));
                    }
                    break;
                }
                case "res": {
                    String preset = msg.optString("p", "720p");
                    applyResolution(preset);
                    scheduleStateBroadcast(700);
                    break;
                }
                case "keystone": {
                    if ("reset".equalsIgnoreCase(msg.optString("a", "reset"))) {
                        resetKeystone();
                    }
                    break;
                }
                case "open_url": {
                    String url = msg.optString("url", msg.optString("u", ""));
                    if (!url.isEmpty()) {
                        asyncExecutor.execute(() -> openUrlFromShell(url));
                    }
                    break;
                }
                default:
                    break;
            }
        } catch (Throwable ignored) {}
    }

    private static void scheduleStateBroadcast(long delayMs) {
        scheduler.schedule(() -> {
            if (tcpClients.isEmpty()) return;
            String line = buildStateJson().toString();
            for (TcpClient c : tcpClients) {
                if (!c.sendLine(line)) {
                    tcpClients.remove(c);
                    c.close();
                }
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    private static void broadcastAppsToTcp() {
        if (tcpClients.isEmpty()) return;
        String line = getDockAppsJson();
        asyncExecutor.execute(() -> {
            for (TcpClient c : tcpClients) {
                if (!c.sendLine(line)) {
                    tcpClients.remove(c);
                    c.close();
                }
            }
        });
    }

    // =========================================================================
    // 3. UDP Discovery Server (0.0.0.0:8092)
    // =========================================================================

    private static void runUdpDiscovery() {
        DatagramSocket udp = null;
        for (int attempt = 0; attempt < 10; attempt++) {
            try {
                udp = new DatagramSocket(null);
                udp.setReuseAddress(true);
                udp.setBroadcast(true);
                udp.bind(new InetSocketAddress(UDP_PORT));
                break;
            } catch (Throwable t) {
                if (udp != null) try { udp.close(); } catch (Throwable ignored) {}
                udp = null;
                SystemClock.sleep(250);
            }
        }
        if (udp == null) return;
        System.out.println("SystemRemoteDaemon UDP discovery listening on 0.0.0.0:" + UDP_PORT);

        byte[] buf = new byte[256];
        while (!udp.isClosed()) {
            try {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                udp.receive(packet);
                String payload = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8).trim();
                if (payload.contains(DISCOVER_TOKEN)) {
                    JSONObject reply = new JSONObject();
                    reply.put("device", deviceName());
                    reply.put("ip", getLocalIpAddress());
                    reply.put("tcp", TCP_PORT);
                    reply.put("http", HTTP_PORT);
                    reply.put("ver", 2);
                    byte[] respBytes = reply.toString().getBytes(StandardCharsets.UTF_8);
                    DatagramPacket resp = new DatagramPacket(respBytes, respBytes.length, packet.getAddress(), packet.getPort());
                    udp.send(resp);
                }
            } catch (Throwable ignored) {}
        }
    }

    // =========================================================================
    // 4. HTTP API Server (0.0.0.0:8090) — Backwards Compatible & LMK-Immune
    // =========================================================================

    private static void runHttpServer() {
        ServerSocket server = bindWithRetry(HTTP_PORT);
        if (server == null) return;
        System.out.println("SystemRemoteDaemon HTTP API listening on 0.0.0.0:" + HTTP_PORT);

        while (true) {
            try {
                Socket socket = server.accept();
                socket.setTcpNoDelay(true);
                socket.setSoTimeout(5000);
                startDaemonThread(() -> handleHttpClient(socket), "Daemon-HTTP-Client");
            } catch (Throwable ignored) {}
        }
    }

    private static void handleHttpClient(Socket socket) {
        try (Socket s = socket;
             InputStream in = s.getInputStream();
             OutputStream out = s.getOutputStream()) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line = reader.readLine();
            if (line == null) return;
            String[] parts = line.split(" ");
            if (parts.length < 2) return;
            String method = parts[0];
            String path = parts[1];

            if ("OPTIONS".equalsIgnoreCase(method)) {
                out.write("HTTP/1.1 204 No Content\r\nAccess-Control-Allow-Origin: *\r\nAccess-Control-Allow-Methods: GET, POST, OPTIONS\r\nAccess-Control-Allow-Headers: Content-Type\r\n\r\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
                return;
            }

            if (path.equals("/api/status")) {
                writeHttpJson(out, buildStateJson().toString());
                return;
            }

            if (path.equals("/api/apps")) {
                writeHttpJson(out, getDockAppsJson());
                return;
            }

            if (path.equals("/api/apps/kill-all")) {
                int count = killBackgroundApps();
                JSONObject res = new JSONObject();
                res.put("t", "bg_killed");
                res.put("ok", true);
                res.put("count", Math.max(0, count));
                writeHttpJson(out, res.toString());
                return;
            }

            if (path.startsWith("/api/app-icon/")) {
                String rawPkg = path.substring("/api/app-icon/".length());
                int q = rawPkg.indexOf('?');
                if (q >= 0) rawPkg = rawPkg.substring(0, q);
                String pkg = URLDecoder.decode(rawPkg, "UTF-8").trim();
                byte[] png = getCachedIcon(pkg);
                if (png != null && png.length > 0) {
                    out.write(("HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nCache-Control: public, max-age=86400\r\nAccess-Control-Allow-Origin: *\r\nContent-Length: " + png.length + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                    out.write(png);
                    out.flush();
                    return;
                }
                out.write("HTTP/1.1 404 Not Found\r\nAccess-Control-Allow-Origin: *\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
                return;
            }

            if (path.equals("/api/power/wake")) {
                handleKeyTap(KeyEvent.KEYCODE_WAKEUP);
                scheduleStateBroadcast(350);
                writeHttpJson(out, "{\"ok\":true,\"action\":\"wake\"}");
                return;
            }

            if (path.equals("/api/power/standby") || path.equals("/api/power/sleep")) {
                handleKeyTap(KeyEvent.KEYCODE_SLEEP);
                scheduleStateBroadcast(350);
                writeHttpJson(out, "{\"ok\":true,\"action\":\"standby\"}");
                return;
            }

            if (path.startsWith("/api/resolution/")) {
                String preset = path.substring("/api/resolution/".length());
                applyResolution(preset);
                scheduleStateBroadcast(700);
                writeHttpJson(out, "{\"ok\":true}");
                return;
            }

            if (path.equals("/api/keystone/reset")) {
                resetKeystone();
                writeHttpJson(out, "{\"ok\":true}");
                return;
            }

            if (path.startsWith("/key/") || path.startsWith("/api/key/")) {
                String keyStr = path.substring(path.lastIndexOf("/") + 1);
                int keyCode = mapKey(keyStr);
                if (keyCode != 0) handleKeyTap(keyCode);
                writeHttpJson(out, "{\"status\":\"ok\",\"key\":\"" + keyStr + "\"}");
                return;
            }

            if (path.startsWith("/api/open_url") || path.startsWith("/open_url")) {
                String url = null;
                if ("POST".equalsIgnoreCase(method)) {
                    int len = 0;
                    String header;
                    while ((header = reader.readLine()) != null && !header.isEmpty()) {
                        if (header.toLowerCase().startsWith("content-length:")) {
                            try { len = Integer.parseInt(header.substring("content-length:".length()).trim()); } catch (Exception ignored) {}
                        }
                    }
                    if (len > 0 && len < 8192) {
                        char[] buf = new char[len];
                        int read = 0;
                        while (read < len) {
                            int r = reader.read(buf, read, len - read);
                            if (r < 0) break;
                            read += r;
                        }
                        String body = new String(buf, 0, read).trim();
                        try {
                            JSONObject j = new JSONObject(body);
                            url = j.optString("url", j.optString("u", null));
                        } catch (Exception ignored) {
                            url = body;
                        }
                    }
                } else {
                    int q = path.indexOf("u=");
                    if (q < 0) q = path.indexOf("url=");
                    if (q >= 0) {
                        String raw = path.substring(path.indexOf('=', q) + 1);
                        int amp = raw.indexOf('&');
                        if (amp >= 0) raw = raw.substring(0, amp);
                        url = java.net.URLDecoder.decode(raw, "UTF-8");
                    }
                }
                if (url != null && !url.isEmpty()) {
                    final String targetUrl = url;
                    asyncExecutor.execute(() -> openUrlFromShell(targetUrl));
                    writeHttpJson(out, "{\"status\":\"ok\"}");
                } else {
                    writeHttpJson(out, "{\"status\":\"error\"}");
                }
                return;
            }

            if (path.startsWith("/app/") || path.startsWith("/api/app/")) {
                String app = path.substring(path.lastIndexOf("/") + 1);
                asyncExecutor.execute(() -> launchAppFromShell(app));
                writeHttpJson(out, "{\"status\":\"ok\"}");
                return;
            }

            if ("POST".equalsIgnoreCase(method) && (path.startsWith("/text") || path.startsWith("/api/text"))) {
                int len = 0;
                String header;
                while ((header = reader.readLine()) != null && !header.isEmpty()) {
                    if (header.toLowerCase().startsWith("content-length:")) {
                        try { len = Integer.parseInt(header.substring("content-length:".length()).trim()); } catch (Exception ignored) {}
                    }
                }
                char[] buf = new char[Math.max(0, Math.min(len, 8192))];
                if (buf.length > 0) reader.read(buf, 0, buf.length);
                String body = new String(buf);
                String txt = null;
                try { txt = new JSONObject(body).optString("text", null); } catch (Exception ignored) {}
                if (txt != null && !txt.isEmpty()) handleText(txt);
                writeHttpJson(out, "{\"status\":\"ok\"}");
                return;
            }

            if ("GET".equalsIgnoreCase(method)) {
                String html = cachedRemoteHtml;
                if (html == null) html = "<html><body>HY300Pro Remote Daemon Active</body></html>";
                html = html.replace("%IP%", getLocalIpAddress());
                byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: " + bytes.length + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                out.write(bytes);
                out.flush();
                return;
            }

            out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (Throwable ignored) {}
    }

    private static void writeHttpJson(OutputStream out, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nAccess-Control-Allow-Origin: *\r\nContent-Length: " + bytes.length + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(bytes);
        out.flush();
    }

    private static ServerSocket bindWithRetry(int port) {
        for (int attempt = 0; attempt < 12; attempt++) {
            try {
                ServerSocket s = new ServerSocket();
                s.setReuseAddress(true);
                s.bind(new InetSocketAddress(port), 50);
                return s;
            } catch (Throwable t) {
                SystemClock.sleep(250);
            }
        }
        System.out.println("Failed to bind port " + port);
        return null;
    }

    // =========================================================================
    // 5. State, Dock Cache, Volume, Power, App Launch & Input Injection
    // =========================================================================

    private static JSONObject buildStateJson() {
        JSONObject obj = new JSONObject();
        try {
            obj.put("t", "state");
            obj.put("online", true);
            obj.put("device", deviceName());
            obj.put("ip", getLocalIpAddress());
            obj.put("tcpPort", TCP_PORT);
            obj.put("httpPort", HTTP_PORT);
            obj.put("awake", isAwake());
            obj.put("vol", getMusicVolume());
            obj.put("maxVol", getMusicMaxVolume());
            obj.put("muted", isMusicMuted());
            obj.put("res", getResolution());
            obj.put("daemon", true);
        } catch (Throwable ignored) {}
        return obj;
    }

    private static boolean isAwake() {
        try {
            if (powerService != null && powerIsInteractiveMethod != null) {
                return Boolean.TRUE.equals(powerIsInteractiveMethod.invoke(powerService));
            }
        } catch (Throwable ignored) {}
        String out = runShellCapture("dumpsys power | grep -m1 'mWakefulness='");
        return out == null || out.contains("Awake");
    }

    private static int getMusicVolume() {
        try {
            if (audioService != null && audioGetVolMethod != null) {
                return (Integer) audioGetVolMethod.invoke(audioService, STREAM_MUSIC);
            }
        } catch (Throwable ignored) {}
        return 5;
    }

    private static int getMusicMaxVolume() {
        try {
            if (audioService != null && audioGetMaxVolMethod != null) {
                return (Integer) audioGetMaxVolMethod.invoke(audioService, STREAM_MUSIC);
            }
        } catch (Throwable ignored) {}
        return 20;
    }

    private static boolean isMusicMuted() {
        try {
            if (audioService != null && audioIsMuteMethod != null) {
                return Boolean.TRUE.equals(audioIsMuteMethod.invoke(audioService, STREAM_MUSIC));
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static void setMusicVolume(int target) {
        int max = getMusicMaxVolume();
        int clamped = Math.max(0, Math.min(max, target));
        try {
            if (audioService != null && audioSetVolMethod != null) {
                audioSetVolMethod.invoke(audioService, STREAM_MUSIC, clamped, 1 /* FLAG_SHOW_UI */, "com.android.shell");
                return;
            }
        } catch (Throwable ignored) {}
        try {
            Runtime.getRuntime().exec(new String[]{"cmd", "media_session", "volume", "--show", "--stream", "3", "--set", String.valueOf(clamped)});
        } catch (Throwable ignored) {}
    }

    private static String getResolution() {
        long now = SystemClock.elapsedRealtime();
        if (cachedRes != null && now - cachedResAt < 10_000) return cachedRes;
        String out = runShellCapture("wm size");
        String res = "1920x1080";
        if (out != null) {
            for (String ln : out.split("\n")) {
                ln = ln.trim();
                if (ln.startsWith("Override size:")) {
                    res = ln.substring("Override size:".length()).trim();
                    break;
                } else if (ln.startsWith("Physical size:")) {
                    res = ln.substring("Physical size:".length()).trim();
                }
            }
        }
        cachedRes = res;
        cachedResAt = now;
        return res;
    }

    private static String getDockAppsJson() {
        String json = cachedDockAppsJson;
        if (json != null && !json.isEmpty()) return json;
        if (DOCK_APPS_FILE.exists()) {
            byte[] b = readFileBytes(DOCK_APPS_FILE);
            if (b != null && b.length > 2) {
                cachedDockAppsJson = new String(b, StandardCharsets.UTF_8).trim();
                return cachedDockAppsJson;
            }
        }
        // Fallback default dock list if launcher hasn't synced yet
        try {
            JSONObject root = new JSONObject();
            root.put("t", "apps");
            JSONArray arr = new JSONArray();
            String[][] defaults = {
                    {"com.stremio.one", "Stremio", "B8BCC6"},
                    {"io.freemytv.music", "Apple Music", "FA233B"},
                    {"com.global.unitviptv", "UniTV", "FA114F"},
                    {"org.smarttube.stable", "YouTube", "FF0033"},
                    {"com.fgl27.twitch", "Twitch", "9146FF"}
            };
            for (String[] d : defaults) {
                JSONObject it = new JSONObject();
                it.put("pkg", d[0]);
                it.put("label", d[1]);
                it.put("color", d[2]);
                it.put("icon", "/api/app-icon/" + d[0]);
                arr.put(it);
            }
            root.put("items", arr);
            return root.toString();
        } catch (Throwable ignored) {
            return "{\"t\":\"apps\",\"items\":[]}";
        }
    }

    private static byte[] getCachedIcon(String pkg) {
        if (pkg == null || !pkg.matches("^[a-zA-Z0-9._-]+$")) return null;
        byte[] mem = iconCache.get(pkg);
        if (mem != null) return mem;
        File f = new File(ICONS_DIR, pkg + ".png");
        if (f.exists()) {
            byte[] disk = readFileBytes(f);
            if (disk != null && disk.length > 0) {
                iconCache.put(pkg, disk);
                return disk;
            }
        }
        return null;
    }

    private static void openUrlFromShell(String raw) {
        if (raw == null) return;
        String text = raw.trim();
        String url = null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(https?://\\S+|stremio://\\S+|magnet:\\?\\S+)", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(text);
        if (m.find()) url = m.group(1);
        else if (!text.contains(" ") && text.contains(".")) url = "https://" + text;
        if (url == null || url.isEmpty()) return;

        handleKeyTap(224); // KEYCODE_WAKEUP
        String safe = url.replace("'", "'\\''");
        String lower = url.toLowerCase();
        String cmd;
        if (lower.contains("music.apple.com")) {
            cmd = "am start -n io.freemytv/.HomeActivity --ez open_music true";
        } else if (lower.contains("youtube.com") || lower.contains("youtu.be")) {
            cmd = "am start -a android.intent.action.VIEW -d '" + safe + "' -p org.smarttube.stable || am start -a android.intent.action.VIEW -d '" + safe + "'";
        } else if (lower.contains("twitch.tv")) {
            cmd = "am start -a android.intent.action.VIEW -d '" + safe + "' -p com.fgl27.twitch || am start -a android.intent.action.VIEW -d '" + safe + "'";
        } else if (lower.startsWith("stremio://") || lower.startsWith("magnet:") || lower.contains("strem.io") || lower.contains("stremio.com")) {
            cmd = "am start -a android.intent.action.VIEW -d '" + safe + "' -p com.stremio.one || am start -a android.intent.action.VIEW -d '" + safe + "'";
        } else {
            cmd = "am start -a android.intent.action.VIEW -d '" + safe + "'";
        }
        try {
            Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd}).waitFor();
        } catch (Throwable ignored) {}
    }

    private static void applyResolution(String preset) {
        String cmd;
        if ("1080p".equals(preset)) cmd = "wm size 1920x1080 && wm density 320";
        else if ("720p_tv".equals(preset)) cmd = "wm size 1280x720 && wm density 213";
        else cmd = "wm size 1280x720 && wm density 250";
        cachedResAt = 0;
        try { Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd}); } catch (Throwable ignored) {}
    }

    private static void resetKeystone() {
        String cmd = "setprop persist.hisi.keystone.lt 0,720 && setprop persist.hisi.keystone.rt 1280,720 && setprop persist.hisi.keystone.lb 0,0 && setprop persist.hisi.keystone.rb 1280,0 && setprop persist.hisi.keystone.update true";
        try { Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd}); } catch (Throwable ignored) {}
    }

    private static void launchAppFromShell(String name) {
        if (name == null || name.isEmpty()) return;
        handleKeyTap(KeyEvent.KEYCODE_WAKEUP);
        String low = name.toLowerCase();
        String cmd;
        if ("keystone".equals(low) || "projection".equals(low)) {
            cmd = "am start -n com.htc.htcsettings/com.htc.activity.ProjectionActivity";
        } else if ("bluetooth".equals(low)) {
            cmd = "am start -n com.htc.htcsettings/com.htc.activity.BluetoothSettingActivity";
        } else if ("settings".equals(low)) {
            cmd = "am start -n com.htc.htcsettings/com.htc.activity.HomeActivity";
        } else if ("io.freemytv.music".equals(low) || "music".equals(low) || "applemusic".equals(low)) {
            cmd = "am start -n io.freemytv/.HomeActivity --ez open_music true";
        } else {
            String pkg = null;
            switch (low) {
                case "youtube":
                case "smarttube": pkg = "org.smarttube.stable"; break;
                case "unitv": pkg = "com.global.unitviptv"; break;
                case "hdmi": pkg = "com.softwinner.awsource"; break;
                case "netflix": pkg = "com.netflix.ninja"; break;
                case "prime": pkg = "com.amazon.amazonvideo.livingroom"; break;
                case "disney": pkg = "com.disney.disneyplus"; break;
                case "btv": pkg = "com.droid.stbbtvapp"; break;
                case "stremio": pkg = "com.stremio.one"; break;
                case "twitch": pkg = "com.fgl27.twitch"; break;
                default:
                    if (name.matches("^[a-zA-Z0-9._-]+$") && name.contains(".")) pkg = name;
                    break;
            }
            if (pkg == null) return;
            cmd = "monkey -p " + pkg + " -c android.intent.category.LEANBACK_LAUNCHER 1 >/dev/null 2>&1 || monkey -p " + pkg + " 1 >/dev/null 2>&1";
        }
        try {
            Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
        } catch (Throwable ignored) {}
    }

    private static int mapKey(String key) {
        if (key == null) return 0;
        switch (key.toLowerCase()) {
            case "up": return KeyEvent.KEYCODE_DPAD_UP;
            case "down": return KeyEvent.KEYCODE_DPAD_DOWN;
            case "left": return KeyEvent.KEYCODE_DPAD_LEFT;
            case "right": return KeyEvent.KEYCODE_DPAD_RIGHT;
            case "ok":
            case "select":
            case "center": return KeyEvent.KEYCODE_DPAD_CENTER;
            case "enter": return KeyEvent.KEYCODE_ENTER;
            case "back": return KeyEvent.KEYCODE_BACK;
            case "home": return KeyEvent.KEYCODE_HOME;
            case "menu": return KeyEvent.KEYCODE_MENU;
            case "apps": return KeyEvent.KEYCODE_APP_SWITCH;
            case "search":
            case "assistant": return KeyEvent.KEYCODE_SEARCH;
            case "volup":
            case "vol_up": return KeyEvent.KEYCODE_VOLUME_UP;
            case "voldown":
            case "vol_down": return KeyEvent.KEYCODE_VOLUME_DOWN;
            case "mute": return KeyEvent.KEYCODE_VOLUME_MUTE;
            case "wake":
            case "wakeup": return KeyEvent.KEYCODE_WAKEUP;
            case "sleep":
            case "standby": return KeyEvent.KEYCODE_SLEEP;
            case "play_pause":
            case "play":
            case "pause": return KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
            case "backspace":
            case "del": return KeyEvent.KEYCODE_DEL;
            case "rewind": return KeyEvent.KEYCODE_MEDIA_REWIND;
            case "forward":
            case "fast_forward":
            case "ff": return KeyEvent.KEYCODE_MEDIA_FAST_FORWARD;
            case "settings": return KeyEvent.KEYCODE_SETTINGS;
            case "power": return KeyEvent.KEYCODE_POWER;
            case "chup":
            case "ch_up": return KeyEvent.KEYCODE_CHANNEL_UP;
            case "chdown":
            case "ch_down": return KeyEvent.KEYCODE_CHANNEL_DOWN;
            case "0": return KeyEvent.KEYCODE_0;
            case "1": return KeyEvent.KEYCODE_1;
            case "2": return KeyEvent.KEYCODE_2;
            case "3": return KeyEvent.KEYCODE_3;
            case "4": return KeyEvent.KEYCODE_4;
            case "5": return KeyEvent.KEYCODE_5;
            case "6": return KeyEvent.KEYCODE_6;
            case "7": return KeyEvent.KEYCODE_7;
            case "8": return KeyEvent.KEYCODE_8;
            case "9": return KeyEvent.KEYCODE_9;
            default: return 0;
        }
    }

    private static String deviceName() {
        String model = Build.MODEL;
        return (model != null && !model.trim().isEmpty()) ? model.trim() : "HY300Pro";
    }

    private static String getLocalIpAddress() {
        String fallback = null;
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (iface.isLoopback() || !iface.isUp()) continue;
                Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        String host = addr.getHostAddress();
                        if (iface.getName().startsWith("wlan") || iface.getName().startsWith("eth")) {
                            return host;
                        }
                        if (fallback == null) fallback = host;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return fallback != null ? fallback : "192.168.1.7";
    }

    private static void handleKeyDown(int keyCode) {
        HeldKey existing = heldKeys.get(keyCode);
        if (existing != null) {
            rescheduleWatchdog(existing);
            return;
        }

        long now = SystemClock.uptimeMillis();
        HeldKey held = new HeldKey(keyCode, now);
        HeldKey prev = heldKeys.putIfAbsent(keyCode, held);
        if (prev != null) {
            rescheduleWatchdog(prev);
            return;
        }

        if (!injectKeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0)) {
            heldKeys.remove(keyCode, held);
            execInputKeyEvent(keyCode);
            return;
        }

        if (isRepeatableKey(keyCode)) {
            held.repeatTask = scheduler.scheduleAtFixedRate(() -> {
                if (heldKeys.get(keyCode) != held) return;
                int count = held.repeatCount.incrementAndGet();
                long eventTime = SystemClock.uptimeMillis();
                injectKeyEvent(held.downTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, count);
            }, REPEAT_INITIAL_DELAY_MS, REPEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);
        }

        rescheduleWatchdog(held);
    }

    private static void rescheduleWatchdog(HeldKey held) {
        ScheduledFuture<?> old = held.watchdogTask;
        if (old != null) old.cancel(false);
        held.watchdogTask = scheduler.schedule(() -> handleKeyUp(held.keyCode), STUCK_KEY_WATCHDOG_MS, TimeUnit.MILLISECONDS);
    }

    private static void handleKeyUp(int keyCode) {
        HeldKey held = heldKeys.remove(keyCode);
        long now = SystemClock.uptimeMillis();
        long downTime = now;
        if (held != null) {
            held.cancelTasks();
            downTime = held.downTime;
        }
        injectKeyEvent(downTime, now, KeyEvent.ACTION_UP, keyCode, 0);
    }

    private static void handleKeyTap(int keyCode) {
        HeldKey held = heldKeys.remove(keyCode);
        if (held != null) {
            held.cancelTasks();
        }
        long now = SystemClock.uptimeMillis();
        boolean ok = injectKeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0)
                && injectKeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0);
        if (!ok) {
            execInputKeyEvent(keyCode);
        }
    }

    private static void releaseAllKeys() {
        List<Integer> keys = new ArrayList<>(heldKeys.keySet());
        for (Integer kc : keys) {
            handleKeyUp(kc);
        }
    }

    private static void handleText(String text) {
        if (text == null || text.isEmpty()) return;

        if (inputManager != null && injectMethod != null && virtualKeyMap != null) {
            try {
                KeyEvent[] events = virtualKeyMap.getEvents(text.toCharArray());
                if (events != null && events.length > 0) {
                    long now = SystemClock.uptimeMillis();
                    for (KeyEvent ev : events) {
                        KeyEvent stamped = KeyEvent.changeTimeRepeat(ev, now, 0);
                        injectMethod.invoke(inputManager, stamped, INJECT_INPUT_EVENT_MODE_ASYNC);
                    }
                    return;
                }
            } catch (Throwable ignored) {}
        }

        try {
            String escaped = text.replace(" ", "%s");
            Runtime.getRuntime().exec(new String[]{"input", "text", escaped});
        } catch (Throwable ignored) {}
    }

    private static boolean injectKeyEvent(long downTime, long eventTime, int action, int keyCode, int repeatCount) {
        if (inputManager == null || injectMethod == null) return false;
        try {
            KeyEvent ev = new KeyEvent(
                    downTime,
                    eventTime,
                    action,
                    keyCode,
                    repeatCount,
                    0,
                    KeyCharacterMap.VIRTUAL_KEYBOARD,
                    0,
                    0,
                    InputDevice.SOURCE_KEYBOARD
            );
            injectMethod.invoke(inputManager, ev, INJECT_INPUT_EVENT_MODE_ASYNC);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void execInputKeyEvent(int keyCode) {
        try {
            Runtime.getRuntime().exec(new String[]{"input", "keyevent", String.valueOf(keyCode)});
        } catch (Throwable ignored) {}
    }

    private static boolean isRepeatableKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_DEL:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
                return true;
            default:
                return false;
        }
    }

    private static Integer parseIntOrNull(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static byte[] readFileBytes(File f) {
        try (FileInputStream fis = new FileInputStream(f)) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = fis.read(buf)) > 0) baos.write(buf, 0, n);
            return baos.toByteArray();
        } catch (Throwable t) {
            return null;
        }
    }

    private static void writeFileBytes(File f, byte[] data) {
        try {
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            File tmp = new File(f.getPath() + ".tmp");
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write(data);
            }
            tmp.renameTo(f);
        } catch (Throwable ignored) {}
    }

    /**
     * Safely force-stops running third-party/user background apps and runs `am kill-all`
     * to reclaim RAM, while strictly protecting io.freemytv, SystemUI, Bluetooth, and active IME.
     */
    private static int killBackgroundApps() {
        int stopped = 0;
        try {
            String imePkg = "";
            String imeRaw = runShellCapture("settings get secure default_input_method");
            if (imeRaw != null) {
                imeRaw = imeRaw.trim();
                int slash = imeRaw.indexOf('/');
                if (slash > 0) imePkg = imeRaw.substring(0, slash);
            }

            Set<String> runningProcs = new HashSet<>();
            String psOut = runShellCapture("ps -A -o NAME");
            if (psOut != null) {
                for (String ln : psOut.split("\n")) {
                    String name = ln.trim();
                    if (name.isEmpty() || "NAME".equals(name)) continue;
                    int colon = name.indexOf(':');
                    if (colon > 0) name = name.substring(0, colon);
                    runningProcs.add(name);
                }
            }

            String pkgOut = runShellCapture("pm list packages -3");
            if (pkgOut != null) {
                for (String ln : pkgOut.split("\n")) {
                    ln = ln.trim();
                    if (!ln.startsWith("package:")) continue;
                    String pkg = ln.substring("package:".length()).trim();
                    if (pkg.isEmpty() || isProtectedPackage(pkg, imePkg)) continue;
                    if (!runningProcs.isEmpty() && !runningProcs.contains(pkg)) continue;
                    try {
                        Process p = Runtime.getRuntime().exec(new String[]{"am", "force-stop", pkg});
                        p.waitFor();
                        stopped++;
                    } catch (Throwable ignored) {}
                }
            }

            try {
                Process p = Runtime.getRuntime().exec(new String[]{"am", "kill-all"});
                p.waitFor();
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
        return stopped;
    }

    private static boolean isProtectedPackage(String pkg, String imePkg) {
        if ("io.freemytv".equals(pkg) || pkg.startsWith("io.freemytv.")) return true;
        if (imePkg != null && !imePkg.isEmpty() && imePkg.equals(pkg)) return true;
        if ("android".equals(pkg)) return true;
        if ("com.android.systemui".equals(pkg)) return true;
        if ("com.android.bluetooth".equals(pkg)) return true;
        if ("com.htc.htcsettings".equals(pkg)) return true;
        if (pkg.startsWith("com.android.providers.")) return true;
        if (pkg.startsWith("com.android.inputmethod.")) return true;
        if (pkg.startsWith("com.google.android.inputmethod.")) return true;
        return false;
    }

    private static String runShellCapture(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }
            p.waitFor();
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    // =========================================================================
    // 6. Silent Volume Controller Hijacker (suppresses native AOSP volume bar)
    // =========================================================================

    private static final String VC_DESCRIPTOR = "android.media.IVolumeController";
    private static final Object vcWakeLock = new Object();

    private static final class SilentVolumeBinder extends Binder {
        @Override
        public String getInterfaceDescriptor() {
            return VC_DESCRIPTOR;
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            if (code == INTERFACE_TRANSACTION) {
                if (reply != null) {
                    reply.writeString(VC_DESCRIPTOR);
                }
                return true;
            }
            if (code == 5) { // TRANSACTION_dismiss (called when SystemUI respawns and tries to take over)
                synchronized (vcWakeLock) {
                    vcWakeLock.notifyAll();
                }
            }
            return true;
        }
    }

    private static void startVolumeControllerHijacker() {
        Thread hijackerThread = new Thread(() -> {
            try {
                Runtime.getRuntime().exec(new String[]{
                        "sh", "-c",
                        "settings put secure enabled_accessibility_services io.freemytv/io.freemytv.RemoteKeyService; "
                        + "settings put secure accessibility_enabled 1; "
                        + "appops set io.freemytv SYSTEM_ALERT_WINDOW allow"
                });
            } catch (Throwable ignored) {}

            try {
                Class<?> smClass = Class.forName("android.os.ServiceManager");
                Method getService = smClass.getMethod("getService", String.class);
                Class<?> stubClass = Class.forName("android.media.IAudioService$Stub");
                Method asInterface = stubClass.getMethod("asInterface", IBinder.class);
                Class<?> vcClass = Class.forName("android.media.IVolumeController");

                final SilentVolumeBinder silentBinder = new SilentVolumeBinder();
                Object vcProxy = Proxy.newProxyInstance(
                        vcClass.getClassLoader(),
                        new Class<?>[]{vcClass, IInterface.class},
                        (proxy, method, methodArgs) -> {
                            String name = method.getName();
                            if ("asBinder".equals(name)) return silentBinder;
                            if ("hashCode".equals(name)) return System.identityHashCode(silentBinder);
                            if ("equals".equals(name)) return proxy == (methodArgs != null && methodArgs.length > 0 ? methodArgs[0] : null);
                            return null;
                        }
                );

                Method setVc = null;
                Object svc = null;

                while (true) {
                    try {
                        if (svc == null || setVc == null) {
                            IBinder audioBinder = (IBinder) getService.invoke(null, "audio");
                            if (audioBinder != null) {
                                svc = asInterface.invoke(null, audioBinder);
                                setVc = svc.getClass().getMethod("setVolumeController", vcClass);
                            }
                        }
                        if (svc != null && setVc != null) {
                            setVc.invoke(svc, vcProxy);
                        }
                    } catch (Throwable t) {
                        if (t.getCause() instanceof SecurityException || t instanceof SecurityException) {
                            return;
                        }
                        svc = null;
                        setVc = null;
                    }

                    synchronized (vcWakeLock) {
                        vcWakeLock.wait(1500L);
                    }
                    Thread.sleep(25L);
                }
            } catch (Throwable ignored) {}
        }, "Daemon-VolumeHijacker");
        hijackerThread.setDaemon(true);
        hijackerThread.start();
    }
}
