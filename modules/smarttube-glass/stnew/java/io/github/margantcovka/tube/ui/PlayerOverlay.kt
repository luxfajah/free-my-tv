package io.github.margantcovka.tube.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

/**
 * Liquid Glass Player Overlay drawn over the video in sync with Leanback's PlaybackFragment.
 */
class PlayerOverlayState {
    var visible by mutableStateOf(false)
    var title by mutableStateOf<String?>(null)
    var author by mutableStateOf<String?>(null)
    var positionMs by mutableLongStateOf(0)
    var durationMs by mutableLongStateOf(0)
    var seekMs by mutableLongStateOf(-1)
    var playing by mutableStateOf(false)
    var barLeft by mutableFloatStateOf(0f)
    var barTop by mutableFloatStateOf(0f)
    var barWidth by mutableFloatStateOf(0f)
    var barHeight by mutableFloatStateOf(0f)
    var dockTop by mutableFloatStateOf(0f)
}

private val titleStyle = TextStyle(color = Tokens.textPrimary, fontSize = Tokens.heroTitleSize, fontFamily = InterDisplay, fontWeight = FontWeight.Bold)
private val authorStyle = TextStyle(color = Tokens.textSecondary, fontSize = Tokens.heroMetaSize, fontFamily = InterDisplay, fontWeight = FontWeight.Medium)
private val timeStyle = TextStyle(color = Tokens.textSecondary, fontSize = Tokens.heroMetaSize, fontFamily = InterDisplay, fontWeight = FontWeight.SemiBold)

private val trackColor = Color(0x3DFFFFFF)
private val fillColor = Color(0xFFFFFFFF)

internal fun formatTime(ms: Long): String {
    val total = (ms.coerceAtLeast(0) / 1000)
    val h = total / 3600
    val m = total % 3600 / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

@Composable
fun PlayerOverlay(state: PlayerOverlayState) {
    if (!state.visible) return
    val density = LocalDensity.current
    val shown = if (state.seekMs >= 0) state.seekMs else state.positionMs
    val fraction = if (state.durationMs > 0) (shown.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f
    val barWidthDp = with(density) { state.barWidth.toDp() }
    val barHeightDp = with(density) { state.barHeight.toDp() }

    Box(Modifier.fillMaxSize()) {
        // Floating Liquid Glass backdrop pill behind the player title & transport dock
        if (state.barWidth > 0f && state.dockTop > 0f) {
            val padX = with(density) { 18.dp.toPx() }
            val topY = (state.dockTop - with(density) { 12.dp.toPx() }).coerceAtLeast(0f)
            val dockHeightDp = with(density) { ((state.barTop + state.barHeight - topY) + 42.dp.toPx()).coerceAtLeast(96f).toDp() }
            Box(
                Modifier
                    .offset { IntOffset((state.barLeft - padX).toInt().coerceAtLeast(0), topY.toInt()) }
                    .width(barWidthDp + 36.dp)
                    .height(dockHeightDp)
                    .liquidGlass(
                        shape = RoundedCornerShape(24.dp),
                        tint = Color(0x6612141B),
                        borderAlpha = 0.20f,
                        sheenAlpha = 0.14f,
                    ),
            )
        }

        // Video Title & Author above the Liquid Glass dock
        Box(
            Modifier
                .offset { IntOffset(state.barLeft.toInt(), 0) }
                .width(barWidthDp)
                .offset { IntOffset(0, (state.dockTop - 92.dp.toPx()).toInt().coerceAtLeast(0)) },
        ) {
            Column {
                BasicText(state.title.orEmpty(), style = titleStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!state.author.isNullOrBlank()) {
                    BasicText(state.author.orEmpty(), style = authorStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        // Glowing Liquid Glass Progress Bar
        Box(
            Modifier
                .offset { IntOffset(state.barLeft.toInt(), state.barTop.toInt()) }
                .width(barWidthDp),
        ) {
            Box(
                Modifier
                    .width(barWidthDp)
                    .drawBehind {
                        val h = 6.dp.toPx()
                        val y = (size.height.coerceAtLeast(h) - h) / 2f
                        val radius = CornerRadius(h / 2f)
                        drawRoundRect(trackColor, Offset(0f, y), Size(size.width, h), radius)
                        drawRoundRect(fillColor, Offset(0f, y), Size(size.width * fraction, h), radius)
                        if (state.seekMs >= 0) {
                            drawCircle(Color(0x55FFFFFF), 13.dp.toPx(), Offset(size.width * fraction, y + h / 2f))
                            drawCircle(fillColor, 8.dp.toPx(), Offset(size.width * fraction, y + h / 2f))
                        }
                    }
                    .padding(vertical = barHeightDp / 2),
            )
        }

        // Elapsed / Total Time
        Box(
            Modifier
                .offset { IntOffset(state.barLeft.toInt(), (state.barTop + state.barHeight).toInt()) }
                .width(barWidthDp),
        ) {
            BasicText(
                "${formatTime(shown)} / ${formatTime(state.durationMs)}",
                style = timeStyle,
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
    }
}
