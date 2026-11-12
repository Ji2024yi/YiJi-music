package com.music.bitchord.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.sharedui.resources.Res
import com.music.bitchord.sharedui.resources.spotify_logo
import com.music.bitchord.data.OfflinePlaylists
import com.music.bitchord.data.netease.NETEASE_PLAYLIST_PREFIX
import com.music.bitchord.data.netease.NeteaseAuth
import com.music.bitchord.data.netease.NeteaseLikes
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.download.Downloads
import com.music.bitchord.download.SavedCollection
import com.music.bitchord.ui.components.PAGE_GUTTER
import com.music.bitchord.ui.components.ReplayCardRowSkeleton
import com.music.bitchord.ui.replay.ReplayCardRow
import com.music.bitchord.ui.replay.ReplayHeroCard
import com.music.bitchord.ui.replay.ReplayStoryPage

// The phone's halves of the shared Library page: its list of folders, what
// sits on its "On device" shelf, and the way in to Replay at its head.

/**
 * The phone's folders, drawn as a list under the Replay cards: Downloads and
 * Local Music, the song cache when Settings has it shown, and the two remote
 * libraries it can reach.
 */
@Composable
fun libraryLinks(): List<LibraryLink> {
    val webdavConfigured by AppSettings.webdavUrl.collectAsStateWithLifecycle()
    val smbHost by AppSettings.smbHost.collectAsStateWithLifecycle()
    val smbShare by AppSettings.smbShare.collectAsStateWithLifecycle()
    val showCacheFolder by AppSettings.showCacheFolder.collectAsStateWithLifecycle()
    // 登录过网易云后，资料库顶部多一个直达「我喜欢的音乐」的入口。
    // 用的是网易云自己那张系统歌单的 id，点开复用已有歌单详情页。
    val neteaseLikedId by NeteaseLikes.likedPlaylistId.collectAsStateWithLifecycle()
    // 同步失败时这里原来什么都不显示，用户只能看到"没有歌单"，猜不出
    // 是没登录、登录失效还是请求失败。这几个状态合起来能直接说清是哪一种。
    val neteaseSignedIn by NeteaseAuth.signedIn.collectAsStateWithLifecycle()
    val neteaseExpired by NeteaseAuth.sessionExpired.collectAsStateWithLifecycle()
    val neteaseLikesLoaded by NeteaseLikes.loaded.collectAsStateWithLifecycle()
    fun link(title: String, subtitle: String, browseId: String, icon: ImageVector = deviceFolderIcon(browseId) ?: Icons.Rounded.Cloud) = LibraryLink(
        item = ShelfItem(
            title = title,
            subtitle = subtitle,
            thumbnailUrl = null,
            videoId = null,
            browseId = browseId,
        ),
        icon = icon,
    )
    return listOfNotNull(
        // 点喜欢却找不到在哪看。心形那套本来就记在这台设备上
        // （`OfflinePlaylists` 的内置那张 liked 列表），只是资料库一直没有这一行。
        link(
            stringResource(R.string.my_liked),
            stringResource(R.string.my_liked_subtitle),
            OfflinePlaylists.PREFIX + OfflinePlaylists.LIKED_ID,
            icon = Icons.Rounded.Favorite,
        ),
        // 这一行「网易云 · 我喜欢的音乐」删掉——「我的喜欢」里已经
        // 并进了网易云的歌，再单开一个入口就是重复。
        // 拿不到「我喜欢的音乐」时留一行状态，点一下重同步。
        run {
            if (neteaseLikedId != null || neteaseLikesLoaded) return@run null
            link(
                title = when {
                    !neteaseSignedIn -> "网易云 · 未登录"
                    neteaseExpired -> "网易云 · 登录已失效"
                    else -> "网易云 · 未同步"
                },
                subtitle = when {
                    !neteaseSignedIn ->
                        "去 设置 → 第三方账号 → 网易云 登录，登录后自动同步"
                    neteaseExpired -> "重新登录一次即可恢复同步"
                    else -> "点一下重新同步我的喜欢与歌单"
                },
                browseId = "netease:resync",
            )
        },
        link(
            stringResource(R.string.downloads),
            stringResource(R.string.downloaded_songs),
            "local:downloads",
        ),
        link(
            stringResource(R.string.local_music),
            stringResource(R.string.audio_files_on_device),
            "local:all",
        ),
        // 本地 + WebDAV/SMB 合并后的单一列表。原来要分别进「本地音乐」
        // 和「WebDAV」两栏，同一批文件各看一次、各显示一行。
        link(
            stringResource(R.string.library_all_songs),
            "",
            com.music.bitchord.data.localfeed.LocalFeed.ALL_SONGS_BROWSE_ID,
            icon = Icons.Rounded.LibraryMusic,
        ),
        // Opt-in from Settings → Storage: what the song cache is holding from
        // YouTube and JioSaavn. See [com.music.bitchord.playback.AudioCache.cachedSongs].
        link(
            stringResource(R.string.cached_songs),
            stringResource(R.string.cached_songs_subtitle),
            CACHE_FOLDER_BROWSE_ID,
        ).takeIf { showCacheFolder },
        // Only once set up in Sources; unconfigured, they'd just be dead ends.
        link(
            stringResource(R.string.webdav),
            stringResource(R.string.webdav_subtitle),
            com.music.bitchord.data.webdav.WebDavConfig.BROWSE_ID,
        ).takeIf { webdavConfigured.isNotBlank() },
        link(
            stringResource(R.string.smb),
            stringResource(R.string.smb_subtitle),
            com.music.bitchord.data.smb.SmbConfig.BROWSE_ID,
        ).takeIf { smbHost.isNotBlank() && smbShare.isNotBlank() },
        // 原来这里还有一行 Spotify 资料库；它的登录页和导入都已
        // 删掉，留着只会是一个打不开的入口。
    )
}

