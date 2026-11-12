package com.music.bitchord.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.model.LikeStatus
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.playback.PlaybackPosition
import com.music.bitchord.sharedui.resources.*
import com.music.bitchord.ui.rememberIsForeground
import com.music.bitchord.ui.graphics.ColorUtils
import com.music.bitchord.ui.icons.BitChordIcons
import com.music.bitchord.ui.theme.StatusBarIcons
import com.music.bitchord.ui.theme.rememberArtworkDominant
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.Font
import kotlin.math.abs

private val LetterTop = Color(0xFFD98A4E)
private val LetterMid = Color(0xFFA2673B)
private val LetterBottom = Color(0xFF3D231C)
private val LetterInk = Color(0xFFFBF1E4)
private val LetterCardBlank = Color(0xFFEADBC4)

private const val LETTER_TINT_MS = 240
private const val LETTER_LYRIC_MS = 170
private const val LETTER_ART_MS = 240
private const val SAME_CLOCK_NANOS = 250_000_000L

/** 只负责把相邻封面的调色板提前算进缓存，结果不显示。 */
@Composable
private fun WarmLetterTint(url: String?) {
    rememberArtworkDominant(url, rawUrl = true)
}

/** 把封面主色压到指定明度，再和信纸暖色混合，避免亮封面把奶油色文字吃没。
 *  中性（白/灰/黑）封面也有主色，只是饱和度为 0，压明度后得到中性背景。 */
private fun letterStop(base: Color, artTint: Color?, lightness: Float): Color =
    if (artTint == null) base
    else lerp(base, artTint.atLightness(lightness), 0.70f)

private fun Color.atLightness(lightness: Float): Color {
    val hsl = FloatArray(3).also { ColorUtils.colorToHSL(toArgb(), it) }
    hsl[2] = lightness.coerceIn(0f, 1f)
    return Color(ColorUtils.HSLToColor(hsl))
}

/**
 * 信笺歌词播放样式。和主播放页共用同一份播放状态、歌词数据与底部控件，
 * 只换掉封面、歌词和背景的呈现方式，不引入任何新的音源或网络能力。
 */
