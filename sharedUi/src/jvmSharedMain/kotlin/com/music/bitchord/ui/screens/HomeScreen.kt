package com.music.bitchord.ui.screens

import com.music.bitchord.ui.components.trackArtSize
import com.music.bitchord.ui.components.contextClick
import com.music.bitchord.ui.AppUi
import com.music.bitchord.ui.rememberIsForeground
import com.music.bitchord.sharedui.resources.*
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.DrawableResource
import androidx.compose.foundation.Image
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import com.music.bitchord.ui.components.longPressMenuClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListState
import com.music.bitchord.ui.components.ShelfRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cached
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Storage
import com.music.bitchord.ui.icons.BitChordIcons
import coil3.compose.AsyncImage
import com.music.bitchord.data.model.CARD_ART_PX
import com.music.bitchord.data.model.HEADER_ART_PX
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ROW_ART_PX
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.ShelfLayout
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.TopPick
import com.music.bitchord.data.model.TopPickMood
import com.music.bitchord.data.model.UiState
import java.util.Locale
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.ui.components.HERO_CARD_RATIO
import com.music.bitchord.ui.components.MessageState
import com.music.bitchord.ui.components.PAGE_GUTTER
import com.music.bitchord.ui.components.RowMoreButton
import com.music.bitchord.ui.components.PullToRefresh
import com.music.bitchord.ui.components.SHELF_CARD_WIDTH
import com.music.bitchord.ui.components.SearchPlayingBars
import com.music.bitchord.ui.components.SignInBanner
import com.music.bitchord.ui.components.feedMoreSkeleton
import com.music.bitchord.ui.components.feedSkeleton
import com.music.bitchord.ui.components.recentlyPlayedSkeleton
import com.music.bitchord.ui.components.heroCardWidth
import com.music.bitchord.ui.components.thumbnailBorder
import com.music.bitchord.ui.components.trackColumnWidth
import com.music.bitchord.ui.player.MeshGradientBackground
import com.music.bitchord.ui.player.MeshPalette

/** How far the Listen now heading and feed sit above the shared top inset. */
private val HOME_TITLE_LIFT = 28.dp

private const val RECENTS_TITLE = "Recents"
private const val RECENT_TRACKS_PER_COLUMN = 4

private fun List<ShelfItem>.playingItemMatcher(song: Song?): (ShelfItem) -> Boolean {
    song ?: return { false }
    // The card that was played, when the shelf holds it, and only that one —
    // see [com.music.bitchord.data.model.playingRowMatcher], which rows use.
    if (any { it.videoId == song.videoId }) return { it.videoId == song.videoId }
    val byName = firstOrNull { it.videoId != null && it.title == song.title && it.subtitle == song.artist }
        ?: return { false }
    return { it == byName }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: UiState<List<HomeShelf>>,
    listState: LazyListState,
    /** The tapped item and the recommendation shelf it came from. */
    onItemClick: (ShelfItem, String) -> Unit,
    /**
     * 补丁 63：整块货架自己的入口。每日推荐那张大卡点的是「这一批」而不是
     * 其中一首歌；分类歌单标题右边的箭头也走这里。传 null 就维持原来的
     * 「点卡播第一首」行为。
     */
    onOpenShelf: ((HomeShelf) -> Unit)? = null,
    onRetry: () -> Unit,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    pullState: PullToRefreshState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues,
    /** The page's heading, or null for none — the desktop's pages carry no heading. */
    title: String?,
    signedIn: Boolean = true,
    onSignIn: (() -> Unit)? = null,
    /**
     * Holding a card rather than tapping it. Every card on the feed answers,
     * whichever kind it is: the caller reads the item the same way it does for
     * a tap, so a track card opens the track menu and a card that points at a
     * collection opens the album / playlist one.
     */
    onItemLongPress: ((ShelfItem) -> Unit)? = null,
    // Explore doesn't page — only Home has a continuation worth following.
    onLoadMore: (() -> Unit)? = null,
    loadingMore: Boolean = false,
    /**
     * 私房版补丁 35：「最近常听」标题右边那个电台按钮。传入即画；它会拿这一排
     * 的第一首当种子，把整个曲库洗一遍续在后面。
     */
    onRadioClick: ((ShelfItem) -> Unit)? = null,
    recentlyPlayedLoading: Boolean = false,
    /**
     * Whether the feed's first shelf gets the big full-bleed cards. The phone
     * leads with them; on a window as wide as a desktop's they are posters, so
     * there every shelf is the ordinary row.
     */
    leadHero: Boolean = true,
    currentSong: Song? = null,
    isPlaying: Boolean = false,
    /**
     * The "Top Picks for You" row above Recents. Null while it has not been
     * built yet, empty when there is nothing to pick from. The row is only
     * drawn by a caller that can act on it, i.e. passes [onTopPickClick].
     */
    topPicks: List<TopPick>? = null,
    topPicksLoading: Boolean = false,
    /** The signed-in account's name, for the personal station's "Name's Station". */
    listenerName: String? = null,
    onTopPickClick: ((TopPick, String) -> Unit)? = null,
    onTopPickLongPress: ((TopPick) -> Unit)? = null,
    /** A Find Your Mood tile, and its name. The shelf is only drawn when this is set. */
    onMoodClick: ((TopPickMood, String) -> Unit)? = null,
    /** Find Your Mood's Show all: every mood and activity. */
    onMoodsShowAll: () -> Unit = {},
    /** Whether the Top Picks and mood artwork may animate — see [LocalArtworkMotion]. */
    artworkMotion: Boolean = true,
) {
    // Top Picks' pictures and motion wait for the feed — see LocalArtworkImages.
    val feedReady = state !is UiState.Loading
    CompositionLocalProvider(
        LocalArtworkMotion provides (artworkMotion && feedReady),
        LocalArtworkImages provides feedReady,
    ) {
    // The loops also hold still while the feed is being scrolled — see
    // LocalArtworkScrolling for why that is asked rather than provided.
    PausedWhileScrolling(listState) {

    PullToRefresh(
        refreshing = refreshing,
        onRefresh = onRefresh,
        state = pullState,
        modifier = modifier,
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            // The heading sits higher than the other tabs' first row: pull the
            // whole feed up by [HOME_TITLE_LIFT] with it.
            contentPadding = if (title != null) {
                PaddingValues(
                    top = (contentPadding.calculateTopPadding() - HOME_TITLE_LIFT).coerceAtLeast(0.dp),
                    bottom = contentPadding.calculateBottomPadding(),
                )
            } else {
                contentPadding
            },
        ) {
            if (title != null) {
                item { HomeTitle(title) }
            }
            if (!signedIn && onSignIn != null) {
                item {
                    // Read in this item, so a scroll starting or ending
                    // recomposes the banner alone.
                    SignInBanner(
                        onSignIn = onSignIn,
                        modifier = Modifier.padding(bottom = 8.dp),
                        motion = LocalArtworkMotion.current && !listState.isScrollInProgress,
                    )
                }
            }
            if (onTopPickClick != null) {
                when {
                    !topPicks.isNullOrEmpty() -> item(key = "top-picks") {
                        TopPicksShelf(
                            picks = topPicks,
                            listenerName = listenerName,
                            onClick = onTopPickClick,
                            onLongPress = onTopPickLongPress,
                        )
                    }
                    topPicks == null && topPicksLoading -> topPicksSkeleton()
                }
            }
            when (state) {
                is UiState.Loading -> {
                    if (recentlyPlayedLoading) {
                        recentlyPlayedSkeleton()
                        // Recents owns the leading layout while its request is
                        // pending, so the feed behind it starts with ordinary
                        // shelf placeholders rather than another hero card.
                        feedSkeleton(firstIsHero = false)
                    } else {
                        feedSkeleton(firstIsHero = leadHero)
                    }
                }
                is UiState.Error -> item {
                    MessageState(state.message, actionLabel = stringResource(Res.string.retry), onAction = onRetry)
                }
                is UiState.Success -> {
                    if (recentlyPlayedLoading) {
                        recentlyPlayedSkeleton()
                    }
                    // The loading skeleton already owns the hero slot. Until
                    // Recently Played lands, every real shelf must retain its
                    // compact-card layout instead of briefly becoming a hero.
                    itemsIndexedShelves(
                        shelves = state.data,
                        onMoodClick = onMoodClick,
                        onMoodsShowAll = onMoodsShowAll,
                        onItemClick = onItemClick,
                        onOpenShelf = onOpenShelf,
                        onItemLongPress = onItemLongPress,
                        currentSong = currentSong,
                        isPlaying = isPlaying,
                        firstIsHero = leadHero && !recentlyPlayedLoading,
                        onRadioClick = onRadioClick,
                    )
                    if (loadingMore) feedMoreSkeleton()
                }
            }
        }
    }

    }
    }

    if (onLoadMore != null && state is UiState.Success) {
        val loadMore by rememberUpdatedState(onLoadMore)
        // Re-checked on every layout change, rather than on the rising edge of
        // "the tail is in view". A page that appends only a shelf or two leaves
        // the list still near its end, so an edge-triggered effect would never
        // fire a second time: the feed dead-ended at the bottom with no
        // skeleton and no request in flight to explain it. Restarting on
        // [loadingMore] re-checks the moment a page settles, so the next one is
        // asked for while the tail is still on screen to show it loading.
        LaunchedEffect(listState, loadingMore) {
            snapshotFlow {
                val layout = listState.layoutInfo
                (layout.visibleItemsInfo.lastOrNull()?.index ?: -1) to layout.totalItemsCount
            }.collect { (lastVisible, total) ->
                if (!loadingMore && total > 0 && lastVisible >= total - 3) loadMore()
            }
        }
    }
}

