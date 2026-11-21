package io.github.margantcovka.tube

import android.os.Bundle
import androidx.compose.ui.platform.ComposeView
import com.liskovsoft.smartyoutubetv2.common.app.models.data.BrowseSection
import com.liskovsoft.smartyoutubetv2.common.app.models.data.SettingsGroup
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup
import com.liskovsoft.smartyoutubetv2.common.app.models.errors.ErrorFragmentData
import com.liskovsoft.smartyoutubetv2.common.app.presenters.BrowsePresenter
import com.liskovsoft.smartyoutubetv2.common.app.presenters.SearchPresenter
import com.liskovsoft.smartyoutubetv2.common.app.presenters.dialogs.AccountSelectionPresenter
import com.liskovsoft.smartyoutubetv2.common.app.views.BrowseView
import com.liskovsoft.smartyoutubetv2.tv.ui.common.LeanbackActivity
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager
import io.github.margantcovka.tube.ui.BrowseActions
import io.github.margantcovka.tube.ui.BrowseScreen

/**
 * Главная на Compose. Вся логика (разделы, загрузка, вход, плеер) остаётся в презентерах
 * SmartTube: эта Activity только реализует [BrowseView] и рисует его состояние.
 */
class NovaBrowseActivity : LeanbackActivity(), BrowseView {

    private val state = BrowseState()
    private lateinit var presenter: BrowsePresenter

    private val actions = object : BrowseActions {
        override fun onSectionChosen(index: Int) {
            val section = state.sections.getOrNull(index) ?: return
            if (index != state.selected) {
                state.selected = index
                state.clearContent()
            }
            presenter.onSectionFocused(section.id)
        }

        override fun onSectionLongPressed(index: Int) {
            state.sections.getOrNull(index)?.let { presenter.onSectionLongPressed(it.id) }
        }

        override fun onSearch() = SearchPresenter.instance(this@NovaBrowseActivity).startSearch(null)
        override fun onAccountClicked() = AccountSelectionPresenter.instance(this@NovaBrowseActivity).show(true)
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
        presenter = BrowsePresenter.instance(this)
        presenter.setView(this)
        setContentView(ComposeView(this).apply { setContent { BrowseScreen(state, actions) } })
        // Экран целиком закрывает непрозрачный фон: фон окна — лишняя заливка на каждом кадре.
        window.setBackgroundDrawable(null)
        presenter.onViewInitialized()
        refreshAccount()
    }

    override fun onResume() {
        super.onResume()
        presenter.onViewResumed()
        refreshAccount()
    }

    private fun refreshAccount() {
        runCatching {
            val acc = YouTubeServiceManager.instance().signInService.selectedAccount
            state.accountName = acc?.name?.takeIf { it.isNotBlank() } ?: acc?.email
            state.accountAvatarUrl = acc?.avatarImageUrl
        }
    }

    override fun onPause() {
        super.onPause()
        presenter.onViewPaused()
    }

    override fun onDestroy() {
        super.onDestroy()
        presenter.onViewDestroyed()
    }

    // BrowseView

    override fun addSection(index: Int, section: BrowseSection) {
        state.sections.removeAll { it.id == section.id }
        if (index < 0 || index > state.sections.size) state.sections.add(section) else state.sections.add(index, section)
    }

    override fun removeSection(category: BrowseSection) {
        val removed = state.sections.indexOfFirst { it.id == category.id }
        if (removed < 0) return
        val wasCurrent = removed == state.selected
        state.sections.removeAt(removed)
        if (removed < state.selected) state.selected--
        if (wasCurrent) state.clearContent()
    }

    override fun removeAllSections() {
        state.sections.clear()
        state.selected = -1
        state.clearContent()
    }

    override fun selectSection(index: Int, focusOnContent: Boolean) {
        if (index !in state.sections.indices) return
        actions.onSectionChosen(index)
        if (focusOnContent) state.focusContentRequests++
    }

    override fun updateSection(group: VideoGroup) = state.apply(group)

    override fun updateSection(group: SettingsGroup) {
        if (!state.belongsHere(group.category)) return
        state.settings.clear()
        state.settings.addAll(group.items.orEmpty())
    }

    override fun clearSection(section: BrowseSection) {
        if (state.belongsHere(section)) state.clearContent()
    }

    override fun selectSectionItem(index: Int) = Unit

    override fun selectSectionItem(item: Video) = Unit

    override fun showError(data: ErrorFragmentData) {
        state.error = data
    }

    override fun showProgressBar(show: Boolean) {
        state.loading = show
    }

    override fun isProgressBarShowing(): Boolean = state.loading

    override fun focusOnContent() {
        state.focusContentRequests++
    }

    override fun isEmpty(): Boolean = state.isEmpty

    override fun updateBadge() = Unit
}