@Composable
fun LetterPlayerScreen(
    song: Song,
    queue: List<Song>,
    queueIndex: Int,
    hasPrevious: Boolean,
    hasNext: Boolean,
    lyrics: List<LyricLine>?,
    isPlaying: Boolean,
    isLoading: Boolean,
    position: PlaybackPosition,
    durationMs: Long,
    likeEnabled: Boolean,
    likeStatus: LikeStatus,
    onToggleLike: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onOpenMenu: () -> Unit,
    onLiveCanvasChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(Unit) { onLiveCanvasChanged(false) }
    // 信纸是浅暖色，状态栏图标要反过来用深色，否则旧播放页留下的白图标看不见。
    StatusBarIcons(dark = true)

    // 两款都是 SIL OFL 1.1 的开源手写字体，许可证随字体一起分发。
    val currentFont = FontFamily(Font(Res.font.zhimangxing_regular))
    val bodyFont = FontFamily(Font(Res.font.longcang_regular))
    val offsetMs by PlayerSettings.lyricsOffsetMs.collectAsStateWithLifecycle()
    val lines = lyrics.orEmpty()
    // 播放进度每半秒才上报一次；直接读它，歌词行会比歌声慢半拍。
    // 这里改用和旧播放页同一套逐帧歌词时钟，让行切换落在准确的那一帧。
    val lyricMs = rememberLetterLyricClock(song.videoId, position, offsetMs, isPlaying)
    val activeIndex by remember(lines, lyricMs) {
        derivedStateOf {
            val ms = lyricMs.longValue
            if (lines.isEmpty()) -1
            else lines.indexOfLast { it.timeMs <= ms }
        }
    }
    var fullLyrics by remember(song.videoId) { mutableStateOf(false) }

    // 背景从当前封面取色。用原始 URL 请求，和卡片堆叠共用 Coil 内存缓存；
    // 相邻两首也先解码进调色板缓存，切歌时新颜色首帧就有，不会先闪默认色。
    WarmLetterTint(queue.getOrNull(queueIndex + 1)?.thumbnailUrl)
    WarmLetterTint(queue.getOrNull(queueIndex - 1)?.thumbnailUrl)
    val remoteArt = rememberRemoteArtworkUrl(song)
    val artTint = rememberArtworkDominant(remoteArt, rawUrl = true)
    // 解码期间沿用上一首的颜色，而不是退回默认暖色。
    var heldTint by remember { mutableStateOf<Color?>(null) }
    LaunchedEffect(artTint) { if (artTint != null) heldTint = artTint }
    val shownTint = artTint ?: heldTint
    // 保留 State，在 draw 阶段取值：换歌取色渐变时只重绘背景，
    // 不再让拍立得、手写歌词和 AnimatedContent 跟着每帧重组。
    val top = animateColorAsState(
        letterStop(LetterTop, shownTint, 0.50f), tween(LETTER_TINT_MS), label = "letterTop",
    )
    val mid = animateColorAsState(
        letterStop(LetterMid, shownTint, 0.28f), tween(LETTER_TINT_MS), label = "letterMid",
    )
    val bottom = animateColorAsState(
        letterStop(LetterBottom, shownTint, 0.10f), tween(LETTER_TINT_MS), label = "letterBottom",
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .drawBehind {
                drawRect(Brush.verticalGradient(listOf(top.value, mid.value, bottom.value)))
            },
    ) {
        // 复用原控件，这些控件从 LocalPlayerTint 取色；这里给它们信纸的墨色。
        CompositionLocalProvider(LocalPlayerTint provides LetterInk) {
            LetterPaper()
            Crossfade(targetState = fullLyrics, label = "letterPlayerView") { full ->
                if (full) {
                    LetterLyricsPage(
                        song = song,
                        lines = lines,
                        activeIndex = activeIndex,
                        currentFont = currentFont,
                        bodyFont = bodyFont,
                        hasPrevious = hasPrevious,
                        hasNext = hasNext,
                        onPrevious = onPrevious,
                        onNext = onNext,
                        onBack = { fullLyrics = false },
                    )
                } else {
                    LetterPlayerPage(
                        song = song,
                        queue = queue,
                        queueIndex = queueIndex,
                        hasPrevious = hasPrevious,
                        hasNext = hasNext,
                        lines = lines,
                        activeIndex = activeIndex,
                        isPlaying = isPlaying,
                        isLoading = isLoading,
                        position = position,
                        durationMs = durationMs,
                        likeEnabled = likeEnabled,
                        likeStatus = likeStatus,
                        currentFont = currentFont,
                        bodyFont = bodyFont,
                        onToggleLike = onToggleLike,
                        onPlayPause = onPlayPause,
                        onNext = onNext,
                        onPrevious = onPrevious,
                        onSeek = onSeek,
                        onOpenMenu = onOpenMenu,
                        onOpenLyrics = { fullLyrics = true },
                    )
                }
            }
        }
    }
}

/** 信纸底色上的柔光与极轻的颗粒，静态绘制，不产生逐帧动画。 */
@Composable
private fun LetterPaper() {
    Canvas(Modifier.fillMaxSize()) {
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color.White.copy(alpha = 0.16f), Color.Transparent),
                center = Offset(size.width * 0.22f, size.height * 0.10f),
                radius = size.maxDimension * 0.55f,
            ),
            radius = size.maxDimension * 0.55f,
            center = Offset(size.width * 0.22f, size.height * 0.10f),
        )
        repeat(110) { i ->
            val x = ((i * 73) % 997) / 997f * size.width
            val y = ((i * 311) % 991) / 991f * size.height
            drawCircle(Color.White, radius = 1.1f, center = Offset(x, y), alpha = 0.045f)
        }
    }
}

/**
 * 信笺页自己的逐帧歌词时钟。和旧播放页共用 [LyricClock] 与
 * [adjustedLyricsPosition]（含歌词偏移的符号），只是把结果交给
 * derivedStateOf：帧帧都在算，但只有行号真的变了才触发重组。
 */
