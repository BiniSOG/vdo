package com.vdo.app.ui

import android.text.format.Formatter
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

object VdoColors {
    val Navy = Color(0xFF0B1030)
    val NavyDeep = Color(0xFF070B22)
    val Surface = Color(0xFF151B45)
    val SurfaceHigh = Color(0xFF1F2760)
    val Violet = Color(0xFF8B6CFF)
    val Indigo = Color(0xFF5560F0)
    val RoseGold = Color(0xFFE9B59C)
    val Muted = Color(0xFF9AA0C8)
    val Accent = Brush.linearGradient(listOf(Indigo, Violet, Color(0xFFC38BE8)))
    val Background = Brush.verticalGradient(listOf(Color(0xFF10163F), NavyDeep))
}

private val scheme = darkColorScheme(
    primary = VdoColors.Violet,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF3A2F8F),
    onPrimaryContainer = Color(0xFFE6DEFF),
    secondary = VdoColors.RoseGold,
    onSecondary = Color(0xFF3B2015),
    secondaryContainer = Color(0xFF2A2F6B),
    onSecondaryContainer = Color(0xFFE3E5FF),
    tertiary = VdoColors.RoseGold,
    background = VdoColors.Navy,
    onBackground = Color(0xFFECEDFA),
    surface = VdoColors.Navy,
    onSurface = Color(0xFFECEDFA),
    surfaceVariant = VdoColors.Surface,
    onSurfaceVariant = VdoColors.Muted,
    surfaceContainer = VdoColors.Surface,
    surfaceContainerLow = Color(0xFF111740),
    surfaceContainerHigh = VdoColors.SurfaceHigh,
    surfaceContainerHighest = VdoColors.SurfaceHigh,
    outline = Color(0xFF3A4288),
    outlineVariant = Color(0xFF262D66),
    error = Color(0xFFFF8A8A),
)

@Composable
fun VdoTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = Typography(), content = content)
}

fun formatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

@Composable
fun formatSize(bytes: Long): String =
    if (bytes < 0) "—" else Formatter.formatShortFileSize(LocalContext.current, bytes)
