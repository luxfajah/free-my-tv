package io.freemytv;

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioManager;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.provider.Settings;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Hardware control for HY300Pro / Allwinner H713 projectors:
 * - Fan always at maximum speed + SoC thermal sensor readout
 * - Installation / Projection Mode (Frontal/Teto/Traseiro) via ITvServer HIDL (transact 127/128)
 * - Auto-Keystone toggle (persist.sys.tpryauto)
 * - Picture Profiles & Color Temperature via ITvServer HIDL PQ HAL (transact 97..102)
 * - Background AirPlay/DLNA receiver auto-start (AirPin PRO, hidden from Home/Dock)
 * - Bluetooth A2DP & Internal Speaker audio routing
 */
final class ProjectorHardware {
    private ProjectorHardware() {}

    private static final String TV_SERVER_IFACE = "vendor.aw.homlet.tvsystem.tvserver@1.0::ITvServer";
    private static final int E_AW_PANEL_CONFIG_MIRROR = 2;
    private static final int PQ_COLOR_TEMP = 12;

    static final String[] PROJECTION_MODES = {
            "Frontal (Mesa)",
            "Frontal (Teto)",
            "Traseiro (Mesa)",
            "Traseiro (Teto)"
    };

    static final String[] COLOR_TEMPS = {
            "Padrão",
            "Quente",
            "Frio"
    };

    static final String[] DEFAULT_PICTURE_MODES = {
            "Standard", "Vivid", "Movie", "Soft", "User"
    };

    // ─── Fan & Thermal ───────────────────────────────────────────────────────────

    /** Forces the HY300Pro fan to maximum speed continuously. */
    static void enforceMaxFan() {
        boolean ok = setProp("persist.sys.prj.fan_speed", "100");
        ok &= setProp("persist.sys.htc_fanctrl", "2");
        ok &= setProp("persist.sys.enablefans", "1");
        if (!ok) {
            sendDaemonCmd("setprop persist.sys.prj.fan_speed 100; setprop persist.sys.htc_fanctrl 2; setprop persist.sys.enablefans 1");
        }
        writeSysfs("/sys/bus/platform/drivers/pwm-fan/fan.25/pwm_fan", "100");
    }

    /** Returns SoC temperature in Celsius, or -1 if unavailable. */
    static int cpuTempC() {
        String[] paths = {
                "/sys/class/thermal/thermal_zone0/temp",
                "/sys/class/thermal/thermal_zone1/temp"
        };
        for (String p : paths) {
            String raw = readFileFirstLine(p);
            if (raw != null && !raw.isEmpty()) {
                try {
                    int val = Integer.parseInt(raw.trim());
                    return val > 1000 ? val / 1000 : val;
                } catch (NumberFormatException ignored) {}
            }
        }
        return -1;
    }

    // ─── Projection Installation Mode & Auto-Keystone ────────────────────────────

    static int getProjectionMode() {
        Integer hal = tvServerGetInt(128, E_AW_PANEL_CONFIG_MIRROR);
        if (hal != null && hal >= 0 && hal < PROJECTION_MODES.length) return hal;
        try {
            int p = Integer.parseInt(readProp("persist.sys.panelvalue", "0").trim());
            if (p >= 0 && p < PROJECTION_MODES.length) return p;
        } catch (NumberFormatException ignored) {}
        return 0;
    }

    static String projectionModeLabel(int mode) {
        if (mode < 0 || mode >= PROJECTION_MODES.length) return PROJECTION_MODES[0];
        return PROJECTION_MODES[mode];
    }

    static boolean setProjectionMode(Context c, int mode) {
        int clamped = Math.max(0, Math.min(PROJECTION_MODES.length - 1, mode));
        boolean propOk = setProp("persist.sys.panelvalue", String.valueOf(clamped));
        if (!propOk) {
            sendDaemonCmd("setprop persist.sys.panelvalue " + clamped);
        }
        boolean halOk = tvServerSetInt(127, E_AW_PANEL_CONFIG_MIRROR, clamped);
        try {
            Intent i = new Intent("android.intent.projective_mode");
            i.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            c.sendBroadcast(i);
        } catch (Throwable ignored) {}
        AppLog.w("ProjectorHardware.setProjectionMode(" + clamped + "): halOk=" + halOk + " propOk=" + propOk);
        return halOk || propOk;
    }

