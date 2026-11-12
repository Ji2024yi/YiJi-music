package com.music.bitchord.data.stats

import java.util.Locale

/**
 * One person behind a credit string, and the key that person is filed under.
 *
 * Listening is counted per *name*, not per id — see [ListeningStats] — so the
 * same artist written two ways becomes two chart rows with their minutes split
 * between them. The private build's library is mostly Chinese files whose
 * artist tag is a whole collaboration: `王梓钰_音阙诗听` on one copy and
 * `王梓钰/音阙诗听` on another are the same singer, and the Replay was showing
 * her twice — once with a picture and once as a bare "王" tile.
 *
 * ## Why the CJK joiners are a separate rule
 *
 * `_` and `/` are how Chinese catalogues join collaborators, but they are also
 * literally part of Latin band names: `AC/DC` must stay one artist, and the
 * scrobbler's own [com.music.bitchord.data.scrobbling] splitter says so too.
 * Slash and underscore therefore only split a credit that contains CJK
 * characters. Everything else keeps the conservative punctuation-only split.
 *
 * Used by both the stats buckets and [ArtistFacts]' cache, so a name reduced
 * for a chart is reduced the same way when its picture is looked up.
 */
object ArtistNames {

    /** The conservative split: punctuation, not words. */
    private val CREDIT_SEPARATORS = Regex(
        """\s*,\s*|\s+&\s+|\s+＆\s+|\s+x\s+|\s+feat\.?\s+|\s+ft\.?\s+|\s+featuring\s+""",
        RegexOption.IGNORE_CASE,
    )

    /** Joiners used by Chinese catalogue tags, applied only to CJK credits. */
    private val CJK_JOINERS = Regex("""\s*[_/／｜|、;；]\s*""")

    /**
     * The lead name of [credit], or null for an empty credit.
     *
     * Only the first name is returned: filing a guest verse as a headline act
     * is how a chart ends up listing one person once solo and again in each
     * collaboration. See [ListeningStats.record] for where this is used.
     */
    fun primary(credit: String): String? {
        val whole = credit.trim()
        if (whole.isEmpty()) return null
        val head = CREDIT_SEPARATORS.split(whole, limit = 2).first().trim()
        val lead = if (containsCjk(whole)) {
            CJK_JOINERS.split(head, limit = 2).first().trim()
        } else {
            head
        }
        return lead.takeIf { it.isNotEmpty() } ?: whole
    }

    /**
     * The key an artist is filed under in a listening bucket or [ArtistFacts].
     *
     * Inner spaces are kept (collapsed to one), which is what the cache has
     * always done: dropping them would invalidate every stored `Bo Peep`-style
     * key on an upgrade. Splitting the credit is new, but it only changes keys
     * for names that were previously filed under a whole collaboration — those
     * are exactly the rows being merged, and the cache answer for them was a
     * miss anyway.
     */
    fun key(name: String): String {
        val lead = (primary(name) ?: name).trim()
        return lead.replace(WHITESPACE, " ").lowercase(Locale.ROOT)
    }

    private val WHITESPACE = Regex("""\s+""")

    private fun containsCjk(text: String): Boolean = text.any { ch ->
        val c = ch.code
        c in 0x3040..0x30FF ||  // kana
            c in 0x3400..0x4DBF || // CJK ext A
            c in 0x4E00..0x9FFF || // CJK unified
            c in 0xAC00..0xD7AF || // hangul
            c in 0xF900..0xFAFF   // compatibility
    }
}
