package com.hy300.remote;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Native GitHub Releases OTA self-updater using Android PackageInstaller.Session
 * (zero FileProvider needed; opens native "Atualizar este app?" confirmation dialog).
 */
public class OtaUpdater {
    public static final String RELEASES_API_URL =
            "https://api.github.com/repos/luxfajah/hy300-remote-ios/releases/latest";
    public static final String FALLBACK_APK_URL =
            "https://github.com/luxfajah/hy300-remote-ios/releases/latest/download/HY300Remote.apk";
    public static final String ACTION_INSTALL_RESULT =
            "com.hy300.remote.OTA_INSTALL_RESULT";

    public interface CheckCallback {
        void onCheckResult(JSONObject result);
    }

    public interface ProgressCallback {
        void onProgress(String stage, int percent, String message);
    }

    private final Context appContext;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean isInstalling = new AtomicBoolean(false);

    public OtaUpdater(Context context) {
        this.appContext = context.getApplicationContext();
    }

    public String getCurrentVersionName() {
        try {
            PackageInfo pi = appContext.getPackageManager().getPackageInfo(appContext.getPackageName(), 0);
            if (pi != null && pi.versionName != null && !pi.versionName.isEmpty()) {
                return pi.versionName;
            }
        } catch (Exception ignored) {}
        return "1.0.6";
    }

    @SuppressWarnings("deprecation")
    public long getCurrentVersionCode() {
        try {
            PackageInfo pi = appContext.getPackageManager().getPackageInfo(appContext.getPackageName(), 0);
            if (pi != null) {
                return Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
            }
        } catch (Exception ignored) {}
        return 6;
    }

    public void checkForUpdate(final CheckCallback cb) {
        executor.execute(() -> {
            JSONObject out = new JSONObject();
            String currentVer = getCurrentVersionName();
            long currentBuild = getCurrentVersionCode();
            try {
                out.put("event", "ota_check");
                out.put("currentVersion", currentVer);
                out.put("currentBuild", currentBuild);
                out.put("fallbackUrl", FALLBACK_APK_URL);

                HttpURLConnection conn = openConnectionWithRedirects(RELEASES_API_URL, true);
                try {
                    int code = conn.getResponseCode();
                    if (code < 200 || code >= 300) {
                        out.put("ok", false);
                        out.put("hasUpdate", false);
                        out.put("error", "GitHub HTTP " + code);
                        cb.onCheckResult(out);
                        return;
                    }

                    BufferedReader br = new BufferedReader(
                            new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) {
                        sb.append(line);
                    }
                    br.close();

                    JSONObject rel = new JSONObject(sb.toString());
                    String tag = rel.optString("tag_name", "").trim();
                    String latestVer = stripLeadingV(tag);
                    String notes = rel.optString("body", "").trim();
                    String apkUrl = FALLBACK_APK_URL;

                    JSONArray assets = rel.optJSONArray("assets");
                    if (assets != null) {
                        for (int i = 0; i < assets.length(); i++) {
                            JSONObject a = assets.optJSONObject(i);
                            if (a == null) continue;
                            String name = a.optString("name", "").toLowerCase();
                            String dl = a.optString("browser_download_url", "").trim();
                            if (name.endsWith(".apk") && !dl.isEmpty()) {
                                apkUrl = dl;
                                break;
                            }
                        }
                    }

                    boolean hasUpdate = !latestVer.isEmpty() && compareSemVer(latestVer, currentVer) > 0;
                    out.put("ok", true);
                    out.put("hasUpdate", hasUpdate);
                    out.put("latestVersion", latestVer.isEmpty() ? currentVer : latestVer);
                    out.put("tagName", tag);
                    out.put("notes", notes);
                    out.put("apkUrl", apkUrl);
                    cb.onCheckResult(out);
                } finally {
                    conn.disconnect();
                }
            } catch (Exception e) {
                try {
                    out.put("ok", false);
                    out.put("hasUpdate", false);
                    out.put("error", "Sem conexão com GitHub");
                } catch (Exception ignored) {}
                cb.onCheckResult(out);
            }
        });
    }

