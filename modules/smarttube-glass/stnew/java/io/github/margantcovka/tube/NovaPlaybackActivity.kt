package io.github.margantcovka.tube

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import com.liskovsoft.smartyoutubetv2.tv.R
import com.liskovsoft.smartyoutubetv2.tv.ui.playback.PlaybackActivity
import com.liskovsoft.smartyoutubetv2.tv.ui.playback.PlaybackFragment
import io.github.margantcovka.tube.ui.PlayerOverlay
import io.github.margantcovka.tube.ui.PlayerOverlayState

/**
 * Плеер с новой панелью. Движок, кнопки и перемотка остаются у PlaybackFragment; Compose-слой
 * поверх рисует название, прогресс и время на месте скрытых элементов Leanback.
 */
class NovaPlaybackActivity : PlaybackActivity() {

    private val overlayState = PlayerOverlayState()
    private val handler = Handler(Looper.getMainLooper())
    private var fragment: PlaybackFragment? = null

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, if (overlayState.visible) 250L else 500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fragment = supportFragmentManager.findFragmentByTag(getString(R.string.playback_tag)) as? PlaybackFragment
        val compose = ComposeView(this).apply {
            setContent { PlayerOverlay(overlayState) }
            // Только рисуем: фокус и клавиши остаются у фрагмента.
            isFocusable = false
            isClickable = false
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        addContentView(compose, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
    }

    private fun refresh() {
        val f = fragment ?: return
        val shown = f.isControlsShown && !f.isInPIPMode
        if (overlayState.visible != shown) overlayState.visible = shown
        if (!shown) return

        f.video?.let {
            overlayState.title = it.title
            overlayState.author = it.author
        }
        overlayState.positionMs = f.positionMs
        overlayState.durationMs = f.durationMs
        overlayState.seekMs = f.seekPositionMs
        overlayState.playing = f.isPlaying

        val bar = findViewById<View>(R.id.playback_progress)
        if (bar != null && bar.width > 0) {
            val at = IntArray(2)
            bar.getLocationInWindow(at)
            overlayState.barLeft = at[0].toFloat()
            overlayState.barTop = at[1].toFloat()
            overlayState.barWidth = bar.width.toFloat()
            overlayState.barHeight = bar.height.toFloat()
        }
        findViewById<View>(R.id.controls_dock)?.let {
            val at = IntArray(2)
            it.getLocationInWindow(at)
            overlayState.dockTop = at[1].toFloat()
        }
    }
}
