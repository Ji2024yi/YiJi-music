package com.music.bitchord.playback

import com.music.bitchord.data.lyrics.LyricLine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 通知栏歌词的**一行状态**。
 *
 * 播放服务与这个 App 同进程（见 BitChordApplication 的注释），所以歌词行不必走
 * Binder / SessionCommand：服务里的 `updateLyricSubtitle` 算出当前句写进这里，
 * 同一段代码再交给 `LyricsNotification` post 那条独立的歌词通知。
 *
 * 之后这里只剩「当前该显示哪一句」这一层判断；媒体通知不再读它——
 * /66 覆写 `DefaultMediaNotificationProvider.getNotificationContentText`
 * 那条路在真机上始终没换来歌词，见 [LyricsNotification] 的注释。
 */
object NotificationLyrics {

    private val _displayLine = MutableStateFlow<String?>(null)
    val displayLine: StateFlow<String?> = _displayLine.asStateFlow()

    val currentLine: String? get() = _displayLine.value

    fun setLine(line: String?) {
        _displayLine.value = line?.takeIf { it.isNotBlank() }
    }

    fun clear() {
        _displayLine.value = null
    }

    /**
     * 纯函数：这个播放位置该显示哪句词。
     *
     * 跟播放页那套 `indexOfLast { timeMs <= pos }` 同一个口径，但空白的 gap 行
     * 不把上一句抹掉——通知栏每两三秒重建一次，一句词闪成空白比留着更难受。
     * 还没到第一句时返回 null，通知就照常显示艺人，不会挂一行空字。
     */
    fun activeLineAt(lines: List<LyricLine>?, positionMs: Long): String? {
        if (lines.isNullOrEmpty()) return null
        var last: String? = null
        for (line in lines) {
            if (line.timeMs > positionMs) break
            if (!line.isGap && line.text.isNotBlank()) last = line.text.trim()
        }
        return last
    }
}