@Composable
private fun rememberLetterLyricClock(
    trackKey: Any,
    position: PlaybackPosition,
    offsetMs: Int,
    isPlaying: Boolean,
): MutableLongState {
    val clock = remember(trackKey) {
        mutableLongStateOf(Snapshot.withoutReadObservation {
            adjustedLyricsPosition(position.positionMs, offsetMs)
        })
    }
    val engine = remember(trackKey) { LyricClock(clock.longValue) }
    val foreground = rememberIsForeground()

    // 暂停时每一次读数（含拖动进度条）都是准的。
    if (!isPlaying) {
        LaunchedEffect(engine, position, offsetMs) {
            snapshotFlow { adjustedLyricsPosition(position.positionMs, offsetMs) }
                .collect { ms ->
                    engine.hold(ms, System.nanoTime() / 1_000_000.0)
                    clock.longValue = ms
                }
        }
    }

    LaunchedEffect(engine, position, isPlaying, foreground) {
        if (!isPlaying || !foreground) return@LaunchedEffect
        engine.restart()
        while (true) {
            withFrameNanos { frameNanos ->
                val system = System.nanoTime()
                val now = if (abs(system - frameNanos) < SAME_CLOCK_NANOS) frameNanos else system
                val sampledAt = position.sampledAtNanos
                clock.longValue = engine.frame(
                    nowMs = now / 1_000_000.0,
                    reportedMs = adjustedLyricsPosition(position.positionMs, offsetMs),
                    sampledAtMs = if (sampledAt > 0L) sampledAt / 1_000_000.0 else Double.NaN,
                    discontinuity = (position.seeks.toLong() shl 32) or
                        (offsetMs.toLong() and 0xFFFF_FFFFL),
                )
            }
        }
    }
    return clock
}

/**
 * 拖拽状态。参照开源卡片堆叠（SwipingCards / compose-swipe-cards / c5inco 的
 * SwipeableCards）：只有顶卡持有位移，后面的卡片不跟着平移，而是按拖动进度做
 * scale / offset 的"递进"(promotion)。位移只在 draw phase 的 graphicsLayer 里读，
 * 逐帧不触发重组。
 */
@Stable
private class LetterSwipeState {
    val offset = Animatable(0f)
    var maxDragPx by mutableFloatStateOf(1f)
    val progress: Float get() = (abs(offset.value) / maxDragPx).coerceIn(0f, 1f)
}

@Composable
private fun rememberLetterSwipeState(): LetterSwipeState = remember { LetterSwipeState() }

/** 整叠封面共用一个手势区；松手 spring 回弹。位移怎么用由各张卡片自己决定。 */
@Composable
private fun Modifier.letterSwipeGestures(
    state: LetterSwipeState,
    hasPrevious: Boolean,
    hasNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
): Modifier {
    val scope = rememberCoroutineScope()
    // 回调和开关会在换歌时变，但手势本身不该被重启，否则动画会被打断。
    val currentPrevious by rememberUpdatedState(onPrevious)
    val currentNext by rememberUpdatedState(onNext)
    val previousEnabled by rememberUpdatedState(hasPrevious)
    val nextEnabled by rememberUpdatedState(hasNext)
    val settle = spring<Float>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )
    return pointerInput(Unit) {
        val threshold = 44.dp.toPx()
        val maxDrag = size.width * 0.55f
        state.maxDragPx = maxDrag
        var total = 0f
        detectHorizontalDragGestures(
            onDragStart = { total = 0f },
            onDragEnd = {
                val moved = total
                total = 0f
                scope.launch {
                    when {
                        moved <= -threshold && nextEnabled -> currentNext()
                        moved >= threshold && previousEnabled -> currentPrevious()
                    }
                    state.offset.animateTo(0f, settle)
                }
            },
            onDragCancel = {
                total = 0f
                scope.launch { state.offset.animateTo(0f, settle) }
            },
        ) { _, delta ->
            total += delta
            scope.launch { state.offset.snapTo(total.coerceIn(-maxDrag, maxDrag)) }
        }
    }
}

// ── 图 1：拍立得堆叠 + 手写歌词 + 原控件 ──────────────────────────────

