package io.freemytv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.view.KeyEvent;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Web Remote Server running on port 8090.
 * Serves the physical-style tactile remote control for iOS Safari and Android Chrome.
 * Routes hardware key events and app launches across the entire BTV box.
 */
public final class WebRemoteServer {
    public static final int PORT = 8090;
    private static WebRemoteServer instance;

    private final Activity activity;
    private final ServerSocket serverSocket;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final String url;
    private Bitmap qrBitmap;

    public interface KeyDispatcher {
        void dispatch(int keyCode);
    }
    private KeyDispatcher keyDispatcher;

    private WebRemoteServer(Activity activity) throws IOException {
        this.activity = activity;
        this.serverSocket = new ServerSocket(PORT);
        String ip = getLocalIpAddress(activity);
        this.url = "http://" + (ip != null ? ip : "localhost") + ":" + PORT + "/";
        try {
            boolean[][] modules = Qr.encode(this.url);
            this.qrBitmap = makeQrBitmap(modules);
        } catch (Exception e) {
            AppLog.w("QR generation error: " + e.getMessage());
        }

        new Thread(this::listenLoop, "WebRemote-Server").start();
        AppLog.w("WebRemoteServer running at " + this.url);
    }

    public static synchronized WebRemoteServer start(Activity activity) {
        if (instance != null && instance.running.get()) return instance;
        try {
            instance = new WebRemoteServer(activity);
        } catch (Exception e) {
            AppLog.w("WebRemoteServer start error: " + e.getMessage());
        }
        return instance;
    }

    public static synchronized WebRemoteServer get() {
        return instance;
    }

    public void setKeyDispatcher(KeyDispatcher dispatcher) {
        this.keyDispatcher = dispatcher;
    }

    public String getUrl() {
        return url;
    }

    public Bitmap getQrBitmap() {
        return qrBitmap;
    }

    private void listenLoop() {
        while (running.get() && !serverSocket.isClosed()) {
            try {
                Socket socket = serverSocket.accept();
                socket.setSoTimeout(5000);
                new Thread(() -> handleClient(socket), "WebRemote-Client").start();
            } catch (Exception e) {
                if (!running.get()) break;
            }
        }
    }

    private void handleClient(Socket socket) {
        try (InputStream in = socket.getInputStream(); OutputStream out = socket.getOutputStream()) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line = reader.readLine();
            if (line == null) return;

            String[] parts = line.split(" ");
            if (parts.length < 2) return;
            String method = parts[0];
            String path = parts[1];

            if ("POST".equalsIgnoreCase(method) && path.startsWith("/key/")) {
                String keyStr = path.substring("/key/".length());
                int keyCode = mapKey(keyStr);
                if (keyCode != 0) {
                    sendSystemKey(keyCode);
                }
                String resp = "{\"status\":\"ok\",\"key\":\"" + keyStr + "\"}";
                byte[] bytes = resp.getBytes(StandardCharsets.UTF_8);
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nAccess-Control-Allow-Origin: *\r\nContent-Length: " + bytes.length + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                out.write(bytes);
                out.flush();
                return;
            }

            if ("POST".equalsIgnoreCase(method) && path.startsWith("/app/")) {
                String app = path.substring("/app/".length());
                launchApp(activity, app);
                out.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nAccess-Control-Allow-Origin: *\r\n\r\n{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8));
                out.flush();
                return;
            }

