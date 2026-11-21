package io.github.margantcovka.tube

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.liskovsoft.smartyoutubetv2.common.app.models.data.BrowseSection
import com.liskovsoft.smartyoutubetv2.common.app.models.data.SettingsItem
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup
import com.liskovsoft.smartyoutubetv2.common.app.models.errors.ErrorFragmentData

/** Ряд видео. В разделах-сетках весь раздел — один ряд с id [GRID_ROW]. */
class VideoRow(val id: Int, title: String?, val shorts: Boolean) {
    var title by mutableStateOf(title)
    val videos = mutableStateListOf<Video>()
    private val keys = HashSet<String>()
    /** Размер, при котором уже попросили следующую страницу: не просить дважды. */
    var requestedMoreAt = -1

    /**
     * Добавляет только новые видео: YouTube в продолжении ленты часто присылает уже показанные,
     * а старый экран отсеивал их (VideoGroupObjectAdapter). Video.equals тяжёлый, поэтому ключи.
     */
    fun add(items: List<Video>?, atStart: Boolean = false) {
        val fresh = items.orEmpty().filter { keys.add(keyOf(it)) }
        // Страница из одних повторов: разрешаем попросить следующую при следующем шаге фокуса.
        if (fresh.isEmpty()) requestedMoreAt = -1
        if (atStart) videos.addAll(0, fresh) else videos.addAll(fresh)
    }

    /** После удаления и замены ключи пересчитываются. */
    fun rekey() {
        keys.clear()
        videos.forEach { keys.add(keyOf(it)) }
    }

    companion object {
        const val GRID_ROW = Int.MIN_VALUE

        private fun keyOf(v: Video): String =
            v.videoId ?: v.playlistId ?: v.channelId ?: v.title ?: System.identityHashCode(v).toString()
    }
}

/**
 * Состояние главной, которое наполняет презентер SmartTube через [NovaBrowseActivity].
 * Правила разбора групп повторяют старые Leanback-экраны (MultipleRowsFragment, VideoGridFragment).
 */
class BrowseState {
    val sections = mutableStateListOf<BrowseSection>()
    var selected by mutableIntStateOf(-1)
    val rows = mutableStateListOf<VideoRow>()
    val settings = mutableStateListOf<SettingsItem>()
    var error by mutableStateOf<ErrorFragmentData?>(null)
    var loading by mutableStateOf(false)
    var focusedVideo by mutableStateOf<Video?>(null)
    /** Logged-in Google/YouTube account for the top-right Liquid Glass profile orb. */
    var accountName by mutableStateOf<String?>(null)
    var accountAvatarUrl by mutableStateOf<String?>(null)
    /** Растёт, когда презентер просит перевести фокус на содержимое. */
    var focusContentRequests by mutableIntStateOf(0)

    val currentSection: BrowseSection? get() = sections.getOrNull(selected)

    val isGrid: Boolean get() = currentSection?.type.let {
        it == BrowseSection.TYPE_GRID || it == BrowseSection.TYPE_MULTI_GRID || it == BrowseSection.TYPE_SHORTS_GRID
    }

    val isSettings: Boolean get() = currentSection?.type == BrowseSection.TYPE_SETTINGS_GRID

    val isEmpty: Boolean get() = if (isSettings) settings.isEmpty() else rows.all { it.videos.isEmpty() }

    fun clearContent() {
        rows.clear()
        settings.clear()
        error = null
        focusedVideo = null
    }

    fun belongsHere(section: BrowseSection?): Boolean = section == null || section.id == currentSection?.id

    fun apply(group: VideoGroup) {
        if (!belongsHere(group.section)) return
        error = null
        val grid = isGrid
        val rowId = if (grid) VideoRow.GRID_ROW else group.id
        fun row() = rows.firstOrNull { it.id == rowId }

        when (group.action) {
            VideoGroup.ACTION_REPLACE -> if (grid || group.position == -1) rows.clear() else rows.removeAll { it.id == rowId }
            VideoGroup.ACTION_REMOVE -> {
                val gone = group.videos.orEmpty()
                row()?.let { r -> r.videos.removeAll { v -> gone.any { it == v } }; r.rekey() }
                return
            }
            VideoGroup.ACTION_REMOVE_AUTHOR -> {
                val channels = group.videos.orEmpty().mapNotNull { it.channelId }.toSet()
                rows.forEach { r -> r.videos.removeAll { it.channelId in channels }; r.rekey() }
                return
            }
            VideoGroup.ACTION_SYNC -> {
                row()?.let { r ->
                    group.videos.orEmpty().forEach { fresh ->
                        val i = r.videos.indexOfFirst { it == fresh }
                        if (i >= 0) r.videos[i] = fresh
                    }
                }
                return
            }
        }
        if (group.isEmpty) return

        val existing = row()
        if (existing == null) {
            val created = VideoRow(rowId, if (grid) null else group.title, group.isShorts || currentSection?.type == BrowseSection.TYPE_SHORTS_GRID)
            created.add(group.videos)
            val at = group.position
            if (at < 0 || at > rows.size) rows.add(created) else rows.add(at, created)
        } else if (group.action == VideoGroup.ACTION_PREPEND) {
            existing.add(group.videos, atStart = true)
        } else {
            existing.add(group.videos)
        }
    }
}
