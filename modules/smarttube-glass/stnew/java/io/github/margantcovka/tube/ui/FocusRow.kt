package io.github.margantcovka.tube.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/** Строка списка в стиле tvOS: в фокусе — белая заливка и тёмный текст, без обводок. */
@Composable
fun FocusRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.(textColor: Color) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val fill by animateColorAsState(if (focused) Tokens.rowFocused else Color.Transparent, tween(Tokens.focusMs), label = "fill")
    val text by animateColorAsState(if (focused) Tokens.rowTextFocused else Tokens.rowText, tween(Tokens.focusMs), label = "text")
    val scale = animateFloatAsState(if (focused) 1.02f else 1f, tween(Tokens.focusMs), label = "scale")
    Row(
        modifier
            .onFocusChanged { focused = it.isFocused }
            .remoteClick(onClick)
            .focusable()
            .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
            .background(fill, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content(text) }
}
