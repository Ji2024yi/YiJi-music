package com.music.bitchord.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.music.bitchord.R
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.data.model.durationMillis
import com.music.bitchord.data.netease.NeteaseApi
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.ui.components.SearchField
import com.music.bitchord.ui.icons.BitChordIcons
import java.util.Locale

/**
 * 补丁 93：顶栏那颗放大镜与页面内的搜索框之间的开关。
 *
 * 这两个东西不在同一个组合作用域里（顶栏在 App 的壳里，搜索框在页面里），
 * 所以用一个极小的状态对象当总线，而不是把状态一路提到最外层再传两层参数。
 */
object NeteasePlaylistSearch {
    private val _open = MutableStateFlow(false)
    val open: StateFlow<Boolean> = _open

    fun request() { _open.value = true }
    fun close() { _open.value = false }
}

/** 歌单内容在别处被改动后，让详情页重新拉一次。 */
object NeteasePlaylistRefresh {
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    fun bump() { _version.value = _version.value + 1 }
}

/**
 * 一个网易云歌单 / 榜单的详情页。
 *
 * 补丁 86：版式改成跟 App 自己的歌单页（`DetailScreen` 的 `ReleaseHeader`）一模一样
 * ——封面居中放大、标题居中、一行「播放 + 随机播放」的胶囊按钮、列表是「封面 +
 * 歌名/歌手 + 时长」。上一版是左边 132dp 封面 + 通栏「播放全部」按钮，跟本机歌单
 * 页完全是两套东西。
 *
 * 数据一次拉全（元信息 + 歌曲），没有分页：网易云的歌单接口本来一次就能给到。
 */
