package io.github.margantcovka.tube.ui

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusTargetModifierNode
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.input.key.KeyInputModifierNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.invalidateMeasurement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.relocation.bringIntoView
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Карточка видео одним узлом: обложка (скругление, бейдж, полоса просмотра уже в ней), тень,
 * масштаб в фокусе, текст под обложкой, фокус и OK пульта. Обычная сборка из Box, Column,
 * focusable и пары Text стоила на процессоре телевизора 4–8 мс на карточку, и новый ряд
 * собирался за 20–50 мс — пропуск нескольких кадров. Здесь в композиции один узел без состояния,
 * всё остальное живёт внутри него и меняется только в фазе отрисовки.
 */
@Composable
fun VideoCard(
    video: Video,
    spec: CardSpec,
    aspect: Float,
    textWidthPx: Int,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    index: Int = 0,
    loadLimit: State<Int>? = null,
) {
    // Слой перед узлом: в него попадает вся отрисовка карточки, и ряд при прокрутке не перерисовывает её.
    Spacer(modifier.graphicsLayer().then(VideoCardElement(video, spec, aspect, textWidthPx, index, loadLimit, onFocused, onClick, onLongClick)))
}

private class VideoCardElement(
    val video: Video,
    val spec: CardSpec,
    val aspect: Float,
    val textWidthPx: Int,
    val index: Int,
    val loadLimit: State<Int>?,
    val onFocused: () -> Unit,
    val onClick: () -> Unit,
    val onLongClick: () -> Unit,
) : ModifierNodeElement<VideoCardNode>() {
    override fun create() = VideoCardNode(video, spec, aspect, textWidthPx, index, loadLimit, onFocused, onClick, onLongClick)

    override fun update(node: VideoCardNode) = node.update(video, spec, aspect, textWidthPx, index, loadLimit, onFocused, onClick, onLongClick)

    // Video сравнивается по ссылке: его equals дорогой (регулярка в isMix).
    override fun equals(other: Any?) = other is VideoCardElement && other.video === video && other.spec === spec &&
        other.aspect == aspect && other.textWidthPx == textWidthPx && other.index == index && other.loadLimit === loadLimit && other.onFocused === onFocused &&
        other.onClick === onClick && other.onLongClick === onLongClick

    override fun hashCode() = System.identityHashCode(video) * 31 + textWidthPx

    override fun InspectorInfo.inspectableProperties() {
        name = "videoCard"
    }
}

