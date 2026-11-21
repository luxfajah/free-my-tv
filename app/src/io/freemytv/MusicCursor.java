package io.freemytv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.webkit.WebView;

import java.util.function.Supplier;

/**
 * Mouse pointer for the remote in Apple Music website mode. Arrows move it: a press nudges, a hold glides and speeds up.
 * OK taps the page under it. Pushing past a screen edge scrolls the page instead.
 */
final class MusicCursor extends View implements Choreographer.FrameCallback {
    private static final long GLIDE_AFTER_MS = 180, HIDE_AFTER_MS = 4000, PULSE_MS = 250, HOVER_EVERY_MS = 100;

    private final Supplier<WebView> target;
    private final Runnable onTap;
    private final float nudge, minSpeed, maxSpeed, accel, wheelPx;
    private final Path arrow = new Path();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), edge = new Paint(Paint.ANTI_ALIAS_FLAG),
            ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Runnable hide = () -> animate().alpha(0).setDuration(400);
    private final Runnable hover = this::hoverNow;
    private long hoveredAt;
    private boolean hoverQueued;

    private float x = -1, y = -1;
    private int hx, hy;                 // direction being held, -1/0/1
    private long heldSince, lastFrame, tappedAt;
    private boolean framing, okDown;

    MusicCursor(Context c, Supplier<WebView> target, Runnable onTap) {
        super(c);
        this.target = target;
        this.onTap = onTap;
        setClickable(false);
        setFocusable(false);
        float dp = c.getResources().getDisplayMetrics().density;
        nudge = 16 * dp;
        minSpeed = 250 * dp;
        maxSpeed = 1300 * dp;
        accel = 1500 * dp;
        wheelPx = ViewConfiguration.get(c).getScaledVerticalScrollFactor();

        float s = 1.4f * dp;
        arrow.moveTo(0, 0);
        arrow.lineTo(0, 17 * s);
        arrow.lineTo(4.5f * s, 13 * s);
        arrow.lineTo(7.5f * s, 20 * s);
        arrow.lineTo(10.5f * s, 18.8f * s);
        arrow.lineTo(7.6f * s, 12 * s);
        arrow.lineTo(13 * s, 12 * s);
        arrow.close();
        fill.setColor(Color.WHITE);
        edge.setColor(Color.BLACK);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(1.5f * dp);
        edge.setStrokeJoin(Paint.Join.ROUND);
        ring.setColor(Color.WHITE);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(3 * dp);
    }

    static boolean handles(int key) {
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER: case KeyEvent.KEYCODE_BUTTON_A:
                return true;
        }
        return false;
    }

    boolean onKey(KeyEvent e) {
        int k = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        show();
        if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
                || k == KeyEvent.KEYCODE_NUMPAD_ENTER || k == KeyEvent.KEYCODE_BUTTON_A) {
            if (down) okDown = true;
            else if (okDown) { okDown = false; tap(); }
            return true;
        }
        int dx = k == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : k == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : 0;
        int dy = k == KeyEvent.KEYCODE_DPAD_UP ? -1 : k == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : 0;
        if (down) {
            if (e.getRepeatCount() == 0) {
                hx = dx;
                hy = dy;
                heldSince = e.getEventTime();
                move(dx * nudge, dy * nudge, true);
                if (!framing) {
                    framing = true;
                    lastFrame = 0;
                    Choreographer.getInstance().postFrameCallback(this);
                }
            }
        } else if (dx == hx && dy == hy) {
            hx = hy = 0;
        }
        return true;
    }

    void show() {
        removeCallbacks(hide);
        animate().cancel();
        setAlpha(1);
        postDelayed(hide, HIDE_AFTER_MS);
    }

    @Override
    public void doFrame(long frameNanos) {
        if (hx == 0 && hy == 0) { framing = false; return; }
        long now = frameNanos / 1_000_000;
        float dt = lastFrame == 0 ? 0 : Math.min(50, now - lastFrame) / 1000f;
        lastFrame = now;
        long held = now - heldSince;
        if (held > GLIDE_AFTER_MS) {
            float speed = Math.min(maxSpeed, minSpeed + accel * (held - GLIDE_AFTER_MS) / 1000f);
            move(hx * speed * dt, hy * speed * dt, false);
        }
        Choreographer.getInstance().postFrameCallback(this);
    }

    private void move(float dx, float dy, boolean nudged) {
        float w = getWidth(), h = getHeight();
        if (w == 0) return;
        float nx = x + dx, ny = y + dy;
        x = Math.max(0, Math.min(w - 1, nx));
        y = Math.max(0, Math.min(h - 1, ny));
        float ox = nx - x, oy = ny - y;
        WebView v = target.get();
        if (v != null) {
            if (ox != 0 || oy != 0) {
                float k = nudged ? 6 : 1.5f;
                if (oy != 0) wheel(v, x, h / 2, 0, -oy * k / wheelPx);
                if (ox != 0) wheel(v, w / 2, y, ox * k / wheelPx, 0);
            }
            hoverLater();
        }
        invalidate();
    }

    private void hoverLater() {
        if (hoverQueued) return;
        long wait = hoveredAt + HOVER_EVERY_MS - SystemClock.uptimeMillis();
        if (wait <= 0) { hoverNow(); return; }
        hoverQueued = true;
        postDelayed(hover, wait);
    }

    private void hoverNow() {
        hoverQueued = false;
        hoveredAt = SystemClock.uptimeMillis();
        WebView v = target.get();
        if (v != null) send(v, mouse(MotionEvent.ACTION_HOVER_MOVE, x, y, 0, 0), false);
    }

    private void wheel(WebView v, float px, float py, float hscroll, float vscroll) {
        send(v, mouse(MotionEvent.ACTION_SCROLL, px, py, hscroll, vscroll), false);
    }

    private void tap() {
        WebView v = target.get();
        if (v == null) return;
        long t = SystemClock.uptimeMillis();
        send(v, finger(t, t, MotionEvent.ACTION_DOWN), true);
        postDelayed(() -> send(v, finger(t, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP), true), 40);
        tappedAt = t;
        postInvalidateOnAnimation();
        onTap.run();
    }

    private static void send(WebView v, MotionEvent e, boolean touch) {
        if (touch) v.dispatchTouchEvent(e);
        else v.dispatchGenericMotionEvent(e);
        e.recycle();
    }

    private MotionEvent mouse(int action, float px, float py, float hscroll, float vscroll) {
        long t = SystemClock.uptimeMillis();
        MotionEvent.PointerProperties[] pp = { new MotionEvent.PointerProperties() };
        pp[0].id = 0;
        pp[0].toolType = MotionEvent.TOOL_TYPE_MOUSE;
        MotionEvent.PointerCoords[] pc = { new MotionEvent.PointerCoords() };
        pc[0].x = px;
        pc[0].y = py;
        pc[0].setAxisValue(MotionEvent.AXIS_HSCROLL, hscroll);
        pc[0].setAxisValue(MotionEvent.AXIS_VSCROLL, vscroll);
        return MotionEvent.obtain(t, t, action, 1, pp, pc, 0, 0, 1, 1, 0, 0, InputDevice.SOURCE_MOUSE, 0);
    }

    private MotionEvent finger(long downTime, long time, int action) {
        MotionEvent.PointerProperties[] pp = { new MotionEvent.PointerProperties() };
        pp[0].id = 0;
        pp[0].toolType = MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerCoords[] pc = { new MotionEvent.PointerCoords() };
        pc[0].x = x;
        pc[0].y = y;
        pc[0].pressure = 1;
        pc[0].size = 1;
        return MotionEvent.obtain(downTime, time, action, 1, pp, pc, 0, 0, 1, 1, 0, 0,
                InputDevice.SOURCE_TOUCHSCREEN, 0);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        if (x < 0) { x = w / 2f; y = h / 2f; }
        x = Math.min(x, w - 1);
        y = Math.min(y, h - 1);
    }

    @Override
    protected void onDraw(Canvas c) {
        long since = SystemClock.uptimeMillis() - tappedAt;
        if (since < PULSE_MS) {
            float f = since / (float) PULSE_MS;
            ring.setAlpha((int) (255 * (1 - f)));
            c.drawCircle(x, y, (8 + 22 * f) * getResources().getDisplayMetrics().density, ring);
            postInvalidateOnAnimation();
        }
        c.save();
        c.translate(x, y);
        c.drawPath(arrow, fill);
        c.drawPath(arrow, edge);
        c.restore();
    }
}