    public void downloadAndInstall(final Activity activity, final String apkUrl, final ProgressCallback cb) {
        if (activity == null) return;

        // Check unknown app sources permission on Android 8.0+ (API 26+)
        if (Build.VERSION.SDK_INT >= 26) {
            if (!activity.getPackageManager().canRequestPackageInstalls()) {
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + activity.getPackageName()));
                    activity.startActivity(intent);
                } catch (Exception ignored) {}
                cb.onProgress("permission_required", 0,
                        "Ative 'Permitir desta fonte' nos Ajustes e toque em Instalar novamente.");
                return;
            }
        }

        if (!isInstalling.compareAndSet(false, true)) {
            cb.onProgress("downloading", 0, "Download já em andamento…");
            return;
        }

        final String targetUrl = (apkUrl == null || apkUrl.trim().isEmpty()) ? FALLBACK_APK_URL : apkUrl.trim();

        executor.execute(() -> {
            PackageInstaller.Session session = null;
            HttpURLConnection conn = null;
            try {
                cb.onProgress("downloading", 2, "Conectando ao GitHub…");
                conn = openConnectionWithRedirects(targetUrl, false);
                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) {
                    cb.onProgress("error", 0, "Erro ao baixar APK (HTTP " + code + ")");
                    return;
                }

                long contentLength = conn.getContentLengthLong();
                PackageInstaller installer = appContext.getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams params =
                        new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                if (contentLength > 0) {
                    params.setSize(contentLength);
                }

                int sessionId = installer.createSession(params);
                session = installer.openSession(sessionId);

                try (InputStream in = conn.getInputStream();
                     OutputStream out = session.openWrite("hy300_ota.apk", 0, contentLength > 0 ? contentLength : -1)) {
                    byte[] buf = new byte[16384];
                    long downloaded = 0;
                    int lastPct = 2;
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        out.write(buf, 0, n);
                        downloaded += n;
                        if (contentLength > 0) {
                            int pct = (int) Math.min(98, Math.max(5, (downloaded * 95L) / contentLength));
                            if (pct >= lastPct + 3 || pct == 98) {
                                lastPct = pct;
                                cb.onProgress("downloading", pct, "Baixando APK… " + pct + "%");
                            }
                        }
                    }
                    session.fsync(out);
                }

                cb.onProgress("committing", 99, "Preparando instalador do Android…");

                Intent resultIntent = new Intent(ACTION_INSTALL_RESULT);
                resultIntent.setPackage(appContext.getPackageName());
                int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                if (Build.VERSION.SDK_INT >= 31) {
                    flags |= PendingIntent.FLAG_MUTABLE;
                }
                PendingIntent pi = PendingIntent.getBroadcast(appContext, sessionId, resultIntent, flags);
                session.commit(pi.getIntentSender());
                session.close();
                session = null;
            } catch (Exception e) {
                if (session != null) {
                    try { session.abandon(); } catch (Exception ignored) {}
                    try { session.close(); } catch (Exception ignored) {}
                }
                cb.onProgress("error", 0, "Falha na atualização: " + e.getMessage());
            } finally {
                if (conn != null) conn.disconnect();
                isInstalling.set(false);
            }
        });
    }

    private HttpURLConnection openConnectionWithRedirects(String initialUrl, boolean isApiJson) throws Exception {
        String current = initialUrl;
        for (int hops = 0; hops < 6; hops++) {
            URL url = new URL(current);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(20000);
            conn.setRequestProperty("User-Agent", "HY300Remote-Android");
            if (isApiJson) {
                conn.setRequestProperty("Accept", "application/vnd.github+json");
            }
            int code = conn.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String loc = conn.getHeaderField("Location");
                conn.disconnect();
                if (loc == null || loc.isEmpty()) {
                    throw new Exception("Redirect sem destino");
                }
                current = new URL(url, loc).toString();
                continue;
            }
            return conn;
        }
        throw new Exception("Muitos redirecionamentos");
    }

    private static String stripLeadingV(String s) {
        if (s == null) return "";
        String t = s.trim();
        if (t.startsWith("v") || t.startsWith("V")) {
            return t.substring(1).trim();
        }
        return t;
    }

    public static int compareSemVer(String a, String b) {
        String[] pa = stripLeadingV(a).split("[^0-9]+");
        String[] pb = stripLeadingV(b).split("[^0-9]+");
        int len = Math.max(pa.length, pb.length);
        for (int i = 0; i < len; i++) {
            int na = (i < pa.length && !pa[i].isEmpty()) ? parseSafeInt(pa[i]) : 0;
            int nb = (i < pb.length && !pb[i].isEmpty()) ? parseSafeInt(pb[i]) : 0;
            if (na != nb) return Integer.compare(na, nb);
        }
        return 0;
    }

    private static int parseSafeInt(String s) {
        try { return Integer.parseInt(s); } catch (Exception e) { return 0; }
    }

    public void shutdown() {
        executor.shutdownNow();
    }
}
