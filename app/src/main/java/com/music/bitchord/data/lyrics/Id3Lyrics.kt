package com.music.bitchord.data.lyrics

/**
 * MP3 里那层 ID3v2 的歌词。
 *
 * 之前 [EmbeddedLyrics] 只认 FLAC / MP4 / Matroska 三种容器，MP3 谁都不认，
 * 于是「有的歌能显示歌词、有的永远显示没有歌词」——区别只在那个文件是 mp3。
 *
 * 读四类，按优先级：
 *
 *  `TXXX:BITCHORD_LYRICS`：本 App 自己写的（见 `LrcWriter`），A2 逐字时间戳
 *    还留在里面，认出来就直接给，逐字高亮不必退化成整行；
 *  `USLT`：标准歌词帧，可能是 `[mm:ss.xx]` 同步的，也可能是纯文本；
 *  `TXXX:LYRICS`：别的工具（落雪、网易云导出等）常写的键；
 *  `SYLT`：同步歌词帧，一段一个毫秒时间戳，改写成 LRC 交给同一个解析器，
 *    不必为它另开一条渲染路径。
 *
 * v2.3 与 v2.4 的帧长写法不同（后者是 synchsafe，每字节只用 7 位），按错会把
 * 游标踩进音频数据里，看上去就像「这文件没有歌词」——所以版本字节决定读法，
 * 其余一律不参与判断。v2.2 用三字节帧名，这里不支持：那是 1999 年以前的文件，
 * 撞上它的概率比读错它的成本低。
 *
 * 全程不抛：任何一处越界、任何一帧长度说不通，就把手上已经读到的交出去。
 */
object Id3Lyrics {

    /**
     * [head] 里的歌词原文，或 null。
     *
     * [head] 是文件的开头若干字节（`EmbeddedLyrics` 的读取上限），ID3v2 就在
     * 最前面，所以远程按 Range 拉的那一截同样能用。
     */
    fun text(head: ByteArray): String? = runCatching { tag(head) }.getOrNull()

    private fun tag(bytes: ByteArray): String? {
        if (bytes.size < 10) return null
        if (ascii(bytes, 0, 3) != "ID3") return null
        val version = bytes[3].toInt() and 0xFF
        if (version < 3) return null
        val tagEnd = (10 + synchsafe(bytes, 6)).coerceAtMost(bytes.size)
        var pos = 10
        // 扩展头：v2.4 把它自己那 4 个长度字节算进了长度，v2.3 不算。跳错一位，
        // 后面每一帧都错位，于是整份歌词一起丢。
        if (bytes[5].toInt() and 0x40 != 0) {
            val ext = synchsafe(bytes, 10)
            pos = (if (version >= 4) 10 + ext else 14 + ext).coerceIn(10, tagEnd)
        }
        var ours: String? = null
        var unsynced: String? = null
        var keyed: String? = null
        var synced: String? = null
        while (pos + 10 <= tagEnd) {
            val name = ascii(bytes, pos, 4)
            // 帧区的尽头是 padding（一串空字节），读到就收手。
            if (name.isEmpty() || name[0] == BLANK) break
            if (!name.all { it.isLetterOrDigit() }) break
            val size = if (version >= 4) synchsafe(bytes, pos + 4) else be32(bytes, pos + 4)
            if (size <= 0) break
            val data = pos + 10
            if (data + size > tagEnd) break
            val end = data + size
            when (name) {
                "USLT" -> unsynced = unsynced ?: uslt(bytes, data, end)
                "TXXX" -> {
                    val pair = txxx(bytes, data, end)
                    if (pair != null && pair.second.isNotBlank()) {
                        val key = pair.first.trim()
                        when {
                            key.equals(WORD_LYRICS_FIELD, ignoreCase = true) -> ours = pair.second
                            key.equals("LYRICS", ignoreCase = true) -> keyed = keyed ?: pair.second
                            key.isBlank() -> keyed = keyed ?: pair.second
                        }
                    }
                }
                "SYLT" -> synced = synced ?: syltAsLrc(bytes, data, end)
            }
            pos = end
        }
        return ours ?: unsynced ?: keyed ?: synced
    }

    /** USLT：编码字节 + 3 字节语言 + 一段说明（可空）+ 歌词正文。 */
    private fun uslt(bytes: ByteArray, at: Int, end: Int): String? {
        if (end - at < 5) return null
        val encoding = bytes[at].toInt() and 0xFF
        val body = afterTerminator(bytes, at + 4, end, wide(encoding))
        return decode(bytes, body, end, encoding).takeIf { it.isNotBlank() }
    }

