package com.music.bitchord.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * iOS 弹窗的共用外壳尺寸和两条共用件。
 *
 * 这些原来和更新弹窗一起住在 `UpdateAvailableDialog.kt` 里。更新体系删掉之后，
 * AccountAlerts、AccountChannelDialog、AppLanguageDialog、AudioPipelineDialog、
 * AudioPopupCard、LyricsSourcesDialog、SongInfoDialog、TranslationLanguageDialog
 * 这八处还在用同一套尺寸和分隔线/按钮，所以把它们单独挪到这个文件——删的是更新
 * 弹窗，不是所有弹窗共用的骨架。
 *
 * 数值原样照搬，没有重新量：改这里会同时挪动那八个弹窗。
 */
internal val ALERT_WIDTH = 270.dp
internal val ALERT_CORNER = 14.dp
internal val ACTION_HEIGHT = 44.dp

/**
 * 弹窗后面的那层压暗。故意是平的——玻璃的是卡片本身，把卡片*后面*的壁纸也糊掉
 * 就没有东西给卡片做磨砂对比了，那正是它先前看起来像一个灰盒子的原因。
 */
internal val SCRIM_COLOR = Color.Black.copy(alpha = 0.28f)

/**
 * One action row in an alert.
 *
 * [emphasised] is the heavier weight for the affirmative choice; the whole point
 * of this alert lineage is that neither choice is a trap, so both stay plain rows.
 *
 * [destructive] is the one exception to that: an action that deletes something is
 * red in this lineage, because the point there *is* to break the symmetry.
 */
@Composable
internal fun AlertAction(
    label: String,
    emphasised: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
    destructive: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(ACTION_HEIGHT)
            // iOS washes the whole row instead of drawing a ripple inside it.
            .background(
                if (pressed) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.09f) else Color.Transparent,
            )
            .clickable(
                enabled = enabled,
                indication = null,
                interactionSource = interactionSource,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontSize = 17.sp,
                fontWeight = if (emphasised) FontWeight.W600 else FontWeight.W400,
            ),
            color = (if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                .copy(alpha = if (enabled) 1f else 0.4f),
        )
    }
}

/** Hairline separator — [HorizontalDivider][androidx.compose.material3.HorizontalDivider]'s 1dp reads as a bar at this scale. */
@Composable
internal fun AlertRule(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(0.5.dp)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)),
    )
}
