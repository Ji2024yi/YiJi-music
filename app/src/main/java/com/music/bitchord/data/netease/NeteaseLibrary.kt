package com.music.bitchord.data.netease

import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * 资料库里歌单卡片的 browseId 前缀。
 *
 * 跟 `netease:playlist:<id>` 是同一个拼法，宿主点卡片时按它推
 * `AppRoute.NeteasePlaylist`；放在这里是为了首页、探索、资料库三处不再各写一份
 * 字符串字面量。
 */
const val NETEASE_PLAYLIST_PREFIX = "netease:playlist:"

/**
 * 网易云的「我喜欢的音乐」，一份进程内状态。
 *
 * 为什么单独一层而不复用 YouTube 的 [com.music.bitchord.data.LikeState]：
 * 那张表按 videoId 记评分，写回走 `YtMusicRepository.rate`，而网易云的写回是
 * `/api/like`，两边连"喜欢"的语义都不同。这里只记**数字 trackId**，跟 cookie
 * 绑定的账号一一对应；退出登录时整份清掉，不会把上个账号的心形留给下个人。
 *
 * 三个来源都会更新它：`MainViewModel.fetchLibrary` 的全量同步、播放器心形的
 * 乐观写、`BitChordApplication` 冷启动那次轻量预热。
 */
object NeteaseLikes {

    private val _ids = MutableStateFlow<Set<Long>>(emptySet())
    val ids: StateFlow<Set<Long>> = _ids.asStateFlow()

    /** 「我喜欢的音乐」那张系统歌单的 id，资料库顶部的入口卡要用。 */
    private val _likedPlaylistId = MutableStateFlow<Long?>(null)
    val likedPlaylistId: StateFlow<Long?> = _likedPlaylistId.asStateFlow()

    /** 已经补齐封面与作者的喜欢歌曲，直接并进 `LibraryPage.likedSongs`。 */
    private val _songs = MutableStateFlow<List<Song>>(emptyList())
    val songs: StateFlow<List<Song>> = _songs.asStateFlow()

    /**
     * 账号的歌单卡片，[ShelfItem.isNetease] 已置 true。
     *
     * 资料库把它们和本机歌单排进同一组（`LibraryScreen` 的 `playlists` 参数），
     * 不再单开一排「网易云歌单」——那样资料库会多出一截。
     */
    private val _playlists = MutableStateFlow<List<ShelfItem>>(emptyList())
    val playlists: StateFlow<List<ShelfItem>> = _playlists.asStateFlow()

    /** 标记「已经问过服务端了」；没登录 / 拉取失败时保持 false，别拿空集合清状态。 */
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    fun isLiked(trackId: Long): Boolean = trackId > 0 && trackId in _ids.value

    /** 一次全量同步的落点。 */
    fun replace(
        ids: Set<Long>,
        likedPlaylistId: Long?,
        songs: List<Song>,
        playlists: List<ShelfItem> = _playlists.value,
    ) {
        _ids.value = ids
        _likedPlaylistId.value = likedPlaylistId
        _songs.value = songs
        _playlists.value = playlists
        _loaded.value = true
    }

    /** ：只换歌单卡片（喜欢没拿到、歌单拿到了的时候也要显示）。 */
    fun replacePlaylists(items: List<ShelfItem>) {
        _playlists.value = items
    }

    /** 只刷新 id（冷启动预热用，不出歌曲对象）。 */
    fun replaceIds(ids: Set<Long>) {
        _ids.value = ids
        _loaded.value = true
    }

    /** 心形点亮：先把 id 和这一首歌都塞进去，界面立刻有反馈。 */
    fun add(trackId: Long, song: Song? = null) {
        if (trackId <= 0) return
        _ids.value = _ids.value + trackId
        if (song != null && _songs.value.none { it.videoId == song.videoId }) {
            _songs.value = _songs.value + song
        }
    }

    /** 心形熄灭 / 写回失败的回滚。 */
    fun remove(trackId: Long) {
        _ids.value = _ids.value - trackId
        _songs.value = _songs.value.filterNot { trackIdOf(it.videoId) == trackId }
    }

    /** 退出登录：账号换了，上一个人的喜欢不能留在屏幕上。 */
    fun clear() {
        _ids.value = emptySet()
        _likedPlaylistId.value = null
        _songs.value = emptyList()
        _playlists.value = emptyList()
        _loaded.value = false
    }

    /**
     * 这个 videoId 是不是**启用的网易云源**里的曲目，是就把数字 trackId 交出来。
     *
     * 不能只判 `src:` 前缀：别的音源也用 `SourceRegistry.trackKey`，拿它们的 id
     * 去调 `/api/like` 会喜欢到一首根本不存在的歌上。
     */
    fun trackIdOf(videoId: String): Long? {
        val (configId, trackId) = SourceRegistry.parseTrackKey(videoId) ?: return null
        // 配置查不到也认。本分支只剩网易云这一个外部源，`src:` key 里
        // 的 trackId 一定是它的数字 id；而 `SourceRegistry.config` 在启动早期
        // 可能还没装载，返回 null 就把心形判成"不是网易云歌"、点了毫无反应。
        // 只有明确查到「是别的源」时才拒绝。
        val kind = SourceRegistry.config(configId)?.kind
        if (kind != null && kind != SourceKind.NETEASE) return null
        return trackId.toLongOrNull()?.takeIf { it > 0 }
    }

    fun isNeteaseKey(videoId: String): Boolean = trackIdOf(videoId) != null
}

/**
 * 「我喜欢的」这一页把两份喜欢合成一份列表。
 *
 * 本机喜欢在前、网易云在后，按 videoId 去重；关掉开关就纯本机。放在这里是
 * 为了能单测，也为了这个合并口径只有一处——播放页心形、资料库 likedSongs
 * 都不走这里，它们各自有自己的语义。
 */
fun mergeLikedSongs(
    device: List<Song>,
    netease: List<Song>,
    includeNetease: Boolean,
): List<Song> {
    if (!includeNetease || netease.isEmpty()) return device
    val seen = device.mapTo(HashSet()) { it.videoId }
    return device + netease.filter { seen.add(it.videoId) }
}

/** 一首网易云的歌 → [Song]，与 `NeteaseSource` 搜索结果的拼法完全一致。 */
fun NeteaseApi.Track.asSong(configId: String): Song = Song(
    videoId = SourceRegistry.trackKey(configId, id.toString()),
    title = title,
    artist = artist,
    albumName = album,
    thumbnailUrl = artwork,
    durationText = durationSec?.let { seconds ->
        "${seconds / 60}:${"%02d".format(Locale.ROOT, seconds % 60)}"
    },
)
