package io.freemytv;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Paçoca OsTV Accessibility Service:
 * 1. Intercepts hardware/IR/Bluetooth volume keys (VOLUME_UP, VOLUME_DOWN, VOLUME_MUTE) without root,
 *    suppresses Android's stock SystemUI volume bar, and displays the Apple tvOS / visionOS Liquid Glass
 *    Vertical Volume HUD.
 * 2. Intercepts remote app shortcut buttons (RemoteKeys) from inside any app.
 */
public class RemoteKeyService extends AccessibilityService {

    private long lastInterceptedKeyTimeMs = 0L;
    private long lastBroadcastShowTimeMs = 0L;
    private int lastObservedMusicVol = -1;

    private final BroadcastReceiver volumeBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String action = intent.getAction();
            if ("android.media.VOLUME_CHANGED_ACTION".equals(action)
                    || "android.media.STREAM_MUTE_CHANGED_ACTION".equals(action)) {

                int streamType = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", AudioManager.STREAM_MUSIC);
                if (streamType != AudioManager.STREAM_MUSIC) {
                    return;
                }

                try {
                    context.sendBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS));
                } catch (Throwable ignored) {
                }

                long now = SystemClock.uptimeMillis();
                if (now - lastInterceptedKeyTimeMs > 250L) {
                    AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
                    int currentVol = am != null ? am.getStreamVolume(AudioManager.STREAM_MUSIC) : -1;
                    if (currentVol != lastObservedMusicVol || (now - lastBroadcastShowTimeMs > 180L)) {
                        lastObservedMusicVol = currentVol;
                        lastBroadcastShowTimeMs = now;
                        LiquidGlassOverlayManager.getInstance(context).showCurrentVolumePreview(false);
                    }
                }
            }
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
            setServiceInfo(info);
        }

        LiquidGlassOverlayManager.getInstance(this).attachAccessibilityContext(this);
        RemoteDaemonBridge.ensureRunningAsync(this, false);

        IntentFilter filter = new IntentFilter();
        filter.addAction("android.media.VOLUME_CHANGED_ACTION");
        filter.addAction("android.media.STREAM_MUTE_CHANGED_ACTION");
        registerReceiver(volumeBroadcastReceiver, filter);
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        int action = event.getAction();

        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            lastInterceptedKeyTimeMs = SystemClock.uptimeMillis();
            if (action == KeyEvent.ACTION_DOWN) {
                LiquidGlassOverlayManager.getInstance(this).stepVolume(1);
            }
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            lastInterceptedKeyTimeMs = SystemClock.uptimeMillis();
            if (action == KeyEvent.ACTION_DOWN) {
                LiquidGlassOverlayManager.getInstance(this).stepVolume(-1);
            }
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_VOLUME_MUTE || keyCode == KeyEvent.KEYCODE_MUTE) {
            lastInterceptedKeyTimeMs = SystemClock.uptimeMillis();
            if (action == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                LiquidGlassOverlayManager.getInstance(this).toggleMute();
            }
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_SETTINGS) {
            if (action == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                try {
                    Intent cc = new Intent(this, QuickSettingsActivity.class);
                    cc.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    startActivity(cc);
                } catch (Throwable ignored) {}
            }
            return true;
        }

        return RemoteKeys.onKey(this, event);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(Intent intent) {
        try {
            unregisterReceiver(volumeBroadcastReceiver);
        } catch (Throwable ignored) {
        }
        LiquidGlassOverlayManager.getInstance(this).detachAccessibilityContext();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        try {
            unregisterReceiver(volumeBroadcastReceiver);
        } catch (Throwable ignored) {
        }
        LiquidGlassOverlayManager.getInstance(this).detachAccessibilityContext();
        super.onDestroy();
    }
}
