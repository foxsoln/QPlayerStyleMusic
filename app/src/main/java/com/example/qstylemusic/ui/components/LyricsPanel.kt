package com.example.qstylemusic.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.qstylemusic.data.LyricsContent
import com.example.qstylemusic.data.LyricsRepository
import com.example.qstylemusic.data.LrcParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlin.math.abs

/** Items in the lyric list: an optional intro/interlude cue followed by timed lines. */
private sealed interface TimedLyricsRow {
    data class TextLine(val lineIndex: Int) : TimedLyricsRow
    data class ReadyDots(val nextLineIndex: Int) : TimedLyricsRow
}

/** Three dots pre-count the upcoming line; after the line starts the cue fades out. */
@Composable
private fun LyricsReadyDots(remainingMs: Long, active: Boolean) {
    val dotIndex = when {
        remainingMs > 4_000L -> 0
        remainingMs > 2_000L -> 1
        else -> 2
    }
    Row(
        modifier = Modifier.fillMaxWidth().height(51.dp),
        horizontalArrangement = Arrangement.spacedBy(11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(3) { index ->
            val targetAlpha = when {
                !active -> 0f
                index == dotIndex -> 1f
                else -> 0.28f
            }
            val alpha by androidx.compose.animation.core.animateFloatAsState(
                targetValue = targetAlpha,
                animationSpec = tween(durationMillis = 270),
                label = "歌词预备倒计时"
            )
            Box(
                Modifier.size(10.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = alpha))
            )
        }
    }
}


/**
 * Full-height lyrics view. The player controls its height with Modifier.weight(1f).
 * Reads embedded lyrics and any manually associated LRC through LyricsRepository.
 * Swipe left to show album art, swipe vertically to scroll lyrics, tap a line to seek.
 */
