package com.music.bitchord.data.lyrics

import java.util.Locale

/**
 * 给「匹配歌词」的候选打分，并把为什么说出来。
 *
 * 面板上那条实时加载走的是「谁先带回逐字就用谁」——那是对的取舍，歌词晚一帧都比
 * 错一版好。但手动「匹配歌词」是另一件事：那里慢一点没关系，**错**才是代价，而
 * 现在的问题正是"有的没匹配对"。所以这条路要把所有源等齐、按下面的分排、把候选
 * 摆给人挑。
 *
 * 大多数源只交回歌词行，不交回它匹配到的标题和艺术家，所以真正有判别力的是两个
 * 客观量：**这份词唱到第几秒**，和**这首歌多长**。同名不同版（单曲版 / 专辑版 /
 * 3 分钟剪辑版）最容易在这种情况下露出来——词只覆盖到大半首歌。
 */
data class LyricCandidate(
    /**
     * 当前实现是谁给的：内置源是它的名字，Lyrico 插件是插件自己的名字。
     *
     * 用字符串而不是 [LyricsSource]，是因为插件不在那个枚举里 —— 把候选列表统一成
     * 「一个标签 + 一组行 + 一个分数」，UI 和打分就都不用再分两种情况。
     */
    val label: String,
    val lines: List<LyricLine>,
    val score: Int,
    /** 给人看的一句话：分数是怎么来的，别让人对着数字猜。 */
    val reason: String,
    /** 这份词唱到的最后一刻，拿不到时是 null。 */
    val endsAtMs: Long?,
) {
    /** 歌词第一行，够认出是不是这首歌了。 */
    val preview: String
        get() = lines.firstOrNull { it.text.isNotBlank() }?.text.orEmpty()
}

object LyricScoring {

    /** 自动采用的门槛：不到这个分就不替他决定，把候选交出去。 */
    const val AUTO_ACCEPT_SCORE = 60

    /** 一份逐字 + 时长完全对得上的答案，大概就在这个区间。 */
    private const val MAX_SCORE = 100

    fun candidate(
        label: String,
        lines: List<LyricLine>,
        title: String,
        artist: String,
        durationMs: Long,
        /**
         * 来源自己说它匹配到的标题与艺术家。内置那些源只交回歌词行，问不到就留
         * null —— 不去猜；插件（Lyrico 的 `tags.ti/ar`）会给，给了就参与打分，
         * 这也正是插件能把"同名不同人"挑出来的原因。
         */
        gotTitle: String? = null,
        gotArtist: String? = null,
    ): LyricCandidate {
        val endsAt = endsAt(lines)
        var score = 0
        val reasons = mutableListOf<String>()

        if (lines.none { it.text.isNotBlank() }) {
            return LyricCandidate(label, lines, -100, "没有有效歌词行", endsAt)
        }
        if (lines.any { it.isWordSynced }) {
            score += 22
            reasons += "逐字"
        } else if (lines.any { it.timeMs > 0 }) {
            score += 12
            reasons += "整行同步"
        } else {
            score -= 5
            reasons += "没有时间戳"
        }

        // 时长。这是最强的一条客观证据，所以它的缺失也要说出来。
        if (durationMs > 0 && endsAt != null) {
            val diff = kotlin.math.abs(endsAt - durationMs)
            val ratio = diff.toDouble() / durationMs
            when {
                diff <= 3_000 -> { score += 32; reasons += "时长对得上（差 ${(diff / 1000)} 秒）" }
                diff <= 8_000 -> { score += 20; reasons += "时长接近（差 ${(diff / 1000)} 秒）" }
                diff <= 15_000 -> { score += 8; reasons += "时长略偏（差 ${(diff / 1000)} 秒）" }
                ratio > 0.35 -> { score -= 30; reasons += "时长对不上（差 ${(diff / 1000)} 秒）" }
                else -> { score -= 10; reasons += "时长偏差 ${(diff / 1000)} 秒" }
            }
            // 唱了不到六成首歌的词，多半是另一版（剪辑、现场、只录了前半）。
            val covered = endsAt.toDouble() / durationMs
            when {
                covered >= 0.85 -> { score += 10; reasons += "盖住全曲" }
                covered < 0.6 -> { score -= 15; reasons += "只覆盖 ${(covered * 100).toInt()}%" }
            }
        } else {
            reasons += if (durationMs <= 0) "这首歌的时长未知，没法按时长校验" else "来源没给结束时间"
        }

        // 来源肯报名字的时候才比名字。内置那些源只交回歌词行，问不到就一分不加
        // 一分不减——不去猜，猜了就是把猜的当成测的。
        val wantTitle = normalize(title)
        val gotTitleName = normalize(gotTitle.orEmpty())
        if (wantTitle.isNotBlank() && gotTitleName.isNotBlank()) {
            when {
                gotTitleName == wantTitle -> { score += 25; reasons += "标题一致" }
                gotTitleName.contains(wantTitle) || wantTitle.contains(gotTitleName) -> {
                    score += 8
                    reasons += "标题相近"
                }
                else -> { score -= 25; reasons += "标题不是这首" }
            }
        }
        val gotOne = leadArtist(gotArtist.orEmpty())
        if (gotOne.isNotBlank()) {
            if (artistsAgree(artist, gotOne)) {
                score += 15
                reasons += "唱的人对得上"
            } else {
                score -= 20
                reasons += "唱的人对不上"
            }
        }

        return LyricCandidate(
            label = label,
            lines = lines,
            score = score.coerceIn(-100, MAX_SCORE),
            reason = reasons.joinToString("、"),
            endsAtMs = endsAt,
        )
    }

    /**
     * 这份词唱到的最后一刻。
     *
     * 优先用行自己声明的结束（`sungUntilMs`，A2/TTML 那份逐字里有）；行级的只有
     * 起始时间，就把最后一个非空行的起点当"唱到那儿"——它会略偏小，所以对
     * 3 分钟的歌仍然落在「接近」这一档里，不影响判同名不同版。
     */
    fun endsAt(lines: List<LyricLine>): Long? =
        lines.mapNotNull { line -> line.sungUntilMs?.takeIf { it > line.timeMs } ?: line.timeMs.takeIf { line.text.isNotBlank() } }
            .maxOrNull()?.takeIf { it > 0 }

    /** 大小写、空白、标点、全角括号都不算差异。 */
    fun normalize(text: String): String = buildString {
        for (ch in text.lowercase(Locale.ROOT)) {
            when {
                ch.isLetterOrDigit() -> append(ch)
                // 中文歌名里的、《》、【】只是包装，不算标题的一部分。全角半角
                // 都得认——文件名里十有八九用的是全角那对。
                ch in "《》【】()（）[]「」『』" -> Unit
                else -> Unit
            }
        }
    }

    /** 主唱对不对得上：任一侧的第一个名字出现在另一侧就算。 */
    fun artistsAgree(wanted: String, got: String): Boolean {
        val a = normalize(leadArtist(wanted))
        val b = normalize(leadArtist(got))
        if (a.isBlank() || b.isBlank()) return false
        return a == b || a.contains(b) || b.contains(a)
    }

    /**
     * 一行里的第一个名字。
     *
     * 文件名和标签常把合作者写在一条里（`王梓钰_音阙诗听`、`A & B`、`A、B`、
     * `A, B`、`A vs B`），歌词库按整串去搜一定落空——查的时候用主唱，显示的时候
     * 仍旧用整串。
     */
    fun leadArtist(text: String): String =
        text.split("_", "&", "、", "，", ",", "/", "＋", "+", " vs ", " feat", " Ft ", " ft", "×")
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            .orEmpty()
}
