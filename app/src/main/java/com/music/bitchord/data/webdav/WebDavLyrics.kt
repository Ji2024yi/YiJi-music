package com.music.bitchord.data.webdav

import java.util.concurrent.ConcurrentHashMap

/**
 * 服务器上一首歌配哪一份歌词文件。
 *
 * 曲库列表本来就把整棵树走了一遍（PROPFIND Depth 1 逐目录），歌词文件当时被
 * 当成「不是歌」扔掉了。这里把那份名单留下来，按「同目录 + 同名前缀」配给音频，
 * 于是往 `音乐/艺术家/专辑/` 里丢一个 `想你的夜.lrc` 或 `想你的夜.ttml` 就生效，
 * 不必再求任何在线歌词源 —— 这也是开源播放器（洛雪、MusicFree 那一类）认歌词
 * 的第一顺位。
 *
 * 每次列库都整表重建：服务器上删掉一个 .lrc，下次刷新就该没有它。
 */
object WebDavLyrics {

    /** 音频 URL → 歌词文件 URL。 */
    private val byAudioUrl = ConcurrentHashMap<String, String>()

    /**
     * 逐字那种优先：同一首同时放了 `.ttml` 和 `.lrc` 时，能唱一个字亮一个字的
     * 那份才是他要的。剩下的按名字长度排——越接近「完全同名」越像是给这首歌的。
     */
    private val RANK = mapOf(
        "ttml" to 0,
        "json3" to 0,
        "xml" to 1,
        "lrc" to 2,
        "txt" to 3,
    )

    fun index(audio: List<WebDavClient.Entry>, lyrics: List<WebDavClient.Entry>) {
        byAudioUrl.clear()
        if (audio.isEmpty() || lyrics.isEmpty()) return
        val byDir = lyrics.groupBy { dirOf(it.url) }
        for (track in audio) {
            val base = nameOf(track.url).substringBeforeLast('.').ifBlank { continue }
            val candidates = byDir[dirOf(track.url)] ?: continue
            val best = candidates
                .filter { isPair(base, nameOf(it.url)) }
                .sortedWith(
                    compareBy<WebDavClient.Entry> { RANK[extOf(nameOf(it.url))] ?: 9 }
                        .thenBy { nameOf(it.url).length },
                )
                .firstOrNull() ?: continue
            byAudioUrl[track.url] = best.url
        }
    }

    /** 这首歌的侧车歌词地址；没有侧车时是 null。 */
    fun urlFor(audioFileUrl: String): String? = byAudioUrl[audioFileUrl]

    /** `想你的夜.lrc`、`想你的夜 [ lyric ].lrc`、`想你的夜_zh.lrc` 都算这一首的。 */
    private fun isPair(trackBase: String, lyricName: String): Boolean {
        val lyricBase = lyricName.substringBeforeLast('.')
        if (lyricBase.equals(trackBase, ignoreCase = true)) return true
        if (!lyricBase.startsWith(trackBase, ignoreCase = true)) return false
        val rest = lyricBase.substring(trackBase.length)
        // 前缀之外只剩分隔性质的小尾巴才认：空格、下划线、连字符、点、括号、
        // 语言后缀（`.zh`）。别的说明这是另一首歌，不是它的歌词。
        return rest.length <= 12 && rest.none { it.isLetterOrDigit() }
    }

    private fun nameOf(url: String): String = runCatching {
        java.net.URI(url).path?.substringAfterLast('/')?.let {
            java.net.URLDecoder.decode(it, "UTF-8")
        }
    }.getOrNull().orEmpty().ifBlank { url.substringAfterLast('/') }

    private fun dirOf(url: String): String = url.substringBeforeLast('/', "")

    private fun extOf(name: String): String =
        name.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
}
