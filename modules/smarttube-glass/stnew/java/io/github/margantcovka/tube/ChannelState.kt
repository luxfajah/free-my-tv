package io.github.margantcovka.tube

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup

/** Состояние экрана канала: ряды (загрузки, плейлисты…) приходят от ChannelPresenter по одному. */
class ChannelState {
    val rows = mutableStateListOf<VideoRow>()
    var title by mutableStateOf<String?>(null)
    var loading by mutableStateOf(false)
    var focusedVideo by mutableStateOf<Video?>(null)

    val isEmpty: Boolean get() = rows.all { it.videos.isEmpty() }

    fun clear() {
        rows.clear()
        focusedVideo = null
    }

    fun apply(group: VideoGroup) {
        if (group.action == VideoGroup.ACTION_REPLACE) rows.removeAll { it.id == group.id }
        if (group.isEmpty) return
        val existing = rows.firstOrNull { it.id == group.id }
        when {
            existing == null -> {
                val created = VideoRow(group.id, group.title, group.isShorts)
                created.add(group.videos)
                val at = group.position
                if (at < 0 || at > rows.size) rows.add(created) else rows.add(at, created)
            }
            group.action == VideoGroup.ACTION_PREPEND -> existing.add(group.videos, atStart = true)
            else -> existing.add(group.videos)
        }
    }
}
