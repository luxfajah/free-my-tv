package io.github.margantcovka.tube

import android.os.Bundle
import androidx.compose.ui.platform.ComposeView
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup
import com.liskovsoft.smartyoutubetv2.common.app.presenters.ChannelPresenter
import com.liskovsoft.smartyoutubetv2.common.app.views.ChannelView
import com.liskovsoft.smartyoutubetv2.tv.ui.common.LeanbackActivity
import io.github.margantcovka.tube.ui.ChannelScreen
import io.github.margantcovka.tube.ui.VideoActions

/** Канал на Compose: логику (загрузка рядов, меню, действия) по-прежнему ведёт ChannelPresenter. */
class NovaChannelActivity : LeanbackActivity(), ChannelView {

    private val state = ChannelState()
    private lateinit var presenter: ChannelPresenter

    private val actions = object : VideoActions {
        override fun onVideoFocused(video: Video) {
            state.focusedVideo = video
            presenter.onVideoItemSelected(video)
        }
        override fun onVideoClicked(video: Video) = presenter.onVideoItemClicked(video)
        override fun onVideoLongClicked(video: Video) = presenter.onVideoItemLongClicked(video)
        override fun onNeedMore(row: VideoRow) {
            val last = row.videos.lastOrNull() ?: return
            if (row.requestedMoreAt == row.videos.size) return
            row.requestedMoreAt = row.videos.size
            presenter.onScrollEnd(last)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        presenter = ChannelPresenter.instance(this)
        presenter.setView(this)
        setContentView(ComposeView(this).apply { setContent { ChannelScreen(state, actions) } })
        window.setBackgroundDrawable(null)
        presenter.onViewInitialized()
    }

    override fun onResume() {
        super.onResume()
        presenter.onViewResumed()
    }

    override fun onDestroy() {
        super.onDestroy()
        presenter.onViewDestroyed()
    }

    override fun finishReally() {
        super.finishReally()
        presenter.onFinish()
    }

    private fun refreshTitle() {
        val channel = presenter.channel
        state.title = channel?.author ?: channel?.title ?: presenter.channelId
    }

    // ChannelView

    override fun update(videoGroup: VideoGroup) {
        refreshTitle()
        state.apply(videoGroup)
    }

    override fun setPosition(index: Int) = Unit

    override fun showProgressBar(show: Boolean) {
        state.loading = show
        refreshTitle()
    }

    override fun clear() = state.clear()
}
