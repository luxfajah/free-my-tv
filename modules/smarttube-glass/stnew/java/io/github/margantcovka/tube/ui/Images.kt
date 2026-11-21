package io.github.margantcovka.tube.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.res.ResourcesCompat
import com.bumptech.glide.Glide
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video
import com.liskovsoft.smartyoutubetv2.tv.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Размер обложки в пикселях при фокусном масштабе: под него готовится картинка, рисуется 1:1. */
class CardSpec(val widthPx: Int, val heightPx: Int, val cornerPx: Float, val shadow: ImageBitmap, val shadowMarginPx: Int)

/**
 * Всё, что дорого рисовать на слабом GPU телевизора (скругление, бейджи, тени, размытие),
 * готовится здесь один раз на CPU. На экране остаются только готовые картинки.
 */
object Images {

    /** Фон верхней полки в пикселях: ширина экрана × высота области полки (960×420 dp). */
    const val SHELF_W = 64
    const val SHELF_H = 28

    /** ~80 обложек 432×243: телевизору с 1.3 ГБ памяти больше держать не стоит. */
    private val thumbs = object : LruCache<String, ImageBitmap>(28 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }
    private val shelves = LruCache<String, IntArray>(48)
    private val heroes = LruCache<String, ImageBitmap>(5)
    private val avatars = LruCache<String, ImageBitmap>(4)

    fun cachedThumb(key: String): ImageBitmap? = thumbs.get(key)

    fun cachedHero(url: String): ImageBitmap? = heroes.get(url)

    /** Sharp widescreen 16:9 backdrop for the Cinema Hero. */
    suspend fun heroBackdrop(context: Context, video: Video): ImageBitmap? {
        val url = video.bgImageUrl?.takeIf { it.isNotBlank() } ?: video.cardImageUrl ?: return null
        heroes.get(url)?.let { return it }
        return withContext(Dispatchers.IO) {
            val glide = Glide.with(context.applicationContext)
            val future = glide.asBitmap().load(url).submit(960, 540)
            try {
                val bmp = future.get()
                bmp.prepareToDraw()
                bmp.asImageBitmap().also { heroes.put(url, it) }
            } catch (_: Exception) {
                null
            } finally {
                glide.clear(future)
            }
        }
    }

    /** Circular user profile avatar for the top-right Liquid Glass orb. */
    suspend fun avatar(context: Context, url: String): ImageBitmap? {
        avatars.get(url)?.let { return it }
        return withContext(Dispatchers.IO) {
            val glide = Glide.with(context.applicationContext)
            val future = glide.asBitmap().load(url).circleCrop().submit(128, 128)
            try {
                val bmp = future.get()
                bmp.prepareToDraw()
                bmp.asImageBitmap().also { avatars.put(url, it) }
            } catch (_: Exception) {
                null
            } finally {
                glide.clear(future)
            }
        }
    }

    fun thumbKey(video: Video, spec: CardSpec) =
        "${video.cardImageUrl}|${spec.widthPx}x${spec.heightPx}|${video.badge}|${video.isLive}|${video.percentWatched}"

