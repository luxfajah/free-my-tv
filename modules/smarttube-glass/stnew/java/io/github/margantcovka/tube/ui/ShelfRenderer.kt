package io.github.margantcovka.tube.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * Фон всего экрана одной непрозрачной картинкой 64×36: размытый фон полки, затухание
 * к цвету фона и плавная смена приложения смешиваются на CPU (2 тыс. пикселей —
 * доли миллисекунды). GPU рисует ровно один растянутый непрозрачный прямоугольник
 * вместо двух полупрозрачных картинок на весь экран, градиента и слоя прозрачности.
 */
class ShelfRenderer(private val background: Int) {

    private val w = Images.SHELF_W
    private val shelfRows = Images.SHELF_H
    private val rows = SCREEN_ROWS

    private val blank = IntArray(w * shelfRows) { background }
    private var from = blank
    private var to = blank
    /** Что было на экране на последнем кадре (без затухания), чтобы новая смена начиналась с него. */
    private val shown = blank.copyOf()
    private val out = IntArray(w * rows)

    /**
     * Сколько смешивать с фоном в каждой строке: градиент полки вниз, ниже полки — только фон.
     * Сверху тоже притемнено — там белым написаны приветствие и время.
     */
    private val rowFade = FloatArray(rows) { r ->
        if (r >= shelfRows) return@FloatArray 1f
        val y = (r + 0.5f) / shelfRows
        val down = if (y < 0.45f) 0.25f * y / 0.45f else 0.25f + 0.75f * (y - 0.45f) / 0.55f
        val top = if (y < 0.3f) 0.45f * (1f - y / 0.3f) else 0f
        maxOf(down, top)
    }

    // Два буфера по очереди: RenderThread может ещё загружать прошлый кадр, пока мы пишем новый.
    private val bitmaps = Array(2) { Bitmap.createBitmap(w, rows, Bitmap.Config.ARGB_8888).apply { setHasAlpha(false) } }
    private val images = bitmaps.map { it.asImageBitmap() }
    private var current = 0
    private var lastProgress = Float.NaN
    private var lastVisibility = Float.NaN
    private var lastShownProgress = Float.NaN

    fun retarget(next: IntArray?) {
        from = shown.copyOf()
        to = next ?: blank
        lastProgress = Float.NaN
        lastShownProgress = Float.NaN
    }

    /** [progress] — доля нового фона (0..1), [visibility] — насколько видна полка (0..1). */
    fun render(progress: Float, visibility: Float): ImageBitmap {
        if (progress == lastProgress && visibility == lastVisibility) return images[current]
        lastProgress = progress
        lastVisibility = visibility

        // Каналы — сдвигами: вызовы Color.red() и т.п. из платформы не встраиваются и стоят заметно дороже.
        val bgR = background shr 16 and 0xFF; val bgG = background shr 8 and 0xFF; val bgB = background and 0xFF
        if (progress != lastShownProgress) {
            for (i in shown.indices) shown[i] = lerpColor(from[i], to[i], progress)
            lastShownProgress = progress
        }
        for (r in 0 until rows) {
            // Полка уходит: смешиваем строку с фоном сильнее.
            val keep = (1f - rowFade[r]) * visibility
            for (x in 0 until w) {
                val idx = r * w + x
                if (r >= shelfRows || keep <= 0f) { out[idx] = background; continue }
                val c = shown[(r * w) + x]
                out[idx] = rgb(
                    (bgR + ((c shr 16 and 0xFF) - bgR) * keep).toInt(),
                    (bgG + ((c shr 8 and 0xFF) - bgG) * keep).toInt(),
                    (bgB + ((c and 0xFF) - bgB) * keep).toInt(),
                )
            }
        }
        current = 1 - current
        bitmaps[current].setPixels(out, 0, w, 0, 0, w, rows)
        return images[current]
    }

    private fun lerpColor(a: Int, b: Int, t: Float): Int {
        if (t <= 0f) return a
        if (t >= 1f) return b
        val ar = a shr 16 and 0xFF; val ag = a shr 8 and 0xFF; val ab = a and 0xFF
        return rgb(
            (ar + ((b shr 16 and 0xFF) - ar) * t).toInt(),
            (ag + ((b shr 8 and 0xFF) - ag) * t).toInt(),
            (ab + ((b and 0xFF) - ab) * t).toInt(),
        )
    }

    private fun rgb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private companion object {
        /** 36 строк на 540 dp; полка (420 dp) — первые 28. */
        const val SCREEN_ROWS = 36
    }
}