@Composable
private fun LetterPlayerPage(
    song: Song,
    queue: List<Song>,
    queueIndex: Int,
    hasPrevious: Boolean,
    hasNext: Boolean,
    lines: List<LyricLine>,
    activeIndex: Int,
    isPlaying: Boolean,
    isLoading: Boolean,
    position: PlaybackPosition,
    durationMs: Long,
    likeEnabled: Boolean,
    likeStatus: LikeStatus,
    currentFont: FontFamily,
    bodyFont: FontFamily,
    onToggleLike: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onOpenMenu: () -> Unit,
    onOpenLyrics: () -> Unit,
) {
    val scrub = rememberPlayerScrub(song.videoId, position, durationMs)
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 22.dp),
    ) {
        // 小屏上把拍立得整体按比例缩小，别把歌词和下面的控制条挤没了。
        val stackHeight = minOf(226.dp, maxHeight * 0.30f)
        val stackWidth = maxWidth
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(32.dp))
            PolaroidStack(
                song = song,
                queue = queue,
                queueIndex = queueIndex,
                height = stackHeight,
                width = stackWidth,
                hasPrevious = hasPrevious,
                hasNext = hasNext,
                onPrevious = onPrevious,
                onNext = onNext,
            )
            Spacer(Modifier.height(20.dp))
            Text(
                text = song.title,
                color = LetterInk,
                fontSize = 21.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = song.artist,
                color = LetterInk.copy(alpha = 0.78f),
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onOpenLyrics,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                HandwrittenLyrics(lines, activeIndex, currentFont, bodyFont, large = false)
            }
            PlayerScrubber(
                shown = { scrub.shown(position.positionMs, durationMs) },
                durationMs = durationMs,
                loading = isLoading,
                mixing = false,
                transitionWindow = null,
                onScrub = scrub::drag,
                onScrubFinished = { scrub.release { fraction -> onSeek((fraction * durationMs).toLong()) } },
            )
            Spacer(Modifier.height(6.dp))
            TransportRow(
                isPlaying = isPlaying,
                isLoading = isLoading,
                previousEnabled = hasPrevious,
                nextEnabled = hasNext,
                onPrevious = onPrevious,
                onPlayPause = onPlayPause,
                onNext = onNext,
            )
            Spacer(Modifier.height(10.dp))
            LetterActionCapsule(
                likeEnabled = likeEnabled,
                likeStatus = likeStatus,
                onToggleLike = onToggleLike,
                onOpenMenu = onOpenMenu,
                onOpenLyrics = onOpenLyrics,
            )
            Spacer(Modifier.height(18.dp))
        }
    }
}

/** 喜欢 / 更多 / 词 合并成一枚圆角胶囊，三段等宽，外轮廓完全一致。 */
@Composable
private fun LetterActionCapsule(
    likeEnabled: Boolean,
    likeStatus: LikeStatus,
    onToggleLike: () -> Unit,
    onOpenMenu: () -> Unit,
    onOpenLyrics: () -> Unit,
) {
    val liked = likeStatus == LikeStatus.LIKE
    Row(
        modifier = Modifier
            .height(44.dp)
            .clip(RoundedCornerShape(percent = 50))
            .background(LetterInk.copy(alpha = 0.12f))
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (likeEnabled) {
            CapsuleCell(
                highlighted = liked,
                onClick = onToggleLike,
            ) {
                Icon(
                    imageVector = if (liked) BitChordIcons.StarFilled else BitChordIcons.Star,
                    contentDescription = "like",
                    tint = LetterInk.copy(alpha = if (liked) 1f else 0.88f),
                    modifier = Modifier.size(19.dp),
                )
            }
            CapsuleDivider()
        }
        CapsuleCell(onClick = onOpenMenu) {
            Icon(
                imageVector = Icons.Rounded.MoreVert,
                contentDescription = "more",
                tint = LetterInk.copy(alpha = 0.88f),
                modifier = Modifier.size(19.dp),
            )
        }
        CapsuleDivider()
        CapsuleCell(onClick = onOpenLyrics) {
            Text("词", color = LetterInk.copy(alpha = 0.88f), fontSize = 15.sp)
        }
    }
}

@Composable
private fun CapsuleCell(
    highlighted: Boolean = false,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .width(46.dp)
            .height(44.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        // 高亮只是一枚居中的小圆，不再铺满整格；否则方形边缘会从胶囊圆头里凸出来。
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(percent = 50))
                .background(if (highlighted) LetterInk.copy(alpha = 0.20f) else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }
}

/** 短、圆头、垂直居中的细线，不会顶到胶囊上下边缘。 */
@Composable
private fun CapsuleDivider() {
    Box(
        Modifier
            .width(1.dp)
            .height(14.dp)
            .clip(RoundedCornerShape(percent = 50))
            .background(LetterInk.copy(alpha = 0.16f)),
    )
}

