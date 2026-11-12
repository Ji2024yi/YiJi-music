package com.music.bitchord.data.localfeed

import android.content.Context
import com.music.bitchord.data.LocalMediaRepository
import com.music.bitchord.data.SongIdentity
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.MoodGenre
import com.music.bitchord.data.model.MoodGenreSection
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.ShelfLayout
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.smb.SmbRepository
import com.music.bitchord.data.webdav.WebDavConfig
import com.music.bitchord.data.webdav.WebDavRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * 首页与探索页的内容源。
 *
 * 原版这两个页面都向 InnerTube 要推荐和流派分类。这里改成从**你自己的曲库**
 * 派生：设备本地文件、App 内已下载、WebDAV、SMB 四个源合并去重。任一源没配置
 * 或读取失败都只是少一批歌，不会让整页变成错误态。
 *
 * 首页每一排的条目都是**歌本身**（带 videoId，点了直接播），不走 browseId 跳
 * 详情页——本地曲目没有可靠的详情页身份，硬套会出现"点了进不去"这种难查的坑。
 * 只有探索页的分类卡片用本模块自己的 `local:feed:` 前缀，点开由 ViewModel 在
 * 内存里过滤，同样不依赖 LocalGroups 的文件夹 id 语义。
 */
object LocalFeed {

    const val PREFIX = "local:feed:"
    /**
     * 资料库里的「全部歌曲」。把本机、下载、WebDAV、SMB 四路合并去重后
     * 一次列全，本地和远端同一首歌只显示一行。现有 `local:all` / `local:webdav`
     * 两栏语义不动——上传、删除、缓存到本机这些动作是按栏判断的，改了会失去依据。
     */
    const val ALL_SONGS_BROWSE_ID = "local:all-merged"
    const val KIND_ARTIST = "artist"
    const val KIND_ALBUM = "album"
    const val KIND_FOLDER = "folder"
    const val KIND_GENRE = "genre"

    /** 本分支：探索页那些格子按发行年分。 */
    const val KIND_YEAR = "year"

    /**
     * 年份格子的底色，从他给的参照图里取的（灰紫 / 青蓝 / 棕金 / 灰 / 砖红 / 深棕 /
     * 墨绿 / 橄榄）。同一年永远同一个色；按年份取模，不是按位置，重排不会变色。
     */
    private val YEAR_COLORS = listOf(
        0xFF8A7F88, 0xFF3C697E, 0xFF8A6212, 0xFF9AA0A0,
        0xFFA83232, 0xFF6E3A22, 0xFF3E5C50, 0xFF5C5A50,
    )

    /** 单个分类页最多列这么多条，再多也没人手滑看完。 */
    private const val MAX_ITEMS = 60

    suspend fun songs(context: Context): List<Song> = coroutineScope {
        val device = async { runCatching { LocalMediaRepository.getLocalMusic(context) }.getOrDefault(emptyList()) }
        val cached = async { runCatching { LocalMediaRepository.getDownloadedSongs(context) }.getOrDefault(emptyList()) }
        val webdav = async { runCatching { WebDavRepository.getSongs() }.getOrDefault(emptyList()) }
        val smb = async { runCatching { SmbRepository.getSongs() }.getOrDefault(emptyList()) }
        val merged = device.await() + cached.await() + webdav.await() + smb.await()
        dedupeByFile(merged)
    }

