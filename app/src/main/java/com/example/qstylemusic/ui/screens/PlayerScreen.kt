package com.example.qstylemusic.ui.screens

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.FormatListNumbered
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import com.example.qstylemusic.data.LyricsRepository
import com.example.qstylemusic.data.formatDuration
import com.example.qstylemusic.ui.components.LyricsPanel
import com.example.qstylemusic.ui.components.MusicArt
import com.example.qstylemusic.ui.components.PlayQueueSheet
import com.example.qstylemusic.viewmodel.PlaybackUiState
import com.example.qstylemusic.viewmodel.PlayQueueUiState

/** QPlayer-inspired immersive cover / lyrics layout. Existing playback callbacks stay unchanged. */
@Composable
fun PlayerScreen(
    playback: PlaybackUiState,
    playQueue: PlayQueueUiState,
    isFavorite: Boolean,
    onBack: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onToggleFavorite: () -> Unit,
    onCyclePlayMode: () -> Unit,
    onChooseQueueItem: (Int) -> Unit,
    onlineLyrics: String? = null
) {
    // Opening the player (and switching tracks) now shows lyrics by default.
    var lyricsMode by remember(playback.currentMediaId) { mutableStateOf(true) }
    var menuExpanded by remember { mutableStateOf(false) }
    var queueExpanded by remember { mutableStateOf(false) }
    var lyricsReloadKey by remember(playback.currentMediaId) { mutableIntStateOf(0) }
    var seekPosition by remember(playback.currentMediaId) { mutableStateOf<Float?>(null) }

    val context = LocalContext.current
    val lyricsRepository = remember(context) { LyricsRepository(context.applicationContext) }
    val importLrcLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val songId = playback.currentSongId
        if (uri != null && songId != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // Some document providers grant access only for this session.
            }
            lyricsRepository.setSelectedDocument(songId, uri)
            lyricsReloadKey += 1
            lyricsMode = true
        }
    }

    val actualProgress = if (playback.durationMs > 0L) {
        (playback.positionMs.toFloat() / playback.durationMs).coerceIn(0f, 1f)
    } else 0f

    val white = Color.White
    val muted = white.copy(alpha = 0.68f)

    // Album cover fills the background; a dark overlay preserves lyric contrast.
    Box(Modifier.fillMaxSize().background(Color(0xFF100F14))) {
        MusicArt(
            playback.artworkUri,
            modifier = Modifier.fillMaxSize().blur(72.dp),
            cornerRadius = 0.dp
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFF1F1318).copy(alpha = 0.72f),
                        Color(0xFF1A1517).copy(alpha = 0.79f),
                        Color(0xFF101014).copy(alpha = 0.97f)
                    )
                )
            )
        )

        // The entire page is fixed-height. Only the lyric list scrolls.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 24.dp)
        ) {
            // Top bar: collapse button + three-dot menu only.
            Row(
                modifier = Modifier.fillMaxWidth().height(54.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Rounded.KeyboardArrowDown, "收起播放界面", tint = white)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Actual Media3 playing queue (not the entire local music library).
                    IconButton(onClick = { queueExpanded = true }) {
                        Icon(Icons.Rounded.QueueMusic, "正在播放列表", tint = white)
                    }
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Rounded.MoreVert, "更多选项", tint = white)
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("导入 LRC") },
                                onClick = {
                                    menuExpanded = false
                                    if (playback.currentSongId == null) {
                                        Toast.makeText(context, "在线歌词由音源插件提供；暂不支持手动关联 LRC", Toast.LENGTH_SHORT).show()
                                    } else {
                                        importLrcLauncher.launch(arrayOf("*/*"))
                                    }
                                }
                            )
                        }
                    }
                }
            }

            // Title remains at the top in both album art and lyric mode.
            Text(
                text = playback.title,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = white,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp)
            )
            Text(
                text = playback.artist,
                fontSize = 15.sp,
                color = muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp)
            )

            Spacer(Modifier.height(12.dp))

            // Fill all remaining space between the title and fixed playback controls.
            AnimatedContent(
                targetState = lyricsMode,
                modifier = Modifier.fillMaxWidth().weight(1f),
                label = "封面和歌词切换"
            ) { showLyrics ->
                if (showLyrics) {
                    LyricsPanel(
                        songId = playback.currentSongId,
                        positionMs = playback.positionMs,
                        onSeek = onSeek,
                        onBackToCover = { lyricsMode = false },
                        reloadKey = lyricsReloadKey,
                        onlineLyrics = onlineLyrics,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    BoxWithConstraints(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        val albumSize = minOf(maxWidth - 12.dp, maxHeight * 0.88f, 390.dp)
                        MusicArt(
                            playback.artworkUri,
                            modifier = Modifier.size(albumSize.coerceAtLeast(0.dp))
                                .clickable { lyricsMode = true },
                            cornerRadius = 24.dp
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // Seek bar and controls are pinned to the bottom, not part of a scrollable Column.
            Slider(
                value = seekPosition ?: actualProgress,
                onValueChange = { seekPosition = it },
                onValueChangeFinished = {
                    val progress = seekPosition ?: actualProgress
                    onSeek((progress * playback.durationMs).toLong())
                    seekPosition = null
                },
                enabled = playback.durationMs > 0L,
                modifier = Modifier.fillMaxWidth().height(30.dp),
                colors = SliderDefaults.colors(
                    thumbColor = white,
                    activeTrackColor = white,
                    inactiveTrackColor = white.copy(alpha = 0.28f)
                )
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(formatDuration(playback.positionMs), fontSize = 12.sp, color = muted)
                Text(formatDuration(playback.durationMs), fontSize = 12.sp, color = muted)
            }

            Spacer(Modifier.height(12.dp))
            // Five controls, with play/pause anchored to the exact horizontal center.
            // Modes: order (no repeat) -> list loop -> random -> order.
            val mode = when {
                playback.shuffle -> 2
                playback.repeatMode != Player.REPEAT_MODE_OFF -> 1
                else -> 0
            }
            val modeNames = listOf("顺序播放", "列表循环", "随机播放")
            val nextModeName = modeNames[(mode + 1) % modeNames.size]

            Box(
                modifier = Modifier.fillMaxWidth().height(76.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    modifier = Modifier.align(Alignment.CenterStart),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            onCyclePlayMode()
                            Toast.makeText(context, "已切换至$nextModeName", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.size(46.dp)
                    ) {
                        Icon(
                            imageVector = when (mode) {
                                1 -> Icons.Rounded.Repeat
                                2 -> Icons.Rounded.Shuffle
                                else -> Icons.Rounded.FormatListNumbered
                            },
                            contentDescription = "当前${modeNames[mode]}，点击切换至$nextModeName",
                            tint = white
                        )
                    }
                    IconButton(onClick = onPrevious, modifier = Modifier.size(46.dp)) {
                        Icon(Icons.Rounded.SkipPrevious, "上一曲", tint = white)
                    }
                }

                FilledIconButton(
                    onClick = onToggle,
                    modifier = Modifier.align(Alignment.Center).size(68.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = white,
                        contentColor = Color(0xFF19151D)
                    )
                ) {
                    Icon(
                        if (playback.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        if (playback.isPlaying) "暂停" else "播放",
                        modifier = Modifier.size(34.dp)
                    )
                }

                Row(
                    modifier = Modifier.align(Alignment.CenterEnd),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onNext, modifier = Modifier.size(46.dp)) {
                        Icon(Icons.Rounded.SkipNext, "下一曲", tint = white)
                    }
                    IconButton(
                        onClick = onToggleFavorite,
                        enabled = playback.hasSong,
                        modifier = Modifier.size(46.dp)
                    ) {
                        Icon(
                            if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                            if (isFavorite) "取消收藏" else "收藏",
                            tint = if (isFavorite) white else muted
                        )
                    }
                }
            }
            Spacer(Modifier.height(17.dp))
        }
    }

    if (queueExpanded) {
        PlayQueueSheet(
            playQueue = playQueue,
            onDismiss = { queueExpanded = false },
            onSelect = { index ->
                onChooseQueueItem(index)
                queueExpanded = false
            }
        )
    }
}
