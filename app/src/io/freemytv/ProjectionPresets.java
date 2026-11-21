package io.freemytv;

import android.content.Context;
import android.os.IBinder;
import android.os.Parcel;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Saved projection positions (keystone + digital zoom / overscan) for HY300Pro / Allwinner H713 projectors.
 * Reads live values from persist.display.keystone_* and applies presets via SystemProperties + SurfaceFlinger (1050).
 */
final class ProjectionPresets {
    private ProjectionPresets() {}

    private static final String KEY_LIST = "projection_presets_v1";
    private static final String KEY_ACTIVE = "projection_active_v1";
    private static final String[] CORNER_PROPS = {
            "persist.display.keystone_lbx",
            "persist.display.keystone_lby",
            "persist.display.keystone_ltx",
            "persist.display.keystone_lty",
            "persist.display.keystone_rtx",
            "persist.display.keystone_rty",
            "persist.display.keystone_rbx",
            "persist.display.keystone_rby",
    };

    static final class Preset {
        final String id;
        final String name;
        final int[] corners;   // lbx, lby, ltx, lty, rtx, rty, rbx, rby (0..1000)
        final String overscan; // e.g. "overscan 100,100,100,100"

        Preset(String id, String name, int[] corners, String overscan) {
            this.id = id;
            this.name = name;
            this.corners = corners != null && corners.length == 8 ? corners.clone() : new int[8];
            this.overscan = overscan != null && !overscan.isEmpty() ? overscan : "overscan 100,100,100,100";
        }

        String summary() {
            boolean flat = true;
            for (int v : corners) if (v != 0) { flat = false; break; }
            int zoom = parseZoom(overscan);
            if (flat) return "Zoom " + zoom + "% · Reto";
            return "Zoom " + zoom + "% · Keystone";
        }
    }

