package com.apple.liquidglass.volume;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;

import java.io.OutputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Persistent VolumeController Interceptor Daemon for Android TV / HY300Pro.
 *
 * Why a persistent daemon is needed on HY300Pro:
 * Because HY300Pro has 1GB RAM, Android's lmkd periodically kills and respawns
 * `com.android.systemui`. Every time SystemUI respawns, its `VolumeUI` service
 * calls `IAudioService.setVolumeController(sysUiController)` to re-enable the
 * native volume bar.
 *
 * How this daemon defeats it permanently:
 * 1. Registers a custom `IVolumeController` backed by `SilentVolumeBinder` with `AudioService`.
 * 2. The exact millisecond SystemUI respawns and calls `setVolumeController(sysUiController)`,
 *    `AudioService` calls `postDismiss()` on our `SilentVolumeBinder` (`onTransact`),
 *    which immediately wakes our daemon to reclaim `setVolumeController(ourProxy)` within 20ms.
 * 3. A lightweight 1.5s watchdog also calls `setVolumeController(ourProxy)` (which is a zero-cost
 *    no-op in `AudioService` when `isSameBinder` is already true).
 */
public final class VolumeControllerHijacker {

    private static final String DESCRIPTOR = "android.media.IVolumeController";
    private static final Object wakeLock = new Object();

    private static final class SilentVolumeBinder extends Binder {
        @Override
        public String getInterfaceDescriptor() {
            return DESCRIPTOR;
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            if (code == INTERFACE_TRANSACTION) {
                if (reply != null) {
                    reply.writeString(DESCRIPTOR);
                }
                return true;
            }
            // TRANSACTION_dismiss is code 5 in IVolumeController.aidl (called when SystemUI tries to take over)
            if (code == 5) {
                synchronized (wakeLock) {
                    wakeLock.notifyAll();
                }
            }
            // Swallow all volumeChanged / displaySafeVolumeWarning / masterMuteChanged notifications
            return true;
        }
    }

    public static void main(String[] args) {
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
                        if ("asBinder".equals(name)) {
                            return silentBinder;
                        }
                        if ("hashCode".equals(name)) {
                            return System.identityHashCode(silentBinder);
                        }
                        if ("equals".equals(name)) {
                            return proxy == (methodArgs != null && methodArgs.length > 0 ? methodArgs[0] : null);
                        }
                        return null;
                    }
            );

            System.out.println("VolumeControllerHijacker daemon active (Binder=" + silentBinder + ")");

            boolean daemonMode = args != null && args.length > 0 && "--daemon".equals(args[0]);
            Method setVc = null;
            Object audioService = null;

            while (true) {
                try {
                    if (audioService == null || setVc == null) {
                        IBinder audioBinder = (IBinder) getService.invoke(null, "audio");
                        if (audioBinder != null) {
                            audioService = asInterface.invoke(null, audioBinder);
                            setVc = audioService.getClass().getMethod("setVolumeController", vcClass);
                        }
                    }
                    if (audioService != null && setVc != null) {
                        setVc.invoke(audioService, vcProxy);
                    }
                } catch (Throwable t) {
                    audioService = null;
                    setVc = null;
                    if (!daemonMode) {
                        t.printStackTrace();
                        return;
                    }
                }

                if (!daemonMode) {
                    return;
                }

                synchronized (wakeLock) {
                    wakeLock.wait(1500L);
                }
                // Brief 25ms pause if woken by postDismiss() so SystemUI finishes its setVolumeController call first
                Thread.sleep(25L);
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    /**
     * Ensures the persistent VolumeControllerHijacker daemon is running via SystemRemoteDaemon (127.0.0.1:8099).
     */
    public static void triggerViaLocalDaemonAsync() {
        Thread t = new Thread(() -> {
            String cmd = "CMD:for p in /proc/[0-9]*; do "
                    + "pid=${p#/proc/}; [ \"$pid\" = \"$$\" ] && continue; "
                    + "grep -aqs com.apple.liquidglass.volume.VolumeControllerHijacker \"$p/cmdline\" "
                    + "&& ! grep -aqs setsid \"$p/cmdline\" "
                    + "&& kill -9 \"$pid\" 2>/dev/null; done; "
                    + "APK=$(pm path com.apple.liquidglass.volume | cut -d: -f2); "
                    + "if [ -n \"$APK\" ]; then "
                    + "setsid sh -c \"CLASSPATH=$APK app_process /system/bin com.apple.liquidglass.volume.VolumeControllerHijacker --daemon\" "
                    + "</dev/null >/dev/null 2>&1 & "
                    + "fi\n";
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("127.0.0.1", 8099), 400);
                OutputStream os = s.getOutputStream();
                os.write(cmd.getBytes(StandardCharsets.UTF_8));
                os.flush();
            } catch (Throwable ignored) {
            }
        }, "LiquidGlass-HijackDaemon");
        t.setDaemon(true);
        t.start();
    }
}
