package dev.dialer.voice.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color
import dev.dialer.voice.data.ThemeMode

private val DialerDarkColors = darkColorScheme(
    primary = Color(0xFF5BE8C3),
    onPrimary = Color(0xFF002119),
    primaryContainer = Color(0xFF0B4439),
    onPrimaryContainer = Color(0xFFB8F7E5),
    secondary = Color(0xFF72A9FF),
    onSecondary = Color(0xFF061B38),
    tertiary = Color(0xFFC4A7FF),
    onTertiary = Color(0xFF28144A),
    background = Color(0xFF070A12),
    onBackground = Color(0xFFEAF0FA),
    surface = Color(0xFF0E1522),
    onSurface = Color(0xFFEAF0FA),
    surfaceVariant = Color(0xFF182333),
    onSurfaceVariant = Color(0xFFB2C0D1),
    outline = Color(0xFF39495D),
    error = Color(0xFFFF7777),
    onError = Color(0xFF3B090B),
)

private val DialerLightColors = lightColorScheme(
    primary = Color(0xFF006C47),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF8CF8BE),
    onPrimaryContainer = Color(0xFF002113),
    secondary = Color(0xFF3D6375),
    tertiary = Color(0xFF6C5677),
    background = Color(0xFFF7FAF7),
    onBackground = Color(0xFF191C1A),
    surface = Color(0xFFFBFDF9),
    onSurface = Color(0xFF191C1A),
    surfaceVariant = Color(0xFFE1E9E3),
    onSurfaceVariant = Color(0xFF414943),
    outline = Color(0xFF717973),
    error = Color(0xFFBA1A1A),
)

@Composable
fun DialerTheme(mode: ThemeMode = ThemeMode.System, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    MaterialTheme(
        colorScheme = if (dark) DialerDarkColors else DialerLightColors,
        content = content,
    )
}