            if ("POST".equalsIgnoreCase(method) && path.startsWith("/text")) {
                int len = 0;
                String header;
                while ((header = reader.readLine()) != null && !header.isEmpty()) {
                    if (header.toLowerCase().startsWith("content-length:")) {
                        try { len = Integer.parseInt(header.substring("content-length:".length()).trim()); } catch (Exception ignored) {}
                    }
                }
                char[] buf = new char[Math.max(0, len)];
                if (len > 0) reader.read(buf, 0, len);
                String body = new String(buf);
                int idx = body.indexOf("\"text\":\"");
                if (idx != -1) {
                    int end = body.indexOf("\"", idx + 8);
                    if (end != -1) {
                        String txt = body.substring(idx + 8, end);
                        sendSystemText(txt);
                    }
                }
                out.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nAccess-Control-Allow-Origin: *\r\n\r\n{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8));
                out.flush();
                return;
            }

            if ("GET".equalsIgnoreCase(method)) {
                String ip = getLocalIpAddress(activity);
                String rendered = getRemoteHtml(activity, ip);
                byte[] html = rendered.getBytes(StandardCharsets.UTF_8);
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: " + html.length + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                out.write(html);
                out.flush();
                return;
            }

            out.write("HTTP/1.1 404 Not Found\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (Exception ignored) {
        } finally {
            try { socket.close(); } catch (Exception ignored) {}
        }
    }

    public static void sendSystemText(String text) {
        new Thread(() -> {
            try (Socket s = new Socket(InetAddress.getByName("127.0.0.1"), SystemRemoteDaemon.PORT);
                 OutputStream os = s.getOutputStream()) {
                os.write(("TEXT:" + text + "\n").getBytes(StandardCharsets.UTF_8));
                os.flush();
            } catch (Exception ignored) {}
        }).start();
    }

    private static void launchApp(Activity activity, String name) {
        String pkg = null;
        switch (name.toLowerCase()) {
            case "youtube": pkg = "com.google.android.youtube.tv"; break;
            case "netflix": pkg = "com.netflix.ninja"; break;
            case "prime": pkg = "com.amazon.amazonvideo.livingroom"; break;
            case "disney": pkg = "com.disney.disneyplus"; break;
            case "btv": pkg = "com.droid.stbbtvapp"; break;
            case "stremio": pkg = "com.stremio.one"; break;
        }
        if (pkg != null && activity != null) {
            final String targetPkg = pkg;
            activity.runOnUiThread(() -> {
                try {
                    Intent i = activity.getPackageManager().getLeanbackLaunchIntentForPackage(targetPkg);
                    if (i == null) i = activity.getPackageManager().getLaunchIntentForPackage(targetPkg);
                    if (i != null) {
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        activity.startActivity(i);
                    }
                } catch (Exception ignored) {}
            });
        }
    }

    public static void sendSystemKey(int keyCode) {
        new Thread(() -> {
            boolean sent = false;
            try (Socket s = new Socket(InetAddress.getByName("127.0.0.1"), SystemRemoteDaemon.PORT);
                 OutputStream os = s.getOutputStream()) {
                os.write((keyCode + "\n").getBytes(StandardCharsets.UTF_8));
                os.flush();
                sent = true;
            } catch (Exception ignored) {}

            if (!sent) {
                // Fallback: in-app dispatch
                if (instance != null && instance.activity != null) {
                    instance.activity.runOnUiThread(() -> {
                        if (instance.keyDispatcher != null) {
                            instance.keyDispatcher.dispatch(keyCode);
                        } else {
                            injectKey(instance.activity, keyCode);
                        }
                    });
                }
            }
        }).start();
    }

    private static int mapKey(String key) {
        switch (key.toLowerCase()) {
            case "up": return KeyEvent.KEYCODE_DPAD_UP;
            case "down": return KeyEvent.KEYCODE_DPAD_DOWN;
            case "left": return KeyEvent.KEYCODE_DPAD_LEFT;
            case "right": return KeyEvent.KEYCODE_DPAD_RIGHT;
            case "ok":
            case "enter": return KeyEvent.KEYCODE_DPAD_CENTER;
            case "back": return KeyEvent.KEYCODE_BACK;
            case "home": return KeyEvent.KEYCODE_HOME;
            case "menu": return KeyEvent.KEYCODE_MENU;
            case "apps": return KeyEvent.KEYCODE_APP_SWITCH;
            case "assistant": return KeyEvent.KEYCODE_SEARCH;
            case "volup": return KeyEvent.KEYCODE_VOLUME_UP;
            case "voldown": return KeyEvent.KEYCODE_VOLUME_DOWN;
            case "mute": return KeyEvent.KEYCODE_VOLUME_MUTE;
            case "play":
            case "pause": return KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
            case "rewind": return KeyEvent.KEYCODE_MEDIA_REWIND;
            case "forward": return KeyEvent.KEYCODE_MEDIA_FAST_FORWARD;
            case "settings": return KeyEvent.KEYCODE_SETTINGS;
            case "power": return KeyEvent.KEYCODE_POWER;
            case "chup": return KeyEvent.KEYCODE_CHANNEL_UP;
            case "chdown": return KeyEvent.KEYCODE_CHANNEL_DOWN;
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

    private static void injectKey(Activity activity, int keyCode) {
        long now = android.os.SystemClock.uptimeMillis();
        KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0);
        KeyEvent up = new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0);
        activity.dispatchKeyEvent(down);
        activity.dispatchKeyEvent(up);
    }

    private static String getRemoteHtml(Context context, String ip) {
        try (InputStream in = context.getAssets().open("remote.html")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            String html = out.toString("UTF-8");
            return html.replace("%IP%", ip != null ? ip : "192.168.1.6");
        } catch (Exception e) {
            return "<html><body>Erro ao carregar controle</body></html>";
        }
    }

    private static String getLocalIpAddress(Context context) {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                Network network = cm.getActiveNetwork();
                if (network != null) {
                    LinkProperties lp = cm.getLinkProperties(network);
                    if (lp != null) {
                        for (LinkAddress la : lp.getLinkAddresses()) {
                            InetAddress addr = la.getAddress();
                            if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                                return addr.getHostAddress();
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {}

        try {
            java.util.Enumeration<java.net.NetworkInterface> interfaces = java.net.NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                java.net.NetworkInterface iface = interfaces.nextElement();
                if (iface.isLoopback() || !iface.isUp()) continue;
                java.util.Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {}

        return "192.168.1.6";
    }

    private static Bitmap makeQrBitmap(boolean[][] modules) {
        int quiet = 3, n = modules.length + 2 * quiet;
        int[] pixels = new int[n * n];
        java.util.Arrays.fill(pixels, 0xFFFFFFFF);
        for (int y = 0; y < modules.length; y++) {
            for (int x = 0; x < modules.length; x++) {
                if (modules[y][x]) pixels[(y + quiet) * n + x + quiet] = 0xFF000000;
            }
        }
        return Bitmap.createBitmap(pixels, n, n, Bitmap.Config.ARGB_8888);
    }
}