    /**
     * 同一首歌经常以两个身份同时存在：手机本地存了一份，WebDAV 上又有一份
     * （同一批文件既传进服务器又留在手机里）。两个身份的封面来源不同——本地走
     * 系统媒体库的内嵌图，WebDAV 走同名 .jpg。
     *
     * 按**文件名**归并，**保留本地那一份**，两个理由：
     *   1. 封面：本地文件的图由 MediaStore/内嵌提取提供，是应用原生就支持的
     *      路径；远程同名 .jpg 这条路没有被稳定用上，整页会塌成同一张图。
     *   2. 流量：播放直接读本地文件，一首 25–45MB 的 FLAC 不再走服务器下行。
     * 只有本地没有的曲目才留远程身份，所以 WebDAV 仍是"补齐"的来源。
     *
     * 原来 key 是「去扩展名的 basename + trim + lowercase」，多一个
     * 空格、一对全角括号就对不上，于是同一首歌仍各占一行。现在交给
     * [SongIdentity] 归一化（去空白、全角转半角），并在文件名拿不到时用
     * 「歌名 + 主艺人」兜底。版本后缀不剥离——「(Live)」和原版是两首歌。
     */
    private fun dedupeByFile(all: List<Song>): List<Song> {
        // 已经缓存到本机的 WebDAV 文件仍带着 `webdav:` id，但它有真实路径、
        // 播放走本地。判成本机，否则两份都被算成远端、又都被过滤掉。
        fun isRemote(song: Song): Boolean =
            song.localPath.isNullOrBlank() && (
                WebDavConfig.isWebDavId(song.videoId) ||
                    song.videoId.startsWith("smb:") ||
                    song.localUri?.startsWith("http") == true
            )

        fun keyOf(song: Song): String? {
            val raw = WebDavConfig.fileUrlOf(song.videoId)
                ?: song.localPath
                ?: song.localUri
            return raw?.let(SongIdentity::fileKey)
        }

        val local = all.filterNot(::isRemote)
        val localKeys = local.mapNotNull(::keyOf).toSet()
        val localTitles = local.map { SongIdentity.titleKey(it.title, it.artist) }.toSet()
        val remoteOnly = all.filter(::isRemote).filterNot { song ->
            keyOf(song)?.let { it in localKeys } == true ||
                SongIdentity.titleKey(song.title, song.artist) in localTitles
        }
        return (local + remoteOnly).distinctBy { it.videoId }
    }

    private fun item(song: Song) = ShelfItem(
        title = song.title,
        subtitle = song.artist,
        thumbnailUrl = song.thumbnailUrl,
        videoId = song.videoId,
        browseId = null,
        isVideo = false,
    )

    private fun addedSeconds(song: Song): Long =
        song.localDateModifiedSeconds ?: song.localDateAddedSeconds ?: 0L

    private fun grouped(all: List<Song>, key: (Song) -> String?): Map<String, List<Song>> =
        all.mapNotNull { song -> key(song)?.takeIf { it.isNotBlank() }?.let { it to song } }
            .groupBy({ it.first }, { it.second })

    /**
     * 首页：每日推荐（三页轮播的大卡）+ 猜你喜欢（竖排列表）+ 你的专辑 / 你的艺术家（横滑卡）。
     *
     * 后两块是照主流音乐 App 首页的通用结构补的：顶部一张推荐轮播，往下是能直接点的
     * 列表，再往下是按专辑、按艺人的横滑入口。数据全部从你自己的曲库派生，不需要
     * 任何在线接口。排行榜、推荐歌单那种要服务端的东西这里做不了，也没塞空壳。
     *
     * 每日推荐用当天日期当随机种子，同一天进来是同一批、标题和封面都不跳，第二天换
     * 一批。三页 = 同一批切成两段（今天这批、随便听听）+ 按加入时间排的最近加入。
     * 猜你喜欢取第一页那批的前四首，不再另抽一次，两块对得上。
     */
    fun homeShelves(all: List<Song>, recentlyPlayed: List<Song> = emptyList()): List<HomeShelf> {
        if (all.isEmpty()) return emptyList()
        val now = java.util.Calendar.getInstance()
        val seed = now.get(java.util.Calendar.YEAR) * 10000L +
            (now.get(java.util.Calendar.MONTH) + 1) * 100L +
            now.get(java.util.Calendar.DAY_OF_MONTH)
        val byDay = all.shuffled(java.util.Random(seed))
        val perPage = ((byDay.size + 2) / 3).coerceAtLeast(1)
        val recent = all.filter { addedSeconds(it) > 0 }
            .sortedByDescending(::addedSeconds)
            .ifEmpty { byDay }
        val pageItems = listOf(
            byDay.take(perPage),
            byDay.drop(perPage).take(perPage),
            recent.take(perPage),
        )
        val pageTitles = listOf(
            "${now.get(java.util.Calendar.MONTH) + 1}月${now.get(java.util.Calendar.DAY_OF_MONTH)}日",
            "随便听听",
            "最近加入",
        )

        val shelves = mutableListOf<HomeShelf>()
        shelves += HomeShelf(
            title = "每日推荐",
            subtitle = "${all.size} 首歌曲",
            items = pageItems.flatten().map(::item),
            layout = ShelfLayout.DAILY_PICK,
            heading = pageTitles[0],
            pages = pageTitles,
        )

        // 猜你喜欢改成参考图那种卡片列表（三条），不再带副标题。
        shelves += HomeShelf(
            title = "猜你喜欢",
            subtitle = "",
            items = pageItems[0].take(3).map(::item),
            layout = ShelfLayout.FOR_YOU,
            heading = "FOR YOU",
        )

        // 最近常听。统计里的歌按 id 对回当前曲库（统计存的是播放
        // 那一刻的快照，封面/作者可能已经变过）；对不上的直接丢掉，没有统计时
        // 退回按加入时间排的一批，首页不会因此空一块。
        val byId = all.associateBy { it.videoId }
        val played = recentlyPlayed.mapNotNull { byId[it.videoId] }
            .distinctBy { it.videoId }
        val recentItems = (played.ifEmpty { recent }).take(10)
        if (recentItems.isNotEmpty()) {
            shelves += HomeShelf(
                title = "最近常听",
                subtitle = "",
                items = recentItems.map(::item),
                layout = ShelfLayout.RECENT_CARDS,
                heading = "RECENTLY PLAYED",
            )
        }
        return shelves
    }

