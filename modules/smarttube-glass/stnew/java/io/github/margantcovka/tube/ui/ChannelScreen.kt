package io.github.margantcovka.tube.ui

import com.liskovsoft.smartyoutubetv2.tv.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import io.github.margantcovka.tube.ChannelState

private val channelTitleStyle = TextStyle(color = Tokens.textPrimary, fontSize = Tokens.heroTitleSize, fontFamily = InterDisplay, fontWeight = FontWeight.Bold)

/** Канал: название сверху и те же ряды с фоном, что и на главной. Фокус сразу на первой карточке. */
@Composable
fun ChannelScreen(state: ChannelState, actions: VideoActions) {
    val density = LocalDensity.current
    val videoSpec = remember(density) { cardSpecFor(density.density, Tokens.cardWidth, 16f / 9f) }
    val shortsSpec = remember(density) { cardSpecFor(density.density, Tokens.shortsWidth, 9f / 16f) }
    val firstCard = remember { FocusRequester() }
    var focusedRow by mutableIntStateOf(0)
    val hasContent = state.rows.isNotEmpty()

    LaunchedEffect(hasContent) {
        if (!hasContent) return@LaunchedEffect
        withFrameNanos { }
        runCatching { firstCard.requestFocus() }
    }

    CompositionLocalProvider(LocalOverscrollFactory provides null) {
        Box(Modifier.fillMaxSize()) {
            Backdrop(state.focusedVideo, videoSpec, visible = focusedRow == 0)
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().height(Tokens.topBarHeight).padding(horizontal = Tokens.sidePadding), contentAlignment = Alignment.CenterStart) {
                    BasicText(state.title.orEmpty(), style = channelTitleStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    VideoRows(state.rows, state.focusedVideo, null, videoSpec, shortsSpec, actions, firstCard, focusedRow == 0) { focusedRow = it }
                    if (state.loading && state.isEmpty) {
                        BasicText(stringResource(R.string.nova_loading), style = plainStyle, modifier = Modifier.align(Alignment.Center))
                    }
                }
            }
        }
    }
}
