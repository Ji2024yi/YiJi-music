package com.music.bitchord.data.lyrics

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.durationMillis
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.webdav.WebDavConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 这首歌到底多长。
 *
 * 匹配歌词时最关键的一条证据就是时长（[LyricScoring] 主要靠它把同名不同版分开），
 * 而 WebDAV 的行是从文件名拼出来的，`durationText` 一直是 null —— 于是每次匹配都
 * 是"时长未知，没法校验"，回来的第一版就被采用。他说的"有的没匹配对"就是这么来的。
 *
 * 三个来源按便宜程度排：
 *
 *  1. 行上已经带的时长（本地下来的文件、播放过一次 backfill 过的）；
 *  2. 本机那份文件的标签；
 *  3. 服务器上那份 —— 只读文件头就够，FLAC 的 STREAMINFO、MP4 的 mvhd、MP3 的
 *     Xing 头都在最前面，MediaMetadataRetriever 会自己停在它需要的那一截。
 *
 * 拿不到就返回 0，调用方照旧匹配，但会在候选上写明"时长未知"，不假装校验过。
 */
object SongDuration {

    private const val TAG = "BitChord"

    suspend fun of(context: Context, song: Song): Long = withContext(Dispatchers.IO) {
        song.durationMillis().takeIf { it > 0L }?.let { return@withContext it }
        copyOf(song)?.let { path -> probe(path, null)?.takeIf { it > 0L }?.let { return@withContext it } }
        val remote = WebDavConfig.fileUrlOf(song.videoId)
            ?: song.localUri?.takeIf { it.startsWith("http") }
            ?: return@withContext 0L
        probe(remote, authHeadersFor(remote)) ?: 0L
    }

    /** 本机那份：下载记录里的优先，其次行自带的 localPath。 */
    private fun copyOf(song: Song): String? {
        song.localPath?.let { if (java.io.File(it).isFile) return it }
        song.localUri?.takeIf { it.startsWith("file://") }?.let { return Uri.parse(it).path }
        val saved = com.music.bitchord.download.Downloads.verifiedSavedUri(song.videoId) ?: return null
        return if (saved.startsWith("file://")) Uri.parse(saved).path else saved
    }

    /**
     * 只给那台 WebDAV 带口令：地址是曲库列表给的，跳转之后就换了一家，
     * 凭据不能跟着走。
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

    private fun probe(source: String, headers: Map<String, String>?): Long? = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            if (source.startsWith("http") && headers != null) {
                retriever.setDataSource(source, headers)
            } else {
                retriever.setDataSource(source)
            }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } finally {
            runCatching { retriever.release() }
        }
    }.onFailure { Log.d(TAG, "duration probe failed for $source: ${it.message}") }.getOrNull()
}
