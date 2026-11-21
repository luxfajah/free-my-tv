package io.freemytv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Region;
import android.graphics.Shader;
import android.os.Build;
import android.util.TypedValue;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.View;

/**
 * Apple tvOS / iOS / visionOS Liquid Glass Vertical Volume HUD integrated into Paçoca OsTV.
 * - Expanded width: 56dp | Compact width: 36dp | Height: 180dp (rounded-full)
 * - Glassmorphism Apple: bg-white/15, border-white/30, inner specular thickness, pure white fill (bg-white/90)
 * - Dynamic speaker SF Symbol at base (mute, low, medium, high) with color inversion above 18%
 * - Rubber-banding overshoot (scaleY: 1.06) at 0% and 100% bounds
 */
public class LiquidGlassVolumeView extends View implements Choreographer.FrameCallback {

    public interface OnVolumeScrubListener {
        void onVolumeScrubbed(float normalizedLevel);
        void onInteractionActive();
    }

    public static final int STYLE_REGULAR_DARK = 0;
    public static final int STYLE_REGULAR_LIGHT = 1;
    public static final int STYLE_ULTRA_CLEAR = 2;

    private float targetLevel = 0.65f;
    private float currentLevel = 0.65f;
    private float levelVelocity = 0.0f;

    private float targetExpand = 1.0f; // 1.0 = expanded (56dp), 0.0 = compact (36dp)
    private float currentExpand = 1.0f;
    private float expandVelocity = 0.0f;

    private float targetOvershoot = 0.0f; // -1.0 (0% bottom stretch) ... +1.0 (100% top stretch)
    private float currentOvershoot = 0.0f;
    private float overshootVelocity = 0.0f;

    private boolean isMuted = false;
    private int glassStyle = STYLE_REGULAR_DARK;
    private boolean alignLeft = false;

    private final float expandedWidthPx;
    private final float compactWidthPx;
    private final float pillHeightPx;
    private final float shadowPadPx;
    private final float strokeWidthPx;

    private final Paint outerHaloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fresnelHighlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint innerGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint meniscusPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF capsuleRect = new RectF();
    private final RectF haloRect = new RectF();
    private final RectF innerRimRect = new RectF();
    private final RectF fresnelRect = new RectF();
    private final RectF fillRect = new RectF();
    private final Path clipPath = new Path();
    private final Path speakerBodyPath = new Path();
    private final RectF waveArcRect = new RectF();

    private boolean isAnimating = false;
    private long lastFrameTimeNanos = 0L;
    private OnVolumeScrubListener scrubListener;

    public LiquidGlassVolumeView(Context context) {
        super(context);
        expandedWidthPx = dp(56f);
        compactWidthPx = dp(36f);
        pillHeightPx = dp(180f);
        shadowPadPx = dp(16f);
        strokeWidthPx = dp(1.25f);

        outerHaloPaint.setStyle(Paint.Style.STROKE);

        rimPaint.setStyle(Paint.Style.STROKE);
        rimPaint.setStrokeWidth(strokeWidthPx);

        innerGlowPaint.setStyle(Paint.Style.STROKE);
        innerGlowPaint.setStrokeWidth(dp(1.6f));

        meniscusPaint.setStyle(Paint.Style.STROKE);
        meniscusPaint.setStrokeWidth(dp(1.6f));

        iconFillPaint.setStyle(Paint.Style.FILL);
        iconStrokePaint.setStyle(Paint.Style.STROKE);
        iconStrokePaint.setStrokeCap(Paint.Cap.ROUND);
        iconStrokePaint.setStrokeJoin(Paint.Join.ROUND);
        iconStrokePaint.setStrokeWidth(dp(1.85f));

        setContentDescription("Volume Liquid Glass");
    }

