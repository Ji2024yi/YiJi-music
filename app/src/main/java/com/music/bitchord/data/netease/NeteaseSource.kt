package com.music.bitchord.data.netease

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.MusicSource
import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceHealth
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.data.sources.StreamFormat
import com.music.bitchord.data.sources.StreamRequest
import java.util.Locale

/**
 * 网易云音乐作为一个音源。
 *
 * 之后 YouTube 已经从这台机器上清掉（见
 * [SourceRegistry.sourcesForInit][com.music.bitchord.data.sources.SourceRegistry]），
 * 所以下面这段"排在 YouTube 之后"的排序考量实际已不生效——它留在 `rank` 上只是
 * 为了不因为一次删除而改变枚举的相对顺序。真正决定它在哪出现的，现在只有一处：
 * 搜索页的「在线源」栏（[com.music.bitchord.ui.MainViewModel] 的 `runAddonSearch`
 * 问的是所有启用的、非 YouTube 的源）。
 *
 * 它不能做的两件事，都不打算假装能做：
 *
 * **会员曲目**。接口只给 30 秒试听，[NeteaseApi.stream] 判成未命中，交回给
 *   [SourceResolver] 去找别的源。所以点一条 VIP 曲目通常还是有声的，只是从别处来。
 * **无损**。 起真的去要：`/api/song/enhance/player/url/v1` 的
 *   `level=lossless&encodeType=flac` 对会员曲目会给 FLAC，拿到了
 *   [StreamFormat.isLossless][com.music.bitchord.data.sources.StreamFormat.isLossless]
 *   就是真，播放页那颗徽章会显示「无损」而不是「高品质」。非会员 / 该曲没有
 *   无损时服务端会回一份 MP3，这里判成低于要求、连同 `belowRequest` 一起交出去，
 *   再由 [SourceResolver] 决定是留着播还是找别的源——比整首放弃强。
 */
class NeteaseSource(override val config: SourceConfig) :
    MusicSource, SourceRegistry.ConfigBacked {

    override val configId: String get() = config.id

    override val kind: SourceKind get() = SourceKind.NETEASE

    override val displayName: String get() = config.displayName

    override suspend fun health(): SourceHealth {
        if (!NeteaseApi.ping()) {
            return SourceHealth.Unreachable("网易云音乐没有响应")
        }
        // 能连上就顺带说一句登录状态：这个源在没登录时也能用，但能听到的东西
        // 少很多，而那是用户在音源页最需要知道的一句。
        val account = NeteaseApi.account()
        return SourceHealth.Ok(
            account?.let { "已登录：${it.nickname}" } ?: "未登录，只能播放免费曲目",
        )
    }

    override suspend fun search(
        query: String,
        limit: Int,
        waitForAll: Boolean,
        request: StreamRequest?,
    ): List<Song> = NeteaseApi.search(query, limit).map { it.toSong() }

    override suspend fun stream(trackId: String, request: StreamRequest): SourceStream? {
        val id = trackId.toLongOrNull() ?: return null
        val maxKbps = kbpsFor(request)
        val row = NeteaseApi.stream(id, maxKbps) ?: return null
        return SourceStream(
            // 不需要 Referer 或 UA：直链上的 `vuutv` 签名已经带上了身份，
            // 换一个 ExoPlayer 的 UA 照样 206。空白头比抄一份假的更诚实。
            url = row.url,
            format = StreamFormat(codec = row.codec, kbps = row.kbps),
            durationSec = row.durationSec,
            // 只有"要无损、回的不是无损编码"才算低于要求。要 320 给
            // 320 不算；要无损真拿到 FLAC 也不算。
            belowRequest = request is StreamRequest.Lossless &&
                (row.codec != "flac" && row.codec != "alac" && row.kbps < 900),
        )
    }

    private fun NeteaseApi.Track.toSong() = Song(
        videoId = SourceRegistry.trackKey(config.id, id.toString()),
        title = title,
        artist = artist,
        albumName = album,
        thumbnailUrl = artwork,
        durationText = durationSec?.let { seconds ->
            "${seconds / 60}:${"%02d".format(Locale.ROOT, seconds % 60)}"
        },
    )

    companion object {
        /**
         * 「无损」档位向网易云要什么。
         *
         * 这个数不再表示"最高只能给 320k"，而是一个哨兵：`NeteaseApi.stream`
         * 看到它就先走 `level=lossless&encodeType=flac`，拿不到再逐级回落。
         * /66 在这里写 320，等于主动把 FLAC 让掉了——设置里选无损、
         * 播放页却只有「高品质」，就是这个数造成的。
         */
        const val LOSSLESS_TIER_KBPS = 999

        /**
         * 设置页的音质 → 网易云实际请求的码率上限。
         *
         * 单独抽成纯函数是为了把这条链路钉在单测里：
         * `AppSettings.effectiveAudioQuality` → `SourceResolver.requestForNow`
         * → 这里 → `NeteaseApi.tierFor` → `/api/song/enhance/player/url?br=`。
         * 设置在**每次取地址时**读，所以切换后下一首立刻按新档位请求，不需要
         * 重启 App、也不用重新登录网易云。
         */
        fun kbpsFor(request: StreamRequest): Int = when (request) {
            // 只有真的选了「无损」才去要 FLAC。 把 Best 也并进
            // 这个分支，于是"高音质"一样走 lossless，设置形同虚设——他切到高音质
            // 仍显示无损，就是这个。
            StreamRequest.Lossless -> LOSSLESS_TIER_KBPS
            StreamRequest.Best -> 320
            is StreamRequest.Capped -> request.maxKbps
        }
    }
}