@Composable
fun LyricsPanel(
    songId: Long?,
    positionMs: Long,
    onSeek: (Long) -> Unit,
    onBackToCover: () -> Unit,
    modifier: Modifier = Modifier,
    reloadKey: Int = 0,
    onlineLyrics: String? = null,
    timeOffsetMs: Long = 0L // 正数：歌词延后；负数：歌词提前。
) {
    val context = LocalContext.current
    val repository = remember(context) { LyricsRepository(context.applicationContext) }
    val document = remember(songId, reloadKey) {
        songId?.let(repository::selectedDocument)
    }
    var content by remember(songId, reloadKey, onlineLyrics) { mutableStateOf<LyricsContent?>(null) }
    var loading by remember(songId, reloadKey, onlineLyrics) { mutableStateOf(songId != null) }
    var error by remember(songId, reloadKey, onlineLyrics) { mutableStateOf<String?>(null) }

    LaunchedEffect(songId, document, reloadKey, onlineLyrics) {
        content = null
        error = null
        loading = true
        try {
            content = if (onlineLyrics != null) {
                val lines = LrcParser.parse(onlineLyrics)
                LyricsContent(
                    lines = lines,
                    plain = if (lines.isEmpty()) LrcParser.plainLines(onlineLyrics) else emptyList(),
                    source = "网易云在线歌词"
                )
            } else if (songId != null) repository.load(songId, document) else null
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            error = "歌词读取失败：${ex.localizedMessage.orEmpty()}"
        } finally {
            loading = false
        }
    }

    val synced = content?.lines.orEmpty()
    // 使用修正后的歌词时间轴定位当前歌词，不影响音频的实际 positionMs。
    // 正数偏移让歌词晚出现，负数偏移让歌词提前出现。
    val syncPositionMs = positionMs - timeOffsetMs
    val active = remember(synced, syncPositionMs) {
        synced.indexOfLast { it.timeMs <= syncPositionMs }
    }

    // Add a cue before the first line (when the intro is long enough),
    // and before lyrics resume after a long instrumental break.
    val displayRows = remember(synced) {
        buildList<TimedLyricsRow> {
            synced.forEachIndexed { index, line ->
                val previousMs = if (index == 0) 0L else synced[index - 1].timeMs
                val minimumGap = if (index == 0) 2_500L else 10_000L
                if (line.timeMs - previousMs >= minimumGap) {
                    add(TimedLyricsRow.ReadyDots(index))
                }
                add(TimedLyricsRow.TextLine(index))
            }
        }
    }
    val upcomingLineIndex = (active + 1).takeIf { it in synced.indices }
    val upcomingMs = upcomingLineIndex?.let { synced[it].timeMs - syncPositionMs } ?: Long.MAX_VALUE
    val readyIndex = displayRows.indexOfFirst { row ->
        row is TimedLyricsRow.ReadyDots && row.nextLineIndex == upcomingLineIndex
    }
    // Show intro cue immediately; for interludes begin 6 seconds before lyrics resume.
    val showReady = readyIndex >= 0 && upcomingMs > 0L &&
            (active < 0 || upcomingMs <= 6_000L)
    val centeredIndex = if (showReady) {
        readyIndex
    } else {
        displayRows.indexOfFirst {
            it is TimedLyricsRow.TextLine && it.lineIndex == active.coerceAtLeast(0)
        }
    }
    val lyricList = rememberLazyListState()

    // Keep the previously positioned target and viewport size; do not reposition every tick,
    // reposition only on the next timed lyric or viewport change.
    var lastAnchoredRow by remember(songId, reloadKey, onlineLyrics) {
        mutableIntStateOf(-1)
    }
    var lastViewportHeight by remember(songId, reloadKey, onlineLyrics) {
        mutableIntStateOf(0)
    }

    val swipeThreshold = with(LocalDensity.current) { 72.dp.toPx() }
    val latestOnBack = rememberUpdatedState(onBackToCover)

    Box(
        modifier = modifier.pointerInput(swipeThreshold) {
            var totalDrag = 0f
            detectHorizontalDragGestures(
                onDragStart = { totalDrag = 0f },
                onHorizontalDrag = { change, amount ->
                    totalDrag += amount
                    change.consume()
                },
                onDragEnd = {
                    if (totalDrag < -swipeThreshold) latestOnBack.value()
                    totalDrag = 0f
                },
                onDragCancel = { totalDrag = 0f }
            )
        }
    ) {
        val current = content
        when {
            loading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("正在读取歌词…", fontSize = 17.sp, color = Color.White.copy(alpha = 0.6f))
                }
            }
            current == null || !current.exists -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = error ?: "暂无歌词\n点击右上角三个点导入 LRC",
                        textAlign = TextAlign.Center,
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 16.sp
                    )
                }
            }
            current.synced -> {
                // A real spacer before the first lyric gives every line physical room
                // to reach the same anchor. contentPadding does not pin an item once
                // it is scrolled into the middle of the list.
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val anchorDp = maxHeight * 0.40f
                    val anchorPx = with(LocalDensity.current) { anchorDp.roundToPx() }
                    // Essential for the LAST lyric: otherwise the end of the list
                    // clamps the scroll, leaving the highlighted line at the bottom.
                    val tailSpace = (maxHeight - anchorDp) + 32.dp

                    LaunchedEffect(songId, reloadKey, onlineLyrics, displayRows, centeredIndex, anchorPx) {
                        if (centeredIndex < 0) return@LaunchedEffect
                        // Do not attempt to position before the LazyColumn has laid out.
                        val viewportHeight = snapshotFlow {
                            lyricList.layoutInfo.viewportSize.height
                        }.first { it > 0 }

                        // List index 0 is the leading spacer. Timed rows begin at 1.
                        val targetItemIndex = centeredIndex + 1
                        val previousIsNearby = lastAnchoredRow >= 0 &&
                                abs(centeredIndex - lastAnchoredRow) <= 2 &&
                                lastViewportHeight == viewportHeight
                        val visibleTarget = lyricList.layoutInfo.visibleItemsInfo
                            .firstOrNull { it.index == targetItemIndex }

                        if (previousIsNearby && visibleTarget != null) {
                            // Adjacent lyric: correct the measured distance with one
                            // smooth animation, without a jump-to-top intermediate step.
                            val distancePx = (visibleTarget.offset - anchorPx).toFloat()
                            if (abs(distancePx) > 1.5f) {
                                lyricList.animateScrollBy(
                                    value = distancePx,
                                    animationSpec = tween(
                                        durationMillis = 680,
                                        easing = FastOutSlowInEasing
                                    )
                                )
                            }
                        } else {
                            // Initial load, large seek, track switch, or an offscreen line.
                            // Negative offset places the item BELOW the viewport start.
                            // The real leading/trailing spacers prevent end clamping.
                            lyricList.scrollToItem(
                                index = targetItemIndex,
                                scrollOffset = -anchorPx
                            )
                        }

                        lastAnchoredRow = centeredIndex
                        lastViewportHeight = lyricList.layoutInfo.viewportSize.height
                    }

                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        state = lyricList,
                        horizontalAlignment = Alignment.Start
                    ) {
                        item(key = "lyric_leading_space") {
                            Spacer(Modifier.height(anchorDp))
                        }
                        // Preparation marker is part of the same scrolling time line.
                        displayRows.forEachIndexed { rowIndex, row ->
                            when (row) {
                                is TimedLyricsRow.ReadyDots -> {
                                    item(key = "ready_${row.nextLineIndex}") {
                                        Box(Modifier.fillMaxWidth().padding(bottom = 23.dp)) {
                                            LyricsReadyDots(
                                                remainingMs = synced[row.nextLineIndex].timeMs - syncPositionMs,
                                                active = showReady && centeredIndex == rowIndex
                                            )
                                        }
                                    }
                                }
                                is TimedLyricsRow.TextLine -> {
                                    val index = row.lineIndex
                                    val line = synced[index]
                                    item(key = "line_$index") {
                                        val selected = !showReady && active == index
                                        val distance = if (active < 0) 4 else abs(index - active)
                                        val targetColor = when {
                                            selected -> Color.White
                                            distance == 1 -> Color.White.copy(alpha = 0.43f)
                                            distance == 2 -> Color.White.copy(alpha = 0.27f)
                                            else -> Color.White.copy(alpha = 0.17f)
                                        }
                                        // 颜色在两句之间平缓交接，不会瞬间由灰变白。
                                        val lineColor by animateColorAsState(
                                            targetValue = targetColor,
                                            animationSpec = tween(
                                                durationMillis = 500,
                                                easing = FastOutSlowInEasing
                                            ),
                                            label = "歌词高亮渐变"
                                        )

                                        // 高亮使用小幅缩放，且最大仅 1.0 倍。
                                        // 避免旧版 0.90 -> 1.07 的大幅缩放造成视觉跳动。
                                        // 固定字号和行高，整个布局的测量尺寸不会变化。
                                        val lineScale by animateFloatAsState(
                                            targetValue = if (selected) 1.0f else 0.955f,
                                            animationSpec = tween(
                                                durationMillis = 500,
                                                easing = FastOutSlowInEasing
                                            ),
                                            label = "歌词柔和缩放"
                                        )
                                        Text(
                                            text = line.text,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .graphicsLayer {
                                                    scaleX = lineScale
                                                    scaleY = lineScale
                                                    // 左上角固定：多行歌词的首行不再跟着缩放移动。
                                                    transformOrigin = TransformOrigin(0f, 0f)
                                                }
                                                .clickable {
                                                    // 点击歌词时应定位到经过校准的音频时间。
                                                    onSeek((line.timeMs + timeOffsetMs).coerceAtLeast(0L))
                                                }
                                                .padding(top = 3.dp, bottom = 26.dp),
                                            textAlign = TextAlign.Start,
                                            fontSize = 28.sp,
                                            lineHeight = 38.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = lineColor
                                        )
                                    }
                                }
                            }
                        }
                        item(key = "lyric_trailing_space") {
                            Spacer(Modifier.height(tailSpace))
                        }
                    }
                }
            }
            else -> {
                // Untimed embedded lyrics still occupy the full area, without fake sync effects.
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 26.dp),
                    verticalArrangement = Arrangement.spacedBy(22.dp)
                ) {
                    itemsIndexed(current.plain) { _, line ->
                        Text(
                            text = line,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Start,
                            fontSize = 23.sp,
                            lineHeight = 34.sp,
                            color = Color.White.copy(alpha = 0.86f)
                        )
                    }
                }
            }
        }
    }
}
