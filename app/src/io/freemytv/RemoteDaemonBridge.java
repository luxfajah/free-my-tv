package io.freemytv;

import android.content.Context;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Persistent loopback bridge to SystemRemoteDaemon (127.0.0.1:8099) with automatic
 * local-ADB (127.0.0.1:5555) bootstrapping so the daemon self-starts after boot or update.
 */
final class RemoteDaemonBridge {
    private static final String HOST = "127.0.0.1";
    private static final int PORT = SystemRemoteDaemon.PORT;
    private static final long BOOTSTRAP_THROTTLE_MS = 5_000L;

    private static final Object ioLock = new Object();
    private static final Object bootstrapLock = new Object();
    private static final ExecutorService bootstrapExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "RemoteDaemon-Bootstrap");
        t.setDaemon(true);
        return t;
    });

    private static volatile Context appContext;
    private static Socket persistentSocket;
    private static OutputStream persistentOut;
    private static volatile long lastBootstrapAttemptMs;

    private RemoteDaemonBridge() {}

    static void init(Context context) {
        if (context != null) {
            appContext = context.getApplicationContext();
        }
    }

    static void ensureRunningAsync(Context context, boolean forceRestart) {
        init(context);
        bootstrapExecutor.execute(() -> ensureRunning(appContext, forceRestart));
    }

    static boolean ensureRunning(Context context, boolean forceRestart) {
        init(context);
        if (appContext == null) return false;

        synchronized (bootstrapLock) {
            if (!forceRestart && pingDaemon(300)) {
                ensureConnected();
                syncCacheAsync(appContext);
                return true;
            }

            long now = SystemClock.elapsedRealtime();
            if (!forceRestart && now - lastBootstrapAttemptMs < BOOTSTRAP_THROTTLE_MS) {
                return false;
            }
            lastBootstrapAttemptMs = now;

            closePersistent();

            try (Adb adb = Adb.connect(appContext, 10_000, null)) {
                String cmd = "for p in /proc/[0-9]*; do "
                        + "pid=${p#/proc/}; [ \"$pid\" = \"$$\" ] && continue; "
                        + "grep -aqs io.freemytv.SystemRemoteDaemon \"$p/cmdline\" "
                        + "&& ! grep -aqs setsid \"$p/cmdline\" "
                        + "&& kill \"$pid\" 2>/dev/null; done; "
                        + "APK=$(pm path io.freemytv | cut -d: -f2); "
                        + "setsid sh -c \"CLASSPATH=$APK app_process /system/bin io.freemytv.SystemRemoteDaemon\" "
                        + "</dev/null >/dev/null 2>&1 & sleep 0.2; echo OK";
                String out = adb.shell(cmd);
                AppLog.w("SystemRemoteDaemon bootstrap via local ADB: " + out.trim());
            } catch (Exception e) {
                AppLog.w("SystemRemoteDaemon bootstrap failed: " + e.getMessage());
                return false;
            }

            for (int i = 0; i < 14; i++) {
                SystemClock.sleep(150);
                if (pingDaemon(250)) {
                    ensureConnected();
                    syncCacheAsync(appContext);
                    AppLog.w("SystemRemoteDaemon is online on 127.0.0.1:" + PORT);
                    return true;
                }
            }
            AppLog.w("SystemRemoteDaemon did not respond after bootstrap");
            return false;
        }
    }

    static boolean isDaemonAlive() {
        synchronized (ioLock) {
            if (persistentSocket != null && persistentSocket.isConnected() && !persistentSocket.isClosed()) {
                return true;
            }
        }
        if (pingDaemon(250)) {
            ensureConnected();
            return true;
        }
        return false;
    }

    static boolean sendKeyDown(int keyCode) {
        return sendLine("DOWN:" + keyCode);
    }

    static boolean sendKeyUp(int keyCode) {
        return sendLine("UP:" + keyCode);
    }

    static boolean sendKeyTap(int keyCode) {
        return sendLine("TAP:" + keyCode);
    }

    static boolean releaseAll() {
        return sendLine("RELEASE_ALL");
    }

    static boolean sendText(String text) {
        if (text == null || text.isEmpty()) return true;
        String clean = text.replace("\r", "").replace("\n", "");
        return sendLine("TEXT:" + clean);
    }

    static boolean sendCmd(String cmd) {
        if (cmd == null || cmd.isEmpty()) return true;
        String clean = cmd.replace("\r", "").replace("\n", " ");
        return sendLine("CMD:" + clean);
    }

    /** Synchronously asks SystemRemoteDaemon to stop running 3rd-party background apps. Returns count or -1 on error. */
    static int killBackgroundApps() {
        try (Socket s = new Socket()) {
            s.setTcpNoDelay(true);
            s.connect(new InetSocketAddress(HOST, PORT), 500);
            s.setSoTimeout(8_000);
            OutputStream os = s.getOutputStream();
            os.write("KILL_BG\n".getBytes(StandardCharsets.UTF_8));
            os.flush();
            BufferedReader br = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            String resp = br.readLine();
            if (resp != null && resp.startsWith("BG_KILLED:")) {
                return Integer.parseInt(resp.substring("BG_KILLED:".length()).trim());
            }
        } catch (Exception e) {
            AppLog.w("killBackgroundApps error: " + e.getMessage());
        }
        return -1;
    }

    /** Pushes dock apps JSON, 192x192 PNG icons, and remote.html to SystemRemoteDaemon's persistent disk cache. */
    static void syncCacheAsync(Context context) {
        init(context);
        final Context ctx = appContext;
        if (ctx == null) return;
        bootstrapExecutor.execute(() -> {
            try {
                org.json.JSONObject appsObj = HomeApps.getDockAppsForRemote(ctx);
                String appsJson = appsObj.toString();
                sendLine("CACHE_APPS:" + appsJson);

                org.json.JSONArray items = appsObj.optJSONArray("items");
                if (items != null) {
                    for (int i = 0; i < items.length(); i++) {
                        org.json.JSONObject it = items.optJSONObject(i);
                        if (it == null) continue;
                        String pkg = it.optString("pkg", "");
                        if (pkg.isEmpty()) continue;
                        byte[] png = HomeApps.renderAppIconPng(ctx, pkg);
                        if (png != null && png.length > 0) {
                            String b64 = android.util.Base64.encodeToString(png, android.util.Base64.NO_WRAP);
                            sendLine("CACHE_ICON:" + pkg + ":" + b64);
                        }
                    }
                }

                try (java.io.InputStream in = ctx.getAssets().open("remote.html")) {
                    java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = in.read(buf)) > 0) baos.write(buf, 0, n);
                    String b64Html = android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP);
                    sendLine("CACHE_HTML:" + b64Html);
                } catch (Exception ignored) {}
            } catch (Throwable ignored) {}
        });
    }

    private static boolean sendLine(String line) {
        byte[] payload = (line + "\n").getBytes(StandardCharsets.UTF_8);
        synchronized (ioLock) {
            if (writeLocked(payload)) return true;
            // Fast reconnect attempt in case daemon restarted
            if (reconnectLocked() && writeLocked(payload)) return true;
        }
        // Trigger background self-heal
        if (appContext != null) {
            ensureRunningAsync(appContext, false);
        }
        return false;
    }

    private static boolean writeLocked(byte[] payload) {
        if (persistentOut == null || persistentSocket == null || persistentSocket.isClosed()) {
            return false;
        }
        try {
            persistentOut.write(payload);
            persistentOut.flush();
            return true;
        } catch (IOException e) {
            closeLocked();
            return false;
        }
    }

    private static void ensureConnected() {
        synchronized (ioLock) {
            if (persistentSocket != null && persistentSocket.isConnected() && !persistentSocket.isClosed()) {
                return;
            }
            reconnectLocked();
        }
    }

    private static boolean reconnectLocked() {
        closeLocked();
        try {
            Socket s = new Socket();
            s.setTcpNoDelay(true);
            s.setKeepAlive(true);
            s.connect(new InetSocketAddress(HOST, PORT), 300);
            persistentSocket = s;
            persistentOut = s.getOutputStream();
            return true;
        } catch (IOException e) {
            closeLocked();
            return false;
        }
    }

    private static void closePersistent() {
        synchronized (ioLock) {
            closeLocked();
        }
    }

    private static void closeLocked() {
        if (persistentSocket != null) {
            try { persistentSocket.close(); } catch (IOException ignored) {}
        }
        persistentSocket = null;
        persistentOut = null;
    }

    private static boolean pingDaemon(int timeoutMs) {
        try (Socket s = new Socket()) {
            s.setTcpNoDelay(true);
            s.connect(new InetSocketAddress(HOST, PORT), timeoutMs);
            s.setSoTimeout(timeoutMs);
            OutputStream os = s.getOutputStream();
            os.write("PING\n".getBytes(StandardCharsets.UTF_8));
            os.flush();
            BufferedReader br = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            return "PONG".equals(br.readLine());
        } catch (IOException e) {
            return false;
        }
    }
}
