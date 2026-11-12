package com.music.bitchord.data.artwork

import android.content.Context
import android.content.SharedPreferences
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.webdav.WebDavConfig
import java.io.File
import java.security.MessageDigest

/**
 * 私房版补丁 45：一首歌**自己带着**的那张封面。
 *
 * 曲库列表只认放在旁边的图片文件（`WebDavRepository.artworkFor`），而他服务器上
 * 只传了 flac，一个 .jpg 都没有 —— 所以 WebDAV 那些行本来就是没封面的，页头自然
 * 也拿不到东西。可这两个文件里确实各自嵌着一张 JPEG（实测：`青春不打烊` 的封面块
 * 在偏移 4119、`想你` 在 2321，块体里第一个字段就是 `image/jpeg`）。
 *
 * 这里干的就是把那张图取出来、在 App 自己的目录里存成一份 jpg，然后把它的
 * `file://` 地址交回去。取一次就记住（[remember]），之后每回进页面都是本地读，
 * 不再问服务器 —— 服务器上一个字节都没动，也没多放文件。
 *
 * 成本按便宜程度排：已有的行内地址 → 记住过的本机文件 → 本机那份 → 服务器那份
 * （只读文件头那一截，`MediaMetadataRetriever` 需要多少读多少）。
 */
object EmbeddedCovers {

    private const val TAG = "BitChord"
    private const val PREF_NAME = "bitchord_embedded_covers"

    /** 提取过的地址：videoId → covers 目录里的文件名。 */
    private lateinit var store: SharedPreferences