@Composable
private fun PolaroidStack(
    song: Song,
    queue: List<Song>,
    queueIndex: Int,
    height: Dp,
    width: Dp,
    hasPrevious: Boolean,
    hasNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    val neighbours = remember(queue, queueIndex) {
        listOf(
            queue.getOrNull(queueIndex - 2)?.thumbnailUrl,
            queue.getOrNull(queueIndex - 1)?.thumbnailUrl,
            queue.getOrNull(queueIndex + 1)?.thumbnailUrl,
            queue.getOrNull(queueIndex + 2)?.thumbnailUrl,
        )
    }
    val farX = minOf(height * 0.646f, width * 0.40f)
    val nearX = minOf(height * 0.416f, width * 0.26f)
    val swipe = rememberLetterSwipeState()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .letterSwipeGestures(swipe, hasPrevious, hasNext, onPrevious, onNext),
        contentAlignment = Alignment.Center,
    ) {
        // 只有最上面那张跟手；后面几张留在原位，按拖动进度微微放大做递进。
        // 队列不够长时没封面的位置直接不画，换歌瞬间不会留下一张半透明空卡。
        neighbours[0]?.let { PolaroidCard(it, -farX, -16f, height * 0.496f, dim = true, swipe = swipe) }
        neighbours[3]?.let { PolaroidCard(it, farX, 15f, height * 0.496f, dim = true, swipe = swipe) }
        neighbours[1]?.let { PolaroidCard(it, -nearX, -9f, height * 0.655f, swipe = swipe) }
        neighbours[2]?.let { PolaroidCard(it, nearX, 10f, height * 0.655f, swipe = swipe) }
        PolaroidCard(song.artworkAt(500), 0.dp, 0f, height * 0.84f, swipe = swipe, isTop = true)
    }
}

@Composable
private fun PolaroidCard(
    url: String?,
    x: Dp,
    angle: Float,
    cardSize: Dp,
    dim: Boolean = false,
    swipe: LetterSwipeState? = null,
    isTop: Boolean = false,
) {
    Box(
        modifier = Modifier
            .offset(x = x)
            .size(cardSize)
            .graphicsLayer {
                rotationZ = angle
                if (swipe != null) {
                    val dx = swipe.offset.value
                    if (isTop) {
                        translationX = dx
                        rotationZ = angle + (dx / swipe.maxDragPx).coerceIn(-1f, 1f) * 5f
                    } else {
                        // 背景卡只做 scale 递进，绝不跟着一起平移。
                        val s = 1f + swipe.progress * 0.05f
                        scaleX = s
                        scaleY = s
                    }
                }
            }
            .shadow(9.dp, RoundedCornerShape(10.dp))
            .clip(RoundedCornerShape(10.dp))
            // 不透明的纸色底：图片还没解码出来时是一张空白拍立得，不会透出背景变成残影。
            .background(LetterCardBlank),
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(
            targetState = url,
            animationSpec = tween(LETTER_ART_MS),
            label = "letterCardArt",
        ) { art ->
            if (art != null) {
                AsyncImage(
                    model = art,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        if (dim) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.48f)))
        }
    }
}

// ── 图 2：普通圆角封面 + 信笺歌词 ─────────────────────────────────────

