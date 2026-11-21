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

import org.json.JSONObject;

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
 * Serves the physical-style tactile remote control for iOS Safari, Android Chrome, and Siri Shortcuts.
 * Starts RemoteTcpServer (port 8091 + Bonjour + UDP discovery) and routes system-wide input via RemoteDaemonBridge.
 */
public final class WebRemoteServer {
    public static final int PORT = 8090;
    private static WebRemoteServer instance;

    private final Activity activity;
    private ServerSocket serverSocket;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final String url;
    private Bitmap qrBitmap;

    public interface KeyDispatcher {
        void dispatch(int keyCode);
    }
    private KeyDispatcher keyDispatcher;

    private WebRemoteServer(Activity activity) {
        this.activity = activity;
        String ip = getLocalIpAddress(activity);
        this.url = "http://" + (ip != null ? ip : "localhost") + ":" + PORT + "/";
        try {
            boolean[][] modules = Qr.encode(this.url);
            this.qrBitmap = makeQrBitmap(modules);
        } catch (Exception e) {
            AppLog.w("QR generation error: " + e.getMessage());
        }

        // Auto-bootstrap LMK-immune SystemRemoteDaemon via local ADB and sync dock/icon cache
        RemoteDaemonBridge.init(activity);
        RemoteDaemonBridge.ensureRunningAsync(activity, false);
        RemoteTcpServer.start(activity);

        // Only bind HTTP 8090 in-app as fallback if SystemRemoteDaemon is not already owning it
        try {
            ServerSocket ss = new ServerSocket();
            ss.setReuseAddress(true);
            ss.bind(new java.net.InetSocketAddress(PORT), 50);
            this.serverSocket = ss;
            new Thread(this::listenLoop, "WebRemote-Server").start();
            AppLog.w("WebRemoteServer fallback HTTP running at " + this.url);
        } catch (IOException e) {
            AppLog.w("WebRemoteServer: port " + PORT + " already served by LMK-immune SystemRemoteDaemon");
        }
    }