    /** 一首歌所属的目录名：WebDAV 取 URL 路径的上级，本地取文件路径的上级。 */
    private fun folderOf(song: Song): String? {
        val path = WebDavConfig.fileUrlOf(song.videoId)
            ?.let { runCatching { java.net.URI(it).path }.getOrNull() }
            ?: song.localPath
        if (path.isNullOrBlank()) return null
        val segments = path.split('/').filter { it.isNotBlank() }
        return if (segments.size >= 2) segments[segments.size - 2] else null
    }

    /** 探索页：年份 / 流派 / 文件夹 / 艺术家 / 专辑 几类分类卡片。 */
    fun exploreSections(context: Context, all: List<Song>): List<MoodGenreSection> {
        if (all.isEmpty()) return emptyList()
        val sections = mutableListOf<MoodGenreSection>()

        fun tile(kind: String, name: String, thumb: String?): MoodGenre =
            MoodGenre(
                title = name,
                browseId = "$PREFIX$kind:$name",
                params = null,
                // 分类卡带一张代表封面（该组第一首的），否则整页都是纯色块。
                thumbnailUrl = thumb,
            )

        // 探索页第一块是按发行年。格子按他给的参照图做成两列大色块，
        // 所以这里要一并交出「几首歌曲」那行小字和这个年份固定用哪个色。
        // 没有年份的歌不进格子（`YearTags` 只认标签，不拿文件名凑）。
        val dated = YearTags.fill(context, all)
        val years = grouped(dated) { song -> song.year?.toString() }
            .entries.sortedByDescending { entry -> entry.key.toIntOrNull() ?: 0 }
        if (years.isNotEmpty()) {
            val resources = context.applicationContext.resources
            sections += MoodGenreSection(
                title = "按年份",
                items = years.take(60).map { (year, songs) ->
                    tile(
                        KIND_YEAR,
                        year,
                        songs.firstNotNullOfOrNull { song -> song.thumbnailUrl?.takeIf { it.isNotBlank() } },
                    ).copy(
                        subtitle = resources.getQuantityString(
                            com.music.bitchord.R.plurals.song_count_plural,
                            songs.size,
                            songs.size,
                        ),
                        stripeColor = YEAR_COLORS[(year.toIntOrNull() ?: 0)
                            .mod(YEAR_COLORS.size)]
                            .toLong(),
                    )
                },
            )
        }

        // 探索页第一块是按流派。标签写了就按标签，没写就看他
        // 自己在目录里建的那层流派名（音乐/华语/…）；两边都拿不到就不开这块，
        // 不会凭空长出一个「未知」。
        val genres = grouped(all) { genreOf(it) }.entries.sortedByDescending { it.value.size }
        if (genres.isNotEmpty()) {
            sections += MoodGenreSection(
                title = "按流派",
                items = genres.take(60).map { tile(KIND_GENRE, it.key, it.value.first().thumbnailUrl) },
            )
        }

        // 文件夹跟着放第二位：曲库是按目录组织的，它比艺术家更接近「这一类歌」。
        val folders = grouped(all) { folderOf(it) }.entries.sortedByDescending { it.value.size }
        if (folders.isNotEmpty()) {
            sections += MoodGenreSection(
                title = "按文件夹",
                items = folders.take(60).map { tile(KIND_FOLDER, it.key, it.value.first().thumbnailUrl) },
            )
        }

        val artists = grouped(all) { it.artist }.entries.sortedByDescending { it.value.size }
        if (artists.isNotEmpty()) {
            sections += MoodGenreSection(
                title = "按艺术家",
                items = artists.take(60).map { tile(KIND_ARTIST, it.key, it.value.first().thumbnailUrl) },
            )
        }

        val albums = grouped(all) { it.albumName }.entries.sortedByDescending { it.value.size }
        if (albums.isNotEmpty()) {
            sections += MoodGenreSection(
                title = "按专辑",
                items = albums.take(60).map { tile(KIND_ALBUM, it.key, it.value.first().thumbnailUrl) },
            )
        }
        return sections
    }