    private float dp(float value) {
        return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value,
                getResources().getDisplayMetrics()
        );
    }

    public void setOnVolumeScrubListener(OnVolumeScrubListener listener) {
        this.scrubListener = listener;
    }

    public void setGlassStyle(int style) {
        this.glassStyle = style;
        invalidate();
    }

    public int getGlassStyle() {
        return glassStyle;
    }

    public void setAlignLeft(boolean left) {
        this.alignLeft = left;
        invalidate();
    }

    public boolean isAlignLeft() {
        return alignLeft;
    }

    public void setVolumeLevel(float level, boolean animate) {
        float clamped = Math.max(0f, Math.min(1f, level));
        this.targetLevel = clamped;
        if (!animate) {
            this.currentLevel = clamped;
            this.levelVelocity = 0f;
            invalidate();
        } else {
            startSpringLoop();
        }
    }

    public float getVolumeLevel() {
        return targetLevel;
    }

    public void setMuted(boolean muted) {
        this.isMuted = muted;
        startSpringLoop();
    }

    public boolean isMuted() {
        return isMuted;
    }

    public void setExpanded(boolean expanded, boolean animate) {
        this.targetExpand = expanded ? 1.0f : 0.0f;
        if (!animate) {
            this.currentExpand = this.targetExpand;
            this.expandVelocity = 0f;
            invalidate();
        } else {
            startSpringLoop();
        }
    }

    public boolean isExpanded() {
        return targetExpand > 0.5f;
    }

    public void triggerOvershoot(float direction) {
        this.targetOvershoot = Math.max(-1f, Math.min(1f, direction));
        startSpringLoop();
        postDelayed(() -> {
            targetOvershoot = 0.0f;
            startSpringLoop();
        }, 150);
    }

    private void startSpringLoop() {
        if (!isAnimating) {
            isAnimating = true;
            lastFrameTimeNanos = System.nanoTime();
            Choreographer.getInstance().postFrameCallback(this);
        }
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        if (!isAnimating) return;

        float dt = (frameTimeNanos - lastFrameTimeNanos) / 1_000_000_000f;
        lastFrameTimeNanos = frameTimeNanos;
        if (dt <= 0f || dt > 0.05f) dt = 0.016f;

        final float stiffness = 380f;
        final float damping = 30f;

        float effectiveTargetLevel = isMuted ? 0f : targetLevel;
        float levelForce = (effectiveTargetLevel - currentLevel) * 420f - levelVelocity * 32f;
        levelVelocity += levelForce * dt;
        currentLevel += levelVelocity * dt;

        float expandForce = (targetExpand - currentExpand) * stiffness - expandVelocity * damping;
        expandVelocity += expandForce * dt;
        currentExpand += expandVelocity * dt;

        float overshootForce = (targetOvershoot - currentOvershoot) * 440f - overshootVelocity * 24f;
        overshootVelocity += overshootForce * dt;
        currentOvershoot += overshootVelocity * dt;

        invalidate();

        boolean settled =
                Math.abs(effectiveTargetLevel - currentLevel) < 0.0015f && Math.abs(levelVelocity) < 0.005f &&
                Math.abs(targetExpand - currentExpand) < 0.002f && Math.abs(expandVelocity) < 0.005f &&
                Math.abs(targetOvershoot - currentOvershoot) < 0.002f && Math.abs(overshootVelocity) < 0.005f;

        if (settled) {
            currentLevel = effectiveTargetLevel;
            currentExpand = targetExpand;
            currentOvershoot = targetOvershoot;
            isAnimating = false;
            invalidate();
        } else {
            Choreographer.getInstance().postFrameCallback(this);
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = (int) (expandedWidthPx + shadowPadPx * 2f);
        int h = (int) (pillHeightPx + shadowPadPx * 2.4f);
        setMeasuredDimension(w, h);
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float expandClamped = Math.max(0f, Math.min(1.1f, currentExpand));
        float currentWidth = compactWidthPx + (expandedWidthPx - compactWidthPx) * expandClamped;
        float radius = currentWidth / 2f;

        float viewW = getWidth();
        float viewH = getHeight();
        float left = alignLeft ? shadowPadPx : (viewW - shadowPadPx - currentWidth);
        float top = (viewH - pillHeightPx) / 2f;
        float right = left + currentWidth;
        float bottom = top + pillHeightPx;

        capsuleRect.set(left, top, right, bottom);

        // Rubber-banding overshoot: scaleY = 1.06, scaleX = 0.95
        float stretchY = 1.0f + Math.abs(currentOvershoot) * 0.06f;
        float squashX = 1.0f - Math.abs(currentOvershoot) * 0.045f;
        float pivotX = capsuleRect.centerX();
        float pivotY = currentOvershoot >= 0f ? capsuleRect.bottom : capsuleRect.top;

        canvas.save();
        canvas.scale(squashX, stretchY, pivotX, pivotY);

        clipPath.reset();
        clipPath.addRoundRect(capsuleRect, radius, radius, Path.Direction.CW);

        // 1. Soft Outer Ambient Shadow strictly OUTSIDE the capsule
        int haloSave = canvas.save();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            canvas.clipOutPath(clipPath);
        } else {
            canvas.clipPath(clipPath, Region.Op.DIFFERENCE);
        }
        for (int i = 1; i <= 4; i++) {
            float offset = dp(i * 1.6f);
            haloRect.set(
                    capsuleRect.left - offset * 0.5f,
                    capsuleRect.top - offset * 0.2f + dp(1.5f),
                    capsuleRect.right + offset * 0.5f,
                    capsuleRect.bottom + offset * 0.6f + dp(2.0f)
            );
            outerHaloPaint.setStrokeWidth(dp(2.4f));
            int alpha = (5 - i) * 11;
            outerHaloPaint.setColor(Color.argb(alpha, 0, 0, 0));
            float hr = haloRect.width() / 2f;
            canvas.drawRoundRect(haloRect, hr, hr, outerHaloPaint);
        }
        canvas.restoreToCount(haloSave);

        // 2. Clipped Pure Translucent Glass Interior
        configureGlassMaterialPaints(capsuleRect);
        int clipSave = canvas.save();
        canvas.clipPath(clipPath);

        // 2a. Translucent Glass Backing (bg-white/15)
        canvas.drawRoundRect(capsuleRect, radius, radius, trackPaint);

        // 2b. Pure White Internal Level Fill (bg-white/90) rising from bottom to top
        float fillFraction = Math.max(0f, Math.min(1f, currentLevel));
        if (fillFraction > 0.002f) {
            float fillHeight = capsuleRect.height() * fillFraction;
            fillRect.set(
                    capsuleRect.left,
                    capsuleRect.bottom - fillHeight,
                    capsuleRect.right,
                    capsuleRect.bottom
            );
            int fillTopColor = 0xE6FFFFFF; // bg-white/90
            int fillMidColor = 0xE6FFFFFF;
            int fillBotColor = 0xD9F8FAFF;
            fillPaint.setShader(new LinearGradient(
                    fillRect.left, fillRect.top,
                    fillRect.right, fillRect.bottom,
                    new int[]{fillTopColor, fillMidColor, fillBotColor},
                    new float[]{0.0f, 0.55f, 1.0f},
                    Shader.TileMode.CLAMP
            ));
            canvas.drawRect(fillRect, fillPaint);

            if (fillFraction < 0.98f) {
                meniscusPaint.setShader(new LinearGradient(
                        fillRect.left, fillRect.top,
                        fillRect.right, fillRect.top,
                        new int[]{0x44FFFFFF, 0xEEFFFFFF, 0x55FFFFFF},
                        new float[]{0.0f, 0.45f, 1.0f},
                        Shader.TileMode.CLAMP
                ));
                canvas.drawLine(
                        fillRect.left + dp(2f),
                        fillRect.top + dp(0.6f),
                        fillRect.right - dp(2f),
                        fillRect.top + dp(0.6f),
                        meniscusPaint
                );
            }
        }

        // 2c. Cylindrical Specular Glass Sheen
        float stripeInset = dp(3.2f);
        float stripeWidth = Math.max(dp(2.0f), currentWidth * 0.13f);
        fresnelRect.set(
                capsuleRect.left + stripeInset,
                capsuleRect.top + dp(10f),
                capsuleRect.left + stripeInset + stripeWidth,
                capsuleRect.bottom - dp(10f)
        );
        fresnelHighlightPaint.setShader(new LinearGradient(
                fresnelRect.left, fresnelRect.top,
                fresnelRect.right, fresnelRect.top,
                0x38FFFFFF, 0x00FFFFFF,
                Shader.TileMode.CLAMP
        ));
        canvas.drawRoundRect(fresnelRect, stripeWidth / 2f, stripeWidth / 2f, fresnelHighlightPaint);

        // 2d. Inner Glass Thickness Shadow
        float innerInset = dp(1.2f);
        innerRimRect.set(
                capsuleRect.left + innerInset,
                capsuleRect.top + innerInset,
                capsuleRect.right - innerInset,
                capsuleRect.bottom - innerInset
        );
        float innerRad = Math.max(0f, radius - innerInset);
        canvas.drawRoundRect(innerRimRect, innerRad, innerRad, innerGlowPaint);

        canvas.restoreToCount(clipSave);

        // 3. Specular Border (border-white/30)
        canvas.drawRoundRect(capsuleRect, radius, radius, rimPaint);

        // 4. Dynamic Speaker Icon at Base (scales smoothly 1.0x in 56dp -> 0.80x in 36dp)
        float iconScale = 0.80f + 0.20f * Math.max(0f, Math.min(1f, expandClamped));
        drawSFSymbolSpeaker(canvas, capsuleRect, fillFraction, iconScale);

        canvas.restore();
    }

    private void configureGlassMaterialPaints(RectF rect) {
        trackPaint.setShader(new LinearGradient(
                rect.left, rect.top, rect.right, rect.bottom,
                new int[]{0x29FFFFFF, 0x22FFFFFF, 0x26E2ECFF},
                new float[]{0.0f, 0.5f, 1.0f},
                Shader.TileMode.CLAMP
        ));
        innerGlowPaint.setShader(new LinearGradient(
                rect.left, rect.top, rect.left, rect.bottom,
                0x66FFFFFF, 0x22000000, Shader.TileMode.CLAMP
        ));
        rimPaint.setShader(new LinearGradient(
                rect.left, rect.top, rect.right, rect.bottom,
                new int[]{0x66FFFFFF, 0x4DFFFFFF, 0x4DFFFFFF},
                new float[]{0.0f, 0.5f, 1.0f},
                Shader.TileMode.CLAMP
        ));
    }

    private void drawSFSymbolSpeaker(Canvas canvas, RectF rect, float fillFraction, float scaleFactor) {
        float cx = rect.centerX();
        float cy = rect.bottom - dp(20f);

        boolean coveredByFill = fillFraction > 0.18f;
        int color = coveredByFill ? 0xFF171717 : 0xE6FFFFFF;

        iconFillPaint.setColor(color);
        iconStrokePaint.setColor(color);

        float s = dp(1.0f) * scaleFactor;
        iconStrokePaint.setStrokeWidth(dp(1.85f) * scaleFactor);
        float sx = cx - 3.5f * s;

        speakerBodyPath.reset();
        speakerBodyPath.moveTo(sx - 5.2f * s, cy - 2.6f * s);
        speakerBodyPath.lineTo(sx - 1.8f * s, cy - 2.6f * s);
        speakerBodyPath.lineTo(sx + 2.8f * s, cy - 6.8f * s);
        speakerBodyPath.lineTo(sx + 2.8f * s, cy + 6.8f * s);
        speakerBodyPath.lineTo(sx - 1.8f * s, cy + 2.6f * s);
        speakerBodyPath.lineTo(sx - 5.2f * s, cy + 2.6f * s);
        speakerBodyPath.close();

        canvas.drawPath(speakerBodyPath, iconFillPaint);

        if (isMuted || fillFraction <= 0.005f) {
            canvas.drawLine(
                    cx - 7.5f * s, cy - 7.5f * s,
                    cx + 7.5f * s, cy + 7.5f * s,
                    iconStrokePaint
            );
            return;
        }

        float r1 = 4.2f * s;
        waveArcRect.set(sx + 1.2f * s - r1, cy - r1, sx + 1.2f * s + r1, cy + r1);
        canvas.drawArc(waveArcRect, -40f, 80f, false, iconStrokePaint);

        if (fillFraction >= 0.34f) {
            float r2 = 7.2f * s;
            waveArcRect.set(sx + 1.2f * s - r2, cy - r2, sx + 1.2f * s + r2, cy + r2);
            canvas.drawArc(waveArcRect, -42f, 84f, false, iconStrokePaint);
        }

        if (fillFraction >= 0.67f) {
            float r3 = 10.2f * s;
            waveArcRect.set(sx + 1.2f * s - r3, cy - r3, sx + 1.2f * s + r3, cy + r3);
            canvas.drawArc(waveArcRect, -45f, 90f, false, iconStrokePaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
            if (scrubListener != null) {
                scrubListener.onInteractionActive();
            }
            if (targetExpand < 1.0f) {
                setExpanded(true, true);
            }
            float top = (getHeight() - pillHeightPx) / 2f;
            float progress = 1.0f - ((event.getY() - top) / pillHeightPx);

            if (progress > 1.0f) {
                targetLevel = 1.0f;
                targetOvershoot = Math.min(1.0f, (progress - 1.0f) * 2.4f);
            } else if (progress < 0.0f) {
                targetLevel = 0.0f;
                targetOvershoot = Math.max(-1.0f, progress * 2.4f);
            } else {
                isMuted = false;
                targetLevel = progress;
                targetOvershoot = 0.0f;
            }
            startSpringLoop();

            if (scrubListener != null) {
                scrubListener.onVolumeScrubbed(targetLevel);
            }
            return true;
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            targetOvershoot = 0.0f;
            startSpringLoop();
            return true;
        }
        return super.onTouchEvent(event);
    }
}