/**
 * Recents mirrors the artist page's top-tracks pager. Any other lead shelf keeps
 * Apple's full-bleed treatment, while the remaining shelves use square cards.
 */
private fun androidx.compose.foundation.lazy.LazyListScope.itemsIndexedShelves(
    shelves: List<HomeShelf>,
    onItemClick: (ShelfItem, String) -> Unit,
    onOpenShelf: ((HomeShelf) -> Unit)?,
    onItemLongPress: ((ShelfItem) -> Unit)?,
    currentSong: Song?,
    isPlaying: Boolean,
    firstIsHero: Boolean = true,
    onMoodClick: ((TopPickMood, String) -> Unit)? = null,
    onMoodsShowAll: () -> Unit = {},
    onRadioClick: ((ShelfItem) -> Unit)? = null,
) {
    // key 里编进 index 会让每次货架头插都把后面所有 item 判成新项，整列重组、
    // 内部 LazyRow 的滚动位置也丢。改成内容派生 key；同名同版式的极少数情况
    // 才补序号，保证 LazyColumn 的 key 唯一。
    // 这是 LazyListScope 的扩展、不是 Composable，不能用 remember；
    // 货架只有几排，每次重算这点开销可以忽略。
    val baseKeys = shelves.map { "${it.title}|${it.layout.name}" }
    val counts = baseKeys.groupingBy { it }.eachCount()
    val seen = HashMap<String, Int>()
    val shelfKeys = baseKeys.map { base ->
        val n = (seen[base] ?: 0) + 1
        seen[base] = n
        if ((counts[base] ?: 0) > 1) "$base#$n" else base
    }
    shelves.forEachIndexed { index, shelf ->
        // Find Your Mood follows the lead shelf, as on Apple Music's Home.
        if (index == 1 && onMoodClick != null) {
            item(key = "find-your-mood") { FindYourMoodShelf(onMoodClick, onMoodsShowAll) }
        }
        item(key = shelfKeys[index]) {
            val openItem: (ShelfItem) -> Unit = { item -> onItemClick(item, shelf.title) }
            // Listen again and Quick picks are lists on the web player, as
            // Recents is here: one pager for all three.
            if (shelf.layout == ShelfLayout.DAILY_PICK) {
                DailyPickCard(
                    shelf = shelf,
                    onItemClick = openItem,
                    onOpenShelf = onOpenShelf,
                )
            } else if (shelf.layout == ShelfLayout.CATEGORY_TILES) {
                CategoryPlaylistShelf(
                    shelf = shelf,
                    onItemClick = openItem,
                    onOpenShelf = onOpenShelf,
                )
            } else if (shelf.layout == ShelfLayout.FOR_YOU) {
                ForYouCard(
                    shelf = shelf,
                    onItemClick = openItem,
                    onItemLongPress = onItemLongPress,
                )
            } else if (shelf.layout == ShelfLayout.RECENT_CARDS) {
                RecentCardShelf(
                    shelf = shelf,
                    onItemClick = openItem,
                    onItemLongPress = onItemLongPress,
                    onRadioClick = onRadioClick,
                )
            } else if (shelf.layout == ShelfLayout.LIST ||
                (index == 0 && shelf.title.equals(RECENTS_TITLE, ignoreCase = true))
            ) {
                RecentShelf(
                    shelf = shelf,
                    onItemClick = openItem,
                    onItemLongPress = onItemLongPress,
                    currentSong = currentSong,
                    isPlaying = isPlaying,
                )
            } else if (index == 0 && firstIsHero && shelf.layout == ShelfLayout.CARDS) {
                HeroShelf(
                    shelf = shelf,
                    onItemClick = openItem,
                    onItemLongPress = onItemLongPress,
                    currentSong = currentSong,
                    isPlaying = isPlaying,
                )
            } else {
                Shelf(
                    shelf = shelf,
                    onItemClick = openItem,
                    onItemLongPress = onItemLongPress,
                    currentSong = currentSong,
                    isPlaying = isPlaying,
                )
            }
        }
    }
}

