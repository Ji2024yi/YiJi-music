package com.music.bitchord.data.artwork

import android.content.Context
import android.content.SharedPreferences
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 某一页自己的一张封面。
 *
 * 他要的是资料库/歌单这些**页头**那块封面能定下来：要么用这一栏里某首歌的图，
 * 要么以后选一张图。这里存的就是"这一页用哪张图"，按页面的 browseId 记，跟文件
 * 本身无关 —— 所以 WebDAV 上那首歌的标签不用重打，服务器上一个字节都不动。
 *
 * 存的是地址而不是字节：本机和服务器上的封面本来就各自有一个能读的地址，抄下来
 * 比复制一份图片更省，也不会出现两份图不同步。
 */
object CardCovers {

    private const val PREF_NAME = "bitchord_card_covers"

    /** 与 `ArtworkCustomizer` 同一条线：比这大的图不是封面，是照片。 */
    private const val MAX_IMAGE_BYTES = 12L * 1024 * 1024

    private lateinit var prefs: SharedPreferences

    private val _urls = MutableStateFlow<Map<String, String>>(emptyMap())

    /** browseId → 封面地址。页面每次重画都读这份，所以做成流。 */
    val urls: StateFlow<Map<String, String>> = _urls.asStateFlow()

    fun init(context: Context) {
        if (this::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val all = prefs.all.filterKeys { it != "version" }
        _urls.value = all.mapNotNull { (key, value) ->
            (value as? String)?.takeIf { it.isNotBlank() }?.let { key to it }
        }.toMap()
    }

    fun urlOf(browseId: String?): String? = browseId?.let { _urls.value[it] }

    /** 用这首歌的封面当这一页的封面。歌没有封面就什么都不做（不写一个空地址）。 */
    fun setFromSong(context: Context, browseId: String?, song: Song): Boolean {
        val url = artworkOf(song) ?: return false
        return set(context, browseId, url)
    }

    fun set(context: Context, browseId: String?, url: String): Boolean {
        if (browseId.isNullOrBlank() || url.isBlank()) return false
        init(context)
        prefs.edit().putString(browseId, url).apply()
        _urls.value = _urls.value + (browseId to url)
        return true
    }

    fun clear(context: Context, browseId: String?) {
        if (browseId.isNullOrBlank()) return
        init(context)
        prefs.edit().remove(browseId).apply()
        _urls.value = _urls.value - browseId
    }

    /**
     * 从相册选一张图当这一页的封面。
     *
     * 选完拿到的 `content://` 地址只有这一次授权有效，重启就废了 —— 所以字节要抄
     * 一份进 App 自己的目录，记的是那份 `file://`。这也是 [setFromSong] 记"某首歌
     * 的图"时不抄一份的原因：那种地址本来就一直在。
     *
     * 返回 false 是没选、太大或读不出来；调用方按结果给一句话回执。
     */
    suspend fun importImage(context: Context, browseId: String?, uri: android.net.Uri): Boolean =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            if (browseId.isNullOrBlank()) return@withContext false
            val bytes = runCatching {
                context.applicationContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }.getOrNull() ?: return@withContext false
            if (bytes.size > MAX_IMAGE_BYTES) return@withContext false
            val dir = java.io.File(context.applicationContext.filesDir, "covers").apply { mkdirs() }
            val name = "page-${md5(browseId)}.${extensionOf(bytes)}"
            val file = java.io.File(dir, name)
            runCatching { file.writeBytes(bytes).let { true } }.getOrNull() ?: return@withContext false
            set(context, browseId, android.net.Uri.fromFile(file).toString())
        }

    private fun extensionOf(bytes: ByteArray): String = when {
        bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "jpg"
        bytes.size > 4 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() -> "png"
        bytes.size > 12 && String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WEBP" -> "webp"
        else -> "img"
    }

    private fun md5(text: String): String = java.security.MessageDigest.getInstance("MD5")
        .digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    /**
     * 一首歌能拿出来的封面地址。
     *
     * `thumbnailUrl` 对本地文件是 MediaStore 的 albumart 地址、对 WebDAV 是同名
     * .jpg 的地址，两种都能被图片加载器直接读，所以这里只判空，不做二次加工。
     */
    private fun artworkOf(song: Song): String? =
        song.thumbnailUrl?.takeIf { it.isNotBlank() && !it.startsWith("file:///null") }
}
