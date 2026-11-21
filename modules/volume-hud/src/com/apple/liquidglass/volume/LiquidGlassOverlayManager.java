package com.apple.liquidglass.volume;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;

/**
 * Manages the rootless system-wide Apple Liquid Glass Volume HUD overlay window and
 * synchronizes directly with Android's AudioManager (STREAM_MUSIC) without showing the stock UI.
 */
public class LiquidGlassOverlayManager {

    private static final String PREFS_NAME = "apple_liquid_glass_prefs";
    private static final String KEY_STYLE = "glass_style";
    private static final String KEY_ALIGN_LEFT = "align_left";

    private static LiquidGlassOverlayManager instance;

    private final Context appContext;
    private final AudioManager audioManager;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private Context accessibilityContext;
    private WindowManager currentWindowManager;
    private WindowManager.LayoutParams layoutParams;
    private LiquidGlassVolumeView overlayView;
    private boolean isAttached = false;
    private boolean isVisible = false;
    private int consecutivePresses = 0;

    private final Runnable slimDownRunnable = new Runnable() {
        @Override
        public void run() {
            if (overlayView != null && isVisible) {
                overlayView.setExpanded(false, true);
            }
        }
    };

    private final Runnable hideRunnable = new Runnable() {
        @Override
        public void run() {
            dismissOverlay();
        }
    };

    private LiquidGlassOverlayManager(Context context) {
        this.appContext = context.getApplicationContext();
        this.audioManager = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
    }

    public static synchronized LiquidGlassOverlayManager getInstance(Context context) {
        if (instance == null) {
            instance = new LiquidGlassOverlayManager(context);
        }
        return instance;
    }

    public void attachAccessibilityContext(Context serviceContext) {
        this.accessibilityContext = serviceContext;
        rebuildOverlayWindow();
    }

    public void detachAccessibilityContext() {
        removeViewSafely();
        this.accessibilityContext = null;
    }

    public int getSavedGlassStyle() {
        SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getInt(KEY_STYLE, LiquidGlassVolumeView.STYLE_REGULAR_DARK);
    }

