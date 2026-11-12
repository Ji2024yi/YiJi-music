package com.music.bitchord.data.lyrics

import android.content.Context
import android.net.Uri
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.webdav.WebDavLyrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/**
 * The lyrics already sitting inside a downloaded file.
 *
 * The read side of what the download path wrote — see `MediaTagger`, and the
 * three taggers under it. A track that was downloaded had its lyrics fetched
 * once, at download time, and written into the file; asking four servers for
 * them again every time it is played is a network round trip to arrive at a
 * string that is already on disk, and it is the reason a downloaded song showed
 * nothing at all with the connection off.
 *
 * Two fields are read, in this order:
 *
 *  `BITCHORD_LYRICS`, this app's own, holding the "enhanced" A2 form with the
 *    word timings intact — see [toEnhancedLrc].
 *  the container's standard lyrics field, holding plain `[mm:ss.xx]` LRC.
 *
 * The second is what every other player reads and what older downloads have,
 * so it is the fallback rather than the exception. Preferring the first is what
 * keeps a downloaded song lighting up word by word instead of a line at a time.
 *
 * Never throws. A file that isn't one of the three containers, or is one and
 * has no lyrics in it, is a null — the caller falls back to the network, which
 * is exactly what it did before this existed.
 *
 * ### Offline packages
 *
 * One kind of download has no container to have been written into: an HLS or
 * DASH stream is saved as a directory of fMP4 segments behind a playlist, and
 * its `localUri` points at that playlist. `OfflineHls` and `OfflineDash` write
 * the lyrics beside it as a `lyrics.lrc` sidecar for exactly that reason — so
 * [sidecar] is tried first, and a package's lyrics come back the same shape as
 * a tagged file's. Without it those tracks fetched their lyrics from the
 * network on every play and showed none at all offline, which is the failure
 * this whole object exists to prevent.
 */
object EmbeddedLyrics {

    private const val TAG = "BitChord"

    /**
     * Most bytes worth pulling to find a tag.
     *
     * A cap rather than a size: this reads whatever region of the file holds
     * the metadata, and that region is small in all three containers — but its
     * length is stated *by the file*, so a corrupt or hostile one could claim
     * any number at all. `LyricsTag` caps what it writes at 64k, so anything
     * past this is not a tag this app produced.
     */
    private const val MAX_TAG_BYTES = 8 * 1024 * 1024

    /**
     * The lyrics inside [uriString], or null when it has none worth showing.
     *
     * Touches the filesystem, so it runs on [Dispatchers.IO] regardless of
     * where it is called from.
     */
    suspend fun forUri(context: Context, uriString: String): List<LyricLine>? =
        withContext(Dispatchers.IO) {
            val raw = runCatching { read(context, Uri.parse(uriString)) }
                .onFailure { Log.d(TAG, "no embedded lyrics in $uriString: ${it.message}") }
                .getOrNull()
                ?: return@withContext null
            // The same last pass the network sources get, so a downloaded track
            // and a streamed one draw their backing vocals the same way.
            // TTML 和 LRC 在这一步分岔——文件里嵌的、同目录放的，
            // 两种格式都可能，往后一律交给同一个行解析器。
            parseAny(raw).takeIf { lines -> lines.any { it.text.isNotBlank() } }
                ?.withBackgroundVocals()
        }

    /** The raw LRC text for the file, preferring this app's word-timed field. */
    /**
     * 同目录那份歌词排在文件标签之前。
     *
     * 顺序是有意的：他手动往 `音乐/艺术家/专辑/` 里放的 `同一首歌.lrc`，写的就是
     * 他要的那一版；文件里原来嵌的那份多半是从别处顺手带过来的，容易对不上这
     * 个版本（变速、现场、纯音乐段）。开源播放器也把这个放在第一位。
     */
    private fun read(context: Context, uri: Uri): String? =
        if (uri.scheme == "http" || uri.scheme == "https") {
            remote(uri.toString())
        } else {
            val path = uri.path.takeIf { uri.scheme == "file" } ?: realPath(context, uri)
            // 离线包的 lyrics.lrc 仍然第一位：那是本 App 自己写的，不会被当成
            // 「别人放的一份歌词」覆盖掉。
            sidecar(path) ?: localSidecar(path) ?: open(context, uri)
                ?.use { fromBytes(it.readAtMost(MAX_TAG_BYTES)) }
        }

