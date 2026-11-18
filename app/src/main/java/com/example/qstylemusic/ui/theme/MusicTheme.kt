package com.example.qstylemusic.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import com.moriafly.salt.ui.SaltConfigs
import com.moriafly.salt.ui.SaltTheme

/** Salt UI provides the theme foundation; Compose Material3 remains available for controls. */
object MusicTokens {
    val largeRadius = 26.dp
    val cardRadius = 22.dp
    val miniRadius = 20.dp
    val screenSpacing = 18.dp
    val Purple = Color(0xFF9889F7)
}

private val darkScheme = darkColorScheme(
    primary = Color(0xFFB6AAFF),
    onPrimary = Color(0xFF20194D),
    secondary = Color(0xFFAEC8FF),
    background = Color(0xFF10111A),
    surface = Color(0xFF10111A),
    surfaceContainer = Color(0xFF1E2030),
    surfaceContainerHigh = Color(0xFF2B2C41),
    onBackground = Color(0xFFF6F4FF),
    onSurface = Color(0xFFF6F4FF),
    onSurfaceVariant = Color(0xFFB7B6C9)
)

private val lightScheme = lightColorScheme(
    primary = Color(0xFF5D4DBA),
    onPrimary = Color.White,
    secondary = Color(0xFF48699D),
    background = Color(0xFFF9F8FF),
    surface = Color(0xFFF9F8FF),
    surfaceContainer = Color(0xFFF0EDFA),
    surfaceContainerHigh = Color(0xFFE8E3F6),
    onBackground = Color(0xFF171622),
    onSurface = Color(0xFF171622),
    onSurfaceVariant = Color(0xFF626274)
)

@Composable
fun MusicTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    SaltTheme(configs = SaltConfigs.default(isDarkTheme = darkTheme)) {
        MaterialTheme(
            colorScheme = if (darkTheme) darkScheme else lightScheme,
            shapes = Shapes(
                small = RoundedCornerShape(12.dp),
                medium = RoundedCornerShape(18.dp),
                large = RoundedCornerShape(MusicTokens.cardRadius)
            ),
            content = content
        )
    }
}