    public void setSavedGlassStyle(int style) {
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_STYLE, style)
                .apply();
        if (overlayView != null) {
            overlayView.setGlassStyle(style);
        }
    }

    public boolean isSavedAlignLeft() {
        SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_ALIGN_LEFT, false);
    }

    public void setSavedAlignLeft(boolean alignLeft) {
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_ALIGN_LEFT, alignLeft)
                .apply();
        if (overlayView != null) {
            overlayView.setAlignLeft(alignLeft);
        }
        if (isAttached && currentWindowManager != null && layoutParams != null) {
            layoutParams.gravity = (alignLeft ? Gravity.START : Gravity.END) | Gravity.CENTER_VERTICAL;
            try {
                currentWindowManager.updateViewLayout(overlayView, layoutParams);
            } catch ( Throwable ignored) {
            }
        }
    }

    /**
     * Adjusts media volume by +1 or -1 step silently (without Android's stock volume bar)
     * and shows the Apple Liquid Glass vertical HUD.
     */
    public void stepVolume(int direction) {
        int max = Math.max(1, audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        int current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);

        if (direction > 0) {
            if (current >= max) {
                showHudWithLevel(1.0f, false, 1.0f);
                return;
            }
            int next = Math.min(max, current + 1);
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0); // Flag 0 = suppress stock UI!
            showHudWithLevel(next / (float) max, false, 0f);
        } else if (direction < 0) {
            if (current <= 0) {
                showHudWithLevel(0.0f, true, -1.0f);
                return;
            }
            int next = Math.max(0, current - 1);
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0); // Flag 0 = suppress stock UI!
            showHudWithLevel(next / (float) max, next == 0, 0f);
        }
    }

    /**
     * Toggles mute state silently and shows the Apple Liquid Glass vertical HUD.
     */
    public void toggleMute() {
        int max = Math.max(1, audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        boolean currentlyMuted = audioManager.isStreamMute(AudioManager.STREAM_MUSIC)
                || audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) == 0;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            audioManager.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    currentlyMuted ? AudioManager.ADJUST_UNMUTE : AudioManager.ADJUST_MUTE,
                    0
            );
        } else {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, currentlyMuted ? (max / 2) : 0, 0);
        }

        int updated = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        boolean nowMuted = !currentlyMuted || updated == 0;
        consecutivePresses = 0; // Always show expanded pill on mute toggle so speaker.slash.fill is visible
        showHudWithLevel(updated / (float) max, nowMuted, 0f);
    }

    public void showCurrentVolumePreview(boolean forceExpanded) {
        int max = Math.max(1, audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        int current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        if (forceExpanded) {
            consecutivePresses = 0;
        }
        showHudWithLevel(current / (float) max, current == 0, 0f);
    }

    private void showHudWithLevel(float normalizedLevel, boolean muted, float overshootDir) {
        handler.post(() -> {
            ensureOverlayAttached();
            if (overlayView == null) return;

            handler.removeCallbacks(hideRunnable);
            handler.removeCallbacks(slimDownRunnable);

            consecutivePresses++;
            overlayView.setGlassStyle(getSavedGlassStyle());
            overlayView.setAlignLeft(isSavedAlignLeft());
            overlayView.setMuted(muted);
            overlayView.setVolumeLevel(normalizedLevel, true);

            if (overshootDir != 0f) {
                overlayView.triggerOvershoot(overshootDir);
            }

            // Apple iOS/tvOS choreography: 1st click shows expanded 56dp pill;
            // 2nd consecutive click or 1.2s timer narrows to 28dp compact pill.
            if (!isVisible) {
                isVisible = true;
                overlayView.setExpanded(true, false);
                float slideOffset = isSavedAlignLeft() ? -dp(22f) : dp(22f);
                overlayView.setTranslationX(slideOffset);
                overlayView.setScaleX(0.9f);
                overlayView.setScaleY(0.9f);
                overlayView.setAlpha(0f);
                overlayView.animate()
                        .translationX(0f)
                        .scaleX(1f)
                        .scaleY(1f)
                        .alpha(1f)
                        .setDuration(260)
                        .setInterpolator(new OvershootInterpolator(0.9f))
                        .start();
            } else if (consecutivePresses >= 2) {
                overlayView.setExpanded(false, true);
            } else {
                overlayView.setExpanded(true, true);
            }

            // Auto-slim to compact pill (28dp) after 1.2s, then auto-hide after 2.5s
            handler.postDelayed(slimDownRunnable, 1200);
            handler.postDelayed(hideRunnable, 2500);
        });
    }

    private void dismissOverlay() {
        if (overlayView == null || !isVisible) return;
        isVisible = false;
        consecutivePresses = 0;
        float exitOffset = isSavedAlignLeft() ? -dp(22f) : dp(22f);
        overlayView.animate()
                .translationX(exitOffset)
                .scaleX(0.9f)
                .scaleY(0.9f)
                .alpha(0f)
                .setDuration(240)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }

    private void rebuildOverlayWindow() {
        removeViewSafely();
        ensureOverlayAttached();
    }

    private void ensureOverlayAttached() {
        if (isAttached && overlayView != null) return;

        Context targetContext = accessibilityContext != null ? accessibilityContext : appContext;
        int windowType;
        if (accessibilityContext != null) {
            windowType = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!Settings.canDrawOverlays(appContext)) {
                return;
            }
            windowType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            windowType = WindowManager.LayoutParams.TYPE_PHONE;
        }

        currentWindowManager = (WindowManager) targetContext.getSystemService(Context.WINDOW_SERVICE);
        overlayView = new LiquidGlassVolumeView(targetContext);
        overlayView.setGlassStyle(getSavedGlassStyle());
        overlayView.setAlignLeft(isSavedAlignLeft());
        overlayView.setAlpha(0f);

        overlayView.setOnVolumeScrubListener(new LiquidGlassVolumeView.OnVolumeScrubListener() {
            @Override
            public void onVolumeScrubbed(float normalizedLevel) {
                int max = Math.max(1, audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
                int vol = Math.round(normalizedLevel * max);
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, vol, 0);
            }

            @Override
            public void onInteractionActive() {
                consecutivePresses = 1;
                handler.removeCallbacks(hideRunnable);
                handler.removeCallbacks(slimDownRunnable);
                handler.postDelayed(slimDownRunnable, 1300);
                handler.postDelayed(hideRunnable, 2500);
            }
        });

        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;

        layoutParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                windowType,
                flags,
                PixelFormat.RGBA_8888
        );
        layoutParams.gravity = (isSavedAlignLeft() ? Gravity.START : Gravity.END) | Gravity.CENTER_VERTICAL;
        layoutParams.x = (int) dp(6f);

        try {
            currentWindowManager.addView(overlayView, layoutParams);
            isAttached = true;
        } catch (Throwable t) {
            isAttached = false;
        }
    }

    private void removeViewSafely() {
        if (isAttached && currentWindowManager != null && overlayView != null) {
            try {
                currentWindowManager.removeViewImmediate(overlayView);
            } catch (Throwable ignored) {
            }
        }
        isAttached = false;
        isVisible = false;
        overlayView = null;
    }

    private float dp(float value) {
        return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value,
                appContext.getResources().getDisplayMetrics()
        );
    }
}
