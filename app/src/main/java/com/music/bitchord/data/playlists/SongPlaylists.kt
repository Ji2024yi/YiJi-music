package com.music.bitchord.data.playlists

import android.content.Context
import android.content.SharedPreferences
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * 自己建的歌单。
 *
 * 和 `OfflinePlaylists`（只装本机文件、按 `localUri` 认成员）与
 * `LocalPlaylistStore`（Spotify 导入时代的遗留）都不同的地方在于：这里的成员
 * 按 [Song.videoId] 认，WebDAV 那首 `webdav:http…` 和本地那首 `file://…` 能进
 * 同一张歌单 —— 曲库跟着服务器和本机走，歌单也就跟着走。
 *
 * 每张歌单存的不只是 id，还有一份行的快照（标题/艺术家/封面/时长）。理由是
 * 曲库是「在线的」：WebDAV 没连上、或者那首被移走时，歌单页不该塌成空白 ——
 * 它照常把行画出来，能不能播是另一回事。打开时先用 [SongPlaylists.resolve]
 * 拿当前曲库里的那份（URL 会刷新），拿不到才退回快照。
 */
data class SongPlaylist(
    val id: String,
    val title: String,
    /** 加入顺序，最早在最前。 */
    val songs: List<Song>,
    val createdAt: Long,
) {
    val browseId: String get() = SongPlaylists.PREFIX + id
}

object SongPlaylists {

    /** 别的本地页一样挂在 `local:` 下，段名是自己的，好认也好让下载按钮避开。 */
    const val PREFIX = "local:pl:"

    private const val PREF_NAME = "bitchord_song_playlists"
    private const val KEY_PLAYLISTS = "playlists_json"

    private lateinit var prefs: SharedPreferences
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _playlists = MutableStateFlow<List<SongPlaylist>>(emptyList())
    val playlists: StateFlow<List<SongPlaylist>> = _playlists.asStateFlow()

    fun init(context: Context) {
        if (this::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        _playlists.value = load()
        val loaded = _playlists.value
        io.launch {
            playlists.filter { it !== loaded }.conflate().collect { persist(it) }
        }
    }

    fun isPlaylist(browseId: String?): Boolean = browseId?.startsWith(PREFIX) == true

    /** 从 `local:pl:xyz` 拿到歌单本身；不是这个前缀或已被删掉时是 null。 */
    fun get(browseId: String?): SongPlaylist? {
        val id = browseId?.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX) ?: return null
        return _playlists.value.firstOrNull { it.id == id }
    }

    /**
     * 建一张空歌单。名字空着的时候不建 —— 返回 null，让调用方什么都不做，
     * 而不是留下一张「未命名」让人清理。
     */
    fun create(rawTitle: String): SongPlaylist? {
        val title = rawTitle.trim()
        if (title.isEmpty()) return null
        val playlist = SongPlaylist(
            id = "pl_" + System.currentTimeMillis(),
            title = title,
            songs = emptyList(),
            createdAt = System.currentTimeMillis(),
        )
        write(_playlists.value + playlist)
        return playlist
    }

    fun rename(browseId: String, title: String) {
        val id = browseId.removePrefix(PREFIX)
        val clean = title.trim()
        if (clean.isEmpty()) return
        write(_playlists.value.map { if (it.id == id) it.copy(title = clean) else it })
    }

    fun delete(browseId: String) {
        val id = browseId.removePrefix(PREFIX)
        write(_playlists.value.filterNot { it.id == id })
    }

    /**
     * 把 [song] 加进 [ids] 里的每张歌单。
     *
     * 同一张里不塞两遍（按 videoId 判重），所以重复点「加到歌单」不会长出双行。
     * 返回值是真正新增了几张，供提示语决定说「已加入」还是「已经在里面了」。
     */
    fun add(ids: Collection<String>, song: Song): Int {
        if (song.videoId.isBlank()) return 0
        var added = 0
        write(
            _playlists.value.map { playlist ->
                if (playlist.id !in ids) return@map playlist
                if (playlist.songs.any { it.videoId == song.videoId }) return@map playlist
                added++
                playlist.copy(songs = playlist.songs + song.copyForStore())
            },
        )
        return added
    }

    /** 整张往里加（播放页「全部加进歌单」用）。 */
    fun addAll(ids: Collection<String>, songs: List<Song>): Int {
        var added = 0
        for (song in songs) added += add(ids, song)
        return added
    }