/** The same four-rows-per-page treatment used by an artist's Top songs. */
@Composable
private fun RecentShelf(
    shelf: HomeShelf,
    onItemClick: (ShelfItem) -> Unit,
    onItemLongPress: ((ShelfItem) -> Unit)?,
    currentSong: Song?,
    isPlaying: Boolean,
) {
    Column(Modifier.padding(bottom = 26.dp)) {
        RecentSectionHeader(title = localizeShelfTitle(shelf.title), subtitle = localizeShelfSubtitle(shelf.subtitle))
        val isCurrentItem = shelf.items.playingItemMatcher(currentSong)
        BoxWithConstraints {
            val columnWidth = trackColumnWidth(maxWidth)
            val columns = remember(shelf.items) {
                shelf.items.chunked(RECENT_TRACKS_PER_COLUMN)
            }
            ShelfRow(
                contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                itemsIndexed(
                    columns,
                    key = { index, column ->
                        "$index:${column.joinToString("|") { it.videoId ?: it.title }}"
                    },
                ) { _, column ->
                    Column(Modifier.width(columnWidth)) {
                        column.forEach { item ->
                            RecentTrackRow(
                                item = item,
                                onClick = { onItemClick(item) },
                                onLongPress = onItemLongPress?.let { { it(item) } },
                                isCurrent = isCurrentItem(item),
                                isPlaying = isCurrentItem(item) && isPlaying,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentSectionHeader(title: String, subtitle: String) {
    Column(
        modifier = Modifier
            .padding(horizontal = PAGE_GUTTER, vertical = 10.dp)
            .fillMaxWidth(),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle.isNotBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun RecentTrackRow(
    item: ShelfItem,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)?,
    isCurrent: Boolean,
    isPlaying: Boolean,
) {
    CompactTrackRow(
        title = item.title,
        subtitle = item.subtitle,
        thumbnailUrl = item.thumbnailUrl,
        onClick = onClick,
        onLongPress = onLongPress,
        isCurrent = isCurrent,
        isPlaying = isPlaying,
        isVideo = item.isVideo,
    )
}

/**
 * One row of Recents' four-to-a-column pager. Public so the desktop artist page
 * can list its top songs the same way.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CompactTrackRow(
    title: String,
    subtitle: String,
    thumbnailUrl: String?,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)?,
    isCurrent: Boolean = false,
    isPlaying: Boolean = false,
    /** A music video: its thumbnail at 16:9 rather than squared off. */
    isVideo: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .longPressMenuClickable(onClick = onClick, onLongClick = onLongPress)
            .contextClick(onLongPress)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.trackArtSize(48.dp, isVideo)) {
            AsyncImage(
                model = thumbnailUrl.artworkAt(ROW_ART_PX),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .matchParentSize()
                    .clip(RoundedCornerShape(7.dp))
                    .thumbnailBorder(RoundedCornerShape(7.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            if (isCurrent && isPlaying) SearchPlayingBars(Modifier.align(Alignment.Center))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (onLongPress != null) {
            RowMoreButton(onClick = onLongPress)
        }
    }
}

/**
 * Listen now's large heading, with the wordmark stacked above it.
 *
 * Only the home tab carries it — the top bar no longer shows the logo, so this
 * is where the app's mark lives. Its height is taken from the heading's own
 * font size rather than a fixed dp, so the two keep their proportion under the
 * system font scale.
 */
@Composable
private fun HomeTitle(title: String, modifier: Modifier = Modifier) {
    val style = MaterialTheme.typography.displayLarge
    val logoHeight = with(LocalDensity.current) { (style.fontSize * LOGO_TO_FONT).toDp() }
    // 私房版补丁 36：宽高都写死，别让 aspectRatio 和 Icon 自带的尺寸打架——
    // 之前看起来比下面的卡片往右缩，就是宽度没有落在这个 Column 的左边缘。
    val logoWidth = logoHeight * LOGO_ASPECT
    // 私房版补丁 37：和下面每日推荐卡里的文字左缘对齐（卡外 16 + 卡内 22），
    // 不再贴着页面边距。
    Column(modifier = modifier.padding(start = PAGE_GUTTER + 22.dp, end = PAGE_GUTTER, top = 8.dp, bottom = 8.dp)) {
        Icon(
            painter = painterResource(Res.drawable.ic_logo),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier
                .padding(bottom = 14.dp)
                .height(logoHeight)
                .width(logoWidth),
        )
        // "Listen now" is hidden for now; the wordmark above stays.
        // Text(
        //     text = title,
        //     style = style,
        //     color = MaterialTheme.colorScheme.onBackground,
        // )
    }
}

/** The wordmark's height against the heading's font size. */
private const val LOGO_TO_FONT = 0.9f

/** ic_logo's viewport, 730 x 484. */
private const val LOGO_ASPECT = 730f / 484f

/**
 * Shared by the home feed, Explore and Library so headings line up across tabs.
 *
 * [onShowAll] is only ever set on Library, whose rows stop at five cards
 * rather than running the shelf's whole length — see [LibraryGridShelf].
 * Home and Explore never pass it, so their heading is unchanged.
 */
@Composable
internal fun SectionHeader(title: String, subtitle: String = "", onShowAll: (() -> Unit)? = null) {
    val displayTitle = localizeShelfTitle(title)
    val displaySubtitle = localizeShelfSubtitle(subtitle)
    Row(
        modifier = Modifier
            .padding(horizontal = PAGE_GUTTER, vertical = 10.dp)
            .fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = displayTitle,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (displaySubtitle.isNotBlank()) {
                Text(
                    text = displaySubtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (onShowAll != null) {
            Text(
                text = stringResource(Res.string.show_all),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable(onClick = onShowAll)
                    .padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
            )
        }
    }
}
@Composable
internal fun localizeShelfTitle(title: String): String {
    val trimmed = title.trim()
    return when {
        trimmed.equals("Recents", ignoreCase = true) ||
            trimmed.equals("Recently played", ignoreCase = true) ||
            trimmed.equals("Gần đây", ignoreCase = true) ||
            trimmed.equals("最近", ignoreCase = true) ->
            stringResource(Res.string.shelf_recents)
        trimmed.equals("Playlists", ignoreCase = true) ||
            trimmed.equals("Danh sách phát", ignoreCase = true) ||
            trimmed.equals("再生リスト", ignoreCase = true) ->
            stringResource(Res.string.playlists)
        trimmed.equals("Albums", ignoreCase = true) ||
            trimmed.equals("Album", ignoreCase = true) ||
            trimmed.equals("アルバム", ignoreCase = true) ->
            stringResource(Res.string.albums)
        trimmed.equals("Artists", ignoreCase = true) ||
            trimmed.equals("Nghệ sĩ", ignoreCase = true) ||
            trimmed.equals("アーティスト", ignoreCase = true) ->
            stringResource(Res.string.artists)
        trimmed.equals("Subscriptions", ignoreCase = true) ||
            trimmed.equals("Đã đăng ký", ignoreCase = true) ||
            trimmed.equals("登録チャンネル", ignoreCase = true) ->
            stringResource(Res.string.subscriptions)
        trimmed.equals("Trending community playlists", ignoreCase = true) ||
            trimmed.equals("Danh sách phát cộng đồng thịnh hành", ignoreCase = true) ||
            trimmed.equals("Danh sách phát thịnh hành trong cộng đồng người dùng", ignoreCase = true) ||
            trimmed.equals("急上昇のコミュニティ再生リスト", ignoreCase = true) ->
            stringResource(Res.string.shelf_trending_community_playlists)
        trimmed.equals("Featured playlists for you", ignoreCase = true) ||
            trimmed.equals("Danh sách phát đề xuất cho bạn", ignoreCase = true) ||
            trimmed.equals("Danh sách phát nổi bật dành cho bạn", ignoreCase = true) ||
            trimmed.equals("おすすめの再生リスト", ignoreCase = true) ->
            stringResource(Res.string.shelf_featured_playlists_for_you)
        trimmed.equals("Quick picks", ignoreCase = true) ||
            trimmed.equals("Lựa chọn nhanh", ignoreCase = true) ||
            trimmed.equals("Chọn nhanh đài phát", ignoreCase = true) ||
            trimmed.equals("クイック ミックス", ignoreCase = true) ->
            stringResource(Res.string.shelf_quick_picks)
        trimmed.equals("Listen again", ignoreCase = true) ||
            trimmed.equals("Nghe lại", ignoreCase = true) ||
            trimmed.equals("もう一度聴く", ignoreCase = true) ->
            stringResource(Res.string.shelf_listen_again)
        trimmed.equals("Mixed for you", ignoreCase = true) ||
            trimmed.equals("Dành riêng cho bạn", ignoreCase = true) ||
            trimmed.equals("ミックス", ignoreCase = true) ->
            stringResource(Res.string.shelf_mixed_for_you)
        trimmed.startsWith("Similar to", ignoreCase = true) -> {
            val rest = trimmed.substring(10).trim()
            stringResource(Res.string.shelf_similar_to, rest)
        }
        trimmed.startsWith("Tương tự như", ignoreCase = true) -> {
            val rest = trimmed.substring(12).trim()
            stringResource(Res.string.shelf_similar_to, rest)
        }
        trimmed.equals("Forgotten favorites", ignoreCase = true) ||
            trimmed.equals("Giai điệu quen thuộc", ignoreCase = true) ||
            trimmed.equals("よく聴いたお気に入りの曲", ignoreCase = true) ->
            stringResource(Res.string.shelf_forgotten_favorites)
        trimmed.equals("Recommended music videos", ignoreCase = true) ||
            trimmed.equals("Video âm nhạc đề xuất", ignoreCase = true) ||
            trimmed.equals("おすすめのミュージック ビデオ", ignoreCase = true) ->
            stringResource(Res.string.shelf_recommended_music_videos)
        trimmed.equals("From your library", ignoreCase = true) ||
            trimmed.equals("Từ thư viện của bạn", ignoreCase = true) ||
            trimmed.equals("ライブラリから", ignoreCase = true) ->
            stringResource(Res.string.shelf_from_your_library)
        trimmed.equals("Charts", ignoreCase = true) ||
            trimmed.equals("Bảng xếp hạng", ignoreCase = true) ||
            trimmed.equals("チャート", ignoreCase = true) ->
            stringResource(Res.string.shelf_charts)
        trimmed.equals("New releases", ignoreCase = true) ||
            trimmed.equals("Bản phát hành mới", ignoreCase = true) ||
            trimmed.equals("最新リリース", ignoreCase = true) ->
            stringResource(Res.string.shelf_new_releases)
        trimmed.equals("Top music videos", ignoreCase = true) ||
            trimmed.equals("Video âm nhạc hàng đầu", ignoreCase = true) ||
            trimmed.equals("人気のミュージック ビデオ", ignoreCase = true) ->
            stringResource(Res.string.shelf_top_music_videos)
        trimmed.equals("For you", ignoreCase = true) ||
            trimmed.equals("Dành cho bạn", ignoreCase = true) ||
            trimmed.equals("あなたへのおすすめ", ignoreCase = true) ->
            stringResource(Res.string.shelf_for_you)
        trimmed.equals("Hits today", ignoreCase = true) ||
            trimmed.equals("Today's Hits", ignoreCase = true) ||
            trimmed.equals("Bản hit hôm nay", ignoreCase = true) ||
            trimmed.equals("今日のヒット曲", ignoreCase = true) ->
            stringResource(Res.string.shelf_hits_today)
        trimmed.equals("Artists on the rise", ignoreCase = true) ||
            trimmed.equals("Nghệ sĩ đang lên", ignoreCase = true) ->
            stringResource(Res.string.shelf_artists_on_the_rise)
        trimmed.equals("Concerts", ignoreCase = true) ||
            trimmed.equals("Buổi hòa nhạc", ignoreCase = true) ||
            trimmed.equals("コンサート", ignoreCase = true) ->
            stringResource(Res.string.shelf_concerts)
        trimmed.startsWith("Shorts", ignoreCase = true) ||
            trimmed.equals("Shorts nổi bật", ignoreCase = true) ||
            trimmed.equals("ショート", ignoreCase = true) ->
            stringResource(Res.string.shelf_shorts)
        trimmed.equals("Trending", ignoreCase = true) ||
            trimmed.equals("Thịnh hành", ignoreCase = true) ||
            trimmed.equals("急上昇", ignoreCase = true) ->
            stringResource(Res.string.shelf_trending)
        else -> title
    }
}
@Composable
internal fun localizeShelfSubtitle(subtitle: String): String {
    val trimmed = subtitle.trim()
    return when {
        trimmed.equals("TOP TUNES RIGHT NOW", ignoreCase = true) ||
            trimmed.equals("Top tunes right now", ignoreCase = true) ||
            trimmed.equals("Giai điệu hàng đầu hiện nay", ignoreCase = true) ||
            trimmed.equals("注目の曲", ignoreCase = true) ->
            stringResource(Res.string.shelf_top_tunes_right_now)
        trimmed.equals("From the community", ignoreCase = true) ||
            trimmed.equals("Từ cộng đồng", ignoreCase = true) ||
            trimmed.equals("コミュニティより", ignoreCase = true) ->
            stringResource(Res.string.shelf_from_the_community)
        trimmed.equals("YouTube Charts", ignoreCase = true) ||
            trimmed.equals("Bảng xếp hạng YouTube", ignoreCase = true) ||
            trimmed.equals("YouTube チャート", ignoreCase = true) ->
            stringResource(Res.string.shelf_youtube_charts)
        else -> subtitle
    }
}

@Composable
internal fun localizeCardSubtitle(subtitle: String): String {
    if (subtitle.isBlank()) return subtitle
    val delimiter = " • "
    val parts = subtitle.split(delimiter)
    val songLabel = stringResource(Res.string.shelf_song_item)
    val singleLabel = stringResource(Res.string.shelf_single)
    val chartLabel = stringResource(Res.string.shelf_chart)
    val playlistLabel = stringResource(Res.string.playlist)
    val localizedParts = parts.map { part ->
        val trimmed = part.trim()
        val lower = trimmed.lowercase(Locale.ROOT)
        when {
            trimmed.equals("Song", ignoreCase = true) || trimmed.equals("Titre", ignoreCase = true) ||
                trimmed.equals("Bài hát", ignoreCase = true) || trimmed.equals("曲", ignoreCase = true) -> songLabel
            trimmed.equals("Single", ignoreCase = true) || trimmed.equals("Đĩa đơn", ignoreCase = true) ||
                trimmed.equals("シングル", ignoreCase = true) -> singleLabel
            trimmed.equals("Chart", ignoreCase = true) || trimmed.equals("Bảng xếp hạng", ignoreCase = true) ||
                trimmed.equals("チャート", ignoreCase = true) -> chartLabel
            trimmed.equals("Playlist", ignoreCase = true) || trimmed.equals("Danh sách phát", ignoreCase = true) ||
                trimmed.equals("再生リスト", ignoreCase = true) -> playlistLabel
            lower.endsWith(" views") || lower.endsWith(" view") || lower.endsWith(" lượt xem") ||
                lower.endsWith(" 回視聴") || lower.endsWith("回視聴") -> {
                val count = trimmed.substringBeforeLast(' ', "").trim()
                if (count.any { it.isDigit() }) stringResource(Res.string.card_views_format, count) else part
            }
            lower.endsWith(" plays") || lower.endsWith(" play") || lower.endsWith(" lượt phát") ||
                lower.endsWith(" 回再生") || lower.endsWith("回再生") -> {
                val count = trimmed.substringBeforeLast(' ', "").trim()
                if (count.any { it.isDigit() }) stringResource(Res.string.card_plays_format, count) else part
            }
            lower.endsWith(" songs") || lower.endsWith(" song") || lower.endsWith(" bài hát") ||
                lower.endsWith(" 曲") || lower.endsWith("曲") -> {
                val count = trimmed.substringBeforeLast(' ', "").trim()
                if (count.any { it.isDigit() }) stringResource(Res.string.card_songs_format, count) else part
            }
            else -> part
        }
    }
    return localizedParts.joinToString(delimiter)
}

/** 卡片里那张封面的圆角，全首页统一 12dp。 */
private val COVER_RADIUS = 12.dp

/**
 * 私房版补丁 24：每日推荐那张卡改成三页轮播。
 *
 * 三页是同一张卡里的三批推荐（今天这批 / 随便听听 / 最近加入），左右滑切换。
 * 分页点从"一个大两个小"改成三个一样大的 6dp 圆，只靠透明度区分当前页——
 * 同一个物件的不同状态不该有两种尺寸。
 *
 * 卡高 202dp 不变。跟分类歌单一样走主题 surface 白底，不再从封面取色。
 */
@Composable
private fun DailyPickCard(
    shelf: HomeShelf,
    onItemClick: (ShelfItem) -> Unit,
    onOpenShelf: ((HomeShelf) -> Unit)? = null,
) {
    val titles = shelf.pages.ifEmpty { listOf(shelf.heading.ifBlank { shelf.title }) }
    val perPage = if (titles.size <= 1) shelf.items.size
    else (shelf.items.size + titles.size - 1) / titles.size
    val groups = titles.indices.map { index -> shelf.items.drop(index * perPage).take(perPage) }
    val pager = rememberPagerState(pageCount = { titles.size })
    val surface = MaterialTheme.colorScheme.surface
    val fallback = MaterialTheme.colorScheme.surfaceVariant
    val onCard = MaterialTheme.colorScheme.onSurface

    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = PAGE_GUTTER)
            .padding(bottom = 26.dp),
    ) {
        HorizontalPager(state = pager, modifier = Modifier.fillMaxWidth()) { index ->
            val page = groups[index].firstOrNull() ?: return@HorizontalPager
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(202.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(surface)
                    // 补丁 63：宿主接得住就整张卡开「这一批」的详情页；没接
                    // 就退回原来的「播这一页第一首」。他连着几轮说的都是
                    // 「每日推荐点不进去」。
                    .clickable {
                        if (onOpenShelf != null) onOpenShelf(shelf) else onItemClick(page)
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 22.dp, end = 10.dp),
                ) {
                    Text(
                        text = shelf.title,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = onCard.copy(alpha = 0.72f),
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = titles[index],
                        fontSize = 24.sp,
                        lineHeight = 30.sp,
                        fontWeight = FontWeight.Bold,
                        color = onCard,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "${groups[index].size} 首歌曲",
                        fontSize = 12.sp,
                        color = onCard.copy(alpha = 0.62f),
                    )
                }
                // 封面画在黑胶之后，压住它左边大部分，只让唱片从右侧露一段边。
                Box(modifier = Modifier.padding(end = 24.dp)) {
                    Box(
                        Modifier
                            .size(132.dp)
                            .align(Alignment.CenterEnd)
                            .offset(x = 16.dp)
                            .clip(CircleShape)
                            // 白底上再用深色唱片会显得像一块脏圆，压成很浅的灰。
                            .background(onCard.copy(alpha = 0.12f)),
                    )
                    Box(
                        Modifier
                            .size(140.dp)
                            .clip(RoundedCornerShape(COVER_RADIUS))
                            .background(fallback),
                    ) {
                        AsyncImage(
                            // 140dp 的封面不该拉 1000px 原图，按卡片尺寸取图。
                            model = page.thumbnailUrl.artworkAt(CARD_ART_PX),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
        // 分页点画在 pager 外面：跟着页走的点会在滑动时一起位移。
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 22.dp, bottom = 22.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            repeat(titles.size) { index ->
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(
                            onCard.copy(alpha = if (index == pager.currentPage) 0.85f else 0.25f),
                        ),
                )
            }
        }
    }
}

/**
 * 补丁 63：首页「分类歌单」，照他给的那张参考图做。
 *
 * 图里是：一行大标题「分类歌单」+ 灰色小字 PLAYLISTS + 右边的箭头，下面
 * 三张竖长的浅色圆角卡并排（还能横滑看后面的）。每张卡从上到下是方形封面
 * （封面左下压一行白字，是封面所属的歌单名）、加粗的分类名、两行灰色描述。
 *
 * 版式故意独立成 [ShelfLayout.CATEGORY_TILES]，没有塞进 [Shelf] 的方卡行——
 * 上一版用普通卡片行，他说「分类歌单要做的和我图片很类似一样的样子」。
 *
 * 补丁 64：字号 / 封面 / 取色都按他这轮反馈收了一档——标题跟别的货架同一个
 * typography，封面从铺满整卡缩到 6 成宽，底色不再是写死的粉橙，而是跟
 * 「猜你喜欢 / 最近常听」一样从这张卡自己的封面取主色再压浅。
 */
@Composable
private fun CategoryPlaylistShelf(
    shelf: HomeShelf,
    onItemClick: (ShelfItem) -> Unit,
    onOpenShelf: ((HomeShelf) -> Unit)?,
) {
    Column(Modifier.padding(bottom = 26.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 补丁 65：跟「最近常听」那排的标题**完全同一档**——titleLarge
                // 且左缘多缩进 10dp（与卡里封面左缘对齐）。补丁 64 用 titleMedium
                // + PAGE_GUTTER，他截图圈出来「字体大小位置怎么不和猜你喜欢
                // 最近常听保持一致」。
                .padding(start = PAGE_GUTTER + 14.dp, end = PAGE_GUTTER, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = localizeShelfTitle(shelf.title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // 补丁 66：他明确要「把分类歌单后面的英文删掉」——原来标题右边那行
            // 灰色 PLAYLISTS 小字取消，只留中文标题和右侧箭头。
            Spacer(Modifier.weight(1f))
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .clickable {
                        if (onOpenShelf != null) {
                            onOpenShelf(shelf)
                        } else {
                            shelf.items.firstOrNull()?.let(onItemClick)
                        }
                    },
            )
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // 一屏露出三张是宽屏参照图的比例；手机上至少给 132dp，于是露出
            // 两张多一点，剩下的横滑——文字不会被挤成单行乱码。
            val cardWidth = ((maxWidth - PAGE_GUTTER * 2 - 10.dp * 2) / 3)
                .coerceAtLeast(132.dp)
            LazyRow(
                contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(shelf.items, key = { it.title }) { item ->
                    CategoryPlaylistTile(
                        item = item,
                        width = cardWidth,
                        onClick = { onItemClick(item) },
                    )
                }
            }
        }
    }
}

/**
 * 分类卡不再用写死的粉 / 橙暖色，也不从封面取主色，直接用主题 surface 白底。
 * 首页的每日推荐、猜你喜欢、漫游电台三排也统一到这一档。文字用主题色，
 * 深色主题下不会糊成一块。
 */
@Composable
private fun CategoryPlaylistTile(
    item: ShelfItem,
    width: Dp,
    onClick: () -> Unit,
) {
    // 数据侧把「封面上的白字」和「下面两行描述」用换行拼在 subtitle 里，
    // 这样不用给 ShelfItem 加字段、不碰别的地方的数据类。
    val parts = item.subtitle.split('\n', limit = 2)
    val coverLabel = parts.getOrNull(0).orEmpty()
    val description = parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: coverLabel
    val fallback = MaterialTheme.colorScheme.surfaceVariant
    Column(
        modifier = Modifier
            .width(width)
            .clip(RoundedCornerShape(20.dp))
            // 补丁 97：同「最近常听」，白底，不取封面颜色。
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                // 补丁 64：原来封面铺满整张卡（方图占 100% 宽），他说「太大」。
                // 收成卡宽的 6 成左右，居中留白。
                .size(width * 0.58f)
                .clip(RoundedCornerShape(COVER_RADIUS))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            AsyncImage(
                // 补丁 84：按卡片尺寸取图。原来直接用接口给的原始地址，一张
                // 1000px 的封面画进 76dp 的方框里，首页一排四张就是四份大图。
                model = item.thumbnailUrl.artworkAt(TILE_ART_PX),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            // 补丁 71：封面上那行白字（"民谣试音碟｜细腻的柔情人声"那类）删掉。
            // 他圈出来要去掉——字压在图上看不清，而且下面的分类名已经说明了。
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = item.title,
            // 对齐「最近常听」卡片的歌名（15sp）；原来是 19sp W700，显得突兀。
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = description,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 2,
            minLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(10.dp))
    }
}

/**
 * 私房版补丁 35：首页「猜你喜欢」——一张圆角卡里竖着三条，照参考图。
 *
 * 卡片底色跟分类歌单一样走主题 surface 白底，不从封面取色。标题 17sp 半粗、
 * 作者 13sp 灰，层级靠字号和颜色拉开；封面 62dp、圆角 12dp。
 */
@Composable
private fun ForYouCard(
    shelf: HomeShelf,
    onItemClick: (ShelfItem) -> Unit,
    onItemLongPress: ((ShelfItem) -> Unit)?,
) {
    val surface = MaterialTheme.colorScheme.surface
    val fallback = MaterialTheme.colorScheme.surfaceVariant
    val onCard = MaterialTheme.colorScheme.onSurface
    val first = shelf.items.firstOrNull()
    Column(Modifier.padding(bottom = 26.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 私房版补丁 37：标题和卡里每一行的封面左缘对齐（卡外 16 + 卡内 14）。
                .padding(start = PAGE_GUTTER + 14.dp, end = PAGE_GUTTER, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = localizeShelfTitle(shelf.title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.weight(1f))
            if (first != null) {
                Box(
                    modifier = Modifier
                        // 私房版补丁 36：和下面每一行末尾的 ⋮ 同一条竖线——两者都
                        // 是 36dp 的盒子，右边各留 6dp。
                        .padding(end = 6.dp)
                        .size(36.dp)
                        .clip(CircleShape)
                        .clickable { onItemClick(first) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = BitChordIcons.Play,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PAGE_GUTTER)
                .clip(RoundedCornerShape(22.dp))
                .background(surface)
                .padding(vertical = 6.dp),
        ) {
            // 「猜你喜欢」以前被截成三条，刷新也是同样三条。这里放宽到六条；
            // 漫游电台那排只放一首，不受影响。
            shelf.items.take(6).forEachIndexed { index, item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .longPressMenuClickable(
                            onClick = { onItemClick(item) },
                            onLongClick = onItemLongPress?.let { { it(item) } },
                        )
                        .contextClick(onItemLongPress?.let { { it(item) } })
                        .padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AsyncImage(
                        model = item.thumbnailUrl.artworkAt(ROW_ART_PX),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(62.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(fallback),
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = item.title,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.W600,
                            color = onCard,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = item.subtitle,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (onItemLongPress != null) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .clickable { onItemLongPress(item) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.MoreVert,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
                if (index < shelf.items.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 90.dp, end = 14.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                    )
                }
            }
        }
    }
}

/**
 * 私房版补丁 35：首页「最近常听」——一排淡色卡片，照参考图的分类歌单外观。
 * 每张卡底色取自己的封面主色，再压到 0.86/0.94，整排像一组同族的彩色卡片。
 */
@Composable
private fun RecentCardShelf(
    shelf: HomeShelf,
    onItemClick: (ShelfItem) -> Unit,
    onItemLongPress: ((ShelfItem) -> Unit)?,
    onRadioClick: ((ShelfItem) -> Unit)?,
) {
    val first = shelf.items.firstOrNull()
    Column(Modifier.padding(bottom = 26.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 私房版补丁 37：和每张卡里的封面左缘对齐（卡外 16 + 卡内 10），
                // 标题后面那串英文提示也去掉了。
                .padding(start = PAGE_GUTTER + 14.dp, end = PAGE_GUTTER, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = localizeShelfTitle(shelf.title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.weight(1f))
            if (onRadioClick != null && first != null) {
                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { onRadioClick(first) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = BitChordIcons.Infinity,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(15.dp),
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = "电台",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        ShelfRow(
            contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 补丁 84：给 key。没有 key 时横滑一遍、封面换色重算，整排都会
            // 重新组合（每张卡还各带一次取色）。带上 key 就只重画变的那张。
            items(shelf.items, key = { it.videoId ?: it.title }) { item ->
                Column(
                    modifier = Modifier
                        .width(152.dp)
                        .longPressMenuClickable(
                            onClick = { onItemClick(item) },
                            onLongClick = onItemLongPress?.let { { it(item) } },
                        )
                        .contextClick(onItemLongPress?.let { { it(item) } })
                        .clip(RoundedCornerShape(20.dp))
                        // 补丁 97：这一排按他的要求**不再从封面取色**，一律白底
                        // （深色主题就是卡片色）。封面颜色只留在每日推荐与猜你喜欢里。
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(10.dp),
                ) {
                    AsyncImage(
                        model = item.thumbnailUrl.artworkAt(CARD_ART_PX),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(COVER_RADIUS))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = item.title,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.W600,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = item.subtitle,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}

@Composable
private fun HeroShelf(
    shelf: HomeShelf,
    onItemClick: (ShelfItem) -> Unit,
    onItemLongPress: ((ShelfItem) -> Unit)? = null,
    currentSong: Song? = null,
    isPlaying: Boolean = false,
) {
    Column(Modifier.padding(bottom = 26.dp)) {
        SectionHeader(shelf.title, shelf.subtitle)
        val isCurrentItem = shelf.items.playingItemMatcher(currentSong)
        // Measured rather than taken as a share of the parent, because the card
        // has a ceiling as well as a fraction — see [heroCardWidth]. A fixed
        // width is also the only one of the two the aspect ratio below can turn
        // into a height, so the card keeps its shape however it was arrived at.
        BoxWithConstraints {
            val cardWidth = heroCardWidth(maxWidth)
            ShelfRow(
                state = rememberLazyListState(),
                contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                itemsIndexed(
                    shelf.items,
                    // 带下标后缀，保证同名歌单也不会撞 key 让 LazyRow 崩。
                    key = { index, item -> "$index:${item.videoId ?: item.title}" },
                ) { _, item ->
                    HeroCard(
                        item = item,
                        onClick = { onItemClick(item) },
                        onLongPress = onItemLongPress?.let { { it(item) } },
                        modifier = Modifier.width(cardWidth),
                        isCurrent = isCurrentItem(item),
                        isPlaying = isCurrentItem(item) && isPlaying,
                    )
                }
            }
        }
    }
}

/** Big card: artwork with the caption laid over a scrim, as on Listen Now. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HeroCard(
    item: ShelfItem,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    isCurrent: Boolean = false,
    isPlaying: Boolean = false,
) {
    Box(
        modifier = modifier
            .aspectRatio(HERO_CARD_RATIO)
            .clip(RoundedCornerShape(18.dp))
            .thumbnailBorder(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .longPressMenuClickable(onClick = onClick, onLongClick = onLongPress)
            .contextClick(onLongPress),
    ) {
        AsyncImage(
            model = item.thumbnailUrl.artworkAt(HEADER_ART_PX),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (isCurrent && isPlaying) SearchPlayingBars(Modifier.align(Alignment.Center))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomStart)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f)),
                    ),
                )
                .padding(start = 16.dp, end = 16.dp, top = 34.dp, bottom = 14.dp),
        ) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.subtitle.isNotBlank()) {
                val displaySubtitle = localizeCardSubtitle(item.subtitle)
                Text(
                    text = displaySubtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.72f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * [leadingCard] rides at the head of the row, ahead of the content — the
 * Library tab's "New playlist" tile, which belongs among the playlists rather
 * than in a bar somewhere above them. [onItemLongPress] opens the album /
 * playlist menu, and is null only where a card points at something with no
 * track list behind it to act on.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Shelf(
    shelf: HomeShelf,
    onItemClick: (ShelfItem) -> Unit,
    onItemLongPress: ((ShelfItem) -> Unit)? = null,
    leadingCard: (@Composable () -> Unit)? = null,
    currentSong: Song? = null,
    isPlaying: Boolean = false,
) {
    Column(Modifier.padding(bottom = 26.dp)) {
        SectionHeader(shelf.title, shelf.subtitle)
        val isCurrentItem = shelf.items.playingItemMatcher(currentSong)
        ShelfRow(
            contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            leadingCard?.let { card -> item(key = "leading") { card() } }
            itemsIndexed(
                shelf.items,
                key = { index, item -> "$index:${item.videoId ?: item.title}" },
            ) { _, item ->
                if (shelf.layout == ShelfLayout.VIDEO) {
                    VideoShelfCard(
                        item = item,
                        onClick = { onItemClick(item) },
                        onLongPress = onItemLongPress?.let { { it(item) } },
                        isCurrent = isCurrentItem(item),
                        isPlaying = isCurrentItem(item) && isPlaying,
                    )
                } else {
                    ShelfCard(
                        item = item,
                        onClick = { onItemClick(item) },
                        onLongPress = onItemLongPress?.let { { it(item) } },
                        isCurrent = isCurrentItem(item),
                        isPlaying = isCurrentItem(item) && isPlaying,
                    )
                }
            }
        }
    }
}

/**
 * A music video, as the web player lays one out: the thumbnail at its own
 * 16:9 rather than cropped into a square, the title and credit under it. About
 * as tall as the square cards, so a video shelf sits in the feed's rhythm.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VideoShelfCard(
    item: ShelfItem,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)? = null,
    modifier: Modifier = Modifier.width(VIDEO_CARD_WIDTH),
    isCurrent: Boolean = false,
    isPlaying: Boolean = false,
    titleColor: Color = MaterialTheme.colorScheme.onBackground,
    subtitleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    placeholderColor: Color = MaterialTheme.colorScheme.surfaceVariant,
) {
    Column(
        modifier = modifier
            .longPressMenuClickable(onClick = onClick, onLongClick = onLongPress)
            .contextClick(onLongPress),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(VIDEO_ASPECT)
                .clip(RoundedCornerShape(12.dp))
                .thumbnailBorder(RoundedCornerShape(12.dp))
                .background(placeholderColor),
        ) {
            AsyncImage(
                // Video stills are not resizable googleusercontent art; the
                // largest the row listed is already about card size.
                model = item.thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (isCurrent && isPlaying) SearchPlayingBars(Modifier.align(Alignment.Center))
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = item.title,
            style = MaterialTheme.typography.titleMedium,
            color = titleColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = localizeCardSubtitle(item.subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = subtitleColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A music video's frame. */
const val VIDEO_ASPECT = 16f / 9f

/** Wide enough that a 16:9 card stands about as tall as a square [SHELF_CARD_WIDTH] one. */
val VIDEO_CARD_WIDTH = 248.dp

/**
 * A card that isn't a thing yet — the dashed "New playlist" tile at the head
 * of the Library's playlist row, sized to sit in line with the covers beside
 * it rather than as a button bolted above them.
 */
@Composable
internal fun NewShelfCard(
    icon: ImageVector,
    label: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier.width(SHELF_CARD_WIDTH),
    /** A full-colour mark drawn in place of [icon], for a card that stands for a brand. */
    logo: DrawableResource? = null,
) {
    Column(
        modifier = modifier.clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (logo != null) {
                // The mark is a disc with the bars cut out, so tinting it
                // leaves the bars clear: white on dark, black on light.
                Icon(
                    painter = painterResource(logo),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(44.dp),
                )
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(34.dp),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A device-folder card: mesh gradient in the folder's colours with its glyph
 * on top. One composable for local music, WebDAV and SMB rather than three
 * copies of the same box.
 */
@Composable
private fun ServiceCard(colors: List<Color>, trackKey: String, icon: ImageVector) {
    val palette = remember { MeshPalette(colors) }
    // 动效照旧，但熄屏/切后台时让 mesh 停在原地：看得见的效果一点不少，
    // 看不见的 60fps 重绘一次都不跑。
    val foreground = rememberIsForeground()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        MeshGradientBackground(
            palette = palette,
            trackKey = trackKey,
            continuous = true,
            paused = !foreground,
            blurRadius = 24.dp,
        )
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(40.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ShelfCard(
    item: ShelfItem,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)? = null,
    modifier: Modifier = Modifier.width(SHELF_CARD_WIDTH),
    /** Set on a Library playlist card that's in [AppSettings.pinnedPlaylists][com.music.bitchord.data.settings.AppSettings.pinnedPlaylists]. */
    isPinned: Boolean = false,
    isCurrent: Boolean = false,
    isPlaying: Boolean = false,
) {
    Column(
        modifier = modifier
            .longPressMenuClickable(onClick = onClick, onLongClick = onLongPress)
            .contextClick(onLongPress),
    ) {
        when (item.browseId) {
            "local:downloads" -> {
                val palette = remember { MeshPalette(listOf(Color(0xFF1E3C72), Color(0xFF2A5298))) }
                val foreground = rememberIsForeground()
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    MeshGradientBackground(
                        palette = palette,
                        trackKey = "local:downloads",
                        continuous = true,
                        paused = !foreground,
                        blurRadius = 24.dp,
                    )
                    Icon(
                        imageVector = BitChordIcons.Download,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(40.dp),
                    )
                }
            }
            "local:all" -> ServiceCard(
                colors = listOf(Color(0xFF134E5E), Color(0xFF71B280)),
                trackKey = "local:all",
                icon = Icons.Rounded.LibraryMusic,
            )
            "local:cache" -> ServiceCard(
                colors = listOf(Color(0xFF42275A), Color(0xFF734B6D)),
                trackKey = "local:cache",
                icon = Icons.Rounded.Cached,
            )
            "local:webdav" -> ServiceCard(
                colors = listOf(Color(0xFF3A1C71), Color(0xFFD76D77)),
                trackKey = "local:webdav",
                icon = Icons.Rounded.Folder,
            )
            "local:smb" -> ServiceCard(
                colors = listOf(Color(0xFF0F2027), Color(0xFF2C5364)),
                trackKey = "local:smb",
                icon = Icons.Rounded.Storage,
            )
            else -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .thumbnailBorder(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    AsyncImage(
                        model = item.thumbnailUrl.artworkAt(CARD_ART_PX),
                        contentDescription = null,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (isCurrent && isPlaying) SearchPlayingBars(Modifier.align(Alignment.Center))
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isPinned) {
                Icon(
                    imageVector = BitChordIcons.Pin,
                    contentDescription = stringResource(Res.string.pinned),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(4.dp))
            }
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        val displaySubtitle = localizeCardSubtitle(item.subtitle)
        Text(
            text = displaySubtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 补丁 84：分类卡封面按卡宽取图（约 76dp @3x）。 */
private const val TILE_ART_PX = 240