@Composable
fun NeteasePlaylistPage(
    playlistId: Long,
    /** 外层顶栏让出来的高度，跟其它页面一样由宿主传进来。 */
    contentPadding: PaddingValues,
    onPlay: (songs: List<Song>, startIndex: Int) -> Unit,
    /** 补丁 86：随机播放。跟本机歌单页一样先开 shuffle 再建队列。 */
    onShuffle: ((songs: List<Song>) -> Unit)? = null,
    /** 点行尾的 ⋮，打开和本机歌单页同一个歌曲菜单（里面含「从歌单移除」）。 */
    onOpenSongMenu: ((Song) -> Unit)? = null,
    /** 删掉整张歌单（每日推荐等虚拟歌单不会传这个回调）。 */
    onDeletePlaylist: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val refreshVersion by NeteasePlaylistRefresh.version.collectAsState()
    var detail by remember(playlistId) { mutableStateOf<NeteaseApi.PlaylistDetail?>(null) }
    var failed by remember(playlistId) { mutableStateOf(false) }
    // 补丁 84：歌单内搜索。同步下来的歌单动辄几百首，之前只能一路滑。
    var query by remember(playlistId) { mutableStateOf("") }
    // 补丁 93：顶栏放大镜点一下就展开并聚焦；离开这一页时自动收起。
    val searchOpen by NeteasePlaylistSearch.open.collectAsState()
    val searchFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(searchOpen) {
        if (searchOpen) {
            searchFocus.requestFocus()
            keyboard?.show()
        }
    }
    DisposableEffect(playlistId) {
        onDispose { NeteasePlaylistSearch.close() }
    }
    val dailyTitle = stringResource(R.string.netease_daily_recommend)

    LaunchedEffect(playlistId, refreshVersion) {
        detail = null
        failed = false
        val loaded = runCatching {
            // 补丁 61：0 表示「每日推荐」。它不是一个真的歌单，没有 id，也没有
            // 播放量和简介——但详情页要的那几样（封面、标题、歌、播放全部）都齐。
            if (playlistId == NETEASE_DAILY_ID) {
                val tracks = NeteaseApi.dailyRecommend()
                if (tracks.isEmpty()) {
                    null
                } else {
                    NeteaseApi.PlaylistDetail(
                        id = NETEASE_DAILY_ID,
                        name = dailyTitle,
                        coverUrl = tracks.firstNotNullOfOrNull { it.artwork },
                        description = null,
                        playCount = 0,
                        trackCount = tracks.size,
                        tracks = tracks,
                    )
                }
            } else {
                NeteaseApi.playlistFull(playlistId)
            }
        }.getOrNull()
        if (loaded == null) failed = true else detail = loaded
    }

    val configId = remember {
        SourceRegistry.configs.value
            .firstOrNull { it.kind == SourceKind.NETEASE && it.enabled }
            ?.id
    }
    val songs = remember(detail, configId) {
        val id = configId ?: return@remember emptyList()
        detail?.tracks.orEmpty().map { track ->
            Song(
                videoId = SourceRegistry.trackKey(id, track.id.toString()),
                title = track.title,
                artist = track.artist,
                albumName = track.album,
                thumbnailUrl = track.artwork,
                durationText = track.durationSec?.let { seconds ->
                    String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
                },
            )
        }
    }

    // 过滤后的列表：搜出来的第 1 条仍然标着它在歌单里的位置，播放交给过滤后的
    // 这一串（点第几条就放第几条，符合搜索后的预期）。
    val shown = remember(songs, query) {
        val q = query.trim()
        if (q.isEmpty()) {
            songs.withIndex().map { it.index to it.value }
        } else {
            songs.withIndex()
                .filter { (_, song) ->
                    song.title.contains(q, ignoreCase = true) ||
                        song.artist.contains(q, ignoreCase = true) ||
                        song.albumName?.contains(q, ignoreCase = true) == true
                }
                .map { it.index to it.value }
        }
    }
    val shownSongs = remember(shown) { shown.map { it.second } }

    // 补丁 65：他截图（23217.jpg）里深色模式下这页的歌名和歌单标题整个看不见，
    // 只剩灰色的艺人 / 序号 / 时长。根因是这些 Text 没写 color，靠
    // LocalContentColor 兜底；而这一页被宿主挂在播放器那层玻璃之下，拿到的
    // contentColor 是深色的，黑底黑字。按全 App 的规矩显式给 onSurface，并在
    // 页根把 LocalContentColor 也定死，免得以后新增一行又踩同一个坑。
    CompositionLocalProvider(
        LocalContentColor provides MaterialTheme.colorScheme.onSurface,
    ) {
        Column(modifier.fillMaxSize()) {
            val loaded = detail
            when {
                failed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.netease_playlist_failed),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                loaded == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = contentPadding,
                ) {
                    item {
                        PlaylistHeader(
                            detail = loaded,
                            songs = songs,
                            enabled = songs.isNotEmpty(),
                            onPlayAll = { onPlay(if (query.isBlank()) songs else shownSongs, 0) },
                            onShuffle = onShuffle?.let { shuffle ->
                                { shuffle(if (query.isBlank()) songs else shownSongs) }
                            },
                        )
                    }
                    if (playlistId > 0 && (onDeletePlaylist != null || onOpenSongMenu != null)) {
                        item(key = "playlist-edit-hint") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = HEADER_GUTTER, vertical = 4.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (onOpenSongMenu != null) {
                                    Text(
                                        text = "点歌曲右侧 ⋮ 可移出歌单",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (onDeletePlaylist != null) {
                                    Spacer(Modifier.width(16.dp))
                                    Text(
                                        text = "删除歌单",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.clickable(onClick = onDeletePlaylist),
                                    )
                                }
                            }
                        }
                    }
                    // 补丁 84：歌单内搜索。放在按钮下面，进歌单就能看到；不用
                    // stickyHeader 是因为这一页的顶栏是浮在上面的玻璃条，吸顶的
                    // 搜索条会被它压住一半。歌曲少（12 首以内）时藏起来。
                    if (searchOpen || songs.size > SEARCH_FROM_TRACKS) {
                        item(key = "playlist-search") {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = HEADER_GUTTER, vertical = 6.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.weight(1f)) {
                                        SearchField(
                                            query = query,
                                            onQueryChange = { query = it },
                                            onSubmit = {},
                                            focusRequester = searchFocus,
                                            placeholder = stringResource(R.string.netease_search_in_playlist),
                                        )
                                    }
                                    if (searchOpen) {
                                        Text(
                                            text = "取消",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier
                                                .clickable {
                                                    query = ""
                                                    keyboard?.hide()
                                                    NeteasePlaylistSearch.close()
                                                }
                                                .padding(start = 10.dp, top = 10.dp, bottom = 10.dp),
                                        )
                                    }
                                }
                                if (query.isNotBlank()) {
                                    Spacer(Modifier.size(6.dp))
                                    Text(
                                        text = stringResource(
                                            R.string.netease_search_match_count,
                                            shown.size,
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                    if (shown.isEmpty()) {
                        item {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 40.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = stringResource(R.string.netease_search_no_match),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    itemsIndexed(shown, key = { _, entry -> entry.second.videoId }) { position, entry ->
                        SongRow(
                            song = entry.second,
                            onClick = { onPlay(shownSongs, position) },
                            onMenu = if (playlistId > 0 && onOpenSongMenu != null) {
                                { onOpenSongMenu.invoke(entry.second) }
                            } else {
                                null
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 补丁 86：照本机歌单页的头部排——封面居中（带阴影、圆角），下面依次是标题、
 * 「网易云」、一行「N 首曲目 · X 分钟 · 播放 N」，最后一排「播放 / 随机播放」。
 *
 * 描述文字不再显示：参考图那页没有，长简介一多会把按钮顶到屏外。
 */
@Composable
private fun PlaylistHeader(
    detail: NeteaseApi.PlaylistDetail,
    songs: List<Song>,
    enabled: Boolean,
    onPlayAll: () -> Unit,
    onShuffle: (() -> Unit)?,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val sleeve = (maxWidth * SLEEVE_FRACTION).coerceAtMost(SLEEVE_MAX)
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(sleeve)
                    .shadow(SLEEVE_SHADOW, SLEEVE_SHAPE)
                    .clip(SLEEVE_SHAPE)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                val cover = detail.coverUrl
                if (cover != null) {
                    AsyncImage(
                        model = cover.artworkAt(HEADER_ART_PX),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(
            text = detail.name,
            style = MaterialTheme.typography.headlineMedium,
            // 补丁 65：深色模式下不写 color 就变成黑底黑字（23217.jpg）。
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = HEADER_GUTTER),
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = stringResource(R.string.netease_badge),
            style = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.Normal,
                fontSize = 19.sp,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = HEADER_GUTTER),
        )
        val meta = headerMeta(detail, songs)
        if (meta.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = meta,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = HEADER_GUTTER),
            )
        }
        if (enabled) {
            Spacer(Modifier.height(18.dp))
            Row(
                modifier = Modifier
                    .widthIn(max = ACTIONS_MAX_WIDTH)
                    .fillMaxWidth()
                    .padding(horizontal = HEADER_GUTTER),
                horizontalArrangement = Arrangement.spacedBy(ACTION_GAP),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ActionPill(
                    icon = BitChordIcons.Play,
                    label = stringResource(R.string.play),
                    filled = true,
                    onClick = onPlayAll,
                    modifier = Modifier.weight(1f),
                )
                if (onShuffle != null) {
                    ActionPill(
                        icon = BitChordIcons.Shuffle,
                        label = stringResource(R.string.shuffle),
                        filled = false,
                        onClick = onShuffle,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** 补丁 86：「歌单长度 · 时长 · 播放量」——跟本机歌单页那行副标题同一个口气。 */
@Composable
private fun headerMeta(detail: NeteaseApi.PlaylistDetail, songs: List<Song>): String {
    val count = songs.size.takeIf { it > 0 } ?: detail.trackCount
    val parts = mutableListOf<String>()
    if (count > 0) {
        parts += pluralStringResource(R.plurals.track_count_plural, count, count)
    }
    playtime(songs)?.let { parts += it }
    if (detail.playCount > 0) {
        parts += stringResource(R.string.netease_play_count, plays(detail.playCount))
    }
    return parts.joinToString(" · ")
}

/** 总时长：不到一小时给「38 分钟」，超过给「1 小时 25 分钟」。 */
@Composable
private fun playtime(songs: List<Song>): String? {
    // 搜索框每敲一个字都会重组这一页；几百首歌的时长解析只在列表变化时算。
    val minutes = remember(songs) { songs.sumOf { it.durationMillis() } / 60_000 }
    return when {
        minutes <= 0 -> null
        minutes < 60 -> stringResource(R.string.minutes_short, minutes.toInt())
        else -> stringResource(
            R.string.hours_minutes_short,
            (minutes / 60).toInt(),
            (minutes % 60).toInt(),
        )
    }
}

/**
 * 补丁 86：跟本机歌单页同一种胶囊按钮——填充款是「播放」，描边款是「随机播放」。
 * 不用 Material 的 `Button`：它的最小宽度和内部留白会把两个按钮挤成一大一小。
 */
@Composable
private fun ActionPill(
    icon: ImageVector,
    label: String,
    filled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val background = if (filled) MaterialTheme.colorScheme.onSurface else Color.Transparent
    val foreground = if (filled) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = modifier
            .height(ACTION_HEIGHT)
            .clip(RoundedCornerShape(percent = 50))
            .background(background)
            .then(
                if (filled) {
                    Modifier
                } else {
                    Modifier.border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(percent = 50))
                },
            )
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = foreground,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            color = foreground,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
private fun SongRow(song: Song, onClick: () -> Unit, onMenu: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = HEADER_GUTTER, end = 6.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            val art = song.thumbnailUrl
            if (art != null) {
                AsyncImage(
                    model = art.artworkAt(ROW_COVER_PX),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.bodyLarge,
                // 补丁 65：就是他截图里整排消失的歌名。原来靠 LocalContentColor
                // 兜底，深色模式下拿到深色，只剩下面那行灰色艺人还看得见。
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(1.dp))
            Text(
                text = song.artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        song.durationText?.let {
            Spacer(Modifier.width(12.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (onMenu != null) {
            // 跟本机歌单页一样用行尾 ⋮，点开同一个歌曲菜单；删歌在菜单里，
            // 不用长按这种用户猜不到的手势。
            IconButton(onClick = onMenu, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = Icons.Rounded.MoreVert,
                    contentDescription = "歌曲菜单",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/** 补丁 84：歌单超过这个首数才显示搜索栏。 */
private const val SEARCH_FROM_TRACKS = 12

/** 补丁 86：封面按显示尺寸取图（大封面 / 列表行）。 */
private const val HEADER_ART_PX = 700
private const val ROW_COVER_PX = 160

/** 补丁 86：头部尺寸，照本机歌单页那一套。 */
private val HEADER_GUTTER = 20.dp
private val SLEEVE_FRACTION = 0.72f
private val SLEEVE_MAX = 340.dp
private val SLEEVE_SHAPE = RoundedCornerShape(16.dp)
private val SLEEVE_SHADOW = 18.dp
private val ACTION_HEIGHT = 50.dp
private val ACTION_GAP = 14.dp
private val ACTIONS_MAX_WIDTH = 420.dp

/** 每日推荐在路由里用这个 id（它没有真的歌单 id）。 */
const val NETEASE_DAILY_ID = 0L

/** 「65.2亿」「1.2万」——播放量按中文习惯缩写，不显示一串原始数字。 */
private fun plays(count: Long): String = when {
    count >= 100_000_000 -> String.format(Locale.ROOT, "%.1f亿", count / 100_000_000.0)
    count >= 10_000 -> String.format(Locale.ROOT, "%.1f万", count / 10_000.0)
    else -> count.toString()
}