    /**
     * 这首歌归哪个流派。
     *
     * 两个来源，都不猜：文件自己写的流派标签（本地那批由 MediaStore 的 GENRE 列
     * 带出来），以及路径里的一段流派目录名。他的曲库是 `音乐/艺术家/专辑/`，谁
     * 也没写流派时返回 null，探索页就少一块，而不是凑一堆「未知流派」的卡。
     */
    private fun genreOf(song: Song): String? {
        song.genre?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val path = WebDavConfig.fileUrlOf(song.videoId)
            ?.let { runCatching { java.net.URI(it).path }.getOrNull() }
            ?: song.localPath
        if (path.isNullOrBlank()) return null
        for (segment in path.split('/').map(String::trim).filter { it.isNotBlank() }) {
            val hit = GENRE_NAMES.firstOrNull { segment.equals(it, ignoreCase = true) }
            if (hit != null) return hit
        }
        return null
    }

    /** 常见的目录流派名，中英都收着；相等才算，不做包含匹配（「音乐」会吞掉一切）。 */
    private val GENRE_NAMES = listOf(
        "华语", "国语", "粤语", "闽南语", "欧美", "日韩", "日语", "韩语",
        "流行", "摇滚", "说唱", "嘻哈", "电子", "舞曲", "民谣", "古风",
        "古典", "爵士", "蓝调", "金属", "朋克", "独立", "轻音乐", "纯音乐",
        "影视原声", "原声", "合唱", "儿歌", "佛教", "道教", "其他",
        "pop", "rock", "hip-hop", "hiphop", "rap", "electronic", "edm",
        "folk", "classical", "jazz", "blues", "metal", "punk", "indie",
        "r&b", "rb", "soul", "country", "reggae", "latin", "ambient",
        "soundtrack", "new age", "acoustic", "cover", "live",
    )

    /**
     * 一个 `local:feed:` id 对应哪一批歌。探索页的格子、播放页菜单里「打开专辑 /
     * 打开艺人」开的页，读的都是这一份过滤，两处不会各写一套口径。
     *
     * 认不出这个 id 返回 null（不是空列表）—— 空列表的意思是"这批歌确实没有"。
     */
    fun songsFor(context: Context, browseId: String, all: List<Song>): List<Song>? {
        if (!browseId.startsWith(PREFIX)) return null
        val body = browseId.removePrefix(PREFIX)
        val kind = body.substringBefore(':', "")
        val name = body.substringAfter(':', "")
        if (kind.isBlank() || name.isBlank()) return emptyList()
        return when (kind) {
            KIND_ARTIST -> all.filter { it.artist.equals(name, ignoreCase = true) }
            KIND_ALBUM -> all.filter { it.albumName.equals(name, ignoreCase = true) }
            KIND_FOLDER -> all.filter { folderOf(it).equals(name, ignoreCase = true) }
            KIND_GENRE -> all.filter { genreOf(it).equals(name, ignoreCase = true) }
            // 年份格子点开的就是这一年所有的歌。
            KIND_YEAR -> all.filter { YearTags.yearOf(context, it)?.toString() == name }
            else -> null
        }
    }

    /** 点开一张分类卡片：在内存里过滤出该分类的歌，拼成一排。 */
    fun shelvesFor(context: Context, browseId: String, all: List<Song>): List<HomeShelf>? {
        if (!browseId.startsWith(PREFIX)) return null
        val songs = songsFor(context, browseId, all) ?: return null
        if (songs.isEmpty()) return emptyList()
        val name = browseId.removePrefix(PREFIX).substringAfter(':', "")
        val kind = browseId.removePrefix(PREFIX).substringBefore(':', "")
        val heading = when (kind) {
            KIND_ARTIST -> name
            KIND_ALBUM -> "$name — ${songs.first().artist}"
            else -> name
        }
        return listOf(
            HomeShelf(
                title = heading,
                subtitle = "${songs.size} 首",
                items = songs.take(MAX_ITEMS).map(::item),
            ),
        )
    }
}
