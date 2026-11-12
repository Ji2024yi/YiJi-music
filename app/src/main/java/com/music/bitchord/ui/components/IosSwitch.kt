package com.music.bitchord.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import com.music.bitchord.ui.haptics.Haptic
import com.music.bitchord.ui.haptics.rememberHaptics

/**
 * The iOS 26 switch: a wide green capsule and a white pill of a thumb, which
 * stretches a little toward where it is going while pressed.
 *
 * Plain drawing only — a track, a thumb, a shadow. Every settings page has a
 * column of these, and anything heavier per switch is paid that many times
 * over on every scroll frame.
 *
 * It takes Material3's `Switch` call shape so a settings row adopts it by name
 * alone. [thumbContent] and [colors] are accepted and ignored: on is iOS green
 * whatever the theme's accent — the one place the platform keeps its own
 * colour, and the reason it reads as a switch.
 *
 * A null [onCheckedChange] makes it a read-out, the way M3's does: the row
 * around it owns the tap.
 *
 * Interactive, it takes up M3's 48dp touch target around the drawn capsule, as
 * M3's does. The settings rows were spaced around that height — a sub-row has
 * no top padding of its own — and without it they close up on each other.
 *
 * It buzzes when [checked] flips rather than when it is tapped: most rows also
 * toggle from their own `onClick`, which never passes through here, and the
 * value changing is the one thing every path has in common.
 *
 * There is deliberately no ripple or focus indication here. The default one
 * outlines the whole 48dp touch target as a rectangle around a capsule that is
 * only half that size, which reads as a stray black frame on a dark theme. The
 * pressed/checked state is already drawn by NeuSwitchArt.
 */
@Composable
fun IosSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    @Suppress("UNUSED_PARAMETER") thumbContent: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    @Suppress("UNUSED_PARAMETER") colors: SwitchColors? = null,
) {
    // 外观由 NeuSwitch.kt 负责，这里只留「点击区 + 触感」。
    val haptics = rememberHaptics()
    // Skips the first run, so a switch scrolling into view stays quiet.
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(checked) {
        if (settled) haptics.play(if (checked) Haptic.ToggleOn else Haptic.ToggleOff)
        settled = true
    }
    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .graphicsLayer { alpha = if (enabled) 1f else 0.45f }
            .then(
                if (onCheckedChange != null) {
                    Modifier
                        .minimumInteractiveComponentSize()
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            enabled = enabled,
                            role = Role.Switch,
                            onClick = { onCheckedChange(!checked) },
                        )
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        NeuSwitchArt(checked = checked)
    }
}
