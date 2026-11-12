package com.music.bitchord.data.localfeed

import android.content.Context
import android.content.SharedPreferences
import android.media.MediaMetadataRetriever
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.artwork.EmbeddedCovers
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 这首歌是哪一年的。
 *
 * 探索页要按设计稿改成「按年份」的两列大格子，而年份这件事仓库里原来一点
 * 没有：`Song` 没这个字段，MediaStore 的 `YEAR` 列也没进投影。所以这一步先把数据
 * 补齐，分两条路：
 *
 *  本机那批 —— MediaStore 直接给（`LocalMediaRepository` 的投影里加了 `YEAR`），
 *    随行带出来，不用读文件；
 *  服务器上那批 —— 标签写在文件里，行是文件名拼出来的，只能自己读。读法与内嵌
 *    封面同一条路（`EmbeddedCovers.probe`：本机副本优先，其次只读服务器文件的头
 *    那一截），读出来记进偏好，之后每回都是本地读。
 *
 * 读不到就是 null，这首歌不进任何年份格子。不拿文件名里的四位数字凑：他文件名里
 * 那个「JOKER 2018Mix」是版本名，不是发行年，凑出来的格子会把人引到错的地方。
 */
object YearTags {

    private const val TAG = "BitChord"
    private const val PREF_NAME = "bitchord_media_years"

    private lateinit var store: SharedPreferences

    private fun prefs(context: Context): SharedPreferences =
        if (this::store.isInitialized) store else context.applicationContext
            .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).also { store = it }

    /** 记住过的年份，不做任何读取。列表每行都同步调它。 */
    fun cachedYearOf(context: Context, videoId: String): Int? =
        prefs(context).getInt(videoId, 0).takeIf { it > 0 }

    /** 随行带出来的年份优先，其次记住过的那份。 */
    fun yearOf(context: Context, song: Song): Int? =
        song.year ?: cachedYearOf(context, song.videoId)

    /** 把记住过的年份填回那些行上没有年份的歌（纯本地，同步）。 */
    fun fill(context: Context, songs: List<Song>): List<Song> {
        if (songs.none { it.year == null }) return songs
        return songs.map { song ->
            if (song.year != null) return@map song
            val year = cachedYearOf(context, song.videoId) ?: return@map song
            song.copy(year = year)
        }
    }

    /**
     * 问这一首的年份：行上已有 → 记住过 → 打开文件读标签。
     *
     * 与封面共用 `EmbeddedCovers` 那套"同一首只开一次、读不出来就记一笔"的账，
     * 所以后台一栏一栏补的时候不会把服务器打穿。
     */
    suspend fun yearFor(context: Context, song: Song): Int? {
        song.year?.let { return it }
        cachedYearOf(context, song.videoId)?.let { return it }
        val raw = withContext(Dispatchers.IO) {
            EmbeddedCovers.readOnce(song) { it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR) }
        }
        val year = raw?.trim()?.takeWhile { it.isDigit() }?.toIntOrNull()?.takeIf { it in 1900..2999 }
        if (year != null) {
            prefs(context).edit().putInt(song.videoId, year).apply()
        } else {
            Log.d(TAG, "no year tag in ${song.videoId} (raw=$raw)")
        }
        return year
    }
}
