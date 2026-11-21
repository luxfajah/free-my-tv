package io.freemytv;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Apple TV (tvOS 26) Control Center for HY300Pro / Paçoca OsTV.
 * - Fullscreen 100% opaque backdrop using the exact currently selected movie's gradient from HomeView
 * - Compact Apple TV top-right proportions (1/3 footprint)
 * - Native Scene.liquid() Liquid Glass shader matching HomeView with clean tvOS white focus
 * - Inline Audio Output selector (Internal Speaker + Bluetooth A2DP) and Sleep Timer.
 */
public final class QuickSettingsActivity extends Activity {

    static volatile boolean isOpen = false;
    private ControlCenterView view;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        isOpen = true;
        Ui.init(this);
        Window w = getWindow();
        w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        w.setGravity(Gravity.FILL);
        w.setBackgroundDrawableResource(android.R.color.transparent);
        w.setFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_FULLSCREEN);

        view = new ControlCenterView(this);
        setContentView(view, new android.view.ViewGroup.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT));
        w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        finish(); // Pressing Control Center again toggles it closed
    }

    @Override protected void onResume() {
        super.onResume();
        isOpen = true;
        if (view != null) view.refreshState();
    }

    @Override protected void onDestroy() {
        isOpen = false;
        super.onDestroy();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (view != null && view.handleKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    // ─── Compact tvOS Liquid Glass Control Center View ───────────────────────────

    private final class ControlCenterView extends View implements Choreographer.FrameCallback {
        private static final int MODE_GRID = 0, MODE_AUDIO = 1, MODE_SLEEP = 2;

        private static final int T_POWER = 0;
        private static final int T_WIFI = 1;
        private static final int T_KEYSTONE = 2;
        private static final int T_AUDIO = 3;
        private static final int T_SLEEP = 4;
        private static final int T_HDMI = 5;
        private static final int T_PROJ = 6;
        private static final int T_PICTURE = 7;
        private static final int T_SETTINGS = 8;
        private static final int TILE_COUNT = 9;

        private final int[] SLEEP_OPTIONS = {0, 15, 30, 45, 60, 90, 120};

        private final float d;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final SimpleDateFormat clockFmt = new SimpleDateFormat("HH:mm", Locale.getDefault());
        private final Scene scene = new Scene();
        private final Paint bgPaint = new Paint(Paint.FILTER_BITMAP_FLAG);

        private int mode = MODE_GRID;
        private int focus = T_POWER;
        private int subFocus = 0;
        private boolean okDown = false;
        private boolean frameScheduled = false;
        private long lastNanos = 0;
        private final RectF panelBounds = new RectF();
        private final RectF screenRect = new RectF();
        private final RectF tmpRect = new RectF();

        // Smooth focus animation per tile (0..1)
        private final float[] anim = new float[12];
        private final RectF[] rects = new RectF[TILE_COUNT];
        private final List<RectF> subRects = new ArrayList<>();

        // Live hardware state
        private String wifiName = "Wi-Fi";
        private boolean autoKeystone = false;
        private String activeAudioName = "Alto-falante";
        private boolean btAudioActive = false;
        private int sleepLeftMin = 0;
        private final List<ProjectorHardware.AudioTarget> audioTargets = new ArrayList<>();
        private boolean audioBusy = false;

        // Paints
        private final Paint fillP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint strokeP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint iconP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint titleP = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint subP = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Paint clockP = new Paint(Paint.ANTI_ALIAS_FLAG);

        private final Runnable clockTick = new Runnable() {
            @Override public void run() {
                sleepLeftMin = SleepTimer.minutesLeft(getContext());
                invalidate();
                handler.postDelayed(this, 15_000);
            }
        };

        ControlCenterView(Context ctx) {
            super(ctx);
            this.d = ctx.getResources().getDisplayMetrics().density;
            for (int i = 0; i < TILE_COUNT; i++) rects[i] = new RectF();
            setFocusable(true);
            setFocusableInTouchMode(true);
            requestFocus();

            strokeP.setStyle(Paint.Style.STROKE);
            iconP.setStyle(Paint.Style.STROKE);
            iconP.setStrokeCap(Paint.Cap.ROUND);
            iconP.setStrokeJoin(Paint.Join.ROUND);

            titleP.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            titleP.setColor(Color.WHITE);
            subP.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            subP.setColor(0xB8FFFFFF);
            clockP.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            clockP.setColor(0xF2FFFFFF);
            clockP.setTextSize(12.5f * d);
            clockP.setFontFeatureSettings("tnum");

            anim[T_POWER] = 1f;
            Shaders.sky(scene.palette, System.currentTimeMillis(), 6 * 3600_000L, 18 * 3600_000L);
            scene.applyPalette();
            syncMovieBackdrop();
            refreshState();
        }

        private void syncMovieBackdrop() {
            Backdrop b = HomeView.currentMovieBackdrop;
            if (b != null) {
                int[] glass = b.nightGlass != null ? b.nightGlass : b.lightGlass;
                int[] frost = b.nightFrost != null ? b.nightFrost : b.frost;
                if (glass != null) scene.setGlassImage(glass);
                if (frost != null) scene.setFrost(frost);
            }
        }

        @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            screenRect.set(0, 0, w, h);
            scene.setSize(w, h);
            syncMovieBackdrop();
        }

        @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec));
        }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            handler.postDelayed(clockTick, 15_000);
        }

        @Override protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            handler.removeCallbacks(clockTick);
            Choreographer.getInstance().removeFrameCallback(this);
        }

        void refreshState() {
            Context ctx = getContext();
            syncMovieBackdrop();
            wifiName = ProjectorHardware.wifiSsid(ctx);
            autoKeystone = ProjectorHardware.isAutoKeystone();
            sleepLeftMin = SleepTimer.minutesLeft(ctx);
            ProjectorHardware.queryAudioTargets(ctx, (targets, activeName, btActive) -> handler.post(() -> {
                audioTargets.clear();
                audioTargets.addAll(targets);
                activeAudioName = activeName;
                btAudioActive = btActive;
                audioBusy = false;
                invalidate();
            }));
            invalidate();
        }

        private void kickAnim() {
            if (!frameScheduled) {
                frameScheduled = true;
                lastNanos = 0;
                Choreographer.getInstance().postFrameCallback(this);
            }
        }

        @Override public void doFrame(long frameTimeNanos) {
            frameScheduled = false;
            float dt = lastNanos == 0 ? 1 / 60f : Math.min(0.05f, (frameTimeNanos - lastNanos) / 1e9f);
            lastNanos = frameTimeNanos;
            boolean moving = false;
            int activeIdx = mode == MODE_GRID ? focus : subFocus;
            int count = mode == MODE_GRID ? TILE_COUNT : Math.min(anim.length, subItemCount());
            float speed = Math.min(1f, dt * 18f);
            for (int i = 0; i < count; i++) {
                float target = i == activeIdx ? 1f : 0f;
                float diff = target - anim[i];
                if (Math.abs(diff) > 0.004f) {
                    anim[i] += diff * speed;
                    moving = true;
                } else {
                    anim[i] = target;
                }
            }
            invalidate();
            if (moving) kickAnim();
        }

        private int subItemCount() {
            if (mode == MODE_AUDIO) return audioTargets.size() + 1;
            if (mode == MODE_SLEEP) return SLEEP_OPTIONS.length;
            return 0;
        }

        // ─── Key & Touch Navigation ──────────────────────────────────────────────

        boolean handleKey(KeyEvent e) {
            int kc = e.getKeyCode();
            boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
            if (kc == KeyEvent.KEYCODE_BACK || kc == KeyEvent.KEYCODE_ESCAPE) {
                if (!down) {
                    if (mode != MODE_GRID) {
                        mode = MODE_GRID;
                        kickAnim();
                        invalidate();
                    } else {
                        finish();
                    }
                }
                return true;
            }
            if (kc == KeyEvent.KEYCODE_DPAD_CENTER || kc == KeyEvent.KEYCODE_ENTER || kc == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                if (down) {
                    if (e.getRepeatCount() == 0) okDown = true;
                } else if (okDown) {
                    okDown = false;
                    activateCurrent();
                }
                return true;
            }
            if (!down) return false;

            if (mode != MODE_GRID) {
                int max = subItemCount() - 1;
                if (kc == KeyEvent.KEYCODE_DPAD_UP && subFocus > 0) {
                    subFocus--;
                    kickAnim();
                    return true;
                } else if (kc == KeyEvent.KEYCODE_DPAD_DOWN && subFocus < max) {
                    subFocus++;
                    kickAnim();
                    return true;
                }
                return true;
            }

            int next = focus;
            switch (kc) {
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (focus == T_POWER) next = T_WIFI;
                    else if (focus == T_AUDIO) next = T_SLEEP;
                    else if (focus >= T_HDMI && focus < T_SETTINGS) next = focus + 1;
                    break;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (focus == T_WIFI || focus == T_KEYSTONE) next = T_POWER;
                    else if (focus == T_SLEEP) next = T_AUDIO;
                    else if (focus > T_HDMI && focus <= T_SETTINGS) next = focus - 1;
                    break;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (focus == T_POWER) next = T_AUDIO;
                    else if (focus == T_WIFI) next = T_KEYSTONE;
                    else if (focus == T_KEYSTONE) next = T_SLEEP;
                    else if (focus == T_AUDIO) next = T_HDMI;
                    else if (focus == T_SLEEP) next = T_PICTURE;
                    break;
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (focus == T_KEYSTONE) next = T_WIFI;
                    else if (focus == T_AUDIO) next = T_POWER;
                    else if (focus == T_SLEEP) next = T_KEYSTONE;
                    else if (focus == T_HDMI || focus == T_PROJ) next = T_AUDIO;
                    else if (focus == T_PICTURE || focus == T_SETTINGS) next = T_SLEEP;
                    break;
                default:
                    return false;
            }
            if (next != focus) {
                focus = next;
                kickAnim();
            }
            return true;
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (event.getAction() == MotionEvent.ACTION_UP) {
                float x = event.getX(), y = event.getY();
                if (mode == MODE_GRID) {
                    for (int i = 0; i < TILE_COUNT; i++) {
                        if (rects[i].contains(x, y)) {
                            focus = i;
                            kickAnim();
                            activateCurrent();
                            return true;
                        }
                    }
                } else {
                    for (int i = 0; i < subRects.size(); i++) {
                        if (subRects.get(i).contains(x, y)) {
                            subFocus = i;
                            kickAnim();
                            activateCurrent();
                            return true;
                        }
                    }
                }
                if (!panelBounds.contains(x, y)) finish();
            }
            return true;
        }

        private void activateCurrent() {
            Context ctx = getContext();
            if (mode == MODE_AUDIO) {
                if (subFocus < audioTargets.size()) {
                    ProjectorHardware.AudioTarget t = audioTargets.get(subFocus);
                    audioBusy = true;
                    invalidate();
                    Toast.makeText(ctx, "Áudio: " + t.name, Toast.LENGTH_SHORT).show();
                    ProjectorHardware.selectAudioTarget(ctx, t, () -> handler.postDelayed(this::refreshState, 900));
                } else {
                    openBluetoothSettings();
                }
                return;
            }
            if (mode == MODE_SLEEP) {
                int mins = SLEEP_OPTIONS[Math.max(0, Math.min(SLEEP_OPTIONS.length - 1, subFocus))];
                if (mins == 0) {
                    SleepTimer.stop(ctx);
                    Toast.makeText(ctx, "Sleep Timer desligado", Toast.LENGTH_SHORT).show();
                } else {
                    SleepTimer.set(ctx, mins);
                    Toast.makeText(ctx, "Sleep Timer: " + mins + " min", Toast.LENGTH_SHORT).show();
                }
                sleepLeftMin = mins;
                mode = MODE_GRID;
                kickAnim();
                invalidate();
                return;
            }

            switch (focus) {
                case T_POWER:
                    Toast.makeText(ctx, "Desligando projetor…", Toast.LENGTH_SHORT).show();
                    RemoteDaemonBridge.sendKeyTap(KeyEvent.KEYCODE_POWER);
                    finish();
                    break;
                case T_WIFI:
                    openWifiSettings();
                    break;
                case T_KEYSTONE:
                    autoKeystone = !autoKeystone;
                    ProjectorHardware.setAutoKeystone(ctx, autoKeystone);
                    Toast.makeText(ctx, "Auto-Keystone: " + (autoKeystone ? "Ligado" : "Desligado"), Toast.LENGTH_SHORT).show();
                    invalidate();
                    break;
                case T_AUDIO:
                    mode = MODE_AUDIO;
                    subFocus = 0;
                    for (int i = 0; i < audioTargets.size(); i++) {
                        if (audioTargets.get(i).connected) { subFocus = i; break; }
                    }
                    resetSubAnim();
                    refreshState();
                    break;
                case T_SLEEP:
                    mode = MODE_SLEEP;
                    subFocus = 0;
                    for (int i = 0; i < SLEEP_OPTIONS.length; i++) {
                        if (SLEEP_OPTIONS[i] >= sleepLeftMin && sleepLeftMin > 0) { subFocus = i; break; }
                    }
                    resetSubAnim();
                    break;
                case T_HDMI:
                    if (!ProjectorHardware.openHdmi(ctx)) {
                        Toast.makeText(ctx, "Entrada HDMI indisponível", Toast.LENGTH_SHORT).show();
                    }
                    finish();
                    break;
                case T_PROJ:
                    startActivity(new Intent(ctx, SettingsActivity.class).putExtra("projection", true));
                    finish();
                    break;
                case T_PICTURE:
                    startActivity(new Intent(ctx, SettingsActivity.class).putExtra("picture", true));
                    finish();
                    break;
                case T_SETTINGS:
                    startActivity(new Intent(ctx, SettingsActivity.class));
                    finish();
                    break;
            }
        }

        private void resetSubAnim() {
            for (int i = 0; i < anim.length; i++) anim[i] = (i == subFocus ? 1f : 0f);
            invalidate();
        }

        private void openWifiSettings() {
            Intent htcWifi = new Intent().setComponent(new ComponentName("com.htc.htcsettings", "com.htc.activity.WIFIActivity"));
            if (tryStart(htcWifi)) return;
            tryStart(new Intent(Settings.ACTION_WIFI_SETTINGS));
        }

        private void openBluetoothSettings() {
            Intent htcBt = new Intent().setComponent(new ComponentName("com.htc.htcsettings", "com.htc.activity.BluetoothSettingActivity"));
            if (tryStart(htcBt)) return;
            tryStart(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
        }

        private boolean tryStart(Intent i) {
            try {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                finish();
                return true;
            } catch (Throwable ignored) {
                return false;
            }
        }

        // ─── Drawing ─────────────────────────────────────────────────────────────

        /** Draws a refined ambient gradient using the dominant colors of the active movie (no blurry photo). */
        private void drawMovieBackdrop(Canvas c, float w, float h) {
            Backdrop b = HomeView.currentMovieBackdrop;
            int cTop = b != null ? b.dominantTop : 0xFF141D2E;
            int cMid = b != null ? b.dominantMid : 0xFF1B273D;
            int cBot = b != null ? b.dominantBottom : 0xFF090C13;

            fillP.setShader(new LinearGradient(0f, 0f, w, h,
                    new int[] { cBot, cMid, cTop },
                    new float[] { 0f, 0.55f, 1f }, Shader.TileMode.CLAMP));
            c.drawRect(0f, 0f, w, h, fillP);

            // Soft ambient color bloom behind the top-right Control Center panel
            int bloom = (0x88 << 24) | (cTop & 0x00FFFFFF);
            fillP.setShader(new android.graphics.RadialGradient(w * 0.82f, h * 0.24f, Math.max(w, h) * 0.65f,
                    new int[] { bloom, 0x00000000 },
                    new float[] { 0f, 1f }, Shader.TileMode.CLAMP));
            c.drawRect(0f, 0f, w, h, fillP);

            // Subtle vignette toward the left so the right-side Liquid Glass tiles pop crisply
            fillP.setShader(new LinearGradient(0f, 0f, w, 0f,
                    new int[] { 0x5506080D, 0x2206080D, 0x1006080D },
                    new float[] { 0f, 0.55f, 1f }, Shader.TileMode.CLAMP));
            c.drawRect(0f, 0f, w, h, fillP);
            fillP.setShader(null);
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth();
            float h = getHeight();

            // Layer 1 (100% opaque): Exact selected movie gradient backdrop from Home
            drawMovieBackdrop(c, w, h);

            // Layer 2: Compact Apple TV Control Center (~1/3 footprint, top-right corner)
            float rightMargin = 38f * d;
            float panelW = 248f * d;
            float panelLeft = w - rightMargin - panelW;
            float gap = 9f * d;

            float topY = 34f * d;
            drawTopBar(c, w - rightMargin, topY);

            float gridTop = topY + 28f * d;
            panelBounds.set(panelLeft - 12f * d, topY - 18f * d, w - rightMargin + 12f * d, gridTop + 260f * d);

            if (mode == MODE_AUDIO) {
                drawSubPanel(c, panelLeft, gridTop, panelW, "Saída de Áudio",
                        audioBusy ? "Conectando…" : "Alto-falante e Bluetooth");
                return;
            }
            if (mode == MODE_SLEEP) {
                drawSubPanel(c, panelLeft, gridTop, panelW, "Sleep Timer",
                        sleepLeftMin > 0 ? "Desliga em " + sleepLeftMin + " min" : "Tempo para desligar");
                return;
            }

            float colW = (panelW - gap) / 2f;
            float pillH = 44f * d;
            float tallH = pillH * 2f + gap;

            rects[T_POWER].set(panelLeft, gridTop, panelLeft + colW, gridTop + tallH);
            rects[T_WIFI].set(panelLeft + colW + gap, gridTop, panelLeft + panelW, gridTop + pillH);
            rects[T_KEYSTONE].set(panelLeft + colW + gap, gridTop + pillH + gap, panelLeft + panelW, gridTop + tallH);

            float row3Top = gridTop + tallH + gap;
            rects[T_AUDIO].set(panelLeft, row3Top, panelLeft + colW, row3Top + pillH);
            rects[T_SLEEP].set(panelLeft + colW + gap, row3Top, panelLeft + panelW, row3Top + pillH);

            float row4Top = row3Top + pillH + gap;
            float circleD = (panelW - gap * 3f) / 4f;
            for (int i = 0; i < 4; i++) {
                float cx = panelLeft + i * (circleD + gap);
                rects[T_HDMI + i].set(cx, row4Top, cx + circleD, row4Top + circleD);
            }

            drawPowerTile(c, rects[T_POWER], anim[T_POWER]);
            drawPillTile(c, rects[T_WIFI], anim[T_WIFI], "Wi-Fi", wifiName, 0xFF38C6FF, GLYPH_WIFI, true);
            drawPillTile(c, rects[T_KEYSTONE], anim[T_KEYSTONE], "Auto-Keystone",
                    autoKeystone ? "Ligado" : "Desligado",
                    autoKeystone ? 0xFF38C6FF : 0xFFE8F0FA, GLYPH_KEYSTONE, autoKeystone);
            drawPillTile(c, rects[T_AUDIO], anim[T_AUDIO], activeAudioName,
                    btAudioActive ? "Bluetooth" : "Projetor",
                    btAudioActive ? 0xFF38C6FF : 0xFFE8F0FA, GLYPH_AIRPODS, btAudioActive);
            drawPillTile(c, rects[T_SLEEP], anim[T_SLEEP], "Sleep Timer",
                    sleepLeftMin > 0 ? sleepLeftMin + " min" : "Desligado",
                    sleepLeftMin > 0 ? 0xFFFFB74D : 0xFFE8F0FA, GLYPH_TIMER, sleepLeftMin > 0);

            drawCircleTile(c, rects[T_HDMI], anim[T_HDMI], GLYPH_HDMI, "HDMI");
            drawCircleTile(c, rects[T_PROJ], anim[T_PROJ], GLYPH_PROJ, "Projeção");
            drawCircleTile(c, rects[T_PICTURE], anim[T_PICTURE], GLYPH_PICTURE, "Imagem");
            drawCircleTile(c, rects[T_SETTINGS], anim[T_SETTINGS], GLYPH_SETTINGS, "Ajustes");
        }

        private void drawTopBar(Canvas c, float rightX, float cy) {
            float avR = 11.5f * d;
            float avX = rightX - avR;
            float ccR = 12.5f * d;
            float ccX = avX - avR - 9f * d - ccR;
            float homeX = ccX - ccR - 14f * d;
            float wifiX = homeX - 21f * d;
            float spkX = wifiX - 21f * d;
            float clockRightX = btAudioActive ? (spkX - 14f * d) : (wifiX - 15f * d);
            String time = clockFmt.format(new Date());
            clockP.setTextAlign(Paint.Align.RIGHT);
            float clockW = clockP.measureText(time);

            // 0. Liquid Glass Capsule behind the entire top-right status bar (matches Home)
            float capR = 16.5f * d;
            float capLeft = clockRightX - clockW - 12f * d;
            float capRight = rightX + 4.5f * d;
            tmpRect.set(capLeft, cy - capR, capRight, cy + capR);
            scene.liquid(c, tmpRect, capR, 0xFF141820, 0.48f, 0.018f, 6f * d, 3.5f * d, 0.48f, 1f);
            strokeP.setColor(0x38FFFFFF);
            strokeP.setStrokeWidth(0.9f * d);
            c.drawRoundRect(tmpRect, capR, capR, strokeP);

            // 1. Apple Music Profile Avatar (far right)
            Bitmap avatar = ProjectorHardware.profileAvatar(getContext());
            if (avatar != null) {
                c.save();
                Path clip = new Path();
                clip.addCircle(avX, cy, avR, Path.Direction.CW);
                c.clipPath(clip);
                tmpRect.set(avX - avR, cy - avR, avX + avR, cy + avR);
                c.drawBitmap(avatar, null, tmpRect, bgPaint);
                c.restore();
            } else {
                fillP.setShader(new LinearGradient(avX - avR, cy - avR, avX + avR, cy + avR,
                        0xFFFA233B, 0xFFFB5C74, Shader.TileMode.CLAMP));
                c.drawCircle(avX, cy, avR, fillP);
                fillP.setShader(null);
                titleP.setTextSize(9.5f * d);
                titleP.setColor(Color.WHITE);
                titleP.setTextAlign(Paint.Align.CENTER);
                c.drawText("P", avX, cy + 3.4f * d, titleP);
            }
            strokeP.setColor(0xCCFFFFFF);
            strokeP.setStrokeWidth(1.0f * d);
            c.drawCircle(avX, cy, avR, strokeP);

            // 2. Active Control Center white circle (SF Symbols 7)
            fillP.setShader(null);
            fillP.setColor(0xF5FFFFFF);
            c.drawCircle(ccX, cy, ccR, fillP);
            drawGlyph(c, GLYPH_TOGGLES, ccX, cy, 7.6f * d, 0xFF14181F);

            // 3. HomeKit House icon
            drawGlyph(c, GLYPH_HOME, homeX, cy, 7.0f * d, 0xE6FFFFFF);

            // 4. Wi-Fi icon
            drawGlyph(c, GLYPH_WIFI, wifiX, cy, 7.0f * d, 0xE6FFFFFF);

            // 5. Bluetooth Speaker (only when BT connected)
            if (btAudioActive) {
                drawGlyph(c, GLYPH_BT_SPEAKER, spkX, cy, 7.0f * d, 0xF2FFFFFF);
            }

            // 6. Live Clock
            c.drawText(time, clockRightX, cy + 4.3f * d, clockP);
        }

        /** Uses Paçoca OsTV's native Scene.liquid() Liquid Glass shader tinted by the movie's dominant hue. */
        private void drawGlassSurface(Canvas c, RectF r, float radius, float f) {
            float scale = 1f + 0.05f * f;
            float hw = r.width() * 0.5f * scale, hh = r.height() * 0.5f * scale;
            tmpRect.set(r.centerX() - hw, r.centerY() - hh, r.centerX() + hw, r.centerY() + hh);
            float rad = radius * scale;

            Backdrop b = HomeView.currentMovieBackdrop;
            int glassTint = b != null ? b.dominantMid : 0xFF1A222C;

            // Unfocused: native Scene.liquid() smoked glass tinted with the movie's dominant color
            scene.liquid(c, tmpRect, rad, glassTint, 0.44f, 0.016f, 5.5f * d, 3.5f * d, 0.44f, 1f - f);

            // Focused: crisp Apple TV luminous white surface
            if (f > 0.01f) {
                fillP.setShader(null);
                fillP.setColor(blendColor(0x00FFFFFF, 0xF5FFFFFF, f));
                c.drawRoundRect(tmpRect, rad, rad, fillP);
            }

            // Razor-thin specular rim
            strokeP.setShader(null);
            strokeP.setColor(blendColor(0x38FFFFFF, 0xFFFFFFFF, f));
            strokeP.setStrokeWidth(0.9f * d);
            c.drawRoundRect(tmpRect, rad, rad, strokeP);
        }

        private void drawPowerTile(Canvas c, RectF r, float f) {
            float rad = 20f * d;
            drawGlassSurface(c, r, rad, f);
            c.save();
            float scale = 1f + 0.05f * f;
            c.scale(scale, scale, r.centerX(), r.centerY());
            int ink = blendColor(0xF5FFFFFF, 0xFF14181F, f);
            drawGlyph(c, GLYPH_POWER, r.centerX(), r.centerY() - 8f * d, 17f * d, ink);
            titleP.setTextAlign(Paint.Align.CENTER);
            titleP.setTextSize(10f * d);
            titleP.setColor(ink);
            c.drawText("Desligar", r.centerX(), r.bottom - 13f * d, titleP);
            c.restore();
        }

        private void drawPillTile(Canvas c, RectF r, float f, String label, String sub, int accent, int glyph, boolean highlighted) {
            float rad = r.height() / 2f;
            drawGlassSurface(c, r, rad, f);
            c.save();
            float scale = 1f + 0.05f * f;
            c.scale(scale, scale, r.centerX(), r.centerY());

            float iconCx = r.left + 19f * d;
            float iconCy = r.centerY();
            if (glyph == GLYPH_KEYSTONE || glyph == GLYPH_TIMER) {
                fillP.setColor(blendColor(highlighted ? 0x3338C6FF : 0x22FFFFFF, 0x16000000, f));
                c.drawCircle(iconCx, iconCy, 11f * d, fillP);
            }
            int glyphColor = f > 0.5f ? (highlighted ? 0xFF007AFF : 0xFF14181F) : accent;
            drawGlyph(c, glyph, iconCx, iconCy, 7.6f * d, glyphColor);

            float textX = r.left + 35f * d;
            float maxTextW = r.right - textX - 9f * d;
            titleP.setTextAlign(Paint.Align.LEFT);
            titleP.setTextSize(9.2f * d);
            titleP.setColor(blendColor(0xF5FFFFFF, 0xFF14181F, f));
            String ellTitle = TextUtils.ellipsize(label, titleP, maxTextW, TextUtils.TruncateAt.END).toString();
            c.drawText(ellTitle, textX, r.centerY() - 1.5f * d, titleP);

            subP.setTextAlign(Paint.Align.LEFT);
            subP.setTextSize(7.8f * d);
            subP.setColor(blendColor(0xB8FFFFFF, 0x9914181F, f));
            String ellSub = TextUtils.ellipsize(sub, subP, maxTextW, TextUtils.TruncateAt.END).toString();
            c.drawText(ellSub, textX, r.centerY() + 9.2f * d, subP);
            c.restore();
        }

        private void drawCircleTile(Canvas c, RectF r, float f, int glyph, String caption) {
            float rad = r.width() / 2f;
            drawGlassSurface(c, r, rad, f);
            c.save();
            float scale = 1f + 0.06f * f;
            c.scale(scale, scale, r.centerX(), r.centerY());
            int ink = blendColor(0xF5FFFFFF, 0xFF14181F, f);
            drawGlyph(c, glyph, r.centerX(), r.centerY(), 10.5f * d, ink);
            c.restore();

            if (f > 0.05f) {
                subP.setTextAlign(Paint.Align.CENTER);
                subP.setTextSize(8.2f * d);
                subP.setColor(((int) (f * 235) << 24) | 0x00FFFFFF);
                c.drawText(caption, r.centerX(), r.bottom + 12f * d, subP);
            }
        }

        private void drawSubPanel(Canvas c, float left, float top, float width, String heading, String subtitle) {
            titleP.setTextAlign(Paint.Align.LEFT);
            titleP.setTextSize(13f * d);
            titleP.setColor(Color.WHITE);
            c.drawText(heading, left + 4f * d, top + 13f * d, titleP);

            subP.setTextAlign(Paint.Align.LEFT);
            subP.setTextSize(8.5f * d);
            subP.setColor(0xB0FFFFFF);
            c.drawText(subtitle, left + 4f * d, top + 25f * d, subP);

            float y = top + 36f * d;
            float rowH = 38f * d;
            float gap = 7f * d;
            subRects.clear();

            int count = subItemCount();
            for (int i = 0; i < count; i++) {
                RectF r = new RectF(left, y, left + width, y + rowH);
                subRects.add(r);
                float f = i < anim.length ? anim[i] : (i == subFocus ? 1f : 0f);
                drawGlassSurface(c, r, 15f * d, f);

                String label, rightText;
                boolean active;
                int glyph;

                if (mode == MODE_AUDIO) {
                    if (i < audioTargets.size()) {
                        ProjectorHardware.AudioTarget t = audioTargets.get(i);
                        label = t.name;
                        active = t.connected;
                        rightText = active ? "✓ Ativo" : (t.isBluetooth ? "Conectar" : "Selecionar");
                        glyph = t.isBluetooth ? GLYPH_AIRPODS : GLYPH_SPEAKER;
                    } else {
                        label = "Parear novo Bluetooth…";
                        active = false;
                        rightText = "›";
                        glyph = GLYPH_SETTINGS;
                    }
                } else {
                    int mins = SLEEP_OPTIONS[i];
                    label = mins == 0 ? "Desligado" : mins + " minutos";
                    active = (mins == 0 && sleepLeftMin <= 0) || (mins > 0 && Math.abs(sleepLeftMin - mins) <= 5);
                    rightText = active ? "✓" : "";
                    glyph = GLYPH_TIMER;
                }

                c.save();
                float scale = 1f + 0.035f * f;
                c.scale(scale, scale, r.centerX(), r.centerY());
                int ink = blendColor(0xF5FFFFFF, 0xFF14181F, f);
                int iconCol = active ? (f > 0.5f ? 0xFF007AFF : 0xFF38C6FF) : ink;
                drawGlyph(c, glyph, r.left + 18f * d, r.centerY(), 7.5f * d, iconCol);

                titleP.setTextAlign(Paint.Align.LEFT);
                titleP.setTextSize(9.5f * d);
                titleP.setColor(ink);
                float maxW = r.width() - 92f * d;
                String ell = TextUtils.ellipsize(label, titleP, maxW, TextUtils.TruncateAt.END).toString();
                c.drawText(ell, r.left + 33f * d, r.centerY() + 3.5f * d, titleP);

                subP.setTextAlign(Paint.Align.RIGHT);
                subP.setTextSize(8.5f * d);
                subP.setColor(active ? (f > 0.5f ? 0xFF007AFF : 0xFF38C6FF) : blendColor(0xAAFFFFFF, 0x9914181F, f));
                c.drawText(rightText, r.right - 13f * d, r.centerY() + 3.2f * d, subP);
                c.restore();

                y += rowH + gap;
            }
        }

        // ─── Official Apple SF Symbols 7 Glyphs ──────────────────────────────────

        private static final int GLYPH_POWER = 0, GLYPH_WIFI = 1, GLYPH_KEYSTONE = 2, GLYPH_AIRPODS = 3,
                GLYPH_TIMER = 4, GLYPH_HDMI = 5, GLYPH_PROJ = 6, GLYPH_PICTURE = 7, GLYPH_SETTINGS = 8,
                GLYPH_TOGGLES = 9, GLYPH_HOME = 10, GLYPH_WAVE = 11, GLYPH_SPEAKER = 12, GLYPH_BT_SPEAKER = 13;

        private void drawGlyph(Canvas c, int kind, float cx, float cy, float r, int color) {
            String name;
            switch (kind) {
                case GLYPH_POWER:      name = "power"; break;
                case GLYPH_WIFI:       name = "wifi"; break;
                case GLYPH_KEYSTONE:   name = "keystone"; break;
                case GLYPH_AIRPODS:    name = "airpods"; break;
                case GLYPH_SPEAKER:    name = "speaker"; break;
                case GLYPH_TIMER:      name = "timer"; break;
                case GLYPH_HDMI:       name = "hdmi"; break;
                case GLYPH_PROJ:       name = "projector"; break;
                case GLYPH_PICTURE:    name = "sunny"; break;
                case GLYPH_SETTINGS:   name = "tv_settings"; break;
                case GLYPH_TOGGLES:    name = "control_center"; break;
                case GLYPH_HOME:       name = "homekit"; break;
                case GLYPH_BT_SPEAKER: name = "hifispeaker"; break;
                default: return;
            }
            Symbols.draw(getContext(), c, name, cx, cy, r * 2.15f, color);
        }

        private int blendColor(int a, int b, float t) {
            float k = Math.max(0f, Math.min(1f, t));
            int aa = (a >>> 24) & 0xFF, ar = (a >>> 16) & 0xFF, ag = (a >>> 8) & 0xFF, ab = a & 0xFF;
            int ba = (b >>> 24) & 0xFF, br = (b >>> 16) & 0xFF, bg = (b >>> 8) & 0xFF, bb = b & 0xFF;
            return ((int) (aa + (ba - aa) * k) << 24)
                    | ((int) (ar + (br - ar) * k) << 16)
                    | ((int) (ag + (bg - ag) * k) << 8)
                    | (int) (ab + (bb - ab) * k);
        }
    }
}
