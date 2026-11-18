package com.example.qstylemusic.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import android.net.Uri

/** Real album art if the device exposes it, otherwise a gradient placeholder. */
@Composable
fun MusicArt(uri: Uri?, modifier: Modifier = Modifier, cornerRadius: Dp = 16.dp) {
    val clipped = modifier.clip(RoundedCornerShape(cornerRadius))
    val fallback: @Composable () -> Unit = {
        Box(
            Modifier.fillMaxSize().background(
                Brush.linearGradient(
                    listOf(Color(0xFF6552B6), Color(0xFF4D6AB5), Color(0xFF21233F))
                )
            ), contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Rounded.MusicNote, contentDescription = null,
                tint = Color.White.copy(alpha = 0.87f), modifier = Modifier.fillMaxSize(0.43f))
        }
    }
    if (uri == null) {
        Box(clipped, contentAlignment = Alignment.Center) { fallback() }
    } else {
        SubcomposeAsyncImage(
            model = uri,
            contentDescription = "专辑封面",
            contentScale = ContentScale.Crop,
            modifier = clipped,
            loading = { fallback() },
            error = { fallback() }
        )
    }
}
