package com.music.bitchord.data

import com.music.bitchord.data.stats.ArtistNames
import java.util.Locale

/**
 * Whether two rows are the same recording when one lives on the phone and the
 * other on WebDAV / SMB.
 *
 * A private library is usually the same batch of files in two places: kept on
 * the device *and* uploaded to the server. They arrive with different
 * `videoId`s (`content://…` vs `webdav:https://…`), so nothing that keys on the
 * id can see the duplication. What both copies do share is the file name —
 * except that one was renamed by hand on the way up, with a space or a
 * full-width bracket added — so the name is normalised rather than compared.
 *
 * Deliberately conservative: whitespace and character width are folded, but
 * bracketed version suffixes (`(Live)`, `JOKER 2018Mix`) are **not** stripped.
 * Stripping them would silently merge a remix with its original, and there is
 * no way to tell afterwards that it happened.
 */
object SongIdentity {

    /**
     * A key for a file path or URL, or null when there is nothing usable.
     *
     * `https://host/dav/Artist - Title.flac` and
     * `/storage/Music/Artist－Title.flac` both reduce to the same stem.
     */
    fun fileKey(raw: String): String? {
        val clean = raw.substringBefore('?').substringBefore('#').replace('\\', '/')
        val name = clean.substringAfterLast('/')
        val cut = name.lastIndexOf('.')
        val stem = if (cut > 0) name.substring(0, cut) else name
        return normalize(stem).takeIf { it.isNotEmpty() }
    }

    /**
     * Fallback identity when no path is available (streamed tracks, restored
     * listening history). Keys on title + lead artist, not the whole credit, so
     * two copies tagged `王梓钰/音阙诗听` and `王梓钰_音阙诗听` still meet.
     */
    fun titleKey(title: String, artist: String): String =
        normalize(title) + "|" + ArtistNames.key(artist)

    /** Lowercase, fold full-width ASCII, drop whitespace. */
    fun normalize(text: String): String {
        val out = StringBuilder(text.length)
        for (ch in text) {
            when {
                // U+3000 is a space by Unicode but not by Character.isWhitespace.
                ch == '\u3000' || ch.isWhitespace() -> Unit
                // Char - Char yields an Int (the code-point difference), which
                // StringBuilder would append as "40" instead of "(". Subtract
                // an Int so Kotlin picks Char.minus(Int): Char.
                ch in '\uFF01'..'\uFF5E' -> out.append((ch.code - 0xFEE0).toChar())
                else -> out.append(ch)
            }
        }
        return out.toString().lowercase(Locale.ROOT)
    }
}
