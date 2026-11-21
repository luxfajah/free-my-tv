package io.github.margantcovka.tube.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup
import com.liskovsoft.smartyoutubetv2.common.app.models.data.BrowseSection
import com.liskovsoft.smartyoutubetv2.common.app.models.data.SettingsItem
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video
import com.liskovsoft.smartyoutubetv2.tv.R
import io.github.margantcovka.tube.BrowseState
import io.github.margantcovka.tube.VideoRow
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** Что экран сообщает наружу (в Activity, а оттуда в презентер SmartTube). */
interface VideoActions {
    fun onVideoFocused(video: Video)
    fun onVideoClicked(video: Video)
    fun onVideoLongClicked(video: Video)
    fun onNeedMore(row: VideoRow)
}

interface BrowseActions : VideoActions {
    fun onSectionChosen(index: Int)
    fun onSectionLongPressed(index: Int)
    fun onSearch()
    fun onAccountClicked()
}

private const val SEARCH_TAB = -1
private const val LIBRARY_TAB = -2
private const val PRIMARY_HOME = MediaGroup.TYPE_HOME
private const val PRIMARY_SUBS = MediaGroup.TYPE_SUBSCRIPTIONS
private const val PRIMARY_MUSIC = MediaGroup.TYPE_MUSIC

private val primarySectionIds = setOf(PRIMARY_HOME, PRIMARY_SUBS, PRIMARY_MUSIC)

/** Отступ от верха ряда до верха его обложек: заголовок ряда + поля под увеличение. */
private val rowHeader = 28.dp + 8.dp + 10.dp
private val gridHeader = 10.dp

private val rowTitleStyle = TextStyle(color = Tokens.textPrimary, fontSize = Tokens.rowTitleSize, fontFamily = InterDisplay, fontWeight = FontWeight.SemiBold)
internal val plainStyle = TextStyle(color = Tokens.textSecondary, fontSize = Tokens.heroMetaSize, fontFamily = InterDisplay, fontWeight = FontWeight.Medium)