    fun remove(browseId: String, videoId: String) {
        val id = browseId.removePrefix(PREFIX)
        write(
            _playlists.value.map {
                if (it.id == id) it.copy(songs = it.songs.filterNot { s -> s.videoId == videoId }) else it
            },
        )
    }

    /** 一次移除多首，拖多选之后用。 */
    fun removeAll(browseId: String, videoIds: Collection<String>) {
        val id = browseId.removePrefix(PREFIX)
        write(
            _playlists.value.map {
                if (it.id == id) it.copy(songs = it.songs.filterNot { s -> s.videoId in videoIds }) else it
            },
        )
    }

    /** 歌单里有没有这首——用来在菜单上打勾，也用来避开重复添加。 */
    fun contains(videoId: String): Set<String> =
        _playlists.value.filter { p -> p.songs.any { it.videoId == videoId } }.map { it.id }.toSet()

    private fun write(list: List<SongPlaylist>) {
        _playlists.value = list
        if (this::prefs.isInitialized) persist(list)
    }

    /**
     * 落盘的那份只留认得回来的字段。
     *
     * 队列 id、`setVideoId`、radio 种子这些是播放期的临时身份，存进歌单会在下次
     * 打开时冒充成员身份，所以一概不带。
     */
    private fun persist(list: List<SongPlaylist>) {
        io.launch {
            runCatching {
                val array = JSONArray()
                for (playlist in list) {
                    val obj = JSONObject()
                    obj.put("id", playlist.id)
                    obj.put("title", playlist.title)
                    obj.put("createdAt", playlist.createdAt)
                    val songs = JSONArray()
                    for (song in playlist.songs) songs.put(songToJson(song))
                    obj.put("songs", songs)
                    array.put(obj)
                }
                prefs.edit().putString(KEY_PLAYLISTS, array.toString()).apply()
            }
        }
    }

    private fun load(): List<SongPlaylist> {
        if (!this::prefs.isInitialized) return emptyList()
        val raw = prefs.getString(KEY_PLAYLISTS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val obj = array.optJSONObject(index) ?: return@mapNotNull null
                val id = obj.optString("id")
                if (id.isBlank()) return@mapNotNull null
                val songs = obj.optJSONArray("songs") ?: JSONArray()
                SongPlaylist(
                    id = id,
                    title = obj.optString("title").ifBlank { "未命名" },
                    songs = (0 until songs.length()).mapNotNull { i ->
                        songs.optJSONObject(i)?.let(::songFromJson)
                    },
                    createdAt = obj.optLong("createdAt", 0L),
                )
            }
        }.getOrDefault(emptyList())
    }

    /** 只存回查得到的最小一份；`queueEntryId` 之类的临时字段留在外面。 */
    private fun songToJson(song: Song): JSONObject = JSONObject().apply {
        put("videoId", song.videoId)
        put("title", song.title)
        put("artist", song.artist)
        song.thumbnailUrl?.let { put("thumbnailUrl", it) }
        song.durationText?.let { put("durationText", it) }
        song.albumName?.let { put("albumName", it) }
        song.albumId?.let { put("albumId", it) }
        song.artistId?.let { put("artistId", it) }
        song.localUri?.let { put("localUri", it) }
        song.localPath?.let { put("localPath", it) }
        if (song.isVideo) put("isVideo", true)
    }

    private fun songFromJson(obj: JSONObject): Song? {
        val videoId = obj.optString("videoId")
        if (videoId.isBlank()) return null
        return Song(
            videoId = videoId,
            title = obj.optString("title"),
            artist = obj.optString("artist"),
            thumbnailUrl = obj.optString("thumbnailUrl").takeIf { it.isNotBlank() && it != "null" },
            durationText = obj.optString("durationText").takeIf { it.isNotBlank() && it != "null" },
            artistId = obj.optString("artistId").takeIf { it.isNotBlank() && it != "null" },
            albumId = obj.optString("albumId").takeIf { it.isNotBlank() && it != "null" },
            albumName = obj.optString("albumName").takeIf { it.isNotBlank() && it != "null" },
            localUri = obj.optString("localUri").takeIf { it.isNotBlank() && it != "null" },
            localPath = obj.optString("localPath").takeIf { it.isNotBlank() && it != "null" },
            isVideo = obj.optBoolean("isVideo", false),
        )
    }

    /** 入库前先削成快照形态，免得把临时身份写进磁盘。 */
    private fun Song.copyForStore(): Song = songFromJson(songToJson(this)) ?: this
}
