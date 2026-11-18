package com.sikw00f.android.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Dark palette taken from the SiKW00F logo: charcoal surfaces with the badge's steel-cyan accent. */
private val SiKW00FColors = darkColorScheme(
    primary = Color(0xFF7CC8DC),
    onPrimary = Color(0xFF00343F),
    primaryContainer = Color(0xFF1F4A55),
    onPrimaryContainer = Color(0xFFBDEBF7),
    secondary = Color(0xFFA9BCC4),
    onSecondary = Color(0xFF13262D),
    secondaryContainer = Color(0xFF2C3A40),
    onSecondaryContainer = Color(0xFFD5E5EC),
    tertiary = Color(0xFFF2C063),
    onTertiary = Color(0xFF3F2E00),
    tertiaryContainer = Color(0xFF574400),
    onTertiaryContainer = Color(0xFFFFDF9E),
    error = Color(0xFFFF7A70),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF5C1A16),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF121518),
    onBackground = Color(0xFFE1E4E7),
    surface = Color(0xFF121518),
    onSurface = Color(0xFFE1E4E7),
    surfaceVariant = Color(0xFF262B30),
    onSurfaceVariant = Color(0xFFB4BCC3),
    surfaceTint = Color(0xFF7CC8DC),
    outline = Color(0xFF59636B),
    outlineVariant = Color(0xFF353C42),
    inverseSurface = Color(0xFFE1E4E7),
    inverseOnSurface = Color(0xFF2B3035),
    inversePrimary = Color(0xFF00687B),
    scrim = Color(0xFF000000)
)

@Composable
fun SiKW00FTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = SiKW00FColors, content = content)
}
