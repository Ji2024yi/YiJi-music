package com.music.bitchord.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * What stands in for a cover that isn't there: a device folder, a playlist
 * with nothing on it yet, an album whose files carry no picture.
 *
 * Apple Music's answer rather than a flat hole the size of a sleeve — a soft
 * diagonal gradient off the surface colour with a glyph for what the thing is.
 * Callers draw it behind the image, so a cover that loads late simply covers
 * it and nothing changes size.
 */
@Composable
fun CoverPlaceholder(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    /** The surface the gradient is cut from — a release page passes its palette's. */
    base: Color = MaterialTheme.colorScheme.surfaceVariant,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    /** The glyph's share of the tile. */
    iconFraction: Float = 0.36f,
    /**
     * 按内容分一块自己的深浅。
     *
     * 没有封面的歌以前全画成同一片灰，一整屏下来像同一张图铺了十几遍——这正是
     * 他被"封面全是一张图"咬过的那类观感。大厂（网易云、Spotify）的占位是**每张
     * 不同但每次都稳定**：从名字哈希出一个固定的明暗。这里只动明暗、不引入色相，
     * 黑白这条不破。传 null 就是原来那片灰，别的调用方不受影响。
     */
    key: String? = null,
) {
    val band = key?.let { name ->
        // 哈希到 0..1；取模再除，免得 hashCode 的负数把插值比例带出区间。
        val seed = ((name.hashCode() % 997) + 997) % 997 / 997f
        listOf(
            lerp(base, tint, 0.10f + seed * 0.26f),
            lerp(base, tint, 0.02f + seed * 0.10f),
        )
    } ?: listOf(lerp(base, tint, 0.16f), base)
    Box(
        modifier = modifier.background(
            Brush.linearGradient(band),
        ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint.copy(alpha = 0.6f),
            modifier = Modifier.fillMaxSize(iconFraction),
        )
    }
}