@DrawableRes
private fun sectionIconRes(sectionId: Int): Int = when (sectionId) {
    MediaGroup.TYPE_HOME -> R.drawable.sf_home
    MediaGroup.TYPE_SUBSCRIPTIONS -> R.drawable.sf_subscriptions
    MediaGroup.TYPE_MUSIC -> R.drawable.sf_music
    MediaGroup.TYPE_HISTORY -> R.drawable.sf_history
    MediaGroup.TYPE_USER_PLAYLISTS -> R.drawable.sf_playlists
    MediaGroup.TYPE_SHORTS -> R.drawable.sf_shorts
    MediaGroup.TYPE_CHANNEL, MediaGroup.TYPE_CHANNEL_UPLOADS -> R.drawable.sf_channels
    MediaGroup.TYPE_GAMING -> R.drawable.sf_gaming
    MediaGroup.TYPE_NEWS -> R.drawable.sf_news
    MediaGroup.TYPE_SPORTS -> R.drawable.sf_sports
    MediaGroup.TYPE_LIVE -> R.drawable.sf_live
    MediaGroup.TYPE_SETTINGS -> R.drawable.sf_settings
    else -> R.drawable.sf_tv
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun BrowseScreen(state: BrowseState, actions: BrowseActions) {
    val density = LocalDensity.current
    val videoSpec = remember(density) { cardSpecFor(density.density, Tokens.cardWidth, 16f / 9f) }
    val shortsSpec = remember(density) { cardSpecFor(density.density, Tokens.shortsWidth, 9f / 16f) }

    val tabRequesters = remember { HashMap<Int, FocusRequester>() }
    fun tabRequester(index: Int) = tabRequesters.getOrPut(index) { FocusRequester() }
    val firstCard = remember { FocusRequester() }

    var contentFocused by remember { mutableStateOf(false) }
    var focusedRow by remember { mutableIntStateOf(0) }
    var focusedTab by remember { mutableIntStateOf(Int.MIN_VALUE) }
    var libraryHubOpen by remember { mutableStateOf(false) }
    val inFirstRow = !contentFocused || focusedRow == 0

    // Primary tabs: Home, Subscriptions, Music (ordered cleanly); everything else lives inside Library Hub.
    val primaryIndices = remember(state.sections.size, state.sections.map { it.id }) {
        val order = listOf(PRIMARY_HOME, PRIMARY_SUBS, PRIMARY_MUSIC)
        order.mapNotNull { id -> state.sections.indexOfFirst { it.id == id }.takeIf { it >= 0 } }
    }
    val libraryIndices = remember(state.sections.size, state.sections.map { it.id }) {
        state.sections.indices.filter { state.sections[it].id !in primarySectionIds }
    }
    val currentInLibrary = state.selected in libraryIndices

    // Debounced section switching when moving focus across top tabs.
    LaunchedEffect(focusedTab) {
        val tab = focusedTab
        if (tab == LIBRARY_TAB) {
            delay(Tokens.sectionDebounceMs)
            libraryHubOpen = true
            return@LaunchedEffect
        }
        if (tab < 0) return@LaunchedEffect
        delay(Tokens.sectionDebounceMs)
        libraryHubOpen = false
        if (tab != state.selected) actions.onSectionChosen(tab)
    }

    val hasSections = state.sections.isNotEmpty()
    LaunchedEffect(hasSections) {
        if (!hasSections) return@LaunchedEffect
        withFrameNanos { }
        val startTab = primaryIndices.firstOrNull { it == state.selected } ?: primaryIndices.firstOrNull() ?: 0
        if (!contentFocused) runCatching { tabRequester(startTab).requestFocus() }
    }
    LaunchedEffect(state.focusContentRequests) {
        if (state.focusContentRequests == 0) return@LaunchedEffect
        repeat(20) {
            withFrameNanos { }
            if (runCatching { firstCard.requestFocus() }.isSuccess && contentFocused) return@LaunchedEffect
            delay(100)
        }
    }

    CompositionLocalProvider(LocalOverscrollFactory provides null) {
        Box(Modifier.fillMaxSize().background(Tokens.background)) {
            Backdrop(state.focusedVideo, videoSpec, visible = inFirstRow && !libraryHubOpen)
            Column(Modifier.fillMaxSize()) {
                TopBar(
                    state = state,
                    primaryIndices = primaryIndices,
                    hasLibrary = libraryIndices.isNotEmpty(),
                    libraryActive = libraryHubOpen || currentInLibrary,
                    actions = actions,
                    tabRequester = ::tabRequester,
                    onTabFocused = { focusedTab = it },
                    onLibraryClicked = { libraryHubOpen = true },
                    onPrimaryClicked = { idx ->
                        libraryHubOpen = false
                        actions.onSectionChosen(idx)
                    },
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .onFocusChanged { contentFocused = it.hasFocus }
                        .onPreviewKeyEvent { event ->
                            if (event.key != Key.Back || !contentFocused) return@onPreviewKeyEvent false
                            if (event.type == KeyEventType.KeyUp) {
                                if (!libraryHubOpen && currentInLibrary) {
                                    libraryHubOpen = true
                                } else {
                                    val targetTab = if (libraryHubOpen || currentInLibrary) LIBRARY_TAB else state.selected.coerceAtLeast(0)
                                    runCatching { tabRequester(targetTab).requestFocus() }
                                }
                            }
                            true
                        },
                ) {
                    val error = state.error
                    when {
                        libraryHubOpen -> LibraryHub(
                            sections = libraryIndices.map { it to state.sections[it] },
                            firstItem = firstCard,
                            onSelect = { sectionIdx ->
                                libraryHubOpen = false
                                actions.onSectionChosen(sectionIdx)
                                state.focusContentRequests++
                            },
                        )
                        error != null -> Message(error.message, error.actionText) { error.onAction() }
                        state.isSettings -> SettingsGrid(state.settings, firstCard)
                        state.isGrid -> VideoGrid(state, videoSpec, shortsSpec, actions, firstCard, inFirstRow) { focusedRow = it }
                        else -> VideoRows(state.rows, state.focusedVideo, state.currentSection?.id, videoSpec, shortsSpec, actions, firstCard, inFirstRow) { focusedRow = it }
                    }
                    if (!libraryHubOpen && state.loading && state.isEmpty && error == null) {
                        BasicText(stringResource(R.string.nova_loading), style = plainStyle, modifier = Modifier.align(Alignment.Center))
                    }
                }
            }
        }
    }
}

internal fun cardSpecFor(density: Float, width: Dp, aspect: Float): CardSpec {
    val w = (width.value * density * Tokens.focusedScale).roundToInt()
    val h = (w / aspect).roundToInt()
    return Images.cardSpec(w, h, Tokens.cardCorner.value * density * Tokens.focusedScale, Tokens.shadowBlur.value * density)
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun TopBar(
    state: BrowseState,
    primaryIndices: List<Int>,
    hasLibrary: Boolean,
    libraryActive: Boolean,
    actions: BrowseActions,
    tabRequester: (Int) -> FocusRequester,
    onTabFocused: (Int) -> Unit,
    onLibraryClicked: () -> Unit,
    onPrimaryClicked: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(Tokens.topBarHeight)
            .padding(horizontal = Tokens.sidePadding)
            .graphicsLayer()
            .focusRestorer(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // Left: Floating YTXTube Liquid Glass Pill — [🔍] · Início · Inscrições · Música · Biblioteca
        Row(
            modifier = Modifier
                .liquidGlass(shape = CircleShape, tint = Tokens.glassTint, borderAlpha = 0.20f, sheenAlpha = 0.16f)
                .padding(horizontal = 5.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassTab(
                title = null,
                iconRes = R.drawable.sf_search,
                selected = false,
                onFocused = { onTabFocused(SEARCH_TAB) },
                onClick = actions::onSearch,
                onLongClick = {},
                modifier = Modifier.focusRequester(tabRequester(SEARCH_TAB)),
            )
            primaryIndices.forEach { idx ->
                val sec = state.sections[idx]
                GlassTab(
                    title = sec.title.orEmpty(),
                    iconRes = sectionIconRes(sec.id),
                    selected = !libraryActive && idx == state.selected,
                    onFocused = { onTabFocused(idx) },
                    onClick = { onPrimaryClicked(idx) },
                    onLongClick = { actions.onSectionLongPressed(idx) },
                    modifier = Modifier.focusRequester(tabRequester(idx)),
                )
            }
            if (hasLibrary) {
                GlassTab(
                    title = stringResource(R.string.nova_library),
                    iconRes = R.drawable.sf_playlists,
                    selected = libraryActive,
                    onFocused = { onTabFocused(LIBRARY_TAB) },
                    onClick = onLibraryClicked,
                    onLongClick = {},
                    modifier = Modifier.focusRequester(tabRequester(LIBRARY_TAB)),
                )
            }
        }

        // Right: ONLY a single circular Liquid Glass orb with the logged-in user's profile avatar
        ProfileOrb(
            name = state.accountName,
            avatarUrl = state.accountAvatarUrl,
            onClick = actions::onAccountClicked,
        )
    }
}

@Composable
private fun GlassTab(
    title: String?,
    @DrawableRes iconRes: Int,
    selected: Boolean,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.05f else 1f, tween(Tokens.focusMs), label = "tabScale")
    val fill = when {
        focused -> Tokens.rowFocused
        selected -> Tokens.tabSelected
        else -> Color.Transparent
    }
    val contentColor = when {
        focused -> Tokens.rowTextFocused
        selected -> Tokens.textPrimary
        else -> Tokens.textSecondary
    }
    val iconOnly = title.isNullOrEmpty()
    Row(
        modifier = modifier
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .remoteClick(onClick, onLongClick)
            .focusable()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CircleShape)
            .background(fill)
            .then(
                if (selected && !focused) Modifier.border(1.dp, Color.White.copy(alpha = 0.16f), CircleShape)
                else Modifier
            )
            .then(
                if (iconOnly) Modifier.size(34.dp)
                else Modifier.padding(horizontal = 13.dp, vertical = 7.dp)
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
    ) {
        SfIcon(iconRes, tint = contentColor, modifier = Modifier.size(15.dp))
        if (!iconOnly) {
            BasicText(
                title!!,
                style = TextStyle(color = contentColor, fontSize = Tokens.tabSize, fontFamily = InterDisplay, fontWeight = FontWeight.SemiBold),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun ProfileOrb(name: String?, avatarUrl: String?, onClick: () -> Unit) {
    val context = LocalContext.current
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.12f else 1f, tween(Tokens.focusMs), label = "profileScale")
    var bmp by remember(avatarUrl) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(avatarUrl) {
        bmp = avatarUrl?.takeIf { it.isNotBlank() }?.let { Images.avatar(context, it) }
    }
    Box(
        modifier = Modifier
            .size(38.dp)
            .onFocusChanged { focused = it.isFocused }
            .remoteClick(onClick)
            .focusable()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .liquidGlass(
                shape = CircleShape,
                tint = Color(0x881A1D26),
                borderAlpha = if (focused) 0.92f else 0.26f,
                borderWidth = if (focused) 2.dp else 1.dp,
            )
            .padding(2.dp),
        contentAlignment = Alignment.Center,
    ) {
        val avatar = bmp
        if (avatar != null) {
            Image(
                bitmap = avatar,
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(CircleShape),
            )
        } else {
            val initial = name?.trim()?.firstOrNull()?.uppercaseChar()?.toString()
            if (initial != null) {
                BasicText(
                    initial,
                    style = TextStyle(color = Tokens.textPrimary, fontSize = 15.sp, fontFamily = InterDisplay, fontWeight = FontWeight.Bold),
                )
            } else {
                SfIcon(R.drawable.sf_channels, tint = Tokens.textPrimary, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** Liquid Glass Library Hub: Histórico, Playlists, Shorts, Canais, Configurações, etc. */
@Composable
private fun LibraryHub(
    sections: List<Pair<Int, BrowseSection>>,
    firstItem: FocusRequester,
    onSelect: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Tokens.sidePadding, vertical = 14.dp),
    ) {
        BasicText(
            stringResource(R.string.nova_library),
            style = TextStyle(color = Tokens.textPrimary, fontSize = 26.sp, fontFamily = InterDisplay, fontWeight = FontWeight.Bold),
            modifier = Modifier.padding(bottom = 18.dp),
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            itemsIndexed(sections, key = { _, p -> p.second.id }) { i, (secIndex, sec) ->
                LibraryTile(
                    title = sec.title.orEmpty(),
                    iconRes = sectionIconRes(sec.id),
                    onClick = { onSelect(secIndex) },
                    modifier = if (i == 0) Modifier.focusRequester(firstItem) else Modifier,
                )
            }
        }
    }
}

@Composable
private fun LibraryTile(
    title: String,
    @DrawableRes iconRes: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.04f else 1f, tween(Tokens.focusMs), label = "libScale")
    val ink = if (focused) Tokens.rowTextFocused else Tokens.textPrimary
    Row(
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .remoteClick(onClick)
            .focusable()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .then(
                if (focused) Modifier.clip(RoundedCornerShape(18.dp)).background(Tokens.rowFocused)
                else Modifier.liquidGlass(shape = RoundedCornerShape(18.dp), tint = Tokens.glassTint, borderAlpha = 0.20f)
            )
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (focused) Color(0x160B0C10) else Color(0x1FFFFFFF))
                .border(1.dp, if (focused) Color(0x180B0C10) else Color(0x28FFFFFF), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            SfIcon(iconRes, tint = ink, modifier = Modifier.size(20.dp))
        }
        BasicText(
            title,
            style = TextStyle(color = ink, fontSize = 16.sp, fontFamily = InterDisplay, fontWeight = FontWeight.SemiBold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Cinema Hero above the first row: Liquid Glass badge (LIVE / duration), bold title & channel meta.
 */
@Composable
internal fun Hero(video: Video?, padded: Boolean = true) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val widthPx = with(density) { (LocalConfiguration.current.screenWidthDp.dp * 0.64f).roundToPx() }
    var text by remember { mutableStateOf<CardText?>(null) }
    LaunchedEffect(video, widthPx) {
        text = video?.let { CardTexts.hero(context, it, widthPx) }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(Tokens.heroHeight)
            .padding(horizontal = if (padded) Tokens.sidePadding else 0.dp)
            .padding(bottom = 14.dp),
        verticalArrangement = Arrangement.Bottom,
    ) {
        if (video != null) {
            val badgeLabel = when {
                video.isLive -> stringResource(R.string.nova_live)
                video.isUpcoming -> stringResource(R.string.nova_upcoming)
                !video.badge.isNullOrBlank() -> video.badge
                else -> null
            }
            if (badgeLabel != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(bottom = 8.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .liquidGlass(
                                shape = CircleShape,
                                tint = if (video.isLive) Tokens.liveRed else Color(0x66161820),
                                borderAlpha = if (video.isLive) 0.34f else 0.22f,
                                sheenAlpha = 0.14f,
                            )
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        SfIcon(
                            if (video.isLive) R.drawable.sf_live else R.drawable.sf_sparkles,
                            tint = Color.White,
                            modifier = Modifier.size(11.dp),
                        )
                        BasicText(
                            badgeLabel,
                            style = TextStyle(color = Color.White, fontSize = 11.sp, fontFamily = InterDisplay, fontWeight = FontWeight.Bold),
                            maxLines = 1,
                        )
                    }
                }
            }
        }
        Spacer(
            Modifier
                .fillMaxWidth()
                .height(92.dp)
                .drawBehind {
                    val t = text ?: return@drawBehind
                    var bottom = size.height
                    t.meta?.let {
                        bottom -= it.size.height
                        drawText(it, color = Tokens.textSecondary, topLeft = Offset(0f, bottom))
                        bottom -= 6.dp.toPx()
                    }
                    drawText(t.title, color = Tokens.textPrimary, topLeft = Offset(0f, (bottom - t.title.size.height).coerceAtLeast(0f)))
                },
        )
    }
}

@Composable
private fun PrefetchTexts(rows: List<VideoRow>, widthPx: Int, shortsWidthPx: Int = widthPx) {
    val context = LocalContext.current
    val total = rows.sumOf { it.videos.size }
    LaunchedEffect(rows.size, total, widthPx) {
        for (row in rows.toList()) for (video in row.videos.toList()) {
            CardTexts.measure(context, video, if (row.shorts) shortsWidthPx else widthPx)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
internal fun VideoRows(
    rows: List<VideoRow>,
    focusedVideo: Video?,
    resetKey: Any?,
    videoSpec: CardSpec,
    shortsSpec: CardSpec,
    actions: VideoActions,
    firstCard: FocusRequester,
    inFirstRow: Boolean,
    onRowFocused: (Int) -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(resetKey) { listState.scrollToItem(0) }
    val density = LocalDensity.current
    val firstRow by rememberUpdatedState(inFirstRow)
    val vertical = remember(density) {
        AnchoredSpec { if (firstRow) with(density) { (Tokens.heroHeight + rowHeader).toPx() } else with(density) { (Tokens.scrolledRowTop + rowHeader).toPx() } }
    }
    val horizontal = remember(density) { AnchoredSpec { with(density) { Tokens.sidePadding.toPx() } } }
    PrefetchTexts(rows, with(density) { Tokens.cardWidth.roundToPx() }, with(density) { Tokens.shortsWidth.roundToPx() })

    CompositionLocalProvider(LocalBringIntoViewSpec provides vertical) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().graphicsLayer(),
            contentPadding = PaddingValues(bottom = 320.dp),
            verticalArrangement = Arrangement.spacedBy(Tokens.rowGap),
        ) {
            item(key = "hero", contentType = "hero") { Hero(focusedVideo) }
            itemsIndexed(rows, key = { _, row -> row.id }, contentType = { _, row -> if (row.shorts) "shorts" else "row" }) { rowIndex, row ->
                val spec = if (row.shorts) shortsSpec else videoSpec
                val width = if (row.shorts) Tokens.shortsWidth else Tokens.cardWidth
                val aspect = if (row.shorts) 9f / 16f else 16f / 9f
                Column(Modifier.fillMaxWidth().graphicsLayer()) {
                    BasicText(
                        row.title.orEmpty(),
                        style = rowTitleStyle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = Tokens.sidePadding).height(28.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    val scroll = rememberScrollState()
                    val textWidthPx = with(density) { width.roundToPx() }
                    val pitchPx = with(density) { (width + Tokens.cardGap).toPx() }
                    val viewportPx = with(density) { LocalConfiguration.current.screenWidthDp.dp.toPx() }
                    val loadLimit = remember(scroll, pitchPx) { derivedStateOf { ((scroll.value + viewportPx) / pitchPx).toInt() + 2 } }
                    CompositionLocalProvider(LocalBringIntoViewSpec provides horizontal) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .focusRestorer()
                                .horizontalScroll(scroll)
                                .padding(horizontal = Tokens.sidePadding, vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(Tokens.cardGap),
                        ) {
                            row.videos.forEachIndexed { i, video ->
                                key(i) {
                                    VideoCard(
                                        video = video,
                                        spec = spec,
                                        aspect = aspect,
                                        textWidthPx = textWidthPx,
                                        index = i,
                                        loadLimit = loadLimit,
                                        onFocused = {
                                            onRowFocused(rowIndex)
                                            actions.onVideoFocused(video)
                                            if (i >= row.videos.size - 6) actions.onNeedMore(row)
                                        },
                                        onClick = { actions.onVideoClicked(video) },
                                        onLongClick = { actions.onVideoLongClicked(video) },
                                        modifier = Modifier.width(width).then(if (rowIndex == 0 && i == 0) Modifier.focusRequester(firstCard) else Modifier),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VideoGrid(
    state: BrowseState,
    videoSpec: CardSpec,
    shortsSpec: CardSpec,
    actions: BrowseActions,
    firstCard: FocusRequester,
    inFirstRow: Boolean,
    onRowFocused: (Int) -> Unit,
) {
    val row = state.rows.firstOrNull() ?: return
    val columns = if (row.shorts) Tokens.shortsColumns else Tokens.gridColumns
    val spec = if (row.shorts) shortsSpec else videoSpec
    val aspect = if (row.shorts) 9f / 16f else 16f / 9f
    val gridState = rememberLazyGridState(cacheWindow = LazyLayoutCacheWindow(ahead = 300.dp, behind = 300.dp))
    val section = state.currentSection?.id
    LaunchedEffect(section) { gridState.scrollToItem(0) }
    val density = LocalDensity.current
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val textWidthPx = with(density) { ((screenWidth - Tokens.sidePadding * 2 - Tokens.cardGap * (columns - 1)) / columns).roundToPx() }
    PrefetchTexts(state.rows, textWidthPx)
    val firstRow by rememberUpdatedState(inFirstRow)
    val vertical = remember(density) {
        AnchoredSpec { if (firstRow) with(density) { (Tokens.heroHeight + gridHeader).toPx() } else with(density) { (Tokens.scrolledRowTop + gridHeader).toPx() } }
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides vertical) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = gridState,
            modifier = Modifier.fillMaxSize().graphicsLayer(),
            contentPadding = PaddingValues(start = Tokens.sidePadding, end = Tokens.sidePadding, top = 0.dp, bottom = 320.dp),
            horizontalArrangement = Arrangement.spacedBy(Tokens.cardGap),
            verticalArrangement = Arrangement.spacedBy(Tokens.rowGap),
        ) {
            item(key = "hero", span = { GridItemSpan(maxLineSpan) }, contentType = "hero") {
                Hero(state.focusedVideo, padded = false)
            }
            itemsIndexed(row.videos, key = { i, _ -> i }, contentType = { _, _ -> "card" }) { i, video ->
                VideoCard(
                    video = video,
                    spec = spec,
                    aspect = aspect,
                    textWidthPx = textWidthPx,
                    onFocused = {
                        onRowFocused(i / columns)
                        actions.onVideoFocused(video)
                        if (i >= row.videos.size - 2 * columns) actions.onNeedMore(row)
                    },
                    onClick = { actions.onVideoClicked(video) },
                    onLongClick = { actions.onVideoLongClicked(video) },
                    modifier = Modifier.padding(top = gridHeader).then(if (i == 0) Modifier.focusRequester(firstCard) else Modifier),
                )
            }
        }
    }
}

@Composable
private fun SettingsGrid(items: List<SettingsItem>, firstItem: FocusRequester) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = Tokens.sidePadding, end = Tokens.sidePadding, top = 16.dp, bottom = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        itemsIndexed(items) { i, item ->
            FocusRow(
                onClick = { item.onClick?.run() },
                modifier = Modifier
                    .fillMaxWidth()
                    .liquidGlass(shape = RoundedCornerShape(14.dp), tint = Color(0x5214161D), borderAlpha = 0.14f)
                    .then(if (i == 0) Modifier.focusRequester(firstItem) else Modifier),
            ) { color ->
                BasicText(
                    item.title.orEmpty(),
                    style = TextStyle(color = color, fontSize = Tokens.heroMetaSize, fontFamily = InterDisplay, fontWeight = FontWeight.SemiBold),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
internal fun Message(text: String?, action: String?, onAction: () -> Unit) {
    val button = remember { FocusRequester() }
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        BasicText(text.orEmpty(), style = plainStyle)
        if (!action.isNullOrBlank()) {
            Spacer(Modifier.height(16.dp))
            Row { FocusRow(onClick = onAction, modifier = Modifier.focusRequester(button)) { color -> BasicText(action, style = plainStyle.copy(color = color)) } }
        }
    }
}

/**
 * Cinema Full-Bleed Hero Backdrop: sharp widescreen thumbnail with smooth crossfade + 6-stop left/bottom scrims.
 */
@Composable
internal fun Backdrop(video: Video?, spec: CardSpec, visible: Boolean) {
    val context = LocalContext.current
    val fade = remember { Animatable(1f) }
    val visibility = animateFloatAsState(if (visible) 1f else 0f, tween(Tokens.shelfFadeMs, easing = LinearEasing), label = "shelf")
    var prevBmp by remember { mutableStateOf<ImageBitmap?>(null) }
    var currBmp by remember { mutableStateOf<ImageBitmap?>(null) }
    val url = video?.let { it.bgImageUrl?.takeIf { u -> u.isNotBlank() } ?: it.cardImageUrl }

    LaunchedEffect(url) {
        if (url == null) return@LaunchedEffect
        delay(120)
        val loaded = Images.cachedHero(url) ?: video?.let { Images.heroBackdrop(context, it) }
        if (loaded != null && loaded !== currBmp) {
            prevBmp = currBmp
            currBmp = loaded
            fade.snapTo(0f)
            fade.animateTo(1f, tween(Tokens.backdropFadeMs, easing = LinearEasing))
        }
    }

    Spacer(
        Modifier
            .fillMaxSize()
            .graphicsLayer()
            .drawBehind {
                drawRect(Tokens.background)
                val vis = visibility.value
                if (vis <= 0.005f) return@drawBehind
                val w = size.width
                val h = size.height

                fun drawCover(bmp: ImageBitmap, alpha: Float) {
                    if (alpha <= 0.005f) return
                    val scale = maxOf(w / bmp.width, h / bmp.height)
                    val dw = (bmp.width * scale).roundToInt()
                    val dh = (bmp.height * scale).roundToInt()
                    val dx = ((w - dw) * 0.5f).roundToInt()
                    val dy = ((h - dh) * 0.22f).roundToInt()
                    drawImage(
                        image = bmp,
                        dstOffset = IntOffset(dx, dy),
                        dstSize = IntSize(dw, dh),
                        alpha = alpha * vis,
                        filterQuality = FilterQuality.Medium,
                    )
                }

                prevBmp?.let { drawCover(it, 1f - fade.value) }
                currBmp?.let { drawCover(it, fade.value) }

                // Cinema Left Vignette
                drawRect(
                    Brush.horizontalGradient(
                        0f to Color(0xEB08090D),
                        0.34f to Color(0xAE08090D),
                        0.66f to Color(0x2E08090D),
                        1f to Color.Transparent,
                    ),
                )
                // Cinema 6-Stop Bottom Scrim seamlessly blending into rows
                drawRect(
                    Brush.verticalGradient(
                        0f to Color(0xA608090D),
                        0.18f to Color(0x2208090D),
                        0.36f to Color(0x0A08090D),
                        0.56f to Color(0x8808090D),
                        0.72f to Color(0xEC08090D),
                        0.86f to Color(0xFF08090D),
                        1f to Color(0xFF08090D),
                    ),
                )
            },
    )
}

@OptIn(ExperimentalFoundationApi::class)
private class AnchoredSpec(private val target: () -> Float) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = offset - target()
}
