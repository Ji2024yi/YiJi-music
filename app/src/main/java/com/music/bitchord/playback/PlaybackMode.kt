package com.music.bitchord.playback

import androidx.media3.common.Player

/**
 * 播放模式只有三态 —— 顺序 / 循环 / 随机。
 *
 * Media3 把这件事拆在两个轴上（`repeatMode` 与队列级的 [QueueShuffle]），所以"三态"
 * 得有个地方把两个轴合起来说清楚：曲目菜单、通知栏那颗钮、播放页那两颗钮读的都是
 * 这一份，各写一遍 `when` 迟早对不上（他这次点的就是"通知里好像只有循环和随机"）。
 *
 * 映射定死：
 *
 *  顺序 = `REPEAT_MODE_OFF` + 不洗牌；
 *  循环 = `REPEAT_MODE_ALL` + 不洗牌；
 *  随机 = `REPEAT_MODE_ALL` + 洗牌 —— 随机听完还是从头再来，主流 App 也是这么走的。
 *
 * 单曲循环（`REPEAT_MODE_ONE`）不在这三态里：它是播放页那颗钮自己的既有行为。读到的
 * 时候归进"循环"显示，从这一行切走时两个轴一起写回，不会让它悄悄停在单曲上。
 */
enum class PlaybackMode {
    SEQUENTIAL,
    LOOP,
    SHUFFLE,
    ;

    /** 点一下换下一个：顺序 → 循环 → 随机 → 顺序。 */
    val next: PlaybackMode
        get() = when (this) {
            SEQUENTIAL -> LOOP
            LOOP -> SHUFFLE
            SHUFFLE -> SEQUENTIAL
        }

    /** 该写回哪两个轴。 */
    val repeatMode: Int
        get() = when (this) {
            SEQUENTIAL -> Player.REPEAT_MODE_OFF
            else -> Player.REPEAT_MODE_ALL
        }

    val shuffled: Boolean
        get() = this == SHUFFLE

    companion object {
        fun of(shuffled: Boolean, repeatMode: Int): PlaybackMode = when {
            shuffled -> SHUFFLE
            repeatMode == Player.REPEAT_MODE_OFF -> SEQUENTIAL
            else -> LOOP
        }
    }
}
