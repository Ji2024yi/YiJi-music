package com.music.bitchord.data.netease

import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 网易云音乐的三件事：搜、取地址、问账号。
 *
 * ## 为什么是这几个明文接口
 *
 * 网易云官方的 web 接口分两代：新的 `weapi` / `eapi` 要对参数做 AES + RSA 加密，
 * 老的一批 `/api/...` 只要带上 `Referer` 就能用。开源社区（Binaryify 的
 * NeteaseCloudMusicApi 及其众多分支）两种都实现，而这个 App 只需要三个只读接口，
 * 所以走明文这一批：加密那套要维护一份会随服务端改动而失效的密钥协商，成本远大于
 * 收益。代价是这批接口随时可能被关掉——真被关掉时 [search] 和 [stream] 都会返回空，
 * 音源变成一次干净的"没找到"，不会崩，也不会影响别的源。
 *
 * ## 三条实测出来的规矩
 *
 * 1. **搜索不带登录也能用**，所以没登录时这个源照样能搜、能放免费曲目（`fee` 是
 *    0 或 8 的那些），这是它比 QQ 音乐省事的地方——QQ 那边匿名连地址都取不到。
 * 2. **VIP 曲目（`fee` = 1）取回来的是一段 30 秒试听**，`freeTrialInfo` 非空就是
 *    证据。这种直接判成"这首我没有"，而不是把 30 秒片段当整首歌交出去：放一半
 *    突然停了，比压根不放更像故障。判成未命中之后，[SourceResolver] 会照它的规矩
 *    去找别的源，用户点一条 VIP 曲目大概率还是能听到——只是从别处放。
 * 3. **地址是 `http://` 的**，而这个 App 关着明文流量（`usesCleartextTraffic` 为
 *    false）。实测同一个 CDN 换成 `https://` 照样 206，所以这里统一改写，不放宽
 *    应用的网络安全配置。
 *
 * 解析与网络分开：[parseSearchRows] / [parseDetailRows] / [parseStreamRow] /
 * [assemble] / [toStream] 都是纯函数，单测直接喂真实响应体，不发一个包。
 */
object NeteaseApi {

    private const val ORIGIN = "https://music.163.com"

    /**
     * `/api/song/detail` 一次塞几百个 id 时服务端只回前 200 首 —— 这就是
     * 「1054 首的喜欢列表只显示 201 首」的根因。按 NeteaseCloudMusicApi 的
     * `playlist/track/all` 切成 100 一批、4 路并发：拿得全，也不把启动拖成十几秒。
     */
    private const val DETAIL_BATCH_SIZE = 100
    private const val DETAIL_BATCH_PARALLEL = 4

    /**
     * 桌面版 Chrome 的 UA。
     *
     * 不是伪装——是这个接口对移动端 UA 返回的字段会变少（`/api/song/detail` 在部分
     * UA 下不给 `album.picUrl`），而封面是搜索列表要用的。
     */
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /**
     * 没登录时的基线 cookie。
     *
     * 补丁 58：原来是 `os=android; appver=8.0.0` 再加一个伪造的 `X-Real-IP`。
     * 那两样合起来正是「环境异常」的来源——`os` 说是安卓、UA 却是桌面 Chrome、
     * `appver` 又是移动端版本号，三处互相不认；而声明一个和真实出口不同的 IP 在
     * 风控看来就是在遮掩。现在只留一套自洽的桌面客户端身份，设备号由
     * [NeteaseAuth.deviceId] 补上。
     */
    private const val ANONYMOUS_COOKIE = "os=pc; appver=2.9.7"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * 自己的客户端，从全 App 那个派生。
     *
     * 用 [Http.client] 的派生而不是另建一个：那个客户端的连接池、DNS 缓存和代理
     * 配置是这个 App 一致性的来源（见 [Http] 的注释）。只改超时——搜索和取地址都
     * 在用户等结果的关键路径上，几秒钟拿不到就该让别的源上。
     */
    private val client: OkHttpClient = Http.client.newBuilder()
        .callTimeout(12, TimeUnit.SECONDS)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    // ── 对外 ────────────────────────────────────────────────────────────

    /** 一个搜索结果行，已经补齐了封面、专辑和时长。 */
    data class Track(
        val id: Long,
        val title: String,
        val artist: String,
        val album: String?,
        val artwork: String?,
        val durationMs: Long,
        /** 0 / 8 免费，1 会员，4 需购买。只用于界面提示，播放以 [stream] 的答案为准。 */
        val fee: Int,
    ) {
        val durationSec: Int? get() = durationMs.takeIf { it > 0 }?.let { (it / 1000).toInt() }
    }

    /** 一条可以直接交给解码器的地址。 */
    data class Stream(
        val url: String,
        val kbps: Int,
        val codec: String,
        val durationSec: Int?,
    )

    data class Account(val nickname: String, val userId: Long, val avatarUrl: String?)

    /**
     * 搜索，最多 [limit] 行。
     *
     * 两次请求：`/api/search/get/web` 给 id 和曲名，`/api/song/detail` 一次批量
     * 补齐封面与专辑。搜索接口本身不返回 `picUrl`（只有 `picId`），而封面的 URL
     * 是 `picId` 经过一段不公开的加密得来的——自己拼等于把一段会变的算法抄进来，
     * 不如多花一次往返。第二次失败不影响结果：封面空着，别的照常。
     */
    suspend fun search(query: String, limit: Int = 25): List<Track> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@withContext emptyList()
        val url = "$ORIGIN/api/search/get/web?csrf_token=${encode(csrfToken())}" +
            "&s=${encode(trimmed)}&type=1&offset=0&total=true&limit=${limit.coerceIn(1, 50)}"
        val body = get(url) ?: return@withContext emptyList()
        val rows = parseSearchRows(body)
        if (rows.isEmpty()) return@withContext emptyList()

        // 批量补详情。失败就退回搜索行本身，不让一次 5xx 把整个结果清空。
        val details = runCatching {
            get("$ORIGIN/api/song/detail?ids=${idList(rows.map { it.id })}")
                ?.let(::parseDetailRows)
                .orEmpty()
        }
            .onFailure { Log.w(TAG, "song detail unavailable: ${it.message}") }
            .getOrDefault(emptyMap())

