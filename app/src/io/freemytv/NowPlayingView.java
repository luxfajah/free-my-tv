package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.text.TextUtils;

import java.util.Collections;
import java.util.List;

final class NowPlayingView {
    private final Context context;
    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint artPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint artistPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint brandPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lyricActivePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lyricFadedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF artRect = new RectF();
    private final RectF shadowRect = new RectF();
    private final RectF barRect = new RectF();
    private final Matrix artMatrix = new Matrix();

    private List<LyricsEngine.Line> lyrics = Collections.emptyList();
    private String lyricsTrack = "";
    private boolean lyricsSynced = false;
    private float smoothScrollY = 0;
    private int dominantColor1 = 0xFF2A0845;
    private int dominantColor2 = 0xFF140728;
    private Bitmap lastArt = null;

    NowPlayingView(Context c) {
        context = c;
        titlePaint.setColor(0xFFFFFFFF);
        titlePaint.setTextSize(32);
        titlePaint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));

        artistPaint.setColor(0xFFC7BED9);
        artistPaint.setTextSize(24);
        artistPaint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));

        brandPaint.setColor(0xFFFFFFFF);
        brandPaint.setTextSize(36);
        brandPaint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));

        lyricActivePaint.setColor(0xFFFFFFFF);
        lyricActivePaint.setTextSize(46);
        lyricActivePaint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));

        lyricFadedPaint.setColor(0xFFDDD5EC);
        lyricFadedPaint.setTextSize(34);
        lyricFadedPaint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));

        barPaint.setColor(0xFFFFFFFF);
        barPaint.setStyle(Paint.Style.FILL);

        shadowPaint.setColor(0x88000000);
        shadowPaint.setStyle(Paint.Style.FILL);
    }

    void update(NowPlaying.MediaInfo info) {
        if (info == null) return;
        if (!info.title.equalsIgnoreCase(lyricsTrack)) {
            lyricsTrack = info.title;
            lyrics = Collections.emptyList();
            lyricsSynced = false;
            smoothScrollY = 0;
            LyricsEngine.fetch(info.title, info.artist, (t, a, lines, synced) -> {
                if (lyricsTrack.equalsIgnoreCase(t)) {
                    lyrics = lines;
                    lyricsSynced = synced;
                }
            });
        }
        if (info.art != lastArt && info.art != null) {
            lastArt = info.art;
            extractColors(info.art);
        }
    }

    private void extractColors(Bitmap b) {
        try {
            int w = b.getWidth();
            int h = b.getHeight();
            int c1 = b.getPixel(w / 4, h / 4);
            int c2 = b.getPixel((w * 3) / 4, (h * 3) / 4);

            int r1 = Math.min(255, (Color.red(c1) + 30));
            int g1 = Math.min(255, (Color.green(c1) + 10));
            int b1 = Math.min(255, (Color.blue(c1) + 40));

            dominantColor1 = Color.rgb(r1, g1, b1);
            dominantColor2 = Color.rgb(Math.max(10, Color.red(c2) / 3),
                                       Math.max(10, Color.green(c2) / 3),
                                       Math.max(20, Color.blue(c2) / 2));
        } catch (Exception ignored) {
            dominantColor1 = 0xFF350B54;
            dominantColor2 = 0xFF0D0B1C;
        }
    }

    void draw(Canvas c, float w, float h, NowPlaying.MediaInfo info) {
        if (info == null) {
            info = new NowPlaying.MediaInfo("coastline", "mehro", "Dark Corners", "Apple Music", null, 210000, 38500, true, null);
        }
        update(info);

        // 1. Animated Ambient Mesh Background
        long t = SystemClock.uptimeMillis();
        float pulse = 0.05f * (float) Math.sin(t / 800.0);
        float cx = w * (0.35f + pulse);
        float cy = h * (0.42f - pulse);

        RadialGradient grad = new RadialGradient(cx, cy, w * 0.75f,
                new int[]{dominantColor1, dominantColor2, 0xFF08090E},
                new float[]{0.0f, 0.55f, 1.0f}, Shader.TileMode.CLAMP);
        bgPaint.setShader(grad);
        c.drawRect(0, 0, w, h, bgPaint);

        // Vignette overlay
        bgPaint.setShader(new LinearGradient(0, 0, 0, h,
                new int[]{0x33000000, 0x00000000, 0x66000000},
                null, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, bgPaint);

        // 2. Left Column: Album Art
        float artX = 180, artY = 220, artSize = 440;
        artRect.set(artX, artY, artX + artSize, artY + artSize);

        // Shadow behind art
        shadowRect.set(artRect.left - 12, artRect.top + 8, artRect.right + 12, artRect.bottom + 24);
        shadowPaint.setColor(0x66000000);
        c.drawRoundRect(shadowRect, 28, 28, shadowPaint);

        if (info.art != null && !info.art.isRecycled()) {
            BitmapShader bs = new BitmapShader(info.art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            artMatrix.reset();
            float scale = Math.max(artSize / info.art.getWidth(), artSize / info.art.getHeight());
            artMatrix.setScale(scale, scale);
            artMatrix.postTranslate(artX, artY);
            bs.setLocalMatrix(artMatrix);
            artPaint.setShader(bs);
            c.drawRoundRect(artRect, 22, 22, artPaint);
        } else {
            // Elegant placeholder
            artPaint.setShader(new LinearGradient(artX, artY, artX + artSize, artY + artSize,
                    new int[]{0xFF4A148C, 0xFF1A237E}, null, Shader.TileMode.CLAMP));
            c.drawRoundRect(artRect, 22, 22, artPaint);
        }

        // Track metadata below artwork
        float textY = artY + artSize + 46;
        float cap = 20;
        float barWidth = NowPlaying.barsWidth(cap);

        NowPlaying.drawBars(c, artX, textY, cap, barPaint, info.isPlaying, barRect);

        String title = !TextUtils.isEmpty(info.title) ? info.title : (!TextUtils.isEmpty(info.app) ? info.app : "AirPlay Audio");
        if (title.length() > 28) title = title.substring(0, 26) + "...";
        c.drawText(title, artX + barWidth + 14, textY, titlePaint);

        if (!TextUtils.isEmpty(info.artist)) {
            String artist = info.artist;
            if (artist.length() > 36) artist = artist.substring(0, 34) + "...";
            c.drawText(artist, artX, textY + 34, artistPaint);
        }

        // 3. Apple Music Brand Logo at bottom left
        float brandY = 960;
        c.drawText("\uF8FFMusic", artX, brandY, brandPaint);

        // 4. Right Column: Time-Synced Lyrics
        float rightX = 760;
        float centerY = 500;

        if (lyrics != null && !lyrics.isEmpty()) {
            long currentPos = info.currentPositionMs();
            int curIndex = 0;
            for (int i = 0; i < lyrics.size(); i++) {
                if (lyrics.get(i).timeMs <= currentPos) {
                    curIndex = i;
                } else {
                    break;
                }
            }

            // Smooth interpolation to target index
            float targetY = curIndex * 70.0f;
            smoothScrollY += (targetY - smoothScrollY) * 0.15f;

            c.save();
            c.clipRect(rightX - 20, 140, w - 100, 920);

            for (int i = 0; i < lyrics.size(); i++) {
                LyricsEngine.Line line = lyrics.get(i);
                float lineY = centerY + (i * 70.0f) - smoothScrollY;

                if (lineY < 100 || lineY > 960) continue;

                if (i == curIndex) {
                    lyricActivePaint.setColor(0xFFFFFFFF);
                    lyricActivePaint.setAlpha(255);
                    c.drawText(line.text, rightX, lineY, lyricActivePaint);
                } else {
                    int dist = Math.abs(i - curIndex);
                    int alpha = dist == 1 ? 140 : (dist == 2 ? 70 : 35);
                    lyricFadedPaint.setAlpha(alpha);
                    c.drawText(line.text, rightX, lineY, lyricFadedPaint);
                }
            }
            c.restore();
        } else {
            // No lyrics: draw ambient message & wave
            lyricActivePaint.setColor(0xFFFFFFFF);
            lyricActivePaint.setAlpha(220);
            c.drawText("AirPlay", rightX, centerY - 20, lyricActivePaint);
            lyricFadedPaint.setAlpha(120);
            c.drawText("Áudio transmitido em tempo real", rightX, centerY + 30, lyricFadedPaint);
        }
    }
}
