package com.music.bitchord.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import com.music.bitchord.data.model.Song
import java.io.File
import java.security.MessageDigest

/**
 * 本地歌曲的封面改成**逐文件**读内嵌图片。
 *
 * 改之前，两条本地路径（手机曲库 [LocalMediaRepository.getLocalMusic] 和 App 内下载
 * [LocalMediaRepository.getDownloadedSongs]）都把
 * `content://media/external/audio/albumart/<ALBUM_ID>` 当封面。那是 MediaStore 的
 * **专辑**图：album 标签相同、或者根本没写 album 而被归进同一个专辑 id 的歌，就会共用
 * 同一张图——这就是"有几首封面一样"的成因。别的播放器逐个文件读内嵌 picture，所以它们
 * 显示是对的。
 *
 * 现在每个文件自己取自己内嵌的封面，落到 `files/local-art/` 下，URL 用 `file://`。
 * 文件里真没嵌图的，保持原来的专辑图不变，行为不比以前差。
 *
 * 缓存 key 带文件路径、大小和修改时间，所以文件被换掉会重取；同一批歌第二次扫描只读盘，
 * 不再解码。
 */
object LocalEmbeddedArtwork {

    private const val TAG = "LocalArtwork"
    private const val DIR = "local-art"

    /** 就地替换封面地址：拿到内嵌图就换成 file:// 路径，拿不到返回原样。 */
    fun applyTo(context: Context, songs: List<Song>): List<Song> {
        if (songs.isEmpty()) return songs
        val dir = File(context.filesDir, DIR)
        if (!dir.isDirectory && !dir.mkdirs()) return songs
        return songs.map { song -> withEmbedded(context, dir, song) ?: song }
    }

    private fun withEmbedded(context: Context, dir: File, song: Song): Song? {
        val path = song.localPath?.takeIf { it.isNotBlank() }
        val uri = song.localUri?.takeIf { it.isNotBlank() }
        val source = path ?: uri ?: return null
        val handle = path?.let(::File)?.takeIf { it.isFile }
        val stamp = if (handle != null) "${handle.length()}:${handle.lastModified()}" else "u"
        val target = File(dir, sha1("$source|$stamp") + ".img")
        val url = "file://" + target.absolutePath
        if (target.isFile && target.length() > 0L) {
            return if (song.thumbnailUrl == url) null else song.copy(thumbnailUrl = url)
        }
        val bytes = readEmbedded(context, path, uri) ?: return null
        if (!runCatching { target.writeBytes(bytes) }.isSuccess) return null
        return song.copy(thumbnailUrl = url)
    }

    /** 优先按路径读（能拿到内嵌图且不触发 content 权限问题），否则退回 content URI。 */
    private fun readEmbedded(context: Context, path: String?, uri: String?): ByteArray? {
        val retriever = MediaMetadataRetriever()
        return try {
            val readable = path?.let { File(it) }?.takeIf { it.canRead() }
            if (readable != null) {
                retriever.setDataSource(readable.absolutePath)
            } else if (uri != null) {
                retriever.setDataSource(context, Uri.parse(uri))
            } else {
                return null
            }
            retriever.embeddedPicture
        } catch (t: Throwable) {
            Log.w(TAG, "no embedded artwork for ${path ?: uri}: ${t.message}")
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun sha1(raw: String): String =
        MessageDigest.getInstance("SHA-1").digest(raw.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(32)
}