    static List<Preset> list() {
        List<Preset> out = new ArrayList<>();
        String raw = Prefs.getString(KEY_LIST, null);
        if (raw != null && !raw.isEmpty()) {
            try {
                JSONArray arr = new JSONArray(raw);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    int[] c = new int[8];
                    JSONArray ca = o.optJSONArray("corners");
                    if (ca != null) {
                        for (int j = 0; j < 8 && j < ca.length(); j++) c[j] = ca.optInt(j, 0);
                    }
                    out.add(new Preset(
                            o.optString("id", "p" + i),
                            o.optString("name", "Posição " + (i + 1)),
                            c,
                            o.optString("overscan", "overscan 100,100,100,100")
                    ));
                }
            } catch (JSONException ignored) {
            }
        }
        if (out.isEmpty()) {
            // Default saved preset: Desktop PC (Zoom 100%, Keystone 0)
            Preset desktop = new Preset("desktop_pc", "Desktop PC",
                    new int[] {0, 0, 0, 0, 0, 0, 0, 0}, "overscan 100,100,100,100");
            out.add(desktop);
            saveAll(out);
            Prefs.putString(KEY_ACTIVE, desktop.id);
        }
        return out;
    }

    static String activeId() {
        String id = Prefs.getString(KEY_ACTIVE, null);
        if (id != null && !id.isEmpty()) return id;
        List<Preset> all = list();
        return all.isEmpty() ? "" : all.get(0).id;
    }

    static String activeName() {
        String id = activeId();
        for (Preset p : list()) {
            if (p.id.equals(id)) return p.name;
        }
        List<Preset> all = list();
        return all.isEmpty() ? "Desktop PC" : all.get(0).name;
    }

    static Preset saveCurrent(String name) {
        String clean = name == null ? "" : name.trim();
        if (clean.isEmpty()) clean = "Posição " + (list().size() + 1);
        int[] corners = readCurrentCorners();
        String overscan = readProp("persist.vendor.overscan.main", "overscan 100,100,100,100");
        Preset p = new Preset("pos_" + System.currentTimeMillis(), clean, corners, overscan);
        List<Preset> all = list();
        all.add(p);
        saveAll(all);
        Prefs.putString(KEY_ACTIVE, p.id);
        return p;
    }

    static boolean updateWithCurrent(String id) {
        List<Preset> all = list();
        int[] corners = readCurrentCorners();
        String overscan = readProp("persist.vendor.overscan.main", "overscan 100,100,100,100");
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id.equals(id)) {
                all.set(i, new Preset(id, all.get(i).name, corners, overscan));
                saveAll(all);
                return true;
            }
        }
        return false;
    }

    static boolean delete(String id) {
        List<Preset> all = list();
        if (all.size() <= 1) return false;
        boolean removed = false;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id.equals(id)) {
                all.remove(i);
                removed = true;
                break;
            }
        }
        if (removed) {
            saveAll(all);
            if (id.equals(activeId()) && !all.isEmpty()) {
                Prefs.putString(KEY_ACTIVE, all.get(0).id);
            }
        }
        return removed;
    }

    static String currentSummary() {
        int[] corners = readCurrentCorners();
        String overscan = readProp("persist.vendor.overscan.main", "overscan 100,100,100,100");
        return new Preset("now", "Atual", corners, overscan).summary();
    }

    /** Applies the preset to the projector immediately and persists it across reboots. */
    static boolean apply(Context c, Preset p) {
        Prefs.putString(KEY_ACTIVE, p.id);
        StringBuilder zoomCsv = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            if (i > 0) zoomCsv.append(',');
            zoomCsv.append(p.corners[i]);
        }

        // 1. Persist system properties directly (works when SELinux is permissive)
        boolean propOk = true;
        for (int i = 0; i < 8; i++) {
            propOk &= setProp(CORNER_PROPS[i], String.valueOf(p.corners[i]));
        }
        setProp("persist.sys.zoom.value", zoomCsv.toString());
        setProp("persist.vendor.overscan.main", p.overscan);

        // 2. Also send to SystemRemoteDaemon (shell) in background if active
        if (!propOk) {
            StringBuilder cmd = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                cmd.append("setprop ").append(CORNER_PROPS[i]).append(' ').append(p.corners[i]).append("; ");
            }
            cmd.append("setprop persist.sys.zoom.value ").append(zoomCsv).append("; ");
            cmd.append("setprop persist.vendor.overscan.main '").append(p.overscan).append("'");
            sendDaemonCmd(cmd.toString());
        }

        // 3. Apply live via SurfaceFlinger binder transaction 1050 (same as Hotack/HY300 KeystoneUtils)
        boolean sfOk = applySurfaceFlinger(p.corners);
        AppLog.w("ProjectionPresets.apply(" + p.name + "): propOk=" + propOk + " sfOk=" + sfOk);
        return sfOk || propOk;
    }

    private static boolean applySurfaceFlinger(int[] corners) {
        Parcel data = null;
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Method getService = sm.getMethod("getService", String.class);
            IBinder sf = (IBinder) getService.invoke(null, "SurfaceFlinger");
            if (sf == null) return false;
            data = Parcel.obtain();
            data.writeInterfaceToken("android.ui.ISurfaceComposer");
            for (int i = 0; i < 8; i++) {
                data.writeFloat((float) (corners[i] * 0.001d));
            }
            return sf.transact(1050, data, null, 0);
        } catch (Throwable t) {
            AppLog.w("SurfaceFlinger keystone transact failed: " + t.getMessage());
            return false;
        } finally {
            if (data != null) data.recycle();
        }
    }

    private static int[] readCurrentCorners() {
        int[] out = new int[8];
        for (int i = 0; i < 8; i++) {
            String v = readProp(CORNER_PROPS[i], "0");
            try {
                out[i] = Integer.parseInt(v.trim());
            } catch (NumberFormatException ignored) {
                out[i] = 0;
            }
        }
        return out;
    }

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

    private static void sendDaemonCmd(final String cmd) {
        new Thread(() -> {
            try (Socket s = new Socket(InetAddress.getByName("127.0.0.1"), SystemRemoteDaemon.PORT);
                 OutputStream os = s.getOutputStream()) {
                os.write(("CMD:" + cmd + "\n").getBytes(StandardCharsets.UTF_8));
                os.flush();
            } catch (Throwable ignored) {
            }
        }).start();
    }

    private static int parseZoom(String overscan) {
        if (overscan == null) return 100;
        String s = overscan.replace("overscan", "").trim();
        int comma = s.indexOf(',');
        String first = comma > 0 ? s.substring(0, comma).trim() : s;
        try {
            return Integer.parseInt(first);
        } catch (NumberFormatException e) {
            return 100;
        }
    }

    private static void saveAll(List<Preset> list) {
        JSONArray arr = new JSONArray();
        for (Preset p : list) {
            try {
                JSONObject o = new JSONObject();
                o.put("id", p.id);
                o.put("name", p.name);
                JSONArray ca = new JSONArray();
                for (int v : p.corners) ca.put(v);
                o.put("corners", ca);
                o.put("overscan", p.overscan);
                arr.put(o);
            } catch (JSONException ignored) {
            }
        }
        Prefs.putString(KEY_LIST, arr.toString());
    }
}