        assemble(rows, details)
    }

    /**
     * 取 [id] 的播放地址，码率封顶 [maxKbps]。
     *
     * 返回 null 的三种情况都是一个意思——"这个源给不了这首歌"，调用方该去找下一个
     * 源，而不是报错：接口说 200 但 `url` 为空；返回的是试听片段；接口本身失败。
     */
    suspend fun stream(id: Long, maxKbps: Int = 320): Stream? = withContext(Dispatchers.IO) {
        if (id <= 0) return@withContext null
        // 补丁 67：无损档先**真去要 FLAC**。v1 接口按 `level` 要，`lossless`
        // 对会员曲目会给 FLAC；非会员 / 该曲没有无损时服务端回一份 MP3，
        // 这里按编码筛掉、继续走下面的回落链——绝不把 320k MP3 当"无损"交上去。
        if (maxKbps >= LOSSLESS_REQUEST_KBPS) {
            for (level in LOSSLESS_LEVELS) {
                val body = get(playerUrlV1(id, level, encodeType = "flac")) ?: continue
                val row = parseStreamRow(body) ?: continue
                toStream(row, LOSSLESS_REQUEST_KBPS)
                    ?.takeIf { it.isLosslessStream() }
                    ?.let { return@withContext it }
            }
        }
        val wanted = tierFor(maxKbps)
        // 先按要求的档位要，要不到再退到标准档：版权方常常只授权一种码率，而
        // "有 128k 能播"比"320k 没有就整首放弃"好得多。
        for (bitrate in listOf(wanted, FALLBACK_TIER)) {
            val body = get(playerUrl(id, bitrate)) ?: continue
            val row = parseStreamRow(body) ?: continue
            toStream(row, bitrate)?.let { return@withContext it }
        }
        // 最后一层：v1 接口。它按 `level` 要，不按 `br`，对"这首到底授权到哪一档"
        // 的容错更好；同样的曲目在 v0 上一无所获、在 v1 上给标准档的情况是有的。
        for (level in listOf("exhigh", "standard")) {
            val body = get(playerUrlV1(id, level)) ?: continue
            val row = parseStreamRow(body) ?: continue
            toStream(row, FALLBACK_TIER)?.let { return@withContext it }
        }
        Log.w(TAG, "no playable url for song $id")
        null
    }

    /** v1 取地址：按 `level`（lossless / hires / exhigh / standard …）而不是 `br`。 */
    private fun playerUrlV1(id: Long, level: String, encodeType: String = "mp3"): String {
        val csrf = csrfToken()
        return buildString {
            append("$ORIGIN/api/song/enhance/player/url/v1?ids=%5B$id%5D")
            append("&level=").append(level)
            append("&encodeType=").append(encodeType)
            if (csrf.isNotEmpty()) append("&csrf_token=").append(encode(csrf))
        }
    }

    /** 补丁 67：这一条回来的是不是真无损（按容器判断，不看到 320k 就算数）。 */
    private fun Stream.isLosslessStream(): Boolean =
        codec == "flac" || codec == "alac" || codec == "wav" || kbps >= 900

    /** 取地址的完整 URL，登录后带上 `csrf_token`。 */
    private fun playerUrl(id: Long, bitrate: Int): String {
        val csrf = csrfToken()
        return buildString {
            append("$ORIGIN/api/song/enhance/player/url?ids=%5B$id%5D&br=$bitrate")
            if (csrf.isNotEmpty()) append("&csrf_token=").append(encode(csrf))
        }
    }

    /**
     * 当前 cookie 对应的账号，没登录或 cookie 失效时为 null。
     *
     * 设置页用它显示"已登录：某昵称"。失效和没登录在这里是同一件事——两者都只能
     * 靠重新登录解决，而账号行要说的就是这一句。
     */
    /**
     * 一首歌的歌词（LRC 原文）。
     *
     * 补丁 62：这是「能播了但没有歌词」的那一半。App 的联网歌词闸门（补丁 54）
     * 固定关着，网易云的歌又没有本地文件可读内嵌词——两边一夹，界面一片空。
     * 这个词不经过那个闸门：它来自"这首歌所在的那个源"，不是去公开歌词库搜。
     */
    suspend fun lyric(id: Long): String? = withContext(Dispatchers.IO) {
        if (id <= 0) return@withContext null
        val body = get("$ORIGIN/api/song/lyric?id=$id&lv=-1&kv=-1&tv=-1")
            ?: return@withContext null
        decode<LyricEnvelope>(body)?.lrc?.lyric?.takeIf { it.isNotBlank() }
    }

    suspend fun account(): Account? = withContext(Dispatchers.IO) {
        if (NeteaseAuth.cookie.isNullOrBlank()) return@withContext null
        val body = get("$ORIGIN/api/nuser/account/get") ?: return@withContext null
        val found = parseAccount(body)
        // 补丁 67：cookie 里有 MUSIC_U、服务端却给 `account: null`，就是登录已失效。
        // 只标记（设置页据此提示重登），不清 cookie——免费曲目还能放。
        NeteaseAuth.sessionExpired.value = found == null && NeteaseAuth.isSignedIn
        found?.also { account ->
            // 补丁 66：登录态问到 uid 就留一份，喜欢 / 歌单同步不必每次重问。
            if (account.userId > 0) NeteaseAuth.userId = account.userId
        }
    }

    // ── 补丁 65：我的喜欢 / 用户歌单 ──────────────────────────────────────
    //
    // 这三个接口都是明文 `/api/...`，跟搜索、取地址同一套 cookie / csrf 规则。
    // 没登录时 likelist 和 user/playlist 都会回一份访客数据，所以先判
    // NeteaseAuth.isSignedIn 再发请求——否则会把"没登录"渲染成"喜欢列表是空的"。

    /** 用户自己的歌单，以及其中「我喜欢的音乐」那张的 id。 */
    data class UserLibrary(
        val playlists: List<Playlist>,
        /** `specialType = 5` 的那张；打开它就是网易云自己的「我喜欢的音乐」页。 */
        val likedPlaylistId: Long?,
    )

    /**
     * 我喜欢的音乐 id 列表。
     *
     * 返回 null 表示**没问到**（未登录 / 网络失败 / 接口不认），空集合才是真的
     * 一首都没有。调用方必须区分这两者：把 null 当空集合会把一个还没登录的
     * 账号显示成"喜欢列表为空"，并把本地已有的喜欢状态清掉。
     */
    suspend fun likedIds(): Set<Long>? = withContext(Dispatchers.IO) {
        if (!NeteaseAuth.isSignedIn) return@withContext null
        // 补丁 67：`/api/likelist` 与 `/api/v6/likelist` 实测都回
        // `{"code":404,"message":"接口未找到！"}`——这套明文接口上它已经不存在了。
        // 补丁 65/66 一直在这条死路上兜，所以「喜欢」永远同步不过来。
        // 现在从「我喜欢的音乐」这张系统歌单本身取 id：歌单详情只认 cookie。
        val likedId = userLibrary().likedPlaylistId ?: return@withContext null
        // 冷启动只要 id 点亮星形：v6 歌单详情里的 trackIds 本身就是全量，
        // 不为了这一步去取一千首歌的详情（那是资料库那次同步的活）。
        val ids = likedTrackIds(likedId)
        if (ids.isNullOrEmpty()) return@withContext null
        ids.toSet()
    }

    /**
     * 补丁 76：私人漫游（`/api/v1/radio/get`）。
     *
     * 返回的是**一首接一首的推荐流**，不是歌单：匿名也能拿到，登录后按账号口味
     * 排。字段形状与详情行一致（name / artists / album / duration / fee），所以
     * 直接复用 [DetailSong] 与 [toTrack]。
     */
    suspend fun radioTracks(limit: Int = 40): List<Track> = withContext(Dispatchers.IO) {
        val want = limit.coerceIn(1, 60)
        val csrf = csrfToken()
        val url = buildString {
            append("$ORIGIN/api/v1/radio/get?limit=1")
            if (csrf.isNotEmpty()) append("&csrf_token=").append(encode(csrf))
        }
        val seen = LinkedHashMap<Long, Track>()
        // 补丁 82：**只用漫游自己的数据**。补丁 81 拿 `/personalized/newsong`
        // 打底，那正是「猜你喜欢」的数据源——等于把两排歌弄成同一批，他发现的
        // 就是这个。这里回到单曲漫游：每次只要一首，多问几次去重凑一批。
        var guard = 0
        while (seen.size < want && guard < want * 3) {
            guard++
            val body = get(url) ?: break
            parseRadioRows(body).forEach { seen.putIfAbsent(it.id, it) }
        }
        seen.values.toList()
    }

    internal fun parseRadioRows(body: String): List<Track> =
        decode<RadioEnvelope>(body)?.data.orEmpty()
            .filter { it.id > 0 && it.name.isNotBlank() }
            .map { it.toTrack() }

    /** 按 [ids] 的顺序批量取回歌曲详情。分批是为了别把 URL 顶到网关上限。 */
    suspend fun tracksByIds(ids: List<Long>, limit: Int = 200): List<Track> =
        withContext(Dispatchers.IO) {
            val want = ids.asSequence().filter { it > 0 }
                .take(limit.coerceIn(1, 500)).toList()
            if (want.isEmpty()) return@withContext emptyList()
            val details = detailsFor(want)
            inRequestedOrder(want, details).map { it.toTrack() }
        }

    /** v6 歌单详情里的全量 id；只为点亮星形，不取歌曲详情。 */
    private suspend fun likedTrackIds(id: Long): List<Long>? = withContext(Dispatchers.IO) {
        val body = get("$ORIGIN/api/v6/playlist/detail?id=$id") ?: return@withContext null
        val playlist = decode<PlaylistDetailEnvelope>(body)?.playlist
            ?: return@withContext null
        val ids = playlist.trackIds.orEmpty()
            .asSequence().map { it.id }.filter { it > 0 }.distinct().toList()
        ids.ifEmpty { playlist.tracks.orEmpty().map { it.id }.filter { it > 0 } }
            .takeIf { it.isNotEmpty() }
    }

    /**
     * 把 id 切成 [DETAIL_BATCH_SIZE] 一批。抽成纯函数，好让「大歌单不再被服务端
     * 截到 200 首」这条回归规则能直接单测；[detailsFor] 只管并发与合并。
     * 每片保持原顺序、不去重（去重是调用方的事）。
     */
    internal fun detailBatches(
        ids: List<Long>,
        batchSize: Int = DETAIL_BATCH_SIZE,
    ): List<List<Long>> {
        if (ids.isEmpty()) return emptyList()
        return ids.chunked(batchSize.coerceAtLeast(1))
    }

    /** 按请求的 id 顺序挑出已取到的详情；缺哪首跳过哪首，一批失败不拖垮整张歌单。 */
    internal fun <V> inRequestedOrder(ids: List<Long>, found: Map<Long, V>): List<V> =
        ids.mapNotNull { found[it] }

    /**
     * 按 [DETAIL_BATCH_SIZE] 一批并发问详情，结果按 [ids] 的原顺序还原。
     * 一批失败只影响那一批，不会让整张歌单退回空列表。
     */
    private suspend fun detailsFor(ids: List<Long>): Map<Long, DetailSong> = coroutineScope {
        val merged = HashMap<Long, DetailSong>(ids.size * 2)
        detailBatches(ids)
            .chunked(DETAIL_BATCH_PARALLEL)
            .forEach { group ->
                val pages = group.map { batch ->
                    async(Dispatchers.IO) {
                        runCatching {
                            get("$ORIGIN/api/song/detail?ids=${idList(batch)}")
                                ?.let(::parseDetailRows).orEmpty()
                        }.getOrDefault(emptyMap())
                    }
                }.awaitAll()
                pages.forEach { merged.putAll(it) }
            }
        merged
    }

    /** 账号自己的歌单（含置顶的「我喜欢的音乐」）。未登录时给空，不假装成功。 */
    suspend fun userLibrary(): UserLibrary = withContext(Dispatchers.IO) {
        if (!NeteaseAuth.isSignedIn) return@withContext UserLibrary(emptyList(), null)
        // 补丁 67：实测不带 uid 的 `/api/user/playlist` 直接回
        // `{"code":400,"message":"请求参数错误"}`，补丁 66 那条兜底没有任何意义，
        // 只是多一次往返。uid 改由 `AuthStore.neteaseUid` 持久化保证。
        val uid = accountUserId() ?: return@withContext UserLibrary(emptyList(), null)
        val csrf = encode(csrfToken())
        val body = get("$ORIGIN/api/user/playlist?uid=$uid&limit=100&offset=0&csrf_token=$csrf")
            ?: return@withContext UserLibrary(emptyList(), null)
        parseUserLibrary(body)
    }

    /**
     * 喜欢 / 取消喜欢一首歌。
     *
     * `GET /api/like` 是老接口，部分账号只吃表单 POST，所以两条都试：GET 回
     * `code: 200` 就结束，否则再 POST 一次。任一成功即算成功，失败时由调用方
     * 回滚本地心形——setLike 的乐观更新不能停在一个服务端没接受的状态上。
     */
    suspend fun setLiked(id: Long, liked: Boolean): Boolean = withContext(Dispatchers.IO) {
        if (id <= 0 || !NeteaseAuth.isSignedIn) return@withContext false
        val flag = if (liked) "true" else "false"

        // 补丁 83：收藏走 **eapi** 的 `/eapi/song/like`。
        //
        // 这是这次翻开源实现翻出来的关键区别（NeteaseCloudMusicApi 系的两个
        // 模块，DeepWiki 上有逐行摘录）：
        //
        // * `/api/radio/like`（weapi）= **FM 红心**，只影响漫游推荐算法，不进
        //   「我喜欢的音乐」，用户在 App 里根本看不到；
        // * `/api/song/like`（**eapi**）= **收藏**，进「我喜欢的音乐」。载荷是
        //   `{trackId, userid, like}`，加密是 AES-128-ECB + MD5 摘要，跟 weapi
        //   那套 RSA 完全不是一回事。
        //
        // 上一版走的 `/weapi/song/like` 两个都不对：路径不是收藏，算法也写错了
        // （把 16 字节 key 拼成 32 位 hex 当成 AES-256 用）——所以一直"变红又
        // 弹回"。下面先 eapi，再明文歌单操作，最后老接口兜底，每一步都留日志。
        val uid = accountUserId()
        run {
            val payload = """{"trackId":"$id","userid":"${uid ?: 0}","like":$liked}"""
            val body = postForm(
                "/eapi/song/like",
                mapOf("params" to eapiEncrypt("/api/song/like", payload)),
            )?.first
            Log.i(TAG, "like(eapi) $id -> $liked : ${body?.take(160)}")
            if (body != null && parseLikeResult(body)) return@withContext true
        }

        // 兜底一：直接对「我喜欢的音乐」那张歌单做增删。网页版"收藏到歌单"就是
        // 这个接口；加进这张歌单等价于喜欢。
        //
        // 补丁 83：`likedPlaylistId` 是 StateFlow，上一版少了 `.value`，`pid`
        // 实际传的是 `StateFlow{...}` 的 toString —— 接口每次都回 400。
        val pid = NeteaseLikes.likedPlaylistId.value
        if (pid != null && pid > 0) {
            val csrf = encode(csrfToken())
            val body = postForm(
                "/api/playlist/manipulate/tracks" + if (csrf.isNotEmpty()) "?csrf_token=$csrf" else "",
                mapOf(
                    "op" to if (liked) "add" else "del",
                    "pid" to pid.toString(),
                    "trackIds" to "[$id]",
                    "imme" to "true",
                ),
            )?.first
            Log.i(TAG, "like(playlist $pid) $id -> $liked : ${body?.take(160)}")
            if (body != null && parseLikeResult(body)) return@withContext true
        }

        // 兜底二：老的明文 `/api/song/like`（GET/POST 都试，写接口只吃表单）。
        val csrf = encode(csrfToken())
        val legacy = postForm(
            "/api/song/like" + if (csrf.isNotEmpty()) "?csrf_token=$csrf" else "",
            mapOf("id" to id.toString(), "like" to flag),
        )?.first
        Log.i(TAG, "like(legacy) $id -> $liked : ${legacy?.take(160)}")
        legacy != null && parseLikeResult(legacy)
    }

    /**
     * 写接口的统一回执。以前 [setLiked] 只把 code 折成 Boolean，服务端说的
     * 「未登录」「歌单不存在」「歌曲已在歌单里」全被吞掉，UI 只能静默失败。
     */
    data class PlaylistWriteResult(
        val ok: Boolean,
        val code: Int = 0,
        val message: String? = null,
    )

    private fun parseWriteResult(body: String?): PlaylistWriteResult {
        if (body.isNullOrBlank()) return PlaylistWriteResult(false, 0, "服务器没有回应")
        val envelope = decode<CodeEnvelope>(body)
            ?: return PlaylistWriteResult(false, 0, "服务器返回无法解析")
        return PlaylistWriteResult(
            ok = envelope.code == 200,
            code = envelope.code,
            message = envelope.msg ?: envelope.message,
        )
    }

    /**
     * 往任意一张自己的歌单加歌 / 删歌。
     *
     * 明文 `/api/playlist/manipulate/tracks` 就是网页版「收藏到歌单」用的那条，
     * 参数 `op`(add/del) / `pid` / `trackIds`(JSON 数组) / `imme`，登录 cookie 里
     * 的 `__csrf` 要同时写进 query。匿名实测该路由返回 `code:301`（未登录），
     * 说明路由与参数格式都被服务端接受；带 MUSIC_U 后即可写入。
     *
     * 之前 [setLiked] 里那段内联实现把 pid 写死成「我喜欢的音乐」，没法复用。
     */
    suspend fun manipulatePlaylistTracks(
        pid: Long,
        op: String,
        trackIds: List<Long>,
    ): PlaylistWriteResult = withContext(Dispatchers.IO) {
        if (pid <= 0 || trackIds.isEmpty()) {
            return@withContext PlaylistWriteResult(false, 0, "歌单或歌曲无效")
        }
        if (!NeteaseAuth.isSignedIn) {
            return@withContext PlaylistWriteResult(false, 301, "请先登录网易云")
        }
        val csrf = encode(csrfToken())
        val path = "/api/playlist/manipulate/tracks" +
            if (csrf.isNotEmpty()) "?csrf_token=$csrf" else ""
        val ids = trackIds.distinct()
        val form: (String) -> Map<String, String> = { packed ->
            mapOf(
                "op" to op,
                "pid" to pid.toString(),
                "trackIds" to packed,
                "imme" to "true",
            )
        }
        val body = postForm(path, form(ids.joinToString(",", "[", "]")))?.first
        Log.i(TAG, "playlist[$pid] $op ${ids.size} -> ${body?.take(160)}")
        val result = parseWriteResult(body)
        if (result.ok || result.code == 301) return@withContext result
        // 网页版脚本常见的是字符串数组 `["1","2"]`，部分端点只认这种写法。
        val retry = postForm(path, form(ids.joinToString(",", "[", "]") { "\"$it\"" }))?.first
        Log.i(TAG, "playlist[$pid] $op (quoted) -> ${retry?.take(160)}")
        parseWriteResult(retry).takeIf { it.ok } ?: result
    }

    /** 删除自己的一张歌单。两个端点格式不同，任一成功即可。 */
    suspend fun deletePlaylist(pid: Long): PlaylistWriteResult = withContext(Dispatchers.IO) {
        if (pid <= 0) return@withContext PlaylistWriteResult(false, 0, "歌单无效")
        if (!NeteaseAuth.isSignedIn) {
            return@withContext PlaylistWriteResult(false, 301, "请先登录网易云")
        }
        val csrf = encode(csrfToken())
        val suffix = if (csrf.isNotEmpty()) "?csrf_token=$csrf" else ""
        val first = postForm(
            "/api/playlist/delete$suffix",
            mapOf("pid" to pid.toString()),
        )?.first
        val firstResult = parseWriteResult(first)
        Log.i(TAG, "deletePlaylist($pid) delete -> ${first?.take(160)}")
        if (firstResult.ok) return@withContext firstResult
        val second = postForm(
            "/api/playlist/remove$suffix",
            mapOf("ids" to "[$pid]"),
        )?.first
        val secondResult = parseWriteResult(second)
        Log.i(TAG, "deletePlaylist($pid) remove -> ${second?.take(160)}")
        if (secondResult.ok) secondResult else firstResult
    }

    // ── eapi 加密（补丁 83） ──────────────────────────────────────────
    //
    // 网易云移动端的接口：明文是
    //   `<api 路径>-36cd479b6b5-<json>-36cd479b6b5-<大写 md5>`
    // md5 的原文是 `nobody<api 路径>use<json>md5forencrypt`；再用固定 16 字节
    // key 做 AES-128-ECB，输出大写 hex，作为表单里唯一的 `params`。
    //
    // 实测（无登录 cookie）：正确的 params 回 `{"code":301}`，乱填的 params
    // 回空 body —— 说明服务端确实解开并进入了业务层，加密是对的。

    internal fun eapiEncrypt(apiPath: String, payload: String): String {
        val digest = md5Hex("nobody${apiPath}use${payload}md5forencrypt").uppercase()
        val plain = "$apiPath-36cd479b6b5-$payload-36cd479b6b5-$digest"
        val cipher = javax.crypto.Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(
            javax.crypto.Cipher.ENCRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(EAPI_KEY.toByteArray(Charsets.UTF_8), "AES"),
        )
        return cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .uppercase()
    }

    private fun md5Hex(text: String): String =
        java.security.MessageDigest.getInstance("MD5")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    // 补丁 83：weapi 那套（AES-128-CBC + RSA 换 key）已经删掉——收藏走的是
    // eapi，而上一版的 weapi 实现本身也是错的（key 拼成 32 位 hex 当 AES-256
    // 用）。留着只会让人以为那条路已经验证过。

    private suspend fun accountUserId(): Long? =
        NeteaseAuth.userId.takeIf { it > 0 }
            ?: account()?.userId?.takeIf { it > 0 }?.also { NeteaseAuth.userId = it }

    /**
     * 源健康检查：一次最轻的请求，回答"这个源现在能不能用"。
     *
     * 不搜关键词——那会把搜索历史的排序搅乱，也慢。`/api/nuser/account/get`
     * 无论登录与否都回得快。
     */
    suspend fun ping(): Boolean = withContext(Dispatchers.IO) {
        get("$ORIGIN/api/nuser/account/get") != null
    }

    // ── 登录（二维码） ──────────────────────────────────────────────────

    /**
     * 一次扫码的四状态，跟服务端那几个数字一一对应。
     *
     * 这是开源社区的通行做法（`NeteaseCloudMusicApi` 的 `login/qr` 三件套），
     * 不是把 `music.163.com` 塞进一个 WebView：那个站点是桌面版页面，登录表单是
     * 页内弹层，在手机 WebView 里要么渲染不出来、要么点不到——补丁 55 那版的登录页
     * 就是这么"点进去什么也没有"的。二维码这条路只依赖两个 json 接口。
     */
    data class QrState(val code: Int, val message: String, val cookie: String?) {
        val waiting: Boolean get() = code == 801
        val scanned: Boolean get() = code == 802
        val expired: Boolean get() = code == 800
        val granted: Boolean get() = code == 803
    }

    /** 取一个二维码 key。 */
    suspend fun qrKey(): String? = withContext(Dispatchers.IO) {
        val body = get("$ORIGIN/api/login/qrcode/unikey?type=1") ?: return@withContext null
        decode<QrKeyEnvelope>(body)?.unikey?.takeIf { it.isNotBlank() }
    }

    /** 二维码内容。用网易云 App 的扫一扫打开它。 */
    fun qrContent(key: String): String = "$ORIGIN/login?codekey=$key"

    /**
     * 轮询扫码结果。
     *
     * 803 时凭据在响应体的 `cookie` 字段里；少数版本给的是数组形态（`cookies`），
     * 两种都认。**不拿响应头里的 `set-cookie` 兜底**：那是几条零散的域 cookie，
     * 拼起来缺 `__csrf` 这类会员身份要用的项，看着登录了、请求却被当访客。
     */
    suspend fun qrCheck(key: String): QrState = withContext(Dispatchers.IO) {
        val answer = getWithHeaders("$ORIGIN/api/login/qrcode/client/login?key=$key&type=1")
            ?: return@withContext QrState(0, "", null)
        parseQrCheck(answer.first, answer.second)
    }

    /**
     * 轮询响应 → [QrState]。纯函数，单测直接喂真实响应。
     *
     * 凭据三个来源，按可靠程度排：body 里的 `cookie` 串 → body 里的 `cookies`
     * 数组 → `Set-Cookie` 头。前两个是服务端明确给客户端的整串；第三个是浏览器
     * 那套零散域 cookie，拼起来通常也够用，但缺项的风险更高——所以只在前两个都
     * 没有 `MUSIC_U` 时才用它。
     */
    internal fun parseQrCheck(
        body: String,
        setCookies: List<String> = emptyList(),
    ): QrState {
        val env = decode<QrCheckEnvelope>(body) ?: return QrState(0, "", null)
        val fromBody = env.cookie
            ?.let(NeteaseAuth::normalize)
            ?.takeIf(NeteaseAuth::hasSession)
            ?: env.cookies
                ?.joinToString("; ") { it.substringBefore(";") }
                ?.let(NeteaseAuth::normalize)
                ?.takeIf(NeteaseAuth::hasSession)
        val cookie = fromBody ?: cookieFrom(setCookies)
        return QrState(env.code, env.message.orEmpty(), cookie)
    }

    // ── 首页 / 探索的数据 ───────────────────────────────────────────────

    /** 一张榜单或歌单的封面信息。description 只有 /top/playlist 会给，别的接口为空。 */
    data class Playlist(
        val id: Long,
        val name: String,
        val coverUrl: String?,
        val trackCount: Int = 0,
        val description: String? = null,
    )

    /** 每日推荐。要登录：没登录时 `recommend` 是空数组，不是错误。 */
    suspend fun dailyRecommend(): List<Track> = withContext(Dispatchers.IO) {
        val body = get("$ORIGIN/api/v1/discovery/recommend/songs") ?: return@withContext emptyList()
        decode<RecommendEnvelope>(body)?.recommend.orEmpty().map { it.toTrack() }
    }

    /** 推荐新歌（首页「猜你喜欢」）。不登录也有。 */
    suspend fun recommendedNewSongs(limit: Int = 30): List<Track> = withContext(Dispatchers.IO) {
        val body = get("$ORIGIN/api/personalized/newsong?limit=${limit.coerceIn(1, 50)}")
            ?: return@withContext emptyList()
        decode<NewsongEnvelope>(body)?.result.orEmpty()
            .mapNotNull { it.song }
            .map { it.toTrack() }
    }

    /** 排行榜目录（飙升榜 / 新歌榜 / 热歌榜 …，实测 65 个）。 */
    suspend fun toplists(): List<Playlist> = withContext(Dispatchers.IO) {
        val body = get("$ORIGIN/api/toplist") ?: return@withContext emptyList()
        decode<ToplistEnvelope>(body)?.list.orEmpty()
            .filter { it.id > 0 && it.name.isNotBlank() }
            .map {
                Playlist(
                    it.id,
                    it.name,
                    it.coverUrl?.let(::https)?.takeIf(String::isNotBlank),
                )
            }
    }

    /** 推荐歌单，探索页当分类格子用。 */
    suspend fun personalizedPlaylists(
        limit: Int = 12,
        /** 分类名（民谣 / 流行 / 摇滚 …）。空就是"推荐"，不是某个分类。 */
        category: String? = null,
    ): List<Playlist> = withContext(Dispatchers.IO) {
        val cat = category?.takeIf { it.isNotBlank() }
        val url = buildString {
            append("$ORIGIN/api/personalized/playlist?limit=").append(limit.coerceIn(1, 30))
            if (cat != null) append("&cat=").append(encode(cat))
        }
        val body = get(url) ?: return@withContext emptyList()
        decode<PersonalizedEnvelope>(body)?.result.orEmpty()
            .filter { it.id > 0 && it.name.isNotBlank() }
            .map {
                Playlist(
                    it.id,
                    it.name,
                    it.coverUrl?.let(::https)?.takeIf(String::isNotBlank),
                    it.trackCount,
                )
            }
    }

    /**
     * 补丁 64：按**分类**取歌单（首页分类封面 + 歌单广场）。
     *
     * `/api/personalized/playlist` 会忽略 `cat` 参数——补丁 63 用它给六个分类
     * 各取第一张封面，结果六张卡全是同一张图。真正按分类筛的是
     * `/api/top/playlist`：`cat` 生效、匿名可用、`order=hot` 让第一张就是该分类
     * 最热的歌单，而且每项自带 `description`（歌单广场列表要用）。
     */
    suspend fun topPlaylists(
        category: String? = null,
        limit: Int = 30,
    ): List<Playlist> = withContext(Dispatchers.IO) {
        val cat = category?.takeIf { it.isNotBlank() }
        val url = buildString {
            append("$ORIGIN/api/top/playlist?order=hot&limit=")
                .append(limit.coerceIn(1, 50))
            if (cat != null) append("&cat=").append(encode(cat))
        }
        val body = get(url) ?: return@withContext emptyList()
        parseTopPlaylists(body)
    }

    /** 纯函数解析，测试直接喂样本、不发网络（跟 parseSearchRows 同一口径）。 */
    internal fun parseTopPlaylists(body: String): List<Playlist> =
        decode<TopPlaylistEnvelope>(body)?.playlists.orEmpty()
            .filter { it.id > 0 && it.name.isNotBlank() }
            .map {
                Playlist(
                    id = it.id,
                    name = it.name,
                    coverUrl = it.coverUrl?.let(::https)?.takeIf(String::isNotBlank),
                    trackCount = it.trackCount,
                    description = it.description?.takeIf(String::isNotBlank),
                )
            }

    /**
     * 补丁 64：给一排分类各挑一个封面，id 与封面地址都不重复。
     *
     * 服务端万一又忽略 `cat`（补丁 63 就是这么翻车的），六个桶会装着同一批歌单；
     * 这里按分类顺序往下找第一张没用过的，就不会再出现「六张封面一模一样」。
     * 返回 `List<Playlist?>` 而不是 `mapNotNull`，是为了跟分类下标一一对齐。
     */
    internal fun pickDistinctPlaylists(buckets: List<List<Playlist>>): List<Playlist?> {
        val usedIds = mutableSetOf<Long>()
        val usedCovers = mutableSetOf<String>()
        return buckets.map { candidates ->
            val picked = candidates.firstOrNull { candidate ->
                candidate.id !in usedIds &&
                    (candidate.coverUrl == null || candidate.coverUrl !in usedCovers)
            } ?: candidates.firstOrNull()
            if (picked != null) {
                usedIds += picked.id
                picked.coverUrl?.let { usedCovers += it }
            }
            picked
        }
    }

    /** 一个歌单 / 榜单的全部信息，够画一整个详情页。 */
    data class PlaylistDetail(
        val id: Long,
        val name: String,
        val coverUrl: String?,
        val description: String?,
        val playCount: Long,
        val trackCount: Int,
        val tracks: List<Track>,
    )

    /**
     * 歌单详情页要的全部东西：名字、封面、简介、播放量、歌曲。
     *
     * 与 [playlistTracks] 同一条两步路（v6 拿 id 列表，批量问详情），区别只是
     * 这里把 v6 那一层的元信息也留下来——详情页的头部全靠它，光有歌曲列表画不出
     * 他给的那种样子。
     */
    suspend fun playlistFull(id: Long, limit: Int = Int.MAX_VALUE): PlaylistDetail? =
        withContext(Dispatchers.IO) {
            if (id <= 0) return@withContext null
            val body = get("$ORIGIN/api/v6/playlist/detail?id=$id") ?: return@withContext null
            val playlist = decode<PlaylistDetailEnvelope>(body)?.playlist
                ?: return@withContext null
            val want = playlist.trackIds.orEmpty()
                .asSequence()
                .map { it.id }
                .filter { it > 0 }
                .take(limit)
                .toList()
            // 以前整张歌单的 id 拼一条 URL，服务端只回前 200 首；
            // 现在按 100 一批并发取，歌单有多少首就显示多少首。
            val details = if (want.isEmpty()) {
                emptyMap<Long, DetailSong>()
            } else {
                detailsFor(want)
            }
            val tracks = if (want.isEmpty()) {
                playlist.tracks.orEmpty().map { it.toTrack() }
            } else {
                inRequestedOrder(want, details).map { it.toTrack() }
            }
            PlaylistDetail(
                id = playlist.id,
                name = playlist.name.ifBlank { "歌单" },
                coverUrl = playlist.coverUrl?.let(::https)?.takeIf(String::isNotBlank),
                description = playlist.description?.takeIf(String::isNotBlank),
                playCount = playlist.playCount,
                trackCount = playlist.trackCount.takeIf { it > 0 } ?: tracks.size,
                tracks = tracks,
            )
        }

    /**
     * 一个榜单 / 歌单里的歌。
     *
     * 两步：`v6/detail` 给 `trackIds`（全量 id）与前 10 首的完整对象，然后按前
     * [limit] 个 id 批量问一次详情。只用 v6 的话，一个 100 首的榜单只能播前 10 首，
     * 后 90 个连名字都没有。
     */
    suspend fun playlistTracks(id: Long, limit: Int = 40): List<Track> =
        withContext(Dispatchers.IO) {
            if (id <= 0) return@withContext emptyList()
            val body = get("$ORIGIN/api/v6/playlist/detail?id=$id")
                ?: return@withContext emptyList()
            val playlist = decode<PlaylistDetailEnvelope>(body)?.playlist
                ?: return@withContext emptyList()
            val want = playlist.trackIds.orEmpty()
                .asSequence()
                .map { it.id }
                .filter { it > 0 }
                .take(limit)
                .toList()
            if (want.isEmpty()) return@withContext playlist.tracks.orEmpty().map { it.toTrack() }
            val details = runCatching {
                get("$ORIGIN/api/song/detail?ids=${idList(want)}")?.let(::parseDetailRows).orEmpty()
            }
                .onFailure { Log.w(TAG, "playlist detail batch failed: ${it.message}") }
                .getOrDefault(emptyMap())
            inRequestedOrder(want, details).map { it.toTrack() }
        }

    /**
     * 一张歌单（含「我喜欢的音乐」）的**全量**曲目。
     *
     * [playlistTracks] 是给首页预览用的，只取前几十首；
     * 这里给「我的喜欢」和歌单详情页用。v6/detail 的 trackIds 是全量，
     * 详情按 [DETAIL_BATCH_SIZE] 一批并发取，不再把几百个 id 塞进一条 URL。
     */
    suspend fun playlistTracksAll(id: Long): List<Track> = withContext(Dispatchers.IO) {
        if (id <= 0) return@withContext emptyList()
        val body = get("$ORIGIN/api/v6/playlist/detail?id=$id")
            ?: return@withContext emptyList()
        val playlist = decode<PlaylistDetailEnvelope>(body)?.playlist
            ?: return@withContext emptyList()
        val want = playlist.trackIds.orEmpty()
            .asSequence().map { it.id }.filter { it > 0 }.distinct().toList()
        if (want.isEmpty()) return@withContext playlist.tracks.orEmpty().map { it.toTrack() }
        val details = detailsFor(want)
        inRequestedOrder(want, details).map { it.toTrack() }
    }

    // ── 解析（纯函数，单测直接调） ──────────────────────────────────────

    /** 搜索响应体 → 可用的行。缺 id 或缺曲名的行是接口的噪音，不要。 */
    internal fun parseSearchRows(body: String): List<SearchSong> =
        decode<SearchEnvelope>(body)
            ?.result?.songs
            .orEmpty()
            .filter { it.id > 0 && it.name.isNotBlank() }

    /** 批量详情响应体 → 按 id 索引。 */
    internal fun parseDetailRows(body: String): Map<Long, DetailSong> =
        decode<DetailEnvelope>(body)
            ?.songs
            .orEmpty()
            .filter { it.id > 0 }
            .associateBy { it.id }

    /** 取地址响应体 → 第一行。为空数组时是 null。 */
    internal fun parseStreamRow(body: String): UrlRow? =
        decode<UrlEnvelope>(body)?.data?.firstOrNull()

    /** 账号响应体 → 账号。没有 `profile` 就是没登录。 */
    internal fun parseAccount(body: String): Account? {
        val envelope = decode<AccountEnvelope>(body) ?: return null
        val profile = envelope.profile ?: return null
        val nickname = profile.nickname?.takeIf { it.isNotBlank() } ?: return null
        return Account(
            nickname = nickname,
            userId = profile.userId ?: envelope.account?.id ?: 0L,
            avatarUrl = profile.avatarUrl?.takeIf { it.isNotBlank() },
        )
    }

    /** 补丁 65： likelist 的纯解析，单测直接喂响应体。 */
    internal fun parseLikedIds(body: String): Set<Long> =
        decode<LikelistEnvelope>(body)?.ids.orEmpty()
            .filter { it > 0 }
            .toSet()

    /** 补丁 65： `/api/user/playlist` → 卡片用的歌单 + 我喜欢的音乐那张的 id。 */
    internal fun parseUserLibrary(body: String): UserLibrary {
        val rows = decode<UserPlaylistEnvelope>(body)?.playlist.orEmpty()
            .filter { it.id > 0 && it.name.isNotBlank() }
        val playlists = rows.map {
            Playlist(
                id = it.id,
                name = it.name,
                coverUrl = it.coverUrl?.let(::https)?.takeIf(String::isNotBlank),
                trackCount = it.trackCount,
                description = it.description?.takeIf(String::isNotBlank),
            )
        }
        // 服务端把「我喜欢的音乐」标成 specialType = 5；老版本偶尔不给这个
        // 字段，就按名字兜一次，别让资料库里那张入口卡莫名其妙消失。
        val likedId = rows.firstOrNull { it.specialType == 5 }?.id
            ?: rows.firstOrNull { it.name.endsWith("喜欢的音乐") }?.id
        return UserLibrary(playlists, likedId)
    }

    /** 补丁 65： /api/like 的成功判据。只有 200 算数，301 之类是没登录。 */
    internal fun parseLikeResult(body: String): Boolean =
        decode<CodeEnvelope>(body)?.code == 200

    /**
     * 搜索行 + 详情 → 界面要的行。
     *
     * 详情缺失时整行退回搜索接口给的字段，而不是丢行：搜索接口有曲名和 id，够放，
     * 只是没有封面和专辑名。
     */
    internal fun assemble(
        rows: List<SearchSong>,
        details: Map<Long, DetailSong>,
    ): List<Track> = rows.map { row ->
        val detail = details[row.id]
        Track(
            id = row.id,
            title = detail?.name?.takeIf { it.isNotBlank() } ?: row.name,
            artist = (detail?.artists ?: row.artists)
                .mapNotNull { it.name?.takeIf(String::isNotBlank) }
                .joinToString("/")
                .ifBlank { UNKNOWN_ARTIST },
            album = (detail?.album?.name ?: row.album?.name)?.takeIf { it.isNotBlank() },
            // 封面地址是 http://，而 App 关着明文流量。搜索结果这条组装路径
            // 以前漏了 https()，歌能播但封面永远是白块，写进听歌记录后再导入
            // 也不会被补正（备份链路不走网络解析）。
            artwork = detail?.album?.picUrl?.let(::https)?.takeIf { it.isNotBlank() },
            durationMs = detail?.duration?.takeIf { it > 0 } ?: row.duration,
            fee = detail?.fee ?: row.fee,
        )
    }

    /**
     * 取地址行 → 可播放的地址，不可播放时 null。
     *
     * 两条拒绝理由，都在这里集中处理，不散到调用方：
     * - `freeTrial` 非空：只有试听片段（见类注释第 2 条）。
     * - `url` 为空或 `code` 不是 200：接口说这首没有。
     *
     * @param requested 请求时用的码率档，用于响应没报 `br` 时兜底。
     */
    internal fun toStream(row: UrlRow, requested: Int): Stream? {
        if (row.freeTrial != null) {
            Log.d(TAG, "netease row ${row.code} is a trial fragment only; treating as a miss")
            return null
        }
        // 补丁 61：原来要求 `code == 200`，而有地址的响应里这个字段有时是 0 或
        // 干脆没有。有 url 就用它——没有地址的情况下面这一句自己会拦住。
        val playable = row.url?.takeIf { it.isNotBlank() } ?: return null
        val codec = row.type?.lowercase()?.takeIf(String::isNotBlank) ?: "mp3"
        return Stream(
            url = https(playable),
            // 补丁 67：`requested` 现在可能是无损哨兵 999，直接除会得到 0kbps。
            // 响应没给 br 时，flac 按 900 记，其余按请求档位夹在 128~320 之间。
            kbps = if (row.br > 0) {
                row.br / 1000
            } else if (codec == "flac" || codec == "alac") {
                900
            } else {
                requested.coerceIn(128_000, 320_000) / 1000
            },
            codec = codec,
            durationSec = row.durationMs?.takeIf { it > 0 }?.let { (it / 1000).toInt() },
        )
    }

    /**
     * 详情行 → 界面行。
     *
     * 每日推荐、推荐新歌、歌单详情拿回来的都是「详情」那种结构（`artists` /
     * `album` / `duration` / `fee`），跟搜索接口的短字段不同，所以单独一条转换，
     * 不复用 [assemble]（它要的是两步合并的搜索结果）。
     */
    internal fun DetailSong.toTrack(): Track = Track(
        id = id,
        title = name,
        artist = artists.mapNotNull { it.name?.takeIf(String::isNotBlank) }
            .joinToString("/")
            .ifBlank { UNKNOWN_ARTIST },
        album = album?.name?.takeIf { it.isNotBlank() },
        // 补丁 61：封面地址也是 http://，而 App 关着明文流量——不改成 https，
        // Coil 直接加载失败，界面上一排白方块（他截图里的样子）。
        artwork = album?.picUrl?.let(::https)?.takeIf { it.isNotBlank() },
        durationMs = duration,
        fee = fee,
    )

    /**
     * 登录态请求要带的 `csrf_token`。
     *
     * 补丁 61：这是「搜得到、听不了」的原因。`/api/song/enhance/player/url` 在
     * **匿名**时不需要它（所以补丁 55 的实测是通的），但带着 `MUSIC_U` 登录之后
     * 就要求带上，否则服务端只回一个 `url: null`——搜得到、一点就放不出来。
     *
     * 值就是 cookie 里 `__csrf` 那一条。没登录时返回空串，调用方不加这个参数。
     */
    private fun csrfToken(): String =
        NeteaseAuth.cookie.orEmpty()
            .split(';')
            .map(String::trim)
            .firstOrNull { it.substringBefore('=', "").trim() == "__csrf" }
            ?.substringAfter('=', "")
            ?.trim()
            .orEmpty()

    /** `http://` 改 `https://`，别的原样返回。见类注释第 3 条。 */
    internal fun https(url: String): String =
        if (url.startsWith("http://", ignoreCase = true)) "https://" + url.substring(7) else url

    /**
     * 码率上限对应的档位。
     *
     * 只在这三档里选：网易云按 `br` 给的是固定的几档转码，填一个中间值不会得到
     * 中间值，只会被就近取整——不如自己取，至少知道拿到的是什么。
     */
    internal fun tierFor(maxKbps: Int): Int = when {
        maxKbps >= 320 -> 320_000
        maxKbps >= 192 -> 192_000
        else -> 128_000
    }

    // ── 内部 ────────────────────────────────────────────────────────────

    private inline fun <reified T> decode(body: String): T? = runCatching {
        json.decodeFromString<T>(body)
    }
        .onFailure { Log.w(TAG, "unreadable ${T::class.simpleName}: ${it.message}") }
        .getOrNull()

    /** `[1,2,3]`，URL 编码过的方括号。 */
    private fun idList(ids: List<Long>): String =
        ids.joinToString(",", prefix = "%5B", postfix = "%5D")

    /**
     * 一个 GET，把响应头也带回来。
     *
     * 扫码 803 的凭据有时候只在 `Set-Cookie` 里、body 里那个 `cookie` 字段是空的。
     * 补丁 59 之前只读 body，于是"授权成功了却显示二维码获取失败"——网易云那边已经
     * 登录，这边一个字节都没接住。
     */
    private fun getWithHeaders(url: String): Pair<String, List<String>>? = try {
        val request = Request.Builder()
            .url(url)
            .header("Referer", "$ORIGIN/")
            .header("User-Agent", USER_AGENT)
            .header("Cookie", cookieHeader())
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "HTTP ${response.code} from ${url.substringBefore('?')}")
                null
            } else {
                response.body.string() to response.headers.values("Set-Cookie")
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "request failed: ${e.message}")
        null
    }

    private fun get(url: String): String? = try {
        val request = Request.Builder()
            .url(url)
            .header("Referer", "$ORIGIN/")
            .header("User-Agent", USER_AGENT)
            .header("Cookie", cookieHeader())
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "HTTP ${response.code} from ${url.substringBefore('?')}")
                null
            } else {
                response.body.string().takeIf { it.isNotBlank() }
            }
        }
    } catch (e: Exception) {
        // 网络类异常在这里全部吞掉：音源是一个可选来源，它挂了不该让搜索失败。
        Log.w(TAG, "request failed: ${e.message}")
        null
    }

    /**
     * 请求要带的 cookie。
     *
     * 登录过就用浏览器里抓到的那份——里面除了 `MUSIC_U` 还有 `__csrf` 等，缺了
     * 会员身份就不成立。没登录时给一份最小匿名 cookie。
     */
    private fun cookieHeader(): String {
        // 补丁 59：`AuthStore` 是设备号的正常来源，但它在 Application 里初始化，
        // 而网络层可能在它之前就被用到（单元测试、播放服务早启动的路径）。空着
        // 就等于把请求退回到 -460，所以这里补一个——生成一次就留在内存里。
        if (NeteaseAuth.deviceId.isBlank()) {
            NeteaseAuth.deviceId = java.util.UUID.randomUUID().toString().replace("-", "")
        }
        // 补丁 61：`os` / `appver` **必须一直在**，登录前后都一样。上一版只在没有
        // 登录 cookie 时才补这两个，登录后整串用的就是服务端返回的那份——里面没有
        // 客户端身份，取播放地址时被当成来源不明，只回一个空 url。这跟 `-460` 是
        // 同一类问题：网易云认的是"一套自洽的客户端"，不是"有 cookie 就行"。
        return buildString {
            val session = NeteaseAuth.cookie?.takeIf { it.isNotBlank() }
            if (session != null) append(session)
            if (isEmpty() || !contains("os=")) {
                if (isNotEmpty()) append("; ")
                append("os=pc")
            }
            if (!contains("appver=")) append("; appver=2.9.7")
            val device = NeteaseAuth.deviceId
            if (device.isNotBlank() && !contains("_ntes_nuid=")) {
                append("; _ntes_nuid=").append(device)
            }
        }
    }

    /** 表单请求的 Content-Type。登录那几个接口只吃 urlencoded。 */
    private val FORM = "application/x-www-form-urlencoded".toMediaType()

    /**
     * 一个表单 POST，回响应体与每一条 `Set-Cookie`。
     *
     * 必须把 header 带回来：登录成功的凭据只在响应头里，[get] 只回 body。
     */
    private fun postForm(
        path: String,
        params: Map<String, String>,
    ): Pair<String, List<String>>? = try {
        val form = params.entries
            .joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
            .toRequestBody(FORM)
        val request = Request.Builder()
            .url("$ORIGIN$path")
            .header("Referer", "$ORIGIN/")
            .header("User-Agent", USER_AGENT)
            .header("Cookie", cookieHeader())
            .post(form)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "HTTP ${response.code} from $path")
                null
            } else {
                response.body.string() to response.headers.values("Set-Cookie")
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "POST $path failed: ${e.message}")
        null
    }

    /**
     * 发短信验证码。
     *
     * 参数名是 `cellphone` 不是 `phone`——网易云改过这个接口，用 `phone` 一律回
     * `400 参数错误`。`secret` 那一项传的是 RSA 那个模数，社区实现里也这么传。
     */
    suspend fun sendCaptcha(phone: String): SendResult = withContext(Dispatchers.IO) {
        val res = postForm(
            "/api/sms/captcha/sent",
            mapOf("cellphone" to phone, "ctcode" to "86", "secret" to RSA_MODULUS),
        ) ?: return@withContext SendResult(false, "没有响应，检查网络")
        val envelope = decode<CodeEnvelope>(res.first)
            ?: return@withContext SendResult(false, "服务端返回了看不懂的内容")
        if (envelope.code == 200) {
            SendResult(true, null)
        } else {
            // 服务端的中文原话比一个"失败"有用得多：`-460` 是风控、`400` 是手机号
            // 格式、`503` 是发得太频繁，三者的处理方式完全不同。
            SendResult(false, envelope.msg ?: "发送失败（${envelope.code}）")
        }
    }

    /** 发码的结果：成功，或者一句能直接显示给用户的话。 */
    data class SendResult(val ok: Boolean, val message: String?)

    /** 一次登录的结果：成功给 cookie，失败给一句能显示的话。 */
    data class LoginResult(val cookie: String?, val nickname: String?, val message: String?)

    /**
     * 手机号 + 短信验证码登录。
     *
     * 走明文接口（实测可用，不需要 `weapi` 加密），凭据从 `Set-Cookie` 里拼出来——
     * 少了 `__csrf` 之类，后面每个请求都会被当访客答，看着登录了、其实没登。
     */
    suspend fun loginWithCaptcha(phone: String, captcha: String): LoginResult =
        withContext(Dispatchers.IO) {
            val res = postForm(
                "/api/login/cellphone",
                mapOf(
                    "phone" to phone,
                    "captcha" to captcha,
                    "countrycode" to "86",
                    "rememberLogin" to "true",
                ),
            ) ?: return@withContext LoginResult(null, null, "没有响应，检查网络")
            val envelope = decode<LoginEnvelope>(res.first)
            if (envelope?.code != 200) {
                val fallback = "登录失败（${envelope?.code ?: 0}）"
                return@withContext LoginResult(
                    null, null, envelope?.message ?: envelope?.msg ?: fallback,
                )
            }
            val cookie = cookieFrom(res.second)
                ?: envelope.cookie?.let(NeteaseAuth::normalize)
            if (!NeteaseAuth.hasSession(cookie)) {
                return@withContext LoginResult(null, null, "服务端没有返回登录凭据")
            }
            LoginResult(cookie, envelope.profile?.nickname, null)
        }

    /**
     * 把 `Set-Cookie` 的每一条拼成一段 cookie 头。
     *
     * 只取 `name=value`：那些 `Path` / `Domain` / `Expires` 是给浏览器看的，拼进
     * 请求头没有意义。值等于 `deleted` 的是删除标记，不能当成凭据。
     */
    internal fun cookieFrom(setCookies: List<String>): String? {
        val parts = setCookies.mapNotNull { raw ->
            val name = raw.substringBefore('=').trim()
            val value = raw.substringAfter('=', "").substringBefore(';').trim()
            when {
                name.isEmpty() || value.isEmpty() -> null
                value.equals("deleted", ignoreCase = true) -> null
                else -> "$name=$value"
            }
        }
        return NeteaseAuth.normalize(parts.joinToString("; "))
    }

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8")

    private const val TAG = "BitChord"

    /** 要不到指定档位时退到这一档（网易云最低、覆盖最广的一档）。 */
    private const val FALLBACK_TIER = 128_000

    /**
     * 补丁 67：`maxKbps` 大于等于它就算「要无损」，先走 v1 的 lossless 档。
     *
     * 跟 [NeteaseSource.LOSSLESS_TIER_KBPS] 同一个哨兵值；分开写是因为网络层
     * 不该 import 音源实现，两边靠这条注释对齐。
     */
    internal const val LOSSLESS_REQUEST_KBPS = 999

    /** 无损先要 lossless，拿不到再要 hires——后者是 QQ/网易云会员的最高档。 */
    private val LOSSLESS_LEVELS = listOf("lossless", "hires")

    /**
     * RSA 模数，只在发验证码时作为 `secret` 字段传过去。
     *
     * 它同时也是 `weapi` 加密用的那个模数，但本 App 的登录走明文接口，所以这里
     * 只有这一个用途。值来自社区实现，是网易云前端写死的一个常量。
     */
    /** 补丁 83：eapi 的固定 AES-128 key，公开常量，别改。 */
    private const val EAPI_KEY = "e82ckenh8dichen8"

    private const val RSA_MODULUS =
        "00e0b509f6259df8642dbc35662901477df22677ec152b5ff68ace615bb7b725152b3ab17a876aea8a5aa76" +
            "d2e417629ec4ee341f56135fccf695280104e0312ecbda92557c93870114af6c9d05c4f7f0c3685b7a46bee2" +
            "55932575cce10b424d813cfe4875d3e82047b97ddef52741d546b8e289dc6935b3ece0462db0a22b8e7"
    internal const val UNKNOWN_ARTIST = "未知艺人"

    // ── 线上格式 ────────────────────────────────────────────────────────
    //
    // 只声明用得到的字段。`ignoreUnknownKeys` 让服务端加字段不需要改这里。
    // internal 而不是 private：解析函数是 internal 的，返回类型不能更严。

    @Serializable
    internal data class SearchEnvelope(val result: SearchResult? = null)

    @Serializable
    internal data class SearchResult(val songs: List<SearchSong> = emptyList())

    @Serializable
    internal data class SearchSong(
        val id: Long = 0,
        val name: String = "",
        val duration: Long = 0,
        val fee: Int = 0,
        val artists: List<Credit> = emptyList(),
        val album: AlbumRef? = null,
    )

    @Serializable
    internal data class Credit(val name: String? = null)

    /** 搜索行里的专辑只有 `picId`，没有 `picUrl`——见 [search]。 */
    @Serializable
    internal data class AlbumRef(val name: String? = null)

    @Serializable
    internal data class DetailEnvelope(val songs: List<DetailSong> = emptyList())

    @Serializable
    internal data class DetailSong(
        val id: Long = 0,
        val name: String = "",
        val duration: Long = 0,
        val fee: Int = 0,
        val artists: List<Credit> = emptyList(),
        val album: DetailAlbum? = null,
    )

    @Serializable
    internal data class DetailAlbum(val name: String? = null, val picUrl: String? = null)

    @Serializable
    internal data class UrlEnvelope(val data: List<UrlRow> = emptyList())

    @Serializable
    internal data class UrlRow(
        val url: String? = null,
        val br: Int = 0,
        val code: Int = 0,
        val type: String? = null,
        /**
         * 非空即"这条只能试听"。类型是 [JsonObject] 而不是具体的类：这里只关心它
         * 在不在，字段形状无关，用具体类反而会因为某天多一个字段就要改。
         */
        @SerialName("freeTrialInfo") val freeTrial: JsonObject? = null,
        /** 时长（毫秒）。部分响应里没有，所以可空。 */
        @SerialName("duration") val durationMs: Long? = null,
    )

    @Serializable
    internal data class AccountEnvelope(
        val account: AccountRef? = null,
        val profile: Profile? = null,
    )

    @Serializable
    internal data class AccountRef(val id: Long? = null)

    @Serializable
    internal data class Profile(
        val nickname: String? = null,
        val userId: Long? = null,
        val avatarUrl: String? = null,
    )

    // ── 二维码登录 ──────────────────────────────────────────────────────

    @Serializable
    internal data class CodeEnvelope(
        val code: Int = 0,
        val msg: String? = null,
        val message: String? = null,
    )

    @Serializable
    internal data class LyricEnvelope(val lrc: LyricBody? = null)

    @Serializable
    internal data class LyricBody(val lyric: String? = null)

    @Serializable
    internal data class LoginEnvelope(
        val code: Int = 0,
        val message: String? = null,
        val msg: String? = null,
        /** 有些版本在 body 里也带一份凭据，优先用 `Set-Cookie`。 */
        val cookie: String? = null,
        val profile: Profile? = null,
    )

    @Serializable
    internal data class QrKeyEnvelope(val code: Int = 0, val unikey: String? = null)

    @Serializable
    internal data class QrCheckEnvelope(
        val code: Int = 0,
        val message: String? = null,
        /** 803 时这里是整串 cookie（`MUSIC_U=…; __csrf=…`）。 */
        val cookie: String? = null,
        /** 少数版本给数组形态，两种都认——见 [qrCheck]。 */
        val cookies: List<String>? = null,
    )

    // ── 首页 / 探索 ─────────────────────────────────────────────────────

    @Serializable
    internal data class RecommendEnvelope(
        val code: Int = 0,
        /** 每日推荐：数组元素就是 song 对象本身，不是包一层。 */
        val recommend: List<DetailSong> = emptyList(),
    )

    @Serializable
    internal data class NewsongEnvelope(val result: List<NewsongRow> = emptyList())

    @Serializable
    internal data class NewsongRow(val song: DetailSong? = null)

    @Serializable
    internal data class ToplistEnvelope(val list: List<ToplistRow> = emptyList())

    @Serializable
    internal data class ToplistRow(
        val id: Long = 0,
        val name: String = "",
        @SerialName("coverImgUrl") val coverUrl: String? = null,
    )

    @Serializable
    internal data class PersonalizedEnvelope(
        val result: List<PersonalizedRow> = emptyList(),
    )

    @Serializable
    internal data class PersonalizedRow(
        val id: Long = 0,
        val name: String = "",
        @SerialName("picUrl") val coverUrl: String? = null,
        @SerialName("trackCount") val trackCount: Int = 0,
    )

    /** 补丁 64：/top/playlist 的返回体（按分类取歌单）。 */
    @Serializable
    internal data class TopPlaylistEnvelope(
        val playlists: List<TopPlaylistRow> = emptyList(),
    )

    @Serializable
    internal data class TopPlaylistRow(
        val id: Long = 0,
        val name: String = "",
        @SerialName("coverImgUrl") val coverUrl: String? = null,
        val description: String? = null,
        @SerialName("trackCount") val trackCount: Int = 0,
    )

    /** 补丁 65： `/api/likelist` 的返回体。其它字段一律忽略。 */
    /** 补丁 76：私人漫游的返回体，`data` 里是详情形状的歌曲。 */
    @Serializable
    internal data class RadioEnvelope(val data: List<DetailSong> = emptyList())

    @Serializable
    internal data class LikelistEnvelope(val ids: List<Long> = emptyList())

    /** 补丁 65： `/api/user/playlist` 的返回体（字段名是单数 `playlist`）。 */
    @Serializable
    internal data class UserPlaylistEnvelope(
        val playlist: List<UserPlaylistRow> = emptyList(),
    )

    @Serializable
    internal data class UserPlaylistRow(
        val id: Long = 0,
        val name: String = "",
        @SerialName("coverImgUrl") val coverUrl: String? = null,
        @SerialName("trackCount") val trackCount: Int = 0,
        val description: String? = null,
        /** 5 = 「我喜欢的音乐」这张系统歌单。 */
        @SerialName("specialType") val specialType: Int = 0,
    )

    @Serializable
    internal data class PlaylistDetailEnvelope(val playlist: PlaylistBody? = null)

    @Serializable
    internal data class PlaylistBody(
        val id: Long = 0,
        val name: String = "",
        /** v6 只带前 10 首的完整对象；后面的在 [trackIds] 里。 */
        val tracks: List<DetailSong>? = null,
        @SerialName("trackIds") val trackIds: List<TrackId>? = null,
        @SerialName("coverImgUrl") val coverUrl: String? = null,
        val description: String? = null,
        @SerialName("playCount") val playCount: Long = 0,
        @SerialName("trackCount") val trackCount: Int = 0,
    )

    @Serializable
    internal data class TrackId(val id: Long = 0)
}