private class VideoCardNode(
    private var video: Video,
    private var spec: CardSpec,
    private var aspect: Float,
    private var textWidthPx: Int,
    private var index: Int,
    private var loadLimit: State<Int>?,
    private var onFocused: () -> Unit,
    private var onClick: () -> Unit,
    private var onLongClick: () -> Unit,
) : DelegatingNode(), LayoutModifierNode, DrawModifierNode, KeyInputModifierNode, CompositionLocalConsumerModifierNode {

    override val shouldAutoInvalidate = false

    private var focused = false
    private val lift = Animatable(0f)
    // Состояние, читаемое при отрисовке: Compose сам перерисует карточку, когда появится обложка.
    private var image by mutableStateOf<ImageBitmap?>(null)
    private var text by mutableStateOf<CardText?>(null)
    private var thumbKey = ""
    private var textKey = ""
    private var loadJob: Job? = null

    private var textLayer: GraphicsLayer? = null
    private var recordedText: CardText? = null
    private var recordedFocus = false

    private var pressed = false
    private var longFired = false

    init {
        delegate(FocusTargetModifierNode(onFocusChange = { _, state -> onFocusChange(state.isFocused) }))
    }

    override fun onAttach() = load()

    override fun onDetach() {
        textLayer?.let { currentValueOf(LocalGraphicsContext).releaseGraphicsLayer(it) }
        textLayer = null
        // Корутины узла отменяются вместе с ним; при повторном подключении загрузка начнётся заново.
        loadJob = null
        thumbKey = ""
        textKey = ""
    }

    fun update(video: Video, spec: CardSpec, aspect: Float, textWidthPx: Int, index: Int, loadLimit: State<Int>?, onFocused: () -> Unit, onClick: () -> Unit, onLongClick: () -> Unit) {
        val relayout = aspect != this.aspect
        this.video = video
        this.spec = spec
        this.aspect = aspect
        this.textWidthPx = textWidthPx
        this.index = index
        this.loadLimit = loadLimit
        this.onFocused = onFocused
        this.onClick = onClick
        this.onLongClick = onLongClick
        if (relayout) invalidateMeasurement()
        if (isAttached) load()
    }

    /** Обложка и текст: из кэша сразу, иначе в фоне; заново — только если поменялось видео. */
    private fun load() {
        val newThumb = Images.thumbKey(video, spec)
        val newText = CardTexts.key(video, textWidthPx)
        if (newThumb == thumbKey && newText == textKey) return
        thumbKey = newThumb
        textKey = newText
        image = Images.cachedThumb(newThumb)
        text = CardTexts.cached(newText)
        loadJob?.cancel()
        loadJob = null
        if (image != null && text != null) return
        val video = video
        val spec = spec
        val width = textWidthPx
        val index = index
        val limit = loadLimit
        val context: Context = currentValueOf(LocalContext)
        loadJob = coroutineScope.launch {
            if (text == null) text = CardTexts.measure(context, video, width)
            // Обложка — только когда карточка подъезжает к экрану.
            if (limit != null && limit.value < index) snapshotFlow { limit.value }.first { it >= index }
            if (image == null) image = Images.thumb(context, video, spec)
        }
    }

    private fun onFocusChange(isFocused: Boolean) {
        if (focused == isFocused) return
        focused = isFocused
        invalidateDraw()
        if (!isAttached) return
        coroutineScope.launch {
            lift.animateTo(if (isFocused) 1f else 0f, tween(Tokens.focusMs)) { invalidateDraw() }
        }
        if (isFocused) {
            // Слой текста нужен только анимируемой карточке; создавать его всем — лишняя работа при появлении ряда.
            if (textLayer == null) textLayer = currentValueOf(LocalGraphicsContext).createGraphicsLayer().also { recordedText = null }
            onFocused()
            coroutineScope.launch { bringIntoView() }
        }
    }

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else textWidthPx
        val height = (width / aspect).roundToInt() + (GAP + CardTexts.blockHeight).roundToPx()
        val placeable = measurable.measure(Constraints.fixed(width, height))
        return layout(width, height) { placeable.place(0, 0) }
    }

    override fun ContentDrawScope.draw() {
        val w = size.width
        val imageH = (w / aspect).roundToInt().toFloat()
        val k = w / spec.widthPx
        val t = lift.value
        val s = 1f + (Tokens.focusedScale - 1f) * t
        scale(s, pivot = Offset(w / 2f, imageH / 2f)) {
            if (t > 0.01f) {
                val m = spec.shadowMarginPx * k
                drawImage(
                    spec.shadow,
                    dstOffset = IntOffset(-m.roundToInt(), -m.roundToInt()),
                    dstSize = IntSize((w + 2 * m).roundToInt(), (imageH + 2 * m).roundToInt()),
                    alpha = t,
                    filterQuality = FilterQuality.Low,
                )
            }
            val img = image
            val r = CornerRadius(spec.cornerPx * k)
            if (img != null) {
                drawImage(img, dstSize = IntSize(w.roundToInt(), imageH.roundToInt()), filterQuality = FilterQuality.Low)
            } else {
                drawRoundRect(Tokens.placeholder, size = Size(w, imageH), cornerRadius = r)
            }
            // Subtle glass hairline when idle, crisp white specular rim when focused
            drawRoundRect(
                color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.10f + 0.78f * t),
                size = Size(w, imageH),
                cornerRadius = r,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = (1f + 0.8f * t).dp.toPx()),
            )
        }
        val txt = text ?: return
        val top = imageH + GAP.toPx()
        val layer = textLayer
        if (layer == null) {
            drawText(txt.title, color = Tokens.cardTitle, topLeft = Offset(0f, top))
            txt.meta?.let { drawText(it, color = Tokens.textSecondary, topLeft = Offset(0f, top + CardTexts.metaTop.toPx())) }
            return
        }
        // Текст записан в свой слой один раз: кадры анимации фокуса перерисовывают только обложку.
        if (recordedText !== txt || recordedFocus != focused) {
            recordedText = txt
            recordedFocus = focused
            // record(density, …) самого слоя: DrawScope.record узла перерисовал бы сам узел и ушёл в рекурсию.
            layer.record(this, layoutDirection, IntSize(w.roundToInt(), CardTexts.blockHeight.toPx().roundToInt())) {
                drawText(txt.title, color = if (focused) Tokens.textPrimary else Tokens.cardTitle)
                txt.meta?.let { drawText(it, color = Tokens.textSecondary, topLeft = Offset(0f, CardTexts.metaTop.toPx())) }
            }
        }
        translate(0f, top) { drawLayer(layer) }
    }

    /** OK пульта: короткое нажатие — клик, удержание — меню (как Modifier.remoteClick). */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.key != Key.DirectionCenter && event.key != Key.Enter && event.key != Key.NumPadEnter) return false
        val native = event.nativeKeyEvent
        when (event.type) {
            KeyEventType.KeyDown -> {
                if (native.repeatCount == 0) {
                    pressed = true
                    longFired = false
                } else if (pressed && !longFired && (native.isLongPress || native.repeatCount >= LONG_PRESS_REPEATS)) {
                    longFired = true
                    onLongClick()
                }
            }
            KeyEventType.KeyUp -> {
                if (pressed && !longFired) onClick()
                pressed = false
            }
            else -> return false
        }
        return true
    }

    override fun onPreKeyEvent(event: KeyEvent) = false

    private companion object {
        val GAP = 10.dp
        const val LONG_PRESS_REPEATS = 8
    }
}
