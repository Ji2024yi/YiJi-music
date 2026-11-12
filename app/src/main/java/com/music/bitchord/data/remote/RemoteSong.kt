package com.music.bitchord.data.remote

import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.Song

/**
 * What every remote file library names a track: `Artist - Title`, or just
 * the title when there is no artist worth naming — plus the Song row itself,
 * which only differs per library in identity, address and credit.
 */
object RemoteSong {

    fun splitArtistTitle(base: String): Pair<String?, String?> =
        if (" - " in base) {
            val parts = base.split(" - ", limit = 2)
            parts[0].trim().takeIf { it.isNotBlank() } to parts[1].trim().takeIf { it.isNotBlank() }
        } else {
            null to base
        }

    /**
     * 远程文件库的封面地址。
     *
     * 上游在这里把 thumbnailUrl 写死成 null，于是 WebDAV/SMB 的歌只能靠
     * MediaMetadataRetriever 现取内嵌封面，取不到就是一片灰。服务器侧已经
     * 把每首歌的内嵌封面导出成同名 .jpg，这里就按"音频地址换 .jpg"给出地址，
     * 由 Coil + WebDavCoilAuth 带上 Basic 凭据去取。
     * 非 http（SMB 之类）一律返回 null，保持原样不给坏地址。
     */
    private fun coverUrlFor(streamUrl: String): String? {
        if (!streamUrl.startsWith("http")) return null
        val query = streamUrl.indexOf('?')
        val head = if (query >= 0) streamUrl.substring(0, query) else streamUrl
        val dot = head.lastIndexOf('.')
        if (dot < 0) return null
        return head.substring(0, dot) + ".jpg" + if (query >= 0) streamUrl.substring(query) else ""
    }

    fun build(
        videoId: String,
        streamUrl: String,
        fileName: String,
        albumName: String?,
        source: String,
        browseId: String,
    ): Song {
        val base = fileName.substringBeforeLast('.').takeIf { it.isNotBlank() } ?: fileName
        val (artist, title) = splitArtistTitle(base)
        return Song(
            videoId = videoId,
            title = title ?: base,
            artist = artist ?: "Unknown Artist",
            thumbnailUrl = coverUrlFor(streamUrl),
            durationText = null,
            albumName = albumName?.takeIf { it.isNotBlank() },
            localUri = streamUrl,
            playbackSource = source,
            playbackSourceType = PlaybackSourceType.BROWSE,
            playbackSourceId = browseId,
        )
    }
}