    /**
     * 文件里的原始 LRC，供上传时原样写回服务器那份。
     *
     * 上传会给没有内嵌封面的文件重打标签（MediaTagger.embed），而那一步只写
     * TITLE/ARTIST/ALBUM，原来把已有的 LYRICS 一起换掉了——所以「传上去就没
     * 歌词」。这里先把原始歌词读出来，交给上传路径一起带上。
     */
    suspend fun rawForUri(context: Context, uriString: String): String? =
        withContext(Dispatchers.IO) {
            runCatching { read(context, Uri.parse(uriString)) }
                .onFailure { Log.d(TAG, "no raw lyrics in $uriString: ${it.message}") }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
        }

    /**
     * 远程文件（WebDAV）里的歌词。
     *
     * 本地能显示歌词，是因为下过的文件里本来就有 `LYRICS` 标签；传到服务器上的是
     * 同一份字节，标签也在，只是以前只读本地路径，HTTP 地址走
     * `contentResolver.openInputStream` 必然失败，于是远端一律「没有歌词」。
     *
     * 这里按 Range 只拉文件头 [REMOTE_TAG_BYTES]，用同一个 [fromBytes] 解析；
     * 凭据只在目标 host 就是设置里那台 WebDAV 时才带，别把口令发给别人。
     * FLAC 的 VORBIS_COMMENT 就在头部，这一条就能覆盖；MP4 若把 moov 放在文件尾、
     * 或 MP3 的 ID3，这里读不到，返回 null 交给原来的网络源。
     */
    private fun remote(url: String): String? {
        remoteCache[url]?.let { return it }
        val found = runCatching {
            // 同目录那份同名歌词先读。列库时已经把它配好了，一次
            // GET 就是一页文本，比 Range 拉四兆文件头便宜，也是他手动放歌词时
            // 想要的那一份。没有侧车或侧车是空的，才去翻文件里的标签。
            WebDavLyrics.urlFor(url)?.let { sidecar ->
                getText(sidecar)?.takeIf { it.isNotBlank() }?.let { return@runCatching it }
            }
            val builder = okhttp3.Request.Builder()
                .url(url)
                .header("Range", "bytes=0-${REMOTE_TAG_BYTES - 1}")
            authorize(builder, url)
            com.music.bitchord.data.Http.client.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                val bytes = response.body?.byteStream()?.use { it.readAtMost(REMOTE_TAG_BYTES) }
                    ?: return@runCatching null
                fromBytes(bytes)
            }
        }.getOrNull()
        if (remoteCache.size >= REMOTE_CACHE_ENTRIES) remoteCache.clear()
        remoteCache[url] = found
        return found
    }

    /**
     * 口令只发给设置里那台 WebDAV，别的不带。
     *
     * 侧车地址是曲库列表给的、标签地址是曲目 id 给的，两者都可能被服务器上的
     * 一个跳转带去别处——所以每一次 GET 都重新核一遍 host，而不是「既然是
     * WebDAV 的调用就一路放行」。
     */
    private fun authorize(builder: okhttp3.Request.Builder, url: String) {
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: return
        if (!host.equals(
                com.music.bitchord.data.webdav.WebDavConfig.hostOf(
                    com.music.bitchord.data.settings.AppSettings.webdavUrl.value,
                ),
                ignoreCase = true,
            )
        ) {
            return
        }
        com.music.bitchord.data.webdav.WebDavConfig.basicAuthHeader(
            com.music.bitchord.data.settings.AppSettings.webdavUsername.value,
            com.music.bitchord.data.settings.AppSettings.webdavPassword.value,
        )?.let { builder.header("Authorization", it) }
    }

    /** 一个远端歌词条子的正文，按上限读，BOM 去掉。 */
    private fun getText(url: String): String? = runCatching {
        val builder = okhttp3.Request.Builder().url(url)
        authorize(builder, url)
        com.music.bitchord.data.Http.client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) return@runCatching null
            response.body?.byteStream()?.use { it.readAtMost(MAX_TAG_BYTES) }
                ?.toString(Charsets.UTF_8)
        }
    }.getOrNull()

    /** Parsed remote lyrics, keyed by URL. A song played twice costs one fetch. */
    private val remoteCache = java.util.LinkedHashMap<String, String?>()
    private const val REMOTE_CACHE_ENTRIES = 24

    /** Enough of a FLAC's head to hold its comment block; not the whole file. */
    private const val REMOTE_TAG_BYTES = 4 * 1024 * 1024

    /**
     * The `lyrics.lrc` written next to the offline package playlist at [path].
     *
     * Recognised by the playlist rather than by the directory it sits in: the
     * package lives under a path this module has no business knowing, and a
     * local `.m3u8` is only ever one of these packages — nothing else in the
     * app saves a playlist to disk. What the sidecar holds is the enhanced A2
     * form where the download found one, which is why it is preferred over a
     * container search rather than used as a fallback to it.
     *
     * Takes the path rather than the `Uri` it came out of so the pairing with
     * the writers can be tested on a real directory, without a device.
     */
    internal fun sidecar(path: String?): String? {
        val playlist = path?.let(::File)?.takeIf { it.name.endsWith(".m3u8", ignoreCase = true) }
            ?: return null
        val file = File(playlist.parentFile ?: return null, "lyrics.lrc")
        // Bounded for the same reason [MAX_TAG_BYTES] is: this is read into a
        // string, and the size on disk is the only thing that says how big one.
        if (!file.isFile || file.length() !in 1..MAX_TAG_BYTES.toLong()) return null
        return runCatching { file.readText() }
            .onFailure { Log.d(TAG, "could not read $file: ${it.message}") }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    /**
     * 和音频同一个目录、同一个名字的歌词条子。
     *
     * 认 `.lrc` / `.ttml` / `.json3` / `.xml` / `.txt` 五种后缀，逐字的那两种
     * 排在前面（同一首既有 `.ttml` 又有 `.lrc` 时，一个字亮一个字的那份是他要的）；
     * 名字允许带小尾巴——`想你的夜 [lyric].lrc`、`想你的夜_zh.lrc` 是各处工具
     * 导出时的常见写法。带尾巴的按「越短越像给这首歌的」排。
     *
     * 一次列目录，五种后缀都在结果里挑，不去逐个试路径是否存在：一个目录里几十
     * 首歌，每首都试五次的话，翻一遍曲库就是几百次磁盘 stat。
     */
    internal fun localSidecar(path: String?): String? {
        val audio = path?.let(::File) ?: return null
        val dir = audio.parentFile?.takeIf(File::isDirectory) ?: return null
        val base = audio.name.substringBeforeLast('.')
        if (base.isBlank()) return null
        val siblings = runCatching { dir.listFiles() }.getOrNull()
            ?.filter { it.isFile }
            ?: return null
        val ranked = siblings
            .filter { it.name.length > base.length && it.name.startsWith(base, ignoreCase = true) }
            .mapNotNull { file ->
                val ext = file.name.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
                SIDECAR_RANK[ext]?.let { rank -> Triple(rank, file.name.length, file) }
            }
            .sortedWith(compareBy({ it.first }, { it.second }))
        for ((_, _, file) in ranked) {
            if (file.length() !in 1..MAX_TAG_BYTES.toLong()) continue
            // 名字比歌名多出来的那一段只能是分隔性质的小尾巴（空格、下划线、
            // 括号、语言码）；出现字母数字就说明那是另一首歌，不是这首歌的歌词。
            val ext = file.name.substringAfterLast('.')
            val tail = file.name.substring(base.length, file.name.length - ext.length - 1)
            if (tail.isNotEmpty() && tail.any { it.isLetterOrDigit() }) continue
            val text = runCatching { file.readText() }
                .onFailure { Log.d(TAG, "could not read $file: ${it.message}") }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: continue
            return text
        }
        return null
    }

    private val SIDECAR_RANK = mapOf(
        "ttml" to 0,
        "json3" to 0,
        "xml" to 1,
        "lrc" to 2,
        "txt" to 3,
    )

    /** 文件开头的 UTF-8 BOM（U+FEFF）。写成码位是因为它本身在源码里看不见。 */
    private val BOM = Char(0xFEFF).toString()

    /**
     * `content://` 背后的真路径，只为找它同目录的歌词文件。
     *
     * 读音频本身不需要它（contentResolver 就能开），但侧车要按目录找邻居。
     * MediaStore 的 DATA 列在新系统上可能为空——空了就回去读标签，不是失败。
     */
    private fun realPath(context: Context, uri: Uri): String? {
        if (uri.scheme == "file") return uri.path
        if (uri.scheme != "content") return null
        return runCatching {
            context.contentResolver.query(uri, arrayOf(ANDROID_DATA_COLUMN), null, null, null)
                ?.use { cursor ->
                    if (!cursor.moveToFirst() || cursor.columnCount < 1) return@use null
                    cursor.getString(0)?.takeIf { it.startsWith("/") }
                }
        }.getOrNull()
    }

    private const val ANDROID_DATA_COLUMN = "_data"

    /**
     * 歌词文本落到行上：TTML 走 [TtmlLyrics]，其余走 LRC。
     *
     * 认内容而不是认后缀——`.xml` 里可能是 TTML 也可能是别的，`.lrc` 里有人塞了
     * 一份 Apple 的 TTML，`.txt` 更是纯靠内容说话。开头是标签就当 TTML，两处
     * 都不认的行时间戳（`<m:ss.ms>`）也照样解析。
     */
    internal fun parseAny(raw: String): List<LyricLine> {
        // Windows 记事本存出来的 .lrc / .ttml 十个有八个带 BOM。它不算空白字符，
        // 留着会让「开头是不是标签」这一判直接走岔，把一份 TTML 当 LRC 读成零行。
        val text = raw.removePrefix(BOM).trim()
        if (text.startsWith("<") && (text.contains("<tt") || text.contains("<?xml"))) {
            return TtmlLyrics.parse(text)
        }
        return LrcLib.parseLrc(raw.removePrefix(BOM))
    }

    /**
     * The raw LRC text in [head], whichever of the three containers it is.
     *
     * Split from [read] so the parsing can be tested against bytes a tagger
     * just produced, without a device or a `Context` in the way — the round
     * trip is the only thing that proves a reader and a writer agree.
     */
    internal fun fromBytes(head: ByteArray): String? {
        val found = when {
            head.startsWith(FLAC_MAGIC) -> flac(head)
            head.startsWith(MATROSKA_MAGIC) -> matroska(head)
            head.isMp4() -> mp4(head)
            // MP3 以前谁都不认，所以那首 mp3 永远「没有歌词」。
            head.startsWith(ID3_MAGIC) -> Id3Lyrics.text(head)
            else -> null
        }
        return found?.takeIf { it.isNotBlank() }
    }

    private fun open(context: Context, uri: Uri): InputStream? =
        if (uri.scheme == "file") {
            uri.path?.let { File(it).takeIf(File::exists)?.inputStream() }
        } else {
            context.contentResolver.openInputStream(uri)
        }

    // MP4 / M4A ----------------------------------------------------------

    /**
     * The `©lyr` atom's text, or this app's freeform one where it is present.
     *
     * Both live under `moov/udta/meta/ilst`, and the search is scoped to `moov`
     * rather than run over the file: a four-byte pattern turns up in audio data
     * often enough that scanning the whole thing would eventually read a frame
     * as a tag. Inside `moov` the same pattern is a tag or it is nothing.
     */
    private fun mp4(bytes: ByteArray): String? {
        val moov = topLevelBox(bytes, "moov") ?: return null
        // Exclusive, and deliberately so: the lyrics are the last item written
        // into `ilst`, so their value ends exactly on `moov`'s own end — an
        // inclusive bound here rejects the one atom this is looking for.
        val end = moov.last + 1
        return ilstText(bytes, moov.first, end, freeform = true)
            ?: ilstText(bytes, moov.first, end, freeform = false)
    }

    /**
     * The bounds of a top-level box, without walking into it.
     *
     * `moov` is not required to come before the audio — a file written without
     * the faststart pass puts it after `mdat` — so this steps box to box rather
     * than assuming a position.
     */
    private fun topLevelBox(bytes: ByteArray, type: String): IntRange? {
        var pos = 0
        while (pos + 8 <= bytes.size) {
            val declared = readU32(bytes, pos)
            var headerLen = 8
            var size = declared
            if (declared == 1L) {
                if (pos + 16 > bytes.size) return null
                size = readU64(bytes, pos + 8)
                headerLen = 16
            } else if (declared == 0L) {
                size = (bytes.size - pos).toLong()
            }
            if (size < headerLen || size > Int.MAX_VALUE) return null
            val end = (pos + size).toInt().coerceAtMost(bytes.size)
            if (String(bytes, pos + 4, 4, Charsets.ISO_8859_1) == type) return pos until end
            pos += size.toInt()
        }
        return null
    }

    /**
     * The text of the lyrics item inside [within].
     *
     * [freeform] picks which of the two: this app's `----` item, whose name is
     * carried in a `name` box beside the value, or the standard `©lyr`, whose
     * four-byte type *is* the name. They are stored differently enough that one
     * search cannot find both.
     */
    private fun ilstText(bytes: ByteArray, from: Int, endExclusive: Int, freeform: Boolean): String? {
        val marker = if (freeform) WORD_LYRICS_FIELD.toByteArray(Charsets.UTF_8) else LYR_ATOM
        var at = from
        while (true) {
            val found = bytes.indexOf(marker, at, endExclusive) ?: return null
            // The value is the first `data` box after the name, in both layouts:
            // a freeform item is mean/name/data, a standard one is type/data.
            val data = bytes.indexOf(DATA_ATOM, found, endExclusive) ?: return null
            dataText(bytes, data, endExclusive)?.let { return it }
            at = found + marker.size
        }
    }

    /**
     * An iTunes `data` box's payload as text.
     *
     * The box is version/flags(4) + locale(4) + the value, and the length in
     * front of it is what says where the value stops — a lyric sheet has
     * newlines in it and nothing else terminates it.
     */
    private fun dataText(bytes: ByteArray, dataAt: Int, endExclusive: Int): String? {
        val start = dataAt - 4
        if (start < 0 || dataAt + 12 > endExclusive) return null
        val size = readU32(bytes, start).toInt()
        if (size <= 16 || start + size > endExclusive) return null
        // Type indicator 1 is UTF-8 text; a cover's 13/14 is the other thing a
        // `data` box holds, and decoding a JPEG as a string is not a lyric.
        if (readU32(bytes, dataAt + 4).toInt() != 1) return null
        return String(bytes, dataAt + 12, start + size - (dataAt + 12), Charsets.UTF_8)
    }

    // FLAC ---------------------------------------------------------------

    /**
     * The `LYRICS` (or [WORD_LYRICS_FIELD]) comment out of the `VORBIS_COMMENT` block.
     *
     * Every length in the block is **little-endian** — it reuses Ogg Vorbis'
     * layout, which is the one part of FLAC that isn't big-endian.
     */
    private fun flac(bytes: ByteArray): String? {
        var pos = FLAC_MAGIC.size
        while (pos + 4 <= bytes.size) {
            val flags = bytes[pos].toInt() and 0xFF
            val length = ((bytes[pos + 1].toInt() and 0xFF) shl 16) or
                ((bytes[pos + 2].toInt() and 0xFF) shl 8) or
                (bytes[pos + 3].toInt() and 0xFF)
            val start = pos + 4
            if (start + length > bytes.size) return null
            if (flags and 0x7F == FLAC_VORBIS_COMMENT) {
                return vorbisComment(bytes, start, start + length)
            }
            if (flags and 0x80 != 0) return null
            pos = start + length
        }
        return null
    }

    private fun vorbisComment(bytes: ByteArray, start: Int, end: Int): String? {
        var pos = start
        fun u32(): Int? {
            if (pos + 4 > end) return null
            val v = (bytes[pos].toInt() and 0xFF) or ((bytes[pos + 1].toInt() and 0xFF) shl 8) or
                ((bytes[pos + 2].toInt() and 0xFF) shl 16) or ((bytes[pos + 3].toInt() and 0xFF) shl 24)
            pos += 4
            return v
        }
        val vendor = u32() ?: return null
        pos += vendor
        val count = u32() ?: return null
        var plain: String? = null
        repeat(count.coerceAtMost(4_096)) {
            val length = u32() ?: return plain
            if (length < 0 || pos + length > end) return plain
            val entry = String(bytes, pos, length, Charsets.UTF_8)
            pos += length
            val name = entry.substringBefore('=').uppercase()
            val value = entry.substringAfter('=', "")
            // This app's own field wins outright; the standard one is held in
            // case it turns out to be the only one there.
            if (name == WORD_LYRICS_FIELD && value.isNotBlank()) return value
            if (name == "LYRICS" && plain == null && value.isNotBlank()) plain = value
        }
        return plain
    }

    // Matroska / WebM ----------------------------------------------------

    /**
     * The `LYRICS` SimpleTag's string.
     *
     * Scanned for rather than walked down to: the tags a download writes are
     * appended after everything else (see `WebmTagger`), so reaching them
     * properly would mean parsing the whole Segment — every cluster of audio —
     * to arrive at the last few hundred bytes. The name is matched inside a
     * `TagName` element and the value read out of the `TagString` that follows,
     * so this is looking at tag structure rather than guessing at loose bytes.
     */
    private fun matroska(bytes: ByteArray): String? {
        var plain: String? = null
        for (name in listOf(WORD_LYRICS_FIELD, "LYRICS")) {
            val needle = name.toByteArray(Charsets.US_ASCII)
            var from = 0
            while (true) {
                val at = bytes.indexOf(needle, from, bytes.size) ?: break
                from = at + needle.size
                // The name element's own header sits immediately in front of it:
                // id(2) + a one-byte length for a name this short.
                if (at < 3 || bytes[at - 3] != ID_TAGNAME[0] || bytes[at - 2] != ID_TAGNAME[1]) continue
                if ((bytes[at - 1].toInt() and 0x7F) != needle.size) continue
                val string = bytes.indexOf(ID_TAGSTRING, from, bytes.size) ?: continue
                val size = readVint(bytes, string + 2) ?: continue
                val valueAt = string + 2 + size.width
                if (size.value <= 0 || valueAt + size.value > bytes.size) continue
                val value = String(bytes, valueAt, size.value.toInt(), Charsets.UTF_8)
                if (value.isBlank()) continue
                if (name == WORD_LYRICS_FIELD) return value
                if (plain == null) plain = value
            }
        }
        return plain
    }

    private class Vint(val value: Long, val width: Int)

    /**
     * An EBML variable-length integer: the highest set bit of the first byte
     * gives the width, and the bits after it are the value.
     */
    private fun readVint(bytes: ByteArray, offset: Int): Vint? {
        if (offset >= bytes.size) return null
        val first = bytes[offset].toInt() and 0xFF
        if (first == 0) return null
        var width = 1
        var mask = 0x80
        while (first and mask == 0) {
            mask = mask shr 1
            width++
        }
        if (offset + width > bytes.size) return null
        var value = (first and mask.inv() and 0xFF).toLong()
        for (i in 1 until width) value = (value shl 8) or (bytes[offset + i].toLong() and 0xFF)
        return Vint(value, width)
    }

    // Bytes --------------------------------------------------------------

    /** Reads up to [max] bytes, which is all of a normal file and a prefix of a huge one. */
    private fun InputStream.readAtMost(max: Int): ByteArray {
        val out = ByteArrayOutputStream(minOf(max, 1 shl 16))
        val buffer = ByteArray(1 shl 16)
        var total = 0
        while (total < max) {
            val read = read(buffer, 0, minOf(buffer.size, max - total))
            if (read <= 0) break
            out.write(buffer, 0, read)
            total += read
        }
        return out.toByteArray()
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        return prefix.indices.all { this[it] == prefix[it] }
    }

    /** `ftyp` at offset 4 is what says "this is an MP4" — there is no leading magic. */
    private fun ByteArray.isMp4(): Boolean =
        size > 12 && this[4] == 'f'.code.toByte() && this[5] == 't'.code.toByte() &&
            this[6] == 'y'.code.toByte() && this[7] == 'p'.code.toByte()

    private fun ByteArray.indexOf(needle: ByteArray, from: Int, until: Int): Int? {
        if (needle.isEmpty()) return null
        val last = minOf(until, size) - needle.size
        var i = from.coerceAtLeast(0)
        outer@ while (i <= last) {
            for (j in needle.indices) {
                if (this[i + j] != needle[j]) {
                    i++
                    continue@outer
                }
            }
            return i
        }
        return null
    }

    private fun readU32(b: ByteArray, off: Int): Long =
        ((b[off].toLong() and 0xFF) shl 24) or ((b[off + 1].toLong() and 0xFF) shl 16) or
            ((b[off + 2].toLong() and 0xFF) shl 8) or (b[off + 3].toLong() and 0xFF)

    private fun readU64(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (b[off + i].toLong() and 0xFF)
        return v
    }

    private const val FLAC_VORBIS_COMMENT = 4
    private val FLAC_MAGIC = "fLaC".toByteArray(Charsets.US_ASCII)
    private val ID3_MAGIC = "ID3".toByteArray(Charsets.US_ASCII)
    private val MATROSKA_MAGIC = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte())
    private val DATA_ATOM = "data".toByteArray(Charsets.ISO_8859_1)
    private val LYR_ATOM = byteArrayOf(0xA9.toByte()) + "lyr".toByteArray(Charsets.ISO_8859_1)
    private val ID_TAGNAME = byteArrayOf(0x45, 0xA3.toByte())
    private val ID_TAGSTRING = byteArrayOf(0x44, 0x87.toByte())
}
