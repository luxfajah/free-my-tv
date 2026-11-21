package io.github.margantcovka.tube.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * YTXTube-inspired Liquid Glass surface tuned for Android TV (60fps on Mali-470 & modern GPUs).
 * Translucent dark glass fill + top specular sheen + bottom depth + crisp hairline border.
 */
fun Modifier.liquidGlass(
    shape: Shape = RoundedCornerShape(24.dp),
    tint: Color = Color(0x7814161D),
    borderAlpha: Float = 0.20f,
    sheenAlpha: Float = 0.16f,
    borderWidth: Dp = 1.dp,
): Modifier = this
    .clip(shape)
    .background(tint)
    .drawBehind {
        drawRect(
            Brush.verticalGradient(
                0f to Color.White.copy(alpha = sheenAlpha),
                0.48f to Color.White.copy(alpha = 0.02f),
                1f to Color.Black.copy(alpha = 0.14f),
            ),
        )
    }
    .border(borderWidth, Color.White.copy(alpha = borderAlpha), shape)

@Composable
fun SfIcon(
    @DrawableRes resId: Int,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Image(
        painter = painterResource(resId),
        contentDescription = null,
        colorFilter = ColorFilter.tint(tint),
        contentScale = ContentScale.Fit,
        modifier = modifier,
    )
}
