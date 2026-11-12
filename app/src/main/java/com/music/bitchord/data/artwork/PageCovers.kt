package com.music.bitchord.data.artwork

import android.content.Context
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.UiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 资料库那些页（WebDAV、歌单、缓存歌曲、本机文件夹）页头上那张图
 * 到底从哪儿来。
 *
 * 顺序说清楚一遍：
 *
 *  1. 他自己定过的那张（[CardCovers]）—— 显式选过的比任何自动结果都更能代表这一页；
 *  2. 这一栏里第一首带封面的歌 —— 主流 App 给歌单自动配封面就是这么做的，"带封面"
 *     包括 [EmbeddedCovers] 从音频文件里抠出来、之前存过的那份；
 *  3. 一栏里一张都没有：就地从最合适的那首抠一张出来（本机文件是本地读；服务器上
 *     那份只读文件头那一截），抠到就用；
 *  4. 还拿不到才退回调用方顺手带来的地址（下载记录、分组卡片、打开这页那张卡）。
 *
 * 原来第 2 步是压在"调用方没带地址"这个条件下面的：WebDAV 那一栏服务器上只放了
 * 音频，每行 thumbnailUrl 都是空的，于是页头只能顶着占位灰——他说的"歌单、webdav
 * 的页头没有封面"就是这个。第 4 步现在垫在最后，不再挡前面的路。
 */
object PageCovers {

    private const val TAG = "BitChord"

    /** 正在后台补齐的页，一栏只开一轮。 */
    private val backfilling = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /**
     * 列表行：把已经抠出来过的封面填进那些空着的行。
     *
     * 纯本地，一次 SharedPreferences 读，所以在页面组装里同步调没有关系。
     */
    fun fill(context: Context, state: UiState<List<Song>>?): UiState<List<Song>>? {
        val success = state as? UiState.Success ?: return state
        return success.copy(data = EmbeddedCovers.fill(context, success.data))
    }

    /**
     * 卡片用的封面（资料库那一排歌单卡）：自己定过的 → 这一栏里第一首带封面的歌，
     * "带封面"包括从音频里抠出来、存过的那份。
     *
     * 这是同步版本，不会为了一张卡片去打开文件：页头那条就地抠图的路在
     * [headerArtwork]，那里等得起，列表里等不起。
     */
    fun cardCover(context: Context, browseId: String, songs: List<Song>): String? =
        CardCovers.urlOf(browseId)
            ?: songs.firstNotNullOfOrNull { song ->
                song.thumbnailUrl?.takeIf { it.isNotBlank() }
                    ?: EmbeddedCovers.cachedCoverOf(context, song.videoId)
            }

    /** 页头该用哪张图。见类上的顺序说明。 */
    suspend fun headerArtwork(
        context: Context,
        browseId: String,
        pinned: String?,
        rows: List<Song>,
        fallback: String?,
    ): String? {
        pinned?.let { return it }
        rows.firstNotNullOfOrNull { song -> song.thumbnailUrl?.takeIf { it.isNotBlank() } }?.let { return it }
        val song = EmbeddedCovers.candidateFor(rows) ?: return fallback
        return EmbeddedCovers.coverFor(context, song) ?: fallback
    }

    /**
     * 剩下的行一首一首在后台补，补好了叫调用方重算一次这一页。
     *
     * 一栏二十首时前台只等页头那一张，其余慢慢来：[EmbeddedCovers] 里记着谁已经
     * 抠过、谁抠不出来，重算一次页面不会又发起一轮同样的读取。
     */
    fun backfill(
        scope: CoroutineScope,
        context: Context,
        browseId: String,
        rows: List<Song>,
        onDone: () -> Unit,
    ) {
        if (!browseId.startsWith("local:")) return
        val pending = rows.filter { song ->
            song.thumbnailUrl.isNullOrBlank() && song.videoId.isNotBlank()
        }.distinctBy { it.videoId }
        if (pending.isEmpty()) return
        if (!backfilling.add(browseId)) return
        scope.launch {
            var saved = 0
            for (song in pending) {
                if (EmbeddedCovers.coverFor(context, song) != null) saved++
            }
            backfilling.remove(browseId)
            Log.d(TAG, "covers backfilled for $browseId: $saved/${pending.size}")
            if (saved > 0) onDone()
        }
    }
}