@Composable
private fun LetterLyricsPage(
    song: Song,
    lines: List<LyricLine>,
    activeIndex: Int,
    currentFont: FontFamily,
    bodyFont: FontFamily,
    hasPrevious: Boolean,
    hasNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
) {
    val coverSwipe = rememberLetterSwipeState()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 26.dp)
            // 词字按钮去掉后，点页面任意处回到图 1。
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onBack,
            ),
    ) {
        Column(
            modifier = Modifier.align(Alignment.TopStart).padding(top = 54.dp),
        ) {
            Text(song.title, color = LetterInk, fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(song.artist, color = LetterInk.copy(alpha = 0.8f), fontSize = 14.sp)
        }
        // 普通圆角封面，没有邮票齿孔白边、没有邮戳；换歌时封面淡入淡出。
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 40.dp)
                .size(96.dp)
                .shadow(6.dp, RoundedCornerShape(10.dp))
                .clip(RoundedCornerShape(10.dp))
                .background(LetterCardBlank)
                .letterSwipeGestures(coverSwipe, hasPrevious, hasNext, onPrevious, onNext)
                .graphicsLayer { translationX = coverSwipe.offset.value },
        ) {
            Crossfade(
                targetState = song.artworkAt(400),
                animationSpec = tween(LETTER_ART_MS),
                label = "letterLyricsArt",
            ) { art ->
                if (art != null) {
                    AsyncImage(
                        model = art,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        Box(
            modifier = Modifier.align(Alignment.Center).fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            HandwrittenLyrics(lines, activeIndex, currentFont, bodyFont, large = true)
        }
    }
}

// ── 手写体歌词 ────────────────────────────────────────────────────────

@Composable
private fun HandwrittenLyrics(
    lines: List<LyricLine>,
    activeIndex: Int,
    currentFont: FontFamily,
    bodyFont: FontFamily,
    large: Boolean,
) {
    val reduceAnimation by PlayerSettings.reduceAnimation.collectAsStateWithLifecycle()
    val duration = if (reduceAnimation) 0 else LETTER_LYRIC_MS
    AnimatedContent(
        targetState = activeIndex,
        transitionSpec = {
            // 和旧播放页同一条规矩：新到的一行**不淡入**，第一帧就满强度滑上来。
            // 淡入会让新歌词在整段动画里都还是半透明的，人眼要等它「显出来」
            // 才认为换句了 —— 之前说的「慢一拍」就是这 200ms。旧行淡出 +
            // 新行滑动已经足够柔和，不必再压住新行。
            slideInVertically(tween(duration, easing = FastOutSlowInEasing)) { (it * 0.16f).toInt() }
                .togetherWith(
                    fadeOut(tween(duration / 2, easing = FastOutSlowInEasing)) +
                        slideOutVertically(tween(duration / 2, easing = FastOutSlowInEasing)) { -(it * 0.10f).toInt() }
                )
        },
        label = "letterLyricChange",
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) { index ->
        LetterLyricLines(lines, index, currentFont, bodyFont, large)
    }
}

@Composable
private fun LetterLyricLines(
    lines: List<LyricLine>,
    activeIndex: Int,
    currentFont: FontFamily,
    bodyFont: FontFamily,
    large: Boolean,
) {
    val current = lines.getOrNull(activeIndex)?.text?.takeIf { it.isNotBlank() }
        ?: lines.firstOrNull()?.text.orEmpty()
    val previous = lines.getOrNull(activeIndex - 1)?.text.orEmpty()
    val next = lines.getOrNull(activeIndex + 1)?.text.orEmpty()
    val (first, second) = splitHandLine(current)
    val currentSize = if (large) 37.sp else 31.sp

    Column(modifier = Modifier.fillMaxWidth()) {
        // 已经播过的歌词靠左。
        if (previous.isNotBlank()) {
            Text(
                text = previous,
                color = LetterInk.copy(alpha = 0.42f),
                fontFamily = bodyFont,
                fontSize = 18.sp,
                maxLines = 1,
                textAlign = TextAlign.Start,
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp),
            )
            Spacer(Modifier.height(if (large) 34.dp else 24.dp))
        }
        // 当前句：两行当成一个整体，以「首行首 → 末行尾」的中点对齐屏幕中心。
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.Start) {
                Text(
                    text = first,
                    color = LetterInk,
                    fontFamily = currentFont,
                    fontSize = currentSize,
                    maxLines = 1,
                )
                if (second.isNotEmpty()) {
                    Spacer(Modifier.height(if (large) 26.dp else 20.dp))
                    Text(
                        text = second,
                        color = LetterInk,
                        fontFamily = currentFont,
                        fontSize = currentSize,
                        maxLines = 1,
                        modifier = Modifier.padding(start = if (large) 64.dp else 56.dp),
                    )
                }
            }
        }
        // 还没播到的歌词靠右。
        if (next.isNotBlank()) {
            Spacer(Modifier.height(if (large) 40.dp else 30.dp))
            Text(
                text = next,
                color = LetterInk.copy(alpha = 0.48f),
                fontFamily = bodyFont,
                fontSize = 18.sp,
                maxLines = 1,
                textAlign = TextAlign.End,
                modifier = Modifier.fillMaxWidth().padding(end = 4.dp),
            )
        }
    }
}

/** 长句断成错落的两行，尽量靠中间切开；短句保持一行。internal 供单测直接调。 */
internal fun splitHandLine(text: String): Pair<String, String> {
    if (text.length <= 9) return text to ""
    val cut = (text.length + 1) / 2
    val punctuation = "，。！？、；：,.!? "
    for (delta in 0 until minOf(4, text.length - cut)) {
        val i = cut + delta
        if (text[i] in punctuation) return text.substring(0, i + 1) to text.substring(i + 1)
    }
    return text.substring(0, cut) to text.substring(cut)
}