/**
 * The phone's "On device" shelf: the playlists and albums downloaded whole —
 * the same promise the folders above it make, here, now, without a network.
 * Nothing is truncated: the shelf is a row that scrolls, so "all of them"
 * costs nothing.
 */
@Composable
fun libraryDeviceItems(downloadedReleases: List<SavedCollection>): List<ShelfItem> {
    val downloadedPlaylist = stringResource(R.string.downloaded_playlist)
    return downloadedReleases.map { release ->
        ShelfItem(
            title = release.title,
            // The credit the release was downloaded with, because this is also
            // what the page it opens bills itself by — see `headerLines`, which
            // reads the kind and the owner back out of it. Saying "Downloaded
            // playlist" here instead would make that header read "Downloaded
            // playlist" over "PLAYLIST • 12 SONGS", and the shelf this card is on
            // already says where it lives.
            subtitle = release.subtitle.ifBlank { if (release.playlist) downloadedPlaylist else "" },
            thumbnailUrl = release.thumbnailUrl,
            videoId = null,
            browseId = Downloads.pageIdFor(release.id),
        )
    }
}

/**
 * The glyph a device folder is drawn with — its row on the Library page, and
 * the placeholder cover its own page opens on. Null for anything that isn't
 * one of the folders.
 */
fun deviceFolderIcon(browseId: String): ImageVector? = when (browseId) {
    // 「我的喜欢」那一行，和它自己页头的占位封面。
    OfflinePlaylists.PREFIX + OfflinePlaylists.LIKED_ID -> Icons.Rounded.Favorite
    "local:downloads" -> Icons.Rounded.Download
    "local:all" -> Icons.Rounded.Folder
    com.music.bitchord.data.localfeed.LocalFeed.ALL_SONGS_BROWSE_ID -> Icons.Rounded.LibraryMusic
    CACHE_FOLDER_BROWSE_ID -> Icons.Rounded.Storage
    com.music.bitchord.data.webdav.WebDavConfig.BROWSE_ID -> Icons.Rounded.Cloud
    com.music.bitchord.data.smb.SmbConfig.BROWSE_ID -> Icons.Rounded.Lan
    else -> null
}

/** The Library row that opens the connected Spotify account; handled by the app, not a browse page. */
const val SPOTIFY_BROWSE_ID = "app:spotify"

/** The Cached songs folder's page id — one of the `local:` device folders. */
const val CACHE_FOLDER_BROWSE_ID = "local:cache"

/**
 * The phone's way in to Replay: its row of headline cards, placeholders for
 * them while the history is read, and nothing at all while there is not yet
 * enough listening to deal them — Settings still opens Replay either way.
 */
@Composable
fun LibraryReplayEntry(
    cards: List<ReplayHeroCard>,
    loading: Boolean,
    holder: String,
    memberSince: String?,
    onOpenReplay: (ReplayStoryPage) -> Unit,
) {
    if (loading) {
        ReplayCardRowSkeleton()
    } else if (cards.isNotEmpty()) {
        ReplayCardRow(
            cards = cards,
            holder = holder,
            memberSince = memberSince,
            onCardClick = onOpenReplay,
            modifier = Modifier.padding(vertical = 6.dp),
            contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
        )
    }
}
