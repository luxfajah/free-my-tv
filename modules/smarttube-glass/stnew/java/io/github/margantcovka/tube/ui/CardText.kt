package io.github.margantcovka.tube.ui

import android.content.Context
import android.util.LruCache
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Готовые строки видео: название (до двух строк) и автор с просмотрами. */
class CardText(val title: TextLayoutResult, val meta: TextLayoutResult?)

/**
 * Текст под карточками меряется заранее в фоне и рисуется готовым: появление нового ряда
 * не раскладывает десятки строк на UI-потоке, а фокус только меняет цвет при отрисовке.
 */
object CardTexts {

    /** Высота блока текста под карточкой: две строки названия, отступ, строка автора. */
    val blockHeight = (17 * 2 + 3 + 16).dp
    val metaTop = (17 * 2 + 3).dp

    private val titleStyle = TextStyle(fontSize = Tokens.cardTitleSize, fontFamily = InterDisplay, fontWeight = FontWeight.SemiBold, lineHeight = 17.sp)
    private val metaStyle = TextStyle(fontSize = Tokens.cardMetaSize, fontFamily = InterDisplay, fontWeight = FontWeight.Medium)
    private val heroTitleStyle = TextStyle(fontSize = Tokens.heroTitleSize, fontFamily = InterDisplay, fontWeight = FontWeight.Bold)
    private val heroMetaStyle = TextStyle(fontSize = Tokens.heroMetaSize, fontFamily = InterDisplay, fontWeight = FontWeight.Medium)

    private var measurer: TextMeasurer? = null
    private val cache = LruCache<String, CardText>(400)

    fun key(video: Video, widthPx: Int) = "${video.title}|${video.secondTitle ?: video.author}|$widthPx"

    fun cached(key: String): CardText? = cache.get(key)

    suspend fun measure(context: Context, video: Video, widthPx: Int): CardText =
        measure(context, key(video, widthPx), video, widthPx, titleStyle, metaStyle)

    /** Заголовок над первым рядом: те же строки видео, но крупно. */
    suspend fun hero(context: Context, video: Video, widthPx: Int): CardText =
        measure(context, "hero|" + key(video, widthPx), video, widthPx, heroTitleStyle, heroMetaStyle)

    private suspend fun measure(
        context: Context, key: String, video: Video, widthPx: Int, titleStyle: TextStyle, metaStyle: TextStyle,
    ): CardText = cache.get(key) ?: withContext(Dispatchers.Default) {
        synchronized(this@CardTexts) {
            val m = measurer ?: TextMeasurer(
                defaultFontFamilyResolver = createFontFamilyResolver(context.applicationContext),
                defaultDensity = Density(context),
                defaultLayoutDirection = LayoutDirection.Ltr,
                cacheSize = 0,
            ).also { measurer = it }
            val constraints = Constraints(maxWidth = widthPx)
            val title = m.measure(video.title.orEmpty(), titleStyle, TextOverflow.Ellipsis, maxLines = 2, constraints = constraints)
            val metaText = (video.secondTitle ?: video.author)?.toString()
            val meta = if (metaText.isNullOrBlank()) null else m.measure(metaText, metaStyle, TextOverflow.Ellipsis, maxLines = 1, constraints = constraints)
            CardText(title, meta).also { cache.put(key, it) }
        }
    }

    fun widthPx(context: Context, width: androidx.compose.ui.unit.Dp) = (width.value * context.resources.displayMetrics.density).roundToInt()
}
