package io.github.andrea_lyz.assistrestore.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * Baseline Material 3 purple palette, matching the canvas sketch (paletteKey = purple).
 * Dynamic color is deliberately off so the preview looks the same on every device.
 */
@Composable
fun AssistRestoreTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val scheme = if (darkTheme) darkColorScheme() else lightColorScheme()
    MaterialTheme(colorScheme = scheme, content = content)
}
