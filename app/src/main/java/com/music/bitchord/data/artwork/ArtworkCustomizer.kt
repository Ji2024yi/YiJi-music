package com.music.bitchord.data.artwork

import android.content.Context
import android.net.Uri
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.lyrics.EmbeddedLyrics
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.webdav.WebDavClient
import com.music.bitchord.data.webdav.WebDavConfig
import java.io.ByteArrayInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 给一首歌换一张自己定的封面。
 *
 * 存到哪儿，取决于这首歌住在哪儿 —— 两条路都是仓库里现成的机制，没有新格式：
 *
 *  本机文件（含已经缓存下来的 WebDAV 那份）：写进文件自己的标签。FLAC / MP4 /
 *    WebM 都支持内嵌图，别的播放器也读得到。
 *  WebDAV 上那份：往**同一个目录**放一张同名 `.jpg`。曲库读封面走的就是这个约定
 *    （`RemoteArtwork` 的优先名是 cover/folder/front/albumart/album/artwork，其余按
 *    名字排），所以这张图一放上去，列表、播放页、小组件全都跟着变。
 *
 * 写标签那一步有个坑必须避开：重打标签会**整块替换** VORBIS_COMMENT / ilst，只写
 * 封面会把文件里已有的歌词一起抹掉 —— 在上传路径上就是这么弄丢歌词的。
 * 所以这里先把已有歌词读出来一起写回去。
 *
 * 图片本身有上限：一张 40MB 的扫描图不该把一首 30MB 的歌的标签撑成 70MB。
 */
object ArtworkCustomizer {

    private const val TAG = "BitChord"

    /** 单张封面的上限，比绝大多数专辑扫描都宽。 */
    private const val MAX_ARTWORK_BYTES = 12 * 1024 * 1024

    /** 换好了的话，说清存到哪儿了。 */
    sealed interface Outcome {
        data class Saved(val where: String) : Outcome
        data object TooLarge : Outcome
        data object NothingToChange : Outcome
        data class Failed(val message: String) : Outcome
    }

    suspend fun apply(context: Context, song: Song, image: Uri): Outcome =
        withContext(Dispatchers.IO) {
            val app = context.applicationContext
            val bytes = runCatching {
                app.contentResolver.openInputStream(image)?.use { it.readBytes() }
            }.getOrNull()
            if (bytes == null || bytes.isEmpty()) return@withContext Outcome.Failed("读不到那张图")
            if (bytes.size > MAX_ARTWORK_BYTES) return@withContext Outcome.TooLarge
            val mime = app.contentResolver.getType(image)?.takeIf { it.startsWith("image/") } ?: "image/jpeg"
            val wrote = mutableListOf<String>()
            localCopy(app, song)?.let { fileUri ->
                if (writeIntoFile(app, song, fileUri, bytes, mime)) wrote += "本机文件"
            }
            if (WebDavConfig.isWebDavId(song.videoId) && writeBesideTheTrack(app, song, bytes)) {
                wrote += "服务器同目录"
            }
            if (wrote.isEmpty()) {
                return@withContext Outcome.NothingToChange
            }
            Outcome.Saved(wrote.joinToString("、"))
        }

    /**
     * 本机那份文件（可能是缓存下来的 WebDAV 副本）。
     *
     * content:// 的行不能整份重写标签，所以这里只认能拿到路径的那些；拿不到就
     * 只走服务器那一路。
     */
    private fun localCopy(context: Context, song: Song): Uri? {
        song.localPath?.let { if (it.startsWith("/") && java.io.File(it).isFile) return Uri.parse("file://$it") }
        song.localUri?.takeIf { it.startsWith("file://") }?.let { return Uri.parse(it) }
        val saved = com.music.bitchord.download.Downloads.verifiedSavedUri(song.videoId) ?: return null
        return if (saved.startsWith("file://")) Uri.parse(saved) else null
    }

    private suspend fun writeIntoFile(
        context: Context,
        song: Song,
        file: Uri,
        bytes: ByteArray,
        mime: String,
    ): Boolean {
        val extension = (file.path ?: "").substringAfterLast('.', "")
        if (!com.music.bitchord.download.MediaTagger.carriesTags(extension.lowercase())) return false
        // 见类注释：重打标签会连歌词一起换掉，所以先把原来那份读出来带回去。
        val existing = runCatching {
            EmbeddedLyrics.rawForUri(context, file.toString())
        }.getOrNull()?.takeIf { it.isNotBlank() }?.let {
            com.music.bitchord.download.LyricsTag.Embeddable(it, null)
        }
        return runCatching {
            com.music.bitchord.download.MediaTagger.embed(
                context,
                file,
                song,
                extension.lowercase(),
                lyrics = existing,
                artwork = com.music.bitchord.download.MediaTagger.Artwork(bytes, mime),
            )
        }.onFailure { Log.d(TAG, "could not write artwork into $file: ${it.message}") }.isSuccess
    }

    /** 往音频所在目录写一张同名 .jpg —— 和曲库读封面的那条路对上。 */
    private suspend fun writeBesideTheTrack(context: Context, song: Song, bytes: ByteArray): Boolean {
        val audioUrl = WebDavConfig.fileUrlOf(song.videoId) ?: return false
        val coverUrl = coverUrlFor(audioUrl) ?: return false
        val written = runCatching {
            ByteArrayInputStream(bytes).use { input ->
                WebDavClient.putFile(
                    fileUrl = coverUrl,
                    stream = input,
                    contentLength = bytes.size.toLong(),
                    mimeType = "image/jpeg",
                    username = AppSettings.webdavUsername.value,
                    password = AppSettings.webdavPassword.value,
                    overwrite = true,
                )
            }
        }.getOrNull()?.isSuccess == true
        if (!written) Log.d(TAG, "could not put cover at $coverUrl")
        return written
    }

    /**
     * 音频地址换封面地址：去掉 query、扩展名换成 .jpg、把 query 接回去。
     *
     * 和 `RemoteSong.coverUrlFor` 同一套写法 —— 那边读、这边写，两边必须算出同
     * 一个地址，否则传上去的封面没人看得见。
     */
    internal fun coverUrlFor(audioUrl: String): String? {
        val query = audioUrl.indexOf('?')
        val head = if (query >= 0) audioUrl.substring(0, query) else audioUrl
        val dot = head.lastIndexOf('.')
        if (dot < 0) return null
        return head.substring(0, dot) + ".jpg" + if (query >= 0) audioUrl.substring(query) else ""
    }
}
