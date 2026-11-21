package io.freemytv;

import android.hardware.input.InputManager;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyEvent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * System-Wide Remote Control Daemon for BTV.
 * Runs under user `shell` via app_process.
 * Injects real hardware KeyEvents into the Android InputManager across the entire operating system.
 */
public final class SystemRemoteDaemon {
    public static final int PORT = 8099;

    public static void main(String[] args) {
        System.out.println("SystemRemoteDaemon starting on port " + PORT + "...");
        Method injectMethod = null;
        InputManager im = null;
        try {
            Method getInstance = InputManager.class.getMethod("getInstance");
            im = (InputManager) getInstance.invoke(null);
            injectMethod = InputManager.class.getMethod("injectInputEvent", InputEvent.class, int.class);
            System.out.println("InputManager reflection succeeded.");
        } catch (Throwable t) {
            System.out.println("InputManager reflection note: " + t.getMessage());
        }

        ServerSocket server = null;
        try {
            server = new ServerSocket(PORT, 50, InetAddress.getByName("127.0.0.1"));
            System.out.println("SystemRemoteDaemon listening on 127.0.0.1:" + PORT);
        } catch (Throwable t) {
            t.printStackTrace();
            return;
        }

        while (true) {
            try {
                Socket socket = server.accept();
                socket.setSoTimeout(3000);
                BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;

                    if (line.startsWith("TEXT:")) {
                        String text = line.substring(5);
                        try {
                            Runtime.getRuntime().exec(new String[]{"input", "text", text});
                        } catch (Exception ignored) {}
                        continue;
                    }

                    int keyCode = Integer.parseInt(line);
                    boolean injected = false;
                    if (im != null && injectMethod != null) {
                        try {
                            long now = SystemClock.uptimeMillis();
                            KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, 0, -1, 0, 0, InputDevice.SOURCE_KEYBOARD);
                            KeyEvent up = new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, 0, -1, 0, 0, InputDevice.SOURCE_KEYBOARD);
                            injectMethod.invoke(im, down, 2); // 2 = INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH
                            injectMethod.invoke(im, up, 2);
                            injected = true;
                        } catch (Throwable ex) {
                            injected = false;
                        }
                    }

                    if (!injected) {
                        try {
                            Runtime.getRuntime().exec(new String[]{"input", "keyevent", String.valueOf(keyCode)});
                        } catch (Exception ignored) {}
                    }
                }
                socket.close();
            } catch (Exception ignored) {
            }
        }
    }
}
