package com.music.bitchord.ui.components

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme

/**
 * 拟物（neumorphism）开关的绘制。
 *
 * 参数逐条抄自另一个项目里已验证的那套 CSS 组件（浅色 great-skunk-8 / 深色
 * shy-bulldog-11），结构与比例完全一致：
 *
 * * 一块 60×30、圆角 15 的胶囊，**没有灰色槽底、没有描边**；
 * * 外面两层外阴影（左上白、右下灰）做出「凸起」；
 * * 轨道面填 `#ECF0F3`（暗色 `#212121`），把外阴影的中段盖住，只剩边缘光晕；
 * * 里面两层内阴影（左上暗、右下亮）做出「压出一个浅坑」。
 * * 滑块是一根**宽度 200%** 的同色长条，`translateX` 从 −75% 滑到 +25%，
 *   自身两层外阴影被轨道圆角裁掉，只剩贴边柔光——滑块因此像有厚度。
 * * 位移用 400ms 的 `cubic-bezier(0.85,0.05,0.18,1.35)`，末尾有一次很轻的回弹。
 *
 * 只负责画。点击由外层的 `toggleable` 处理（见 [IosSwitch]），免得一次点击翻两次。
 */
@Composable
internal fun NeuSwitchArt(
    checked: Boolean,
    modifier: Modifier = Modifier,
) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val x by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = tween(
            durationMillis = 400,
            easing = CubicBezierEasing(0.85f, 0.05f, 0.18f, 1.35f),
        ),
        label = "neuSwitch",
    )
    Box(modifier.size(60.dp, 30.dp)) {
        Canvas(Modifier.matchParentSize()) {
            drawNeuSwitchArt(dark = dark, x = x)
        }
    }
}

/** 两套配色，值逐字来自原组件源码。 */
private class NeuSkin(
    val surface: Color,
    val outerLight: Color,
    val outerDark: Color,
    val insetLight: Color,
    val insetDark: Color,
    val indicatorBg: Color,
    val indEdgeLight: Color,
    val indEdgeDark: Color,
) {
    companion object {
        val Light = NeuSkin(
            surface = Color(0xFFECF0F3), outerLight = Color(0xFFFFFFFF), outerDark = Color(0xFFD1D9E6),
            insetLight = Color(0xFFFFFFFF), insetDark = Color(0xFFD1D9E6), indicatorBg = Color(0xFFECF0F3),
            indEdgeLight = Color(0xFFFFFFFF), indEdgeDark = Color(0xFFD1D9E6),
        )
        val Dark = NeuSkin(
            surface = Color(0xFF212121), outerLight = Color(0xFF323232), outerDark = Color(0xFF191919),
            insetLight = Color(0xFF323232), insetDark = Color(0xFF191919), indicatorBg = Color(0xFF262626),
            indEdgeLight = Color(0xFF191919), indEdgeDark = Color(0xFF191919),
        )
        fun of(dark: Boolean): NeuSkin = if (dark) Dark else Light
    }
}

private fun DrawScope.drawNeuSwitchArt(dark: Boolean, x: Float) {
    val skin = NeuSkin.of(dark)
    val px8 = 8.dp.toPx()
    val px4 = 4.dp.toPx()
    val px12 = 12.dp.toPx()

    val w = 60.dp.toPx()
    val h = 30.dp.toPx()
    val r = h / 2f

    withTransform({ translate((size.width - w) / 2f, (size.height - h) / 2f) }) {
        // 1) 外阴影（凸起）：先画，位于轨道之下，中段会被轨道面盖住。
        drawSoftRoundRect(w, h, r, Offset(-px8, -px4), blur = px8, color = skin.outerLight)
        drawSoftRoundRect(w, h, r, Offset(px8, px4), blur = px12, color = skin.outerDark)

        // 2) 轨道面。
        drawRoundRect(color = skin.surface, cornerRadius = CornerRadius(r), size = Size(w, h))

        // 3) 内阴影 + 滑块，都裁在轨道圆角内（等价 CSS 的 overflow:hidden）。
        clipPath(Path().apply { addRoundRect(RoundRect(0f, 0f, w, h, r, r)) }) {
            drawInsetRoundRect(w, h, r, Offset(px4, px4), blur = px4, color = skin.insetDark)
            drawInsetRoundRect(w, h, r, Offset(-px4, -px4), blur = px4, color = skin.insetLight)

            val iw = w * 2f
            val from = -0.75f * iw
            val to = 0.25f * iw
            val ix = from + (to - from) * x
            drawSoftRoundRect(iw, h, r, Offset(-px8, -px4), blur = px8, color = skin.indEdgeLight, x = ix)
            drawSoftRoundRect(iw, h, r, Offset(px8, px4), blur = px12, color = skin.indEdgeDark, x = ix)
            drawRoundRect(
                color = skin.indicatorBg,
                topLeft = Offset(ix, 0f),
                size = Size(iw, h),
                cornerRadius = CornerRadius(r),
            )
        }
    }
}

/** 一条 box-shadow 外投影：平移 + 高斯模糊的圆角矩形。 */
private fun DrawScope.drawSoftRoundRect(
    w: Float,
    h: Float,
    radius: Float,
    off: Offset,
    blur: Float,
    color: Color,
    x: Float = 0f,
) {
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color.toArgb()
        maskFilter = android.graphics.BlurMaskFilter(blur, android.graphics.BlurMaskFilter.Blur.NORMAL)
    }
    val rect = android.graphics.RectF(x + off.x, off.y, x + off.x + w, off.y + h)
    drawContext.canvas.nativeCanvas.drawRoundRect(rect, radius, radius, paint)
    paint.maskFilter = null
}

/**
 * CSS `inset` 阴影的近似：把圆角矩形平移后，用与模糊量同宽的描边画一圈。
 * 外层已按轨道圆角裁剪，只剩贴边的一条内阴影带。
 */
private fun DrawScope.drawInsetRoundRect(
    w: Float,
    h: Float,
    radius: Float,
    off: Offset,
    blur: Float,
    color: Color,
) {
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color.toArgb()
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = blur
        maskFilter = android.graphics.BlurMaskFilter(blur, android.graphics.BlurMaskFilter.Blur.NORMAL)
    }
    val rect = android.graphics.RectF(off.x, off.y, off.x + w, off.y + h)
    drawContext.canvas.nativeCanvas.drawRoundRect(rect, radius, radius, paint)
    paint.maskFilter = null
}
