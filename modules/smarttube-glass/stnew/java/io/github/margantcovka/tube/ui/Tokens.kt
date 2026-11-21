package io.github.margantcovka.tube.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liskovsoft.smartyoutubetv2.tv.R

/** Liquid Glass design tokens (tvOS 26 × YTXTube). 1080p Android TV = 960×540 dp. */
object Tokens {
    val background = Color(0xFF08090D)
    val textPrimary = Color(0xFFF5F5F7)
    val textSecondary = Color(0xB3EBEBF5)
    val cardTitle = Color(0xDDEBEBF5)
    val placeholder = Color(0xFF16181F)

    val glassTint = Color(0x7814161D)
    val glassPillSelected = Color(0x2EFFFFFF)
    val liveRed = Color(0xE6FF1E3C)

    val sidePadding = 40.dp
    val topBarHeight = 72.dp
    /** Generous Cinema Hero above the first row. */
    val heroHeight = 196.dp
    /** Where the focused row anchors once the first row scrolls up. */
    val scrolledRowTop = 8.dp

    val cardWidth = 204.dp
    val shortsWidth = 122.dp
    val cardGap = 18.dp
    val rowGap = 22.dp
    val cardCorner = 12.dp
    val shadowBlur = 14.dp
    const val focusedScale = 1.075f
    const val gridColumns = 4
    const val shortsColumns = 6

    val heroTitleSize = 28.sp
    val heroMetaSize = 14.sp
    val rowTitleSize = 17.sp
    val cardTitleSize = 13.sp
    val cardMetaSize = 11.5.sp
    val tabSize = 14.sp

    const val focusMs = 150
    const val backdropFadeMs = 380
    const val shelfFadeMs = 280
    const val sectionDebounceMs = 300L

    val rowFocused = Color(0xFFF5F5F7)
    val rowText = Color(0xFFF5F5F7)
    val rowTextFocused = Color(0xFF0B0C10)
    val tabSelected = Color(0x2EFFFFFF)
}

val InterDisplay = FontFamily(
    Font(R.font.inter_display_medium, FontWeight.Medium),
    Font(R.font.inter_display_semibold, FontWeight.SemiBold),
    Font(R.font.inter_display_bold, FontWeight.Bold),
)
