package com.music.bitchord.data.webdav

import android.content.Context
import android.net.Uri
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.download.DownloadStore
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 把服务器上的歌**存到手机本地**。
 *
 * 反方向的 [WebDavUploads] 早就有了（本地文件传上 WebDAV）；这一边原来没有——长按
 * WebDAV 里的歌点"下载"会走 YouTube 那条解析流，对远程文件不成立。这里改成：直接对
 * 该文件的 https 直链发 GET，流式写进 [DownloadStore] 的落盘位置（设置里"导出到公共
 * 目录"开着就进 Music/BitChord，手机本地曲库和别的播放器都认；关着就进 App 私有目录）。
 *
 * 写的是 `.part`/IS_PENDING 的临时态，失败就 abort，不留半截文件冒充完整歌曲——
 * 这点和上传那边一样要紧。
 */
object WebDavDownloads {

    /** 只有 WebDAV 来的行能这样存；本地文件和 SMB 走各自的路。 */
    fun isSavable(song: Song): Boolean = WebDavConfig.isWebDavId(song.videoId)

    /**
     * 这一首本机是不是已经有一份了。
     *
     * 原来这里还判 `song.localUri != null` —— WebDAV 那行的 localUri 就是服务器
     * 直链（永远非空），那句话等于「全都存过」，批量缓存一首也不会动。只认下载
     * 记录，并核一眼文件还在（可能在文件管理器里被删过）。
     */
    fun alreadySaved(song: Song): Boolean =
        com.music.bitchord.download.Downloads.verifiedSavedUri(song.videoId) != null

    suspend fun save(
        context: Context,
        song: Song,
        onProgress: (fraction: Float?) -> Unit = {},
    ): Result<Uri> = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val fileUrl = WebDavConfig.fileUrlOf(song.videoId)
            ?: return@withContext Result.failure(WebDavClient.WebDavException("This row is not a WebDAV file"))
        val username = AppSettings.webdavUsername.value
        val password = AppSettings.webdavPassword.value
        val extension = extensionOf(fileUrl, song)
        val name = DownloadStore.fileNameFor(song, extension)
        val pending = runCatching { DownloadStore.begin(appContext, name, mimeFor(extension)) }
            .getOrElse { return@withContext Result.failure(it) }
        try {
            val written = WebDavClient.downloadToFile(
                fileUrl = fileUrl,
                username = username,
                password = password,
                sink = pending.openStream(),
            ) { bytes, total ->
                onProgress(if (total > 0L) (bytes.toFloat() / total).coerceIn(0f, 1f) else null)
            }.getOrElse {
                pending.abort()
                return@withContext Result.failure(it)
            }
            if (written <= 0L) {
                pending.abort()
                return@withContext Result.failure(WebDavClient.WebDavException("The server sent no bytes"))
            }
            val committed = pending.commit()
            // 落盘就登记进下载记录。不登记，App 就不知道本机有
            // 这一份，下次播仍然走服务器下行——缓存了等于没缓存。
            com.music.bitchord.download.Downloads.noteSavedOnDevice(song, committed)
            Result.success(committed)
        } catch (t: Throwable) {
            runCatching { pending.abort() }
            Result.failure(t)
        }
    }

    /** 后缀优先看 URL 路径，其次退回文件名；都没有就当 mp3，让解码器自己判。 */
    private fun extensionOf(fileUrl: String, song: Song): String {
        val fromUrl = fileUrl.substringBefore('?').substringAfterLast('/', "")
            .substringAfterLast('.', "").lowercase(Locale.ROOT)
        val known = listOf("flac", "mp3", "m4a", "opus", "ogg", "wav", "aac", "wma", "aiff", "ape")
        if (fromUrl in known) return fromUrl
        val fromTitle = song.title.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return if (fromTitle in known) fromTitle else "mp3"
    }

    private fun mimeFor(extension: String) = when (extension) {
        "flac" -> "audio/flac"
        "mp3" -> "audio/mpeg"
        "m4a", "aac" -> "audio/mp4"
        "opus" -> "audio/opus"
        "ogg" -> "audio/ogg"
        "wav" -> "audio/x-wav"
        else -> "audio/*"
    }
}