    static boolean isAutoKeystone() {
        String v = readProp("persist.sys.tpryauto", "0").trim();
        return "1".equals(v) || "true".equalsIgnoreCase(v);
    }

    static boolean setAutoKeystone(Context c, boolean enabled) {
        String val = enabled ? "1" : "0";
        boolean propOk = setProp("persist.sys.tpryauto", val);
        if (!propOk) {
            sendDaemonCmd("setprop persist.sys.tpryauto " + val);
        }
        try {
            Intent i = new Intent("android.intent.projective_mode");
            i.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            c.sendBroadcast(i);
        } catch (Throwable ignored) {}
        AppLog.w("ProjectorHardware.setAutoKeystone(" + enabled + "): propOk=" + propOk);
        return propOk;
    }

    // ─── Picture Profiles & Color Temperature (Allwinner ITvServer PQ HAL) ───────

    static List<String> getPictureModes() {
        List<String> modes = tvServerGetStringVector(102);
        if (modes != null && !modes.isEmpty()) return modes;
        return new ArrayList<>(Arrays.asList(DEFAULT_PICTURE_MODES));
    }

    static String getPictureMode() {
        String cur = tvServerGetString(100);
        if (cur != null && !cur.isEmpty()) return cur;
        return Prefs.getString("pq_picture_mode", "Standard");
    }

    static boolean setPictureMode(String mode) {
        if (mode == null || mode.isEmpty()) return false;
        Prefs.putString("pq_picture_mode", mode);
        boolean ok = tvServerSetString(99, mode);
        AppLog.w("ProjectorHardware.setPictureMode(" + mode + "): ok=" + ok);
        return ok;
    }