    /** Обложка со скруглением, бейджем длительности и полосой просмотра. null — не загрузилась. */
    suspend fun thumb(context: Context, video: Video, spec: CardSpec): ImageBitmap? {
        val url = video.cardImageUrl ?: return null
        val key = thumbKey(video, spec)
        thumbs.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            val glide = Glide.with(context.applicationContext)
            val future = glide.asBitmap().load(url).submit(spec.widthPx, spec.heightPx)
            try {
                val source = future.get()
                val out = rounded(source, spec)
                decorate(context, out, video, spec)
                out.prepareToDraw()
                out.asImageBitmap().also { thumbs.put(key, it) }
            } catch (e: Exception) {
                null
            } finally {
                glide.clear(future)
            }
        }
    }

    /** Размытый фон полки из уже готовой обложки. */
    suspend fun shelf(key: String, thumb: ImageBitmap): IntArray = shelves.get(key) ?: withContext(Dispatchers.Default) {
        shelfPixels(thumb.asAndroidBitmap()).also { shelves.put(key, it) }
    }

    fun cardSpec(widthPx: Int, heightPx: Int, cornerPx: Float, blurPx: Float): CardSpec {
        val margin = (blurPx * 2).toInt()
        val out = Bitmap.createBitmap(widthPx + 2 * margin, heightPx + 2 * margin, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(170, 0, 0, 0)
            maskFilter = BlurMaskFilter(blurPx, BlurMaskFilter.Blur.NORMAL)
        }
        val dy = blurPx * 0.5f
        Canvas(out).drawRoundRect(RectF(margin.toFloat(), margin + dy, (margin + widthPx).toFloat(), margin + heightPx + dy), cornerPx, cornerPx, paint)
        return CardSpec(widthPx, heightPx, cornerPx, out.asImageBitmap(), margin)
    }

    private fun rounded(content: Bitmap, spec: CardSpec): Bitmap {
        val out = Bitmap.createBitmap(spec.widthPx, spec.heightPx, Bitmap.Config.ARGB_8888)
        val scale = maxOf(spec.widthPx.toFloat() / content.width, spec.heightPx.toFloat() / content.height)
        val matrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate((spec.widthPx - content.width * scale) / 2f, (spec.heightPx - content.height * scale) / 2f)
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            shader = BitmapShader(content, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(matrix) }
        }
        Canvas(out).drawRoundRect(RectF(0f, 0f, spec.widthPx.toFloat(), spec.heightPx.toFloat()), spec.cornerPx, spec.cornerPx, paint)
        return out
    }

    private var badgeFont: Typeface? = null

    /** Бейдж длительности или «В ЭФИРЕ» и красная полоса просмотренного — прямо в картинку. */
    private fun decorate(context: Context, bmp: Bitmap, video: Video, spec: CardSpec) {
        val canvas = Canvas(bmp)
        val unit = spec.heightPx / 120f // 1 dp при фокусном масштабе для обложки 16:9 высотой ~120 dp
        val label = when {
            video.isLive -> context.getString(R.string.nova_live)
            video.isUpcoming -> context.getString(R.string.nova_upcoming)
            else -> video.badge?.takeIf { it.isNotBlank() }
        }
        if (label != null) {
            val font = badgeFont ?: ResourcesCompat.getFont(context, R.font.inter_display_semibold).also { badgeFont = it }
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 11.5f * unit
                typeface = font
            }
            val padH = 5f * unit
            val h = 18f * unit
            val w = text.measureText(label) + 2 * padH
            val right = spec.widthPx - 6f * unit
            val bottom = spec.heightPx - 6f * unit
            val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (video.isLive) Color.rgb(204, 0, 0) else Color.argb(200, 0, 0, 0) }
            canvas.drawRoundRect(RectF(right - w, bottom - h, right, bottom), 4f * unit, 4f * unit, pill)
            val fm = text.fontMetrics
            canvas.drawText(label, right - w + padH, bottom - h / 2f - (fm.ascent + fm.descent) / 2f, text)
        }
        val watched = video.percentWatched
        if (watched > 0f) {
            val barH = 4f * unit
            val paint = Paint().apply { color = Color.argb(150, 120, 120, 120) }
            // Полоса под скруглением: обрезаем той же маской через SRC_ATOP.
            paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_ATOP)
            canvas.drawRect(0f, spec.heightPx - barH, spec.widthPx.toFloat(), spec.heightPx.toFloat(), paint)
            paint.color = Color.rgb(255, 0, 0)
            canvas.drawRect(0f, spec.heightPx - barH, spec.widthPx * (watched.coerceAtMost(100f) / 100f), spec.heightPx.toFloat(), paint)
        }
    }

    /** Сильно размытая, насыщенная и притемнённая копия для фона полки. */
    private fun shelfPixels(source: Bitmap): IntArray {
        val cropH = minOf(source.height, source.width * SHELF_H / SHELF_W)
        var bmp = Bitmap.createBitmap(source, 0, (source.height - cropH) / 2, source.width, cropH)
        while (bmp.width / 2 >= SHELF_W * 2) {
            bmp = Bitmap.createScaledBitmap(bmp, bmp.width / 2, maxOf(1, bmp.height / 2), true)
        }
        bmp = Bitmap.createScaledBitmap(bmp, SHELF_W, SHELF_H, true)
        val px = IntArray(SHELF_W * SHELF_H)
        bmp.getPixels(px, 0, SHELF_W, 0, 0, SHELF_W, SHELF_H)
        repeat(3) {
            boxBlur(px, SHELF_W, SHELF_H, radius = 3, horizontal = true)
            boxBlur(px, SHELF_W, SHELF_H, radius = 3, horizontal = false)
        }
        for (i in px.indices) px[i] = tone(px[i])
        return px
    }

    private fun boxBlur(px: IntArray, w: Int, h: Int, radius: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val len = if (horizontal) w else h
        val line = IntArray(len)
        for (l in 0 until lines) {
            for (i in 0 until len) line[i] = px[if (horizontal) l * w + i else i * w + l]
            for (i in 0 until len) {
                var r = 0; var g = 0; var b = 0; var n = 0
                for (k in -radius..radius) {
                    val c = line[(i + k).coerceIn(0, len - 1)]
                    // Прозрачные скруглённые углы обложки не должны темнить края фона.
                    if (Color.alpha(c) < 128) continue
                    r += Color.red(c); g += Color.green(c); b += Color.blue(c); n++
                }
                if (n > 0) px[if (horizontal) l * w + i else i * w + l] = Color.rgb(r / n, g / n, b / n)
            }
        }
    }

    private fun tone(c: Int): Int {
        val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
        val gray = 0.299f * r + 0.587f * g + 0.114f * b
        fun ch(v: Int) = ((gray + (v - gray) * 1.4f) * 0.72f).toInt().coerceIn(0, 255)
        return Color.rgb(ch(r), ch(g), ch(b))
    }
}
