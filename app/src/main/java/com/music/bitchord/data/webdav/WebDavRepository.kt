package com.music.bitchord.data.webdav

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLDecoder

/**
 * WebDAV library as [Song] rows, shaped like the on-device library so the
 * same Songs / Artists / Albums view can draw it.
 *
 * Stateless apart from [AppSettings]: every load re-lists the server. The
 * album of a track is its parent folder, which groups a "Music/Artist/Album"
 * remote layout back into releases without any tags.
 */
object WebDavRepository {

    fun isConfigured(): Boolean =
        WebDavConfig.isConfigured(AppSettings.webdavUrl.value)

    suspend fun getSongs(): List<Song> = withContext(Dispatchers.IO) {
        val url = AppSettings.webdavUrl.value
        if (!WebDavConfig.isConfigured(url)) return@withContext emptyList()
        val listing = WebDavClient.listLibrary(
            baseUrl = url,
            username = AppSettings.webdavUsername.value,
            password = AppSettings.webdavPassword.value,
        ).getOrNull() ?: return@withContext emptyList()
        val artByDir = listing.images.groupBy { dirKey(it.url) }
        // 顺手把同目录的歌词文件配好，读歌词时不再第二次问服务器。
        WebDavLyrics.index(listing.audio, listing.lyrics)
        listing.audio.map { entry ->
            entry.toSong(artworkFor(entry.url, artByDir[dirKey(entry.url)].orEmpty()))
        }.let { rows ->
            // 旁边没放图片的那些行，用从音频文件里抠出来、之前存过
            // 的那一份（`EmbeddedCovers`）。纯本地读，不问服务器；一份都没有就照旧
            // 空着，由页头那边就地抠第一张。曲库列表是首页、探索、搜索共同的来源，
            // 补在这里比补在某一页彻底。
            com.music.bitchord.data.artwork.EmbeddedCovers.fill(rows)
        }
    }

    suspend fun testConnection(
        url: String,
        username: String,
        password: String,
    ): Result<Unit> = WebDavClient.testConnection(url, username, password)

    /**
     * 按曲目 id 删除服务器上的文件。
     *
     * 只接受 `webdav:http…` 的 id；其余一概拒绝，免得一个拼错的 id 把本地或
     * YouTube 的东西当成远端路径去删。
     */
    suspend fun delete(videoId: String): Result<Unit> {
        val fileUrl = WebDavConfig.fileUrlOf(videoId)
            ?: return Result.failure(IllegalArgumentException("Not a WebDAV track"))
        return WebDavClient.delete(
            fileUrl = fileUrl,
            username = AppSettings.webdavUsername.value,
            password = AppSettings.webdavPassword.value,
        )
    }

    fun WebDavClient.Entry.toSong(artworkUrl: String? = null): Song {
        // A WebDAV directory is a folder, not an album. Stamping the parent
        // segment into albumName turned the Replay "Top albums" chart into a
        // list of directories — "dav" for the share root, "Jiyi" for a dump
        // folder — next to a real release name. Folder grouping is derived
        // from the path independently (LocalFeed.folderOf), so leaving the
        // album unknown costs nothing and stops the chart from lying.
        return WebDavConfig.songFor(url).copy(
            // Prefer the server's display name over the URL-decoded guess when
            // it carries one.
            title = displayName.substringBeforeLast('.').takeIf { it.isNotBlank() }
                ?.let { splitTitle(it).second } ?: WebDavConfig.songFor(url).title,
            artist = splitTitle(displayName.substringBeforeLast('.')).first
                ?: WebDavConfig.songFor(url).artist,
            thumbnailUrl = artworkUrl,
        )
    }

    /**
     * The cover for a track: a picture filed beside it. Embedded pictures
     * inside the audio itself are not read — that would be a ranged fetch per
     * track at list time. See [RemoteArtwork][com.music.bitchord.data.remote.RemoteArtwork].
     */
    fun artworkFor(fileUrl: String, siblings: List<WebDavClient.Entry>): String? =
        com.music.bitchord.data.remote.RemoteArtwork.pick(siblings.map { it.url })

    private fun dirKey(fileUrl: String): String = runCatching {
        val uri = java.net.URI(fileUrl)
        "${uri.host.orEmpty()}${uri.path.orEmpty().trimEnd('/').substringBeforeLast('/', "")}"
    }.getOrDefault(fileUrl.substringBeforeLast('/'))

    private fun splitTitle(base: String): Pair<String?, String?> {
        return if (" - " in base) {
            val parts = base.split(" - ", limit = 2)
            parts[0].trim().takeIf { it.isNotBlank() } to parts[1].trim().takeIf { it.isNotBlank() }
        } else {
            null to base.trim().takeIf { it.isNotBlank() }
        }
    }

    /**
     * The parent directory of a remote file.
     *
     * Kept for path-based grouping and tests; it is deliberately **not** used as
     * `albumName` any more — see [toSong].
     */
    internal fun parentFolderName(fileUrl: String): String? = runCatching {
        val path = java.net.URI(fileUrl).path.orEmpty().trimEnd('/')
        val parent = path.substringBeforeLast('/', "").substringAfterLast('/').trim()
        URLDecoder.decode(parent, "UTF-8").takeIf { it.isNotBlank() }
    }.getOrNull()
}