    /** 正在提取的、这一轮已经确认取不到的，都先记着，免得一进页面就并发重试。 */
    private val inFlight = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val failed = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    private fun prefs(context: Context): SharedPreferences =
        if (this::store.isInitialized) store else context.applicationContext
            .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).also { store = it }

    /**
     * 曲库列表那几条路径手上没有上下文（`WebDavRepository.getSongs()` 就是），所以
     * 这里留一份 App 自己的。`AppSettings.init` 装好，装不上时 [fill] 原样返回，
     * 不报错也不假装做过。
     */
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    @Volatile
    private var coversDir: File? = null

    /** 列表每一行都会调一次，别在每行都 mkdirs() 做一次系统调用。 */
    private fun dir(context: Context): File = coversDir ?: synchronized(this) {
        coversDir ?: File(context.applicationContext.filesDir, "covers")
            .apply { mkdirs() }
            .also { coversDir = it }
    }

    /**
     * 已经有的封面地址，不做任何提取。列表每行都同步调它，所以必须只碰本地。
     *
     * 文件被清掉（用户在系统里清了 App 数据目录里的那份）就顺手把记录删了，
     * 下次进页面重新取。
     */
    fun cachedCoverOf(context: Context, videoId: String): String? {
        val name = prefs(context).getString(videoId, null) ?: return null
        val file = File(dir(context), name)
        if (!file.isFile) {
            prefs(context).edit().remove(videoId).apply()
            return null
        }
        return Uri.fromFile(file).toString()
    }

    /**
     * 把一栏里"没有封面"的行填上已经存好的那份。
     *
     * 只填本来为空的，绝不覆盖曲库列表算出来的侧车图地址——同目录那张 .jpg 才是
     * 用户放的，永远比这里抠出来的更新。
     */
    fun fill(context: Context, songs: List<Song>): List<Song> {
        if (songs.none { it.thumbnailUrl.isNullOrBlank() }) return songs
        return songs.map { song ->
            if (!song.thumbnailUrl.isNullOrBlank()) return@map song
            val url = cachedCoverOf(context, song.videoId) ?: return@map song
            song.copy(thumbnailUrl = url)
        }
    }

    /** 同上，用 [init] 存下的那份上下文——曲库列表那几条路径手上没有上下文。 */
    fun fill(songs: List<Song>): List<Song> =
        appContext?.let { fill(it, songs) } ?: songs

    /**
     * 取这一首的封面：有现成的就用，没有就抠一张出来。
     *
     * 返回 null 只有一种解释——这文件里本来就没图，或者读不到（服务器不通、路径没
     * 了）。失败会记进 [failed]，本次进程里不再为同一首重试，调用方可以放心地对
     * 一栏逐首试。
     */
    suspend fun coverFor(context: Context, song: Song): String? {
        song.thumbnailUrl?.takeIf { it.isNotBlank() }?.let { return it }
        cachedCoverOf(context, song.videoId)?.let { return it }
        val bytes = readOnce(song) { it.embeddedPicture } ?: return null
        return remember(context, song.videoId, bytes)
    }

    /** 这一栏里最合适的"代表曲"：优先本机有文件的，其次服务器上的。 */
    fun candidateFor(songs: List<Song>): Song? =
        songs.firstOrNull { it.thumbnailUrl.isNullOrBlank() && hasReadableSource(it) }

    private fun hasReadableSource(song: Song): Boolean =
        localSourceOf(song) != null || WebDavConfig.fileUrlOf(song.videoId) != null

    /** 抠出来的图存成文件，并把地址记下来；返回可直接加载的 `file://` 地址。 */
    private fun remember(context: Context, videoId: String, bytes: ByteArray): String? = runCatching {
        val name = "${md5(videoId)}.${extensionOf(bytes)}"
        val file = File(dir(context).apply { mkdirs() }, name)
        file.writeBytes(bytes)
        prefs(context).edit().putString(videoId, name).apply()
        Uri.fromFile(file).toString()
    }.onFailure { Log.d(TAG, "cover save failed for $videoId: ${it.message}") }.getOrNull()

    /**
     * 打开一次这首歌的文件，把 retriever 交给 block 去读它要的那个标签。
     *
     * 三个来源挨个试：本机那份（含已缓存的副本）→ 服务器那份（只读文件头那一截）。
     * 封面（[coverFor]）和年份（`YearTags`）走的是同一条路，省得两处各写一遍来源
     * 顺序 —— 那种重复迟早漂移。
     */
    internal fun <T> probe(song: Song, block: (MediaMetadataRetriever) -> T): T? {
        localSourceOf(song)?.let { path ->
            readWith(path, null, block)?.let { return it }
        }
        val remote = WebDavConfig.fileUrlOf(song.videoId)
            ?: song.localUri?.takeIf { it.startsWith("http") }
            ?: return null
        return readWith(remote, authHeadersFor(remote), block)
    }

    /**
     * 同一首歌只开一次的读取：谁在读、谁已经读不出来都记着，免得一进页面就并发
     * 重试同一首。返回 null 可能是"这文件里没这个标签"，也可能是"读不到"，两者
     * 都不值得再试一遍。
     */
    internal suspend fun <T> readOnce(song: Song, block: (MediaMetadataRetriever) -> T): T? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val key = song.videoId
            if (key.isBlank() || key in failed || !inFlight.add(key)) return@withContext null
            try {
                probe(song, block).also { if (it == null) failed += key }
            } finally {
                inFlight.remove(key)
            }
        }

    /** 本机那份：下载记录里的优先，其次行自带的 localPath / file:// 地址。 */
    private fun localSourceOf(song: Song): String? {
        song.localPath?.takeIf { File(it).isFile }?.let { return it }
        song.localUri?.takeIf { it.startsWith("file://") }?.let { return Uri.parse(it).path }
        val saved = com.music.bitchord.download.Downloads.verifiedSavedUri(song.videoId) ?: return null
        return if (saved.startsWith("file://")) Uri.parse(saved).path else saved
    }

    /**
     * 只给那台 WebDAV 带口令：地址是曲库列表给的，跳转之后就换了一家，
     * 凭据不能跟着走。同 `SongDuration`，两处各自保持只认设置里那个 host。
     */
    private fun authHeadersFor(url: String): Map<String, String>? {
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: return null
        val configured = WebDavConfig.hostOf(AppSettings.webdavUrl.value)
        if (!host.equals(configured, ignoreCase = true)) return null
        val auth = WebDavConfig.basicAuthHeader(
            AppSettings.webdavUsername.value,
            AppSettings.webdavPassword.value,
        ) ?: return null
        return mapOf("Authorization" to auth)
    }

    private fun <T> readWith(
        source: String,
        headers: Map<String, String>?,
        block: (MediaMetadataRetriever) -> T,
    ): T? = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            if (source.startsWith("http") && headers != null) {
                retriever.setDataSource(source, headers)
            } else {
                retriever.setDataSource(source)
            }
            block(retriever)
        } finally {
            runCatching { retriever.release() }
        }
    }.onFailure { Log.d(TAG, "tag read failed for $source: ${it.message}") }.getOrNull()

    /** 按文件头认一下格式，只为了让存下来的文件名字能看懂。 */
    private fun extensionOf(bytes: ByteArray): String = when {
        bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "jpg"
        bytes.size > 4 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() -> "png"
        bytes.size > 12 && String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WEBP" -> "webp"
        else -> "img"
    }

    private fun md5(text: String): String = MessageDigest.getInstance("MD5")
        .digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