    public static synchronized WebRemoteServer start(Activity activity) {
        if (instance != null && instance.running.get()) {
            RemoteDaemonBridge.ensureRunningAsync(activity, false);
            RemoteTcpServer.start(activity);
            return instance;
        }
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
                socket.setTcpNoDelay(true);
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

            if ("OPTIONS".equalsIgnoreCase(method)) {
                out.write("HTTP/1.1 204 No Content\r\nAccess-Control-Allow-Origin: *\r\nAccess-Control-Allow-Methods: GET, POST, OPTIONS\r\nAccess-Control-Allow-Headers: Content-Type\r\n\r\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
                return;
            }

            if (path.equals("/api/status")) {
                String resp = RemoteTcpServer.buildStateJson(activity.getApplicationContext()).toString();
                writeJson(out, resp);
                return;
            }

            if (path.equals("/api/apps")) {
                String resp = HomeApps.getDockAppsForRemote(activity.getApplicationContext()).toString();
                writeJson(out, resp);
                return;
            }

            if (path.equals("/api/apps/kill-all")) {
                int count = RemoteDaemonBridge.killBackgroundApps();
                JSONObject res = new JSONObject();
                try {
                    res.put("t", "bg_killed");
                    res.put("ok", count >= 0);
                    res.put("count", Math.max(0, count));
                } catch (Exception ignored) {}
                writeJson(out, res.toString());
                return;
            }

            if (path.startsWith("/api/app-icon/")) {
                String rawPkg = path.substring("/api/app-icon/".length());
                int q = rawPkg.indexOf('?');
                if (q >= 0) rawPkg = rawPkg.substring(0, q);
                String pkg = java.net.URLDecoder.decode(rawPkg, "UTF-8").trim();
                if (pkg.matches("^[a-zA-Z0-9._-]+$")) {
                    byte[] png = HomeApps.renderAppIconPng(activity.getApplicationContext(), pkg);
                    if (png != null && png.length > 0) {
                        out.write(("HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nCache-Control: public, max-age=86400\r\nAccess-Control-Allow-Origin: *\r\nContent-Length: " + png.length + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                        out.write(png);
                        out.flush();
                        return;
                    }
                }
                out.write("HTTP/1.1 404 Not Found\r\nAccess-Control-Allow-Origin: *\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
                return;
            }

            if (path.equals("/api/power/wake")) {
                sendSystemKey(KeyEvent.KEYCODE_WAKEUP);
                writeJson(out, "{\"ok\":true,\"action\":\"wake\"}");
                return;
            }

            if (path.equals("/api/power/standby") || path.equals("/api/power/sleep")) {
                sendSystemKey(KeyEvent.KEYCODE_SLEEP);
                writeJson(out, "{\"ok\":true,\"action\":\"standby\"}");
                return;
            }

            if (path.startsWith("/api/resolution/")) {
                String preset = path.substring("/api/resolution/".length());
                applyResolution(preset);
                writeJson(out, "{\"ok\":true}");
                return;
            }

            if (path.equals("/api/keystone/reset")) {
                resetKeystone();
                writeJson(out, "{\"ok\":true}");
                return;
            }

            if (path.startsWith("/key/") || path.startsWith("/api/key/")) {
                String keyStr = path.substring(path.lastIndexOf("/") + 1);
                int keyCode = mapKey(keyStr);
                if (keyCode != 0) {
                    sendSystemKey(keyCode);
                }
                writeJson(out, "{\"status\":\"ok\",\"key\":\"" + keyStr + "\"}");
                return;
            }

            if (path.startsWith("/app/") || path.startsWith("/api/app/")) {
                String app = path.substring(path.lastIndexOf("/") + 1);
                sendSystemKey(KeyEvent.KEYCODE_WAKEUP);
                launchApp(activity, app);
                writeJson(out, "{\"status\":\"ok\"}");
                return;
            }

            if ("POST".equalsIgnoreCase(method) && path.startsWith("/api/applemusic/login")) {
                String body = readPostBody(reader);
                String email = "", password = "";
                try {
                    JSONObject j = new JSONObject(body);
                    email = j.optString("email", "").trim();
                    password = j.optString("password", "");
                } catch (Exception ignored) {}
                if (activity instanceof HomeActivity) {
                    HomeView hv = ((HomeActivity) activity).getHomeView();
                    if (hv != null && hv.getMusicView() != null) {
                        final String em = email, pw = password;
                        activity.runOnUiThread(() -> {
                            hv.openMusic();
                            hv.getMusicView().submitPhoneLogin(em, pw);
                        });
                    }
                }
                writeJson(out, "{\"ok\":true}");
                return;
            }

            if ("POST".equalsIgnoreCase(method) && path.startsWith("/api/applemusic/2fa")) {
                String body = readPostBody(reader);
                String code = "";
                try {
                    JSONObject j = new JSONObject(body);
                    code = j.optString("code", "").trim();
                } catch (Exception ignored) {}
                if (activity instanceof HomeActivity) {
                    HomeView hv = ((HomeActivity) activity).getHomeView();
                    if (hv != null && hv.getMusicView() != null) {
                        final String c = code;
                        activity.runOnUiThread(() -> hv.getMusicView().submitPhone2fa(c));
                    }
                }
                writeJson(out, "{\"ok\":true}");
                return;
            }

            if ("POST".equalsIgnoreCase(method) && path.startsWith("/api/applemusic/token")) {
                String body = readPostBody(reader);
                String token = "";
                try {
                    JSONObject j = new JSONObject(body);
                    token = j.optString("token", "").trim();
                } catch (Exception ignored) {}
                if (!token.isEmpty()) {
                    activity.getSharedPreferences("amtv", android.content.Context.MODE_PRIVATE)
                            .edit().putString("media_user_token", token).apply();
                    final String tok = token;
                    activity.runOnUiThread(() -> {
                        try {
                            android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
                            cm.setAcceptCookie(true);
                            cm.setCookie("https://music.apple.com/", "media-user-token=" + tok + "; Domain=.apple.com; Path=/; Secure; Max-Age=31536000");
                            cm.setCookie("https://music.apple.com/", "media-user-token=" + tok + "; Domain=.music.apple.com; Path=/; Secure; Max-Age=31536000");
                            cm.setCookie("https://music.apple.com/", "media-user-token=" + tok + "; Domain=music.apple.com; Path=/; Secure; Max-Age=31536000");
                            cm.flush();
                        } catch (Throwable ignored) {}
                    });
                }
                writeJson(out, "{\"ok\":true}");
                return;
            }

            if ("GET".equalsIgnoreCase(method) && (path.startsWith("/applemusic") || path.startsWith("/music-login"))) {
                if (activity instanceof HomeActivity) {
                    HomeView hv = ((HomeActivity) activity).getHomeView();
                    if (hv != null && hv.getMusicView() != null) {
                        activity.runOnUiThread(() -> {
                            hv.openMusic();
                            hv.getMusicView().ensureSigninPopupOpen();
                        });
                    }
                }
                byte[] html = getAppleMusicLoginHtml().getBytes(StandardCharsets.UTF_8);
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: " + html.length + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                out.write(html);
                out.flush();
                return;
            }

            if ("POST".equalsIgnoreCase(method) && (path.startsWith("/text") || path.startsWith("/api/text"))) {
                String body = readPostBody(reader);
                String txt = null;
                try {
                    txt = new JSONObject(body).optString("text", null);
                } catch (Exception ignored) {}
                if (txt != null && !txt.isEmpty()) {
                    sendSystemText(txt);
                }
                writeJson(out, "{\"status\":\"ok\"}");
                return;
            }

            if (path.startsWith("/api/open_url") || path.startsWith("/open_url")) {
                String url = null;
                if ("POST".equalsIgnoreCase(method)) {
                    String body = readPostBody(reader).trim();
                    try {
                        JSONObject j = new JSONObject(body);
                        url = j.optString("url", j.optString("u", null));
                    } catch (Exception ignored) {
                        url = body;
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
                boolean ok = url != null && !url.isEmpty() && ProjectorHardware.openUrl(activity, url);
                writeJson(out, ok ? "{\"status\":\"ok\"}" : "{\"status\":\"error\"}");
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

    private static String readPostBody(BufferedReader reader) throws IOException {
        int len = 0;
        String header;
        while ((header = reader.readLine()) != null && !header.isEmpty()) {
            if (header.toLowerCase().startsWith("content-length:")) {
                try { len = Integer.parseInt(header.substring("content-length:".length()).trim()); } catch (Exception ignored) {}
            }
        }
        char[] buf = new char[Math.max(0, Math.min(len, 16384))];
        int total = 0;
        while (total < buf.length) {
            int n = reader.read(buf, total, buf.length - total);
            if (n <= 0) break;
            total += n;
        }
        return new String(buf, 0, total);
    }

    private static String getAppleMusicLoginHtml() {
        return "<!doctype html>\n"
            + "<html lang=\"pt-BR\">\n"
            + "<head>\n"
            + "<meta charset=\"utf-8\">\n"
            + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no\">\n"
            + "<title>Apple Music Login · Paçoca OsTV</title>\n"
            + "<style>\n"
            + ":root { color-scheme: dark; }\n"
            + "* { box-sizing: border-box; -webkit-tap-highlight-color: transparent; }\n"
            + "body { margin: 0; min-height: 100vh; font-family: -apple-system, BlinkMacSystemFont, 'SF Pro Display', Roboto, sans-serif;\n"
            + "  background: radial-gradient(circle at 50% 0%, #3d121d 0%, #0d0d11 58%, #070709 100%);\n"
            + "  color: #f5f5f7; display: flex; flex-direction: column; align-items: center; padding: 28px 18px 40px; }\n"
            + ".wrap { width: 100%; max-width: 400px; }\n"
            + ".head { text-align: center; margin-bottom: 24px; }\n"
            + ".badge { display: inline-block; padding: 4px 12px; border-radius: 999px; background: rgba(250,35,59,.18);\n"
            + "  color: #ff4d63; font-size: 11px; font-weight: 700; letter-spacing: 1.1px; text-transform: uppercase; margin-bottom: 10px; }\n"
            + "h1 { margin: 0 0 6px; font-size: 26px; font-weight: 800; letter-spacing: -.4px; text-wrap: balance; }\n"
            + ".sub { margin: 0; font-size: 14px; color: #a1a1aa; line-height: 1.45; }\n"
            + ".card { background: rgba(24,24,30,.86); border: 1px solid rgba(255,255,255,.11);\n"
            + "  border-radius: 22px; padding: 22px 18px; margin-bottom: 16px; box-shadow: 0 18px 42px rgba(0,0,0,.45); }\n"
            + ".step { font-size: 12px; font-weight: 700; color: #ff4d63; letter-spacing: .8px; text-transform: uppercase; margin-bottom: 12px; }\n"
            + "label { display: block; font-size: 13px; font-weight: 600; color: #d4d4d8; margin: 12px 0 6px; }\n"
            + "input { width: 100%; height: 50px; padding: 0 14px; border-radius: 13px; border: 1.5px solid rgba(255,255,255,.14);\n"
            + "  background: rgba(10,10,14,.75); color: #fff; font-size: 16px; outline: none; transition: border-color .15s, box-shadow .15s; }\n"
            + "input:focus { border-color: #fa233b; box-shadow: 0 0 0 3px rgba(250,35,59,.25); }\n"
            + ".code-input { text-align: center; font-size: 26px; font-weight: 700; letter-spacing: 8px; font-variant-numeric: tabular-nums; height: 58px; }\n"
            + "button { width: 100%; height: 52px; margin-top: 16px; border: 0; border-radius: 14px; background: linear-gradient(135deg, #fb5c74, #fa233b);\n"
            + "  color: #fff; font-size: 16px; font-weight: 700; cursor: pointer; box-shadow: 0 8px 24px rgba(250,35,59,.35); transition: transform .12s, opacity .15s; }\n"
            + "button:active { transform: scale(.98); }\n"
            + "button.sec { background: rgba(255,255,255,.12); box-shadow: none; font-size: 14px; height: 44px; }\n"
            + ".status { margin-top: 12px; padding: 11px 14px; border-radius: 12px; font-size: 13.5px; font-weight: 600;\n"
            + "  background: rgba(48,209,88,.15); color: #4ade80; text-align: center; display: none; }\n"
            + ".status.on { display: block; }\n"
            + "</style>\n"
            + "</head>\n"
            + "<body>\n"
            + "<div class=\"wrap\">\n"
            + "  <div class=\"head\">\n"
            + "    <span class=\"badge\">Paçoca OsTV · Apple Music</span>\n"
            + "    <h1>Login Rápido na TV</h1>\n"
            + "    <p class=\"sub\">Use o preenchimento automático de senhas do seu celular para entrar no Apple Music da TV sem digitar no controle.</p>\n"
            + "  </div>\n"
            + "  <form class=\"card\" id=\"fLogin\" onsubmit=\"return sendLogin(event)\">\n"
            + "    <div class=\"step\">1. Conta Apple</div>\n"
            + "    <label for=\"em\">ID Apple (E-mail ou Telefone)</label>\n"
            + "    <input id=\"em\" name=\"username\" type=\"email\" autocomplete=\"username\" placeholder=\"seuemail@icloud.com\" required>\n"
            + "    <label for=\"pw\">Senha</label>\n"
            + "    <input id=\"pw\" name=\"password\" type=\"password\" autocomplete=\"current-password\" placeholder=\"Sua senha da Conta Apple\" required>\n"
            + "    <button type=\"submit\" id=\"bLogin\" aria-label=\"Entrar no Apple Music da TV\">Entrar no Apple Music da TV</button>\n"
            + "    <div class=\"status\" id=\"st1\"></div>\n"
            + "  </form>\n"
            + "  <form class=\"card\" id=\"f2fa\" onsubmit=\"return send2fa(event)\">\n"
            + "    <div class=\"step\">2. Código de Verificação (2FA)</div>\n"
            + "    <p class=\"sub\" style=\"font-size:13px;margin-bottom:10px\">Quando o código de 6 dígitos aparecer no seu iPhone/Mac ou SMS, digite-o abaixo:</p>\n"
            + "    <input id=\"cd\" class=\"code-input\" type=\"tel\" inputmode=\"numeric\" pattern=\"[0-9]*\" maxlength=\"6\" autocomplete=\"one-time-code\" placeholder=\"000000\">\n"
            + "    <button type=\"submit\" aria-label=\"Confirmar código de 6 dígitos na TV\">Confirmar Código na TV</button>\n"
            + "    <div class=\"status\" id=\"st2\"></div>\n"
            + "  </form>\n"
            + "  <form class=\"card\" id=\"ftok\" onsubmit=\"return sendToken(event)\">\n"
            + "    <div class=\"step\">3. Ou colar Media-User-Token (Apple Music &amp; Podcasts)</div>\n"
            + "    <p class=\"sub\" style=\"font-size:13px;margin-bottom:10px\">Cole o cookie <code>media-user-token</code> obtido no navegador em music.apple.com:</p>\n"
            + "    <input id=\"tk\" type=\"text\" placeholder=\"media-user-token...\">\n"
            + "    <button type=\"submit\">Salvar Token na TV</button>\n"
            + "    <div class=\"status\" id=\"st3\"></div>\n"
            + "  </form>\n"
            + "  <a href=\"/\" style=\"display:block;text-align:center;color:#a1a1aa;font-size:14px;text-decoration:none;margin-top:8px\">← Voltar para o Controle Remoto</a>\n"
            + "</div>\n"
            + "<script>\n"
            + "function showSt(id, msg) { var e = document.getElementById(id); e.textContent = msg; e.classList.add('on'); }\n"
            + "function sendToken(ev) {\n"
            + "  ev.preventDefault();\n"
            + "  var tk = document.getElementById('tk').value.trim();\n"
            + "  if (!tk) return false;\n"
            + "  showSt('st3', 'Salvando token na TV…');\n"
            + "  fetch('/api/applemusic/token', { method: 'POST', headers: {'Content-Type':'application/json'}, body: JSON.stringify({token: tk}) })\n"
            + "    .then(function() { showSt('st3', 'Token salvo para Apple Music e Podcasts! ✅'); });\n"
            + "  return false;\n"
            + "}\n"
            + "function sendLogin(ev) {\n"
            + "  ev.preventDefault();\n"
            + "  var em = document.getElementById('em').value.trim();\n"
            + "  var pw = document.getElementById('pw').value;\n"
            + "  showSt('st1', 'Enviando ID Apple e Senha para a TV…');\n"
            + "  fetch('/api/applemusic/login', { method: 'POST', headers: {'Content-Type':'application/json'}, body: JSON.stringify({email: em, password: pw}) })\n"
            + "    .then(function() {\n"
            + "      showSt('st1', 'Enviado para a TV! Se pedir verificação, digite os 6 dígitos abaixo.');\n"
            + "      setTimeout(function(){ document.getElementById('cd').focus(); }, 400);\n"
            + "    });\n"
            + "  return false;\n"
            + "}\n"
            + "function send2fa(ev) {\n"
            + "  if (ev) ev.preventDefault();\n"
            + "  var cd = document.getElementById('cd').value.replace(/\\D+/g, '');\n"
            + "  if (cd.length < 6) return false;\n"
            + "  showSt('st2', 'Enviando código ' + cd + ' para a TV…');\n"
            + "  fetch('/api/applemusic/2fa', { method: 'POST', headers: {'Content-Type':'application/json'}, body: JSON.stringify({code: cd}) })\n"
            + "    .then(function() { showSt('st2', 'Código confirmado na TV! ✅'); });\n"
            + "  return false;\n"
            + "}\n"
            + "document.getElementById('cd').addEventListener('input', function() {\n"
            + "  if (this.value.replace(/\\D+/g, '').length === 6) send2fa();\n"
            + "});\n"
            + "</script>\n"
            + "</body>\n"
            + "</html>";
    }

    private static void writeJson(OutputStream out, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nAccess-Control-Allow-Origin: *\r\nContent-Length: " + bytes.length + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(bytes);
        out.flush();
    }

    static void applyResolution(String preset) {
        if ("1080p".equals(preset)) sendSystemCmd("wm size 1920x1080 && wm density 320");
        else if ("720p_tv".equals(preset)) sendSystemCmd("wm size 1280x720 && wm density 213");
        else sendSystemCmd("wm size 1280x720 && wm density 250");
    }

    static void resetKeystone() {
        sendSystemCmd("setprop persist.hisi.keystone.lt 0,720 && setprop persist.hisi.keystone.rt 1280,720 && setprop persist.hisi.keystone.lb 0,0 && setprop persist.hisi.keystone.rb 1280,0 && setprop persist.hisi.keystone.update true");
    }

    public static void sendSystemText(String text) {
        RemoteDaemonBridge.sendText(text);
    }

    public static void sendSystemCmd(String cmd) {
        RemoteDaemonBridge.sendCmd(cmd);
    }

    static void launchApp(Activity activity, String name) {
        if (activity == null || name == null) return;
        String low = name.toLowerCase();
        if ("keystone".equals(low) || "projection".equals(low)) {
            activity.runOnUiThread(() -> {
                try {
                    Intent i = new Intent();
                    i.setClassName("com.htc.htcsettings", "com.htc.activity.ProjectionActivity");
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    activity.startActivity(i);
                } catch (Exception ignored) {}
            });
            return;
        }
        if ("bluetooth".equals(low)) {
            activity.runOnUiThread(() -> {
                try {
                    Intent i = new Intent();
                    i.setClassName("com.htc.htcsettings", "com.htc.activity.BluetoothSettingActivity");
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    activity.startActivity(i);
                } catch (Exception ignored) {}
            });
            return;
        }
        if ("settings".equals(low)) {
            activity.runOnUiThread(() -> {
                try {
                    Intent i = new Intent();
                    i.setClassName("com.htc.htcsettings", "com.htc.activity.HomeActivity");
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    activity.startActivity(i);
                } catch (Exception ignored) {}
            });
            return;
        }
        if (HomeApps.MUSIC_PKG.equals(low) || "music".equals(low) || "applemusic".equals(low)) {
            activity.runOnUiThread(() -> {
                try {
                    Intent homeIntent = new Intent(activity, HomeActivity.class);
                    homeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    activity.startActivity(homeIntent);
                    if (activity instanceof HomeActivity) {
                        HomeView hv = ((HomeActivity) activity).getHomeView();
                        if (hv != null) hv.openMusic();
                    }
                } catch (Exception ignored) {}
            });
            return;
        }
        if (HomeApps.PODCASTS_PKG.equals(low) || "podcasts".equals(low) || "applepodcasts".equals(low)) {
            activity.runOnUiThread(() -> {
                try {
                    Intent homeIntent = new Intent(activity, HomeActivity.class);
                    homeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    activity.startActivity(homeIntent);
                    if (activity instanceof HomeActivity) {
                        HomeView hv = ((HomeActivity) activity).getHomeView();
                        if (hv != null) hv.openPodcasts();
                    }
                } catch (Exception ignored) {}
            });
            return;
        }
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
            default:
                if (name.contains(".")) pkg = name;
                break;
        }
        if (pkg != null) {
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
        if (!RemoteDaemonBridge.sendKeyTap(keyCode)) {
            fallbackInAppKey(keyCode);
        }
    }

    static void fallbackInAppKey(int keyCode) {
        WebRemoteServer srv = instance;
        if (srv != null && srv.activity != null) {
            srv.activity.runOnUiThread(() -> {
                if (srv.keyDispatcher != null) {
                    srv.keyDispatcher.dispatch(keyCode);
                } else {
                    injectKey(srv.activity, keyCode);
                }
            });
        }
    }

    static int mapKey(String key) {
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
            return html.replace("%IP%", ip != null ? ip : "192.168.1.7");
        } catch (Exception e) {
            return "<html><body>Erro ao carregar controle</body></html>";
        }
    }

    static String getLocalIpAddress(Context context) {
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

        return "192.168.1.7";
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