    /** TXXX：编码字节 + 键（说明串）+ 值。返回 (键, 值)。 */
    private fun txxx(bytes: ByteArray, at: Int, end: Int): Pair<String, String>? {
        if (end - at < 2) return null
        val encoding = bytes[at].toInt() and 0xFF
        val value = afterTerminator(bytes, at + 1, end, wide(encoding))
        val key = decode(bytes, at + 1, value, encoding)
        val text = decode(bytes, value, end, encoding)
        if (text.isBlank()) return null
        return key to text
    }

    /**
     * SYLT 改写成 LRC。
     *
     * 帧的布局是：编码(1) 语言(3) 内容类型(1) 时间戳格式(1) 文本是否以空字节结尾(1)，
     * 之后是重复的〔说明串、4 字节毫秒、2 字节文本长度、文本〕。
     *
     * 时间戳格式只有「绝对毫秒」这一种认（另一种是 MIDI 帧，得先知道采样率，
     * 而那是猜）；文本长度那 2 个字节在「以空字节结尾」时不参与，靠终止符找边界。
     */
    private fun syltAsLrc(bytes: ByteArray, at: Int, end: Int): String? {
        if (end - at < 8) return null
        val encoding = bytes[at].toInt() and 0xFF
        if (bytes[at + 5].toInt() and 0xFF != 0) return null
        val nullTerminated = bytes[at + 6].toInt() and 0xFF != 0
        var pos = at + 7
        val out = StringBuilder()
        while (pos + 6 <= end) {
            if (nullTerminated) {
                pos = afterTerminator(bytes, pos, end, wide(encoding))
            }
            if (pos + 6 > end) break
            val ms = be32(bytes, pos)
            val declared = ((bytes[pos + 4].toInt() and 0xFF) shl 8) or
                (bytes[pos + 5].toInt() and 0xFF)
            pos += 6
            val to = if (nullTerminated) {
                afterTerminator(bytes, pos, end, wide(encoding))
            } else {
                (pos + declared).coerceAtMost(end)
            }
            val line = decode(bytes, pos, to, encoding)
            pos = to
            if (line.isNotBlank()) out.append(stamp(ms)).append(line).append('\n')
        }
        return out.toString().takeIf { it.isNotBlank() }
    }

    /** `[m:ss.mmm]`——三位的秒小数被 [LrcLib] 当毫秒读。 */
    private fun stamp(ms: Int): String {
        val minutes = ms / 60_000
        val rest = ms % 60_000
        val seconds = rest / 1000
        val millis = rest % 1000
        return buildString {
            append('[').append(minutes).append(':')
            if (seconds < 10) append('0')
            append(seconds).append('.')
            when {
                millis < 10 -> append("00")
                millis < 100 -> append('0')
            }
            append(millis).append(']')
        }
    }

    private fun wide(encoding: Int): Boolean = encoding == 1 || encoding == 2

    /**
     * 编码字节：0 是 Latin-1，1 是 UTF-16（带 BOM），2 是 UTF-16BE，3 是 UTF-8。
     *
     * UTF-16 的终止符是两个 0 字节，按单字节去找会在字符中间截断，所以步长跟着
     * 编码走。
     */
    private fun decode(bytes: ByteArray, from: Int, to: Int, encoding: Int): String {
        if (to <= from) return ""
        val charset = when (encoding) {
            0 -> Charsets.ISO_8859_1
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            else -> Charsets.UTF_8
        }
        return String(bytes, from, (to - from).coerceAtMost(bytes.size - from), charset)
            .trim(BLANK)
    }

    /** 跳过 [from] 起的这个终止符，返回它之后的位置。 */
    private fun afterTerminator(bytes: ByteArray, from: Int, end: Int, wide: Boolean): Int {
        var i = from
        if (wide) {
            while (i + 1 < end) {
                if (bytes[i] == ZERO && bytes[i + 1] == ZERO) return i + 2
                i += 2
            }
            return end
        }
        while (i < end) {
            if (bytes[i] == ZERO) return i + 1
            i++
        }
        return end
    }

    private fun ascii(bytes: ByteArray, from: Int, length: Int): String =
        String(bytes, from, length, Charsets.ISO_8859_1)

    private fun be32(bytes: ByteArray, off: Int): Int =
        ((bytes[off].toInt() and 0xFF) shl 24) or ((bytes[off + 1].toInt() and 0xFF) shl 16) or
            ((bytes[off + 2].toInt() and 0xFF) shl 8) or (bytes[off + 3].toInt() and 0xFF)

    /** ID3 的「synchsafe」整数：每个字节只用低 7 位。 */
    private fun synchsafe(bytes: ByteArray, off: Int): Int =
        ((bytes[off].toInt() and 0x7F) shl 21) or ((bytes[off + 1].toInt() and 0x7F) shl 14) or
            ((bytes[off + 2].toInt() and 0x7F) shl 7) or (bytes[off + 3].toInt() and 0x7F)

    private val ZERO: Byte = 0
    private val BLANK = Char(0)
}