    static int getColorTemp() {
        Integer v = tvServerGetInt(98, PQ_COLOR_TEMP);
        if (v != null && v >= 0 && v < COLOR_TEMPS.length) return v;
        try {
            int saved = Integer.parseInt(Prefs.getString("pq_color_temp", "0").trim());
            return Math.max(0, Math.min(COLOR_TEMPS.length - 1, saved));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    static boolean setColorTemp(int idx) {
        int clamped = Math.max(0, Math.min(COLOR_TEMPS.length - 1, idx));
        Prefs.putString("pq_color_temp", String.valueOf(clamped));
        boolean ok = tvServerSetInt(97, PQ_COLOR_TEMP, clamped);
        AppLog.w("ProjectorHardware.setColorTemp(" + clamped + "): ok=" + ok);
        return ok;
    }

    static String pictureModeLabel(String raw) {
        if (raw == null) return "Padrão";
        String k = raw.trim().toLowerCase();
        if (k.contains("standard") || k.contains("normal")) return "Padrão";
        if (k.contains("vivid") || k.contains("dynamic") || k.contains("bright")) return "Vívido";
        if (k.contains("movie") || k.contains("cinema") || k.contains("theater")) return "Cinema";
        if (k.contains("soft") || k.contains("mild") || k.contains("warm")) return "Suave";
        if (k.contains("game")) return "Jogo";
        if (k.contains("sport")) return "Esporte";
        if (k.contains("user") || k.contains("custom")) return "Personalizado";
        if (k.contains("monitor") || k.contains("pc") || k.contains("computer")) return "Computador";
        if (k.equals("hdr") || k.contains("hdr")) return "HDR";
        return raw;
    }

    static String pictureModeDesc(String raw) {
        if (raw == null) return "Equilíbrio natural de cores e brilho.";
        String k = raw.trim().toLowerCase();
        if (k.contains("standard") || k.contains("normal")) return "Equilíbrio natural para uso diário.";
        if (k.contains("vivid") || k.contains("dynamic") || k.contains("bright")) return "Cores intensas e contraste alto para ambientes claros.";
        if (k.contains("movie") || k.contains("cinema") || k.contains("theater")) return "Tons quentes e pretos profundos para quarto escuro.";
        if (k.contains("soft") || k.contains("mild")) return "Imagem suave e confortável para assistir à noite.";
        if (k.contains("game")) return "Contraste nítido e resposta rápida para jogos.";
        if (k.contains("sport")) return "Cores vibrantes com realce de movimento.";
        if (k.contains("computer") || k.contains("monitor") || k.contains("pc")) return "Nitidez de texto e cores precisas para PC/Mac.";
        if (k.contains("hdr")) return "Realce de alcance dinâmico e brilho para conteúdo HDR.";
        if (k.contains("user") || k.contains("custom")) return "Perfil personalizado do sistema.";
        return "Perfil de imagem do hardware Allwinner PQ.";
    }

    // ─── Apple Music Profile Avatar Cache ────────────────────────────────────────

    private static volatile Bitmap cachedAvatar;
    private static volatile long cachedAvatarMtime = -1;

    static File avatarFile(Context c) {
        return new File(c.getApplicationContext().getFilesDir(), "apple_music_avatar.png");
    }

    static Bitmap profileAvatar(Context c) {
        try {
            File f = avatarFile(c);
            if (f.exists() && f.length() > 100) {
                long m = f.lastModified();
                if (cachedAvatar != null && cachedAvatarMtime == m) return cachedAvatar;
                Bitmap bm = BitmapFactory.decodeFile(f.getAbsolutePath());
                if (bm != null) {
                    cachedAvatar = bm;
                    cachedAvatarMtime = m;
                    return bm;
                }
            }
        } catch (Throwable ignored) {}
        return cachedAvatar;
    }

    static void downloadProfileAvatar(Context c, String url) {
        if (url == null || url.trim().isEmpty()) return;
        final String cleanUrl = url.trim().replace("{w}", "256").replace("{h}", "256").replace("{f}", "png");
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(cleanUrl).openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0");
                if (conn.getResponseCode() == 200) {
                    try (InputStream in = conn.getInputStream();
                         FileOutputStream out = new FileOutputStream(avatarFile(app))) {
                        byte[] buf = new byte[8192];
                        int r;
                        while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
                        out.flush();
                    }
                    cachedAvatar = null;
                    cachedAvatarMtime = -1;
                    AppLog.w("ProjectorHardware: saved Apple Music profile avatar from " + cleanUrl);
                }
            } catch (Throwable t) {
                AppLog.w("ProjectorHardware: avatar download failed: " + t.getMessage());
            } finally {
                if (conn != null) conn.disconnect();
            }
        }).start();
    }

    // ─── AirPlay / DLNA Background Receiver ──────────────────────────────────────

    static void ensureAirPlayRunning(Context c) {
        try {
            Intent svc = new Intent();
            svc.setComponent(new ComponentName("com.waxrain.airplaydmr2", "com.waxrain.airplaydmr.WaxPlayService"));
            c.startService(svc);
        } catch (Throwable ignored) {}
    }

    // ─── Smart URL Router ("Enviar para o Projetor") ────────────────────────────

    static boolean openUrl(Context c, String raw) {
        if (raw == null) return false;
        String url = extractUrl(raw.trim());
        if (url == null || url.isEmpty()) return false;
        RemoteDaemonBridge.sendKeyTap(android.view.KeyEvent.KEYCODE_WAKEUP);
        String lower = url.toLowerCase();
        Context app = c.getApplicationContext();

        try {
            if (lower.contains("music.apple.com")) {
                Intent i = new Intent(app, HomeActivity.class);
                i.putExtra("open_music", true);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                app.startActivity(i);
                return true;
            }
            if (lower.contains("youtube.com") || lower.contains("youtu.be")) {
                if (tryViewInPackage(app, url, "org.smarttube.stable")) return true;
                if (tryViewInPackage(app, url, "com.liskovsoft.smarttubetv.beta")) return true;
            }
            if (lower.contains("twitch.tv")) {
                if (tryViewInPackage(app, url, "com.fgl27.twitch")) return true;
            }
            if (lower.startsWith("stremio://") || lower.startsWith("magnet:") || lower.contains("strem.io") || lower.contains("stremio.com")) {
                if (tryViewInPackage(app, url, "com.stremio.one")) return true;
            }
            Intent view = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url));
            view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            app.startActivity(view);
            return true;
        } catch (Throwable t) {
            AppLog.w("ProjectorHardware.openUrl failed for " + url + ": " + t.getMessage());
            return false;
        }
    }

    private static boolean tryViewInPackage(Context c, String url, String pkg) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url));
            i.setPackage(pkg);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (c.getPackageManager().resolveActivity(i, 0) != null) {
                c.startActivity(i);
                return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static String extractUrl(String text) {
        if (text.isEmpty()) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(https?://\\S+|stremio://\\S+|magnet:\\?\\S+)", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(text);
        if (m.find()) return m.group(1);
        if (!text.contains(" ") && text.contains(".")) return "https://" + text;
        return null;
    }

    // ─── Wi-Fi & HDMI Helpers ────────────────────────────────────────────────────

    static String wifiSsid(Context c) {
        try {
            WifiManager wm = (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null && wm.isWifiEnabled()) {
                WifiInfo info = wm.getConnectionInfo();
                if (info != null && info.getSSID() != null) {
                    String s = info.getSSID().replace("\"", "").trim();
                    if (!s.isEmpty() && !"<unknown ssid>".equalsIgnoreCase(s)) return s;
                }
                return "Conectado";
            }
            return "Desligado";
        } catch (Throwable t) {
            return "Wi-Fi";
        }
    }

    static boolean openHdmi(Context c) {
        ComponentName[] targets = {
                new ComponentName("com.softwinner.awlivetv", "com.softwinner.awlivetv.MainActivity"),
                new ComponentName("com.softwinner.awsource", "com.softwinner.awsource.MainActivity")
        };
        for (ComponentName cn : targets) {
            try {
                Intent i = new Intent(Intent.ACTION_MAIN).setComponent(cn).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (c.getPackageManager().resolveActivity(i, 0) != null) {
                    c.startActivity(i);
                    return true;
                }
            } catch (Throwable ignored) {}
        }
        return false;
    }

    // ─── Audio Output / Bluetooth A2DP Manager ───────────────────────────────────

    static final class AudioTarget {
        final String name;
        final String address; // null for internal speaker
        final boolean isBluetooth;
        final boolean connected;
        final BluetoothDevice device;

        AudioTarget(String name, String address, boolean isBluetooth, boolean connected, BluetoothDevice device) {
            this.name = name;
            this.address = address;
            this.isBluetooth = isBluetooth;
            this.connected = connected;
            this.device = device;
        }
    }

    interface AudioCallback {
        void onDevices(List<AudioTarget> targets, String activeName, boolean btActive);
    }

    static void queryAudioTargets(Context c, AudioCallback cb) {
        final BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            List<AudioTarget> list = new ArrayList<>();
            list.add(new AudioTarget("Alto-falante do Projetor", null, false, true, null));
            cb.onDevices(list, "Alto-falante", false);
            return;
        }
        try {
            adapter.getProfileProxy(c.getApplicationContext(), new BluetoothProfile.ServiceListener() {
                @Override public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    List<AudioTarget> list = new ArrayList<>();
                    List<BluetoothDevice> activeList = new ArrayList<>();
                    try {
                        activeList = proxy.getConnectedDevices();
                    } catch (Throwable ignored) {}
                    BluetoothDevice connectedBt = activeList != null && !activeList.isEmpty() ? activeList.get(0) : null;
                    boolean btActive = connectedBt != null;
                    list.add(new AudioTarget("Alto-falante do Projetor", null, false, !btActive, null));
                    try {
                        Set<BluetoothDevice> bonded = adapter.getBondedDevices();
                        if (bonded != null) {
                            for (BluetoothDevice d : bonded) {
                                boolean isConn = connectedBt != null && d.getAddress().equals(connectedBt.getAddress());
                                String dName = d.getName();
                                if (dName == null || dName.trim().isEmpty()) dName = d.getAddress();
                                list.add(new AudioTarget(dName, d.getAddress(), true, isConn, d));
                            }
                        }
                    } catch (Throwable ignored) {}
                    String activeName = btActive ? (connectedBt.getName() != null ? connectedBt.getName() : "Bluetooth") : "Alto-falante";
                    try { adapter.closeProfileProxy(BluetoothProfile.A2DP, proxy); } catch (Throwable ignored) {}
                    cb.onDevices(list, activeName, btActive);
                }

                @Override public void onServiceDisconnected(int profile) {}
            }, BluetoothProfile.A2DP);
        } catch (Throwable t) {
            List<AudioTarget> list = new ArrayList<>();
            list.add(new AudioTarget("Alto-falante do Projetor", null, false, true, null));
            cb.onDevices(list, "Alto-falante", false);
        }
    }

    static void selectAudioTarget(Context c, AudioTarget target, Runnable onDone) {
        final BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            setSystemAudioChannel(c, "AUDIO_SPEAKER");
            if (onDone != null) onDone.run();
            return;
        }
        try {
            adapter.getProfileProxy(c.getApplicationContext(), new BluetoothProfile.ServiceListener() {
                @Override public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    try {
                        BluetoothA2dp a2dp = (BluetoothA2dp) proxy;
                        List<BluetoothDevice> connected = a2dp.getConnectedDevices();
                        Method disconnect = BluetoothA2dp.class.getMethod("disconnect", BluetoothDevice.class);
                        Method connect = BluetoothA2dp.class.getMethod("connect", BluetoothDevice.class);
                        if (!target.isBluetooth) {
                            // Switch to Internal Speaker: disconnect any active A2DP devices
                            if (connected != null) {
                                for (BluetoothDevice d : connected) {
                                    try { disconnect.invoke(a2dp, d); } catch (Throwable ignored) {}
                                }
                            }
                            setSystemAudioChannel(c, "AUDIO_SPEAKER");
                        } else if (target.device != null) {
                            // Disconnect other A2DP devices first, then connect target
                            if (connected != null) {
                                for (BluetoothDevice d : connected) {
                                    if (!d.getAddress().equals(target.device.getAddress())) {
                                        try { disconnect.invoke(a2dp, d); } catch (Throwable ignored) {}
                                    }
                                }
                            }
                            connect.invoke(a2dp, target.device);
                            setSystemAudioChannel(c, "AUDIO_A2DP");
                        }
                    } catch (Throwable t) {
                        AppLog.w("selectAudioTarget failed: " + t.getMessage());
                    } finally {
                        try { adapter.closeProfileProxy(BluetoothProfile.A2DP, proxy); } catch (Throwable ignored) {}
                        if (onDone != null) onDone.run();
                    }
                }

                @Override public void onServiceDisconnected(int profile) {}
            }, BluetoothProfile.A2DP);
        } catch (Throwable t) {
            if (onDone != null) onDone.run();
        }
    }

    private static void setSystemAudioChannel(Context c, String channel) {
        try {
            Settings.System.putString(c.getContentResolver(), "audio_output_channel", channel);
        } catch (Throwable t) {
            sendDaemonCmd("settings put system audio_output_channel " + channel);
        }
    }

    // ─── HIDL ITvServer Reflection Helpers ───────────────────────────────────────

    private static Object getTvServerBinder() {
        try {
            Class<?> hwBinder = Class.forName("android.os.HwBinder");
            Method getService = hwBinder.getMethod("getService", String.class, String.class);
            return getService.invoke(null, TV_SERVER_IFACE, "default");
        } catch (Throwable t) {
            return null;
        }
    }

    private static Integer tvServerGetInt(int code, int arg0) {
        Object req = null, rep = null;
        try {
            Object binder = getTvServerBinder();
            if (binder == null) return null;
            Class<?> hp = Class.forName("android.os.HwParcel");
            req = hp.getDeclaredConstructor().newInstance();
            rep = hp.getDeclaredConstructor().newInstance();
            hp.getMethod("writeInterfaceToken", String.class).invoke(req, TV_SERVER_IFACE);
            hp.getMethod("writeInt32", int.class).invoke(req, arg0);
            Class<?> ihb = Class.forName("android.os.IHwBinder");
            ihb.getMethod("transact", int.class, hp, hp, int.class).invoke(binder, code, req, rep, 0);
            hp.getMethod("verifySuccess").invoke(rep);
            hp.getMethod("releaseTemporaryStorage").invoke(req);
            return (Integer) hp.getMethod("readInt32").invoke(rep);
        } catch (Throwable t) {
            return null;
        } finally {
            releaseHwParcel(rep);
        }
    }

    private static boolean tvServerSetInt(int code, int arg0, int arg1) {
        Object req = null, rep = null;
        try {
            Object binder = getTvServerBinder();
            if (binder == null) return false;
            Class<?> hp = Class.forName("android.os.HwParcel");
            req = hp.getDeclaredConstructor().newInstance();
            rep = hp.getDeclaredConstructor().newInstance();
            hp.getMethod("writeInterfaceToken", String.class).invoke(req, TV_SERVER_IFACE);
            hp.getMethod("writeInt32", int.class).invoke(req, arg0);
            hp.getMethod("writeInt32", int.class).invoke(req, arg1);
            Class<?> ihb = Class.forName("android.os.IHwBinder");
            ihb.getMethod("transact", int.class, hp, hp, int.class).invoke(binder, code, req, rep, 0);
            hp.getMethod("verifySuccess").invoke(rep);
            hp.getMethod("releaseTemporaryStorage").invoke(req);
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            releaseHwParcel(rep);
        }
    }

    private static String tvServerGetString(int code) {
        Object req = null, rep = null;
        try {
            Object binder = getTvServerBinder();
            if (binder == null) return null;
            Class<?> hp = Class.forName("android.os.HwParcel");
            req = hp.getDeclaredConstructor().newInstance();
            rep = hp.getDeclaredConstructor().newInstance();
            hp.getMethod("writeInterfaceToken", String.class).invoke(req, TV_SERVER_IFACE);
            Class<?> ihb = Class.forName("android.os.IHwBinder");
            ihb.getMethod("transact", int.class, hp, hp, int.class).invoke(binder, code, req, rep, 0);
            hp.getMethod("verifySuccess").invoke(rep);
            hp.getMethod("releaseTemporaryStorage").invoke(req);
            return (String) hp.getMethod("readString").invoke(rep);
        } catch (Throwable t) {
            return null;
        } finally {
            releaseHwParcel(rep);
        }
    }

    private static boolean tvServerSetString(int code, String arg0) {
        Object req = null, rep = null;
        try {
            Object binder = getTvServerBinder();
            if (binder == null) return false;
            Class<?> hp = Class.forName("android.os.HwParcel");
            req = hp.getDeclaredConstructor().newInstance();
            rep = hp.getDeclaredConstructor().newInstance();
            hp.getMethod("writeInterfaceToken", String.class).invoke(req, TV_SERVER_IFACE);
            hp.getMethod("writeString", String.class).invoke(req, arg0);
            Class<?> ihb = Class.forName("android.os.IHwBinder");
            ihb.getMethod("transact", int.class, hp, hp, int.class).invoke(binder, code, req, rep, 0);
            hp.getMethod("verifySuccess").invoke(rep);
            hp.getMethod("releaseTemporaryStorage").invoke(req);
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            releaseHwParcel(rep);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> tvServerGetStringVector(int code) {
        Object req = null, rep = null;
        try {
            Object binder = getTvServerBinder();
            if (binder == null) return null;
            Class<?> hp = Class.forName("android.os.HwParcel");
            req = hp.getDeclaredConstructor().newInstance();
            rep = hp.getDeclaredConstructor().newInstance();
            hp.getMethod("writeInterfaceToken", String.class).invoke(req, TV_SERVER_IFACE);
            Class<?> ihb = Class.forName("android.os.IHwBinder");
            ihb.getMethod("transact", int.class, hp, hp, int.class).invoke(binder, code, req, rep, 0);
            hp.getMethod("verifySuccess").invoke(rep);
            hp.getMethod("releaseTemporaryStorage").invoke(req);
            return (List<String>) hp.getMethod("readStringVector").invoke(rep);
        } catch (Throwable t) {
            return null;
        } finally {
            releaseHwParcel(rep);
        }
    }

    private static void releaseHwParcel(Object parcel) {
        if (parcel == null) return;
        try {
            parcel.getClass().getMethod("release").invoke(parcel);
        } catch (Throwable ignored) {}
    }

    // ─── SystemProperties & Sysfs Helpers ────────────────────────────────────────

    private static String readProp(String key, String def) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method get = sp.getMethod("get", String.class, String.class);
            String v = (String) get.invoke(null, key, def);
            return v != null ? v : def;
        } catch (Throwable t) {
            return def;
        }
    }

    private static boolean setProp(String key, String val) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method set = sp.getMethod("set", String.class, String.class);
            set.invoke(null, key, val);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void writeSysfs(String path, String val) {
        File f = new File(path);
        if (!f.exists() || !f.canWrite()) return;
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(val.getBytes(StandardCharsets.UTF_8));
            fos.flush();
        } catch (Throwable ignored) {}
    }

    private static String readFileFirstLine(String path) {
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            return br.readLine();
        } catch (Throwable t) {
            return null;
        }
    }

    private static void sendDaemonCmd(final String cmd) {
        new Thread(() -> {
            try (Socket s = new Socket(InetAddress.getByName("127.0.0.1"), SystemRemoteDaemon.PORT);
                 OutputStream os = s.getOutputStream()) {
                os.write(("CMD:" + cmd + "\n").getBytes(StandardCharsets.UTF_8));
                os.flush();
            } catch (Throwable ignored) {}
        }).start();
    }
}
