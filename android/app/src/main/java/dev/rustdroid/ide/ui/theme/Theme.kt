package dev.rustdroid.ide.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider

private val DarkScheme = darkColorScheme(
    primary = md_dark_primary,
    onPrimary = md_dark_onPrimary,
    primaryContainer = md_dark_primaryContainer,
    onPrimaryContainer = md_dark_onPrimaryContainer,
    secondary = md_dark_secondary,
    onSecondary = md_dark_onSecondary,
    secondaryContainer = md_dark_secondaryContainer,
    onSecondaryContainer = md_dark_onSecondaryContainer,
    tertiary = md_dark_tertiary,
    onTertiary = md_dark_onTertiary,
    error = md_dark_error,
    onError = md_dark_onError,
    errorContainer = md_dark_errorContainer,
    onErrorContainer = md_dark_onErrorContainer,
    background = md_dark_background,
    onBackground = md_dark_onBackground,
    surface = md_dark_surface,
    onSurface = md_dark_onSurface,
    surfaceVariant = md_dark_surfaceVariant,
    onSurfaceVariant = md_dark_onSurfaceVariant,
    outline = md_dark_outline,
)

private val LightScheme = lightColorScheme(
    primary = md_light_primary,
    onPrimary = md_light_onPrimary,
    primaryContainer = md_light_primaryContainer,
    onPrimaryContainer = md_light_onPrimaryContainer,
    secondary = md_light_secondary,
    onSecondary = md_light_onSecondary,
    secondaryContainer = md_light_secondaryContainer,
    onSecondaryContainer = md_light_onSecondaryContainer,
    tertiary = md_light_tertiary,
    onTertiary = md_light_onTertiary,
    error = md_light_error,
    onError = md_light_onError,
    errorContainer = md_light_errorContainer,
    onErrorContainer = md_light_onErrorContainer,
    background = md_light_background,
    onBackground = md_light_onBackground,
    surface = md_light_surface,
    onSurface = md_light_onSurface,
    surfaceVariant = md_light_surfaceVariant,
    onSurfaceVariant = md_light_onSurfaceVariant,
    outline = md_light_outline,
)

/** Editor-specific palette (sora themes are separate assets). */
data class EditorPalette(
    val consoleBg: androidx.compose.ui.graphics.Color,
    val stdout: androidx.compose.ui.graphics.Color,
    val stderr: androidx.compose.ui.graphics.Color,
    val system: androidx.compose.ui.graphics.Color,
)

val LocalEditorPalette = staticCompositionLocalOf {
    EditorPalette(
        consoleBg = androidx.compose.ui.graphics.Color(0xFF141311),
        stdout = androidx.compose.ui.graphics.Color(0xFFD4D4D4),
        stderr = androidx.compose.ui.graphics.Color(0xFFFF8A80),
        system = androidx.compose.ui.graphics.Color(0xFF80CBC4),
    )
}

@Composable
fun RustDroidTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val scheme = if (darkTheme) DarkScheme else LightScheme
    val palette = if (darkTheme) {
        EditorPalette(
            consoleBg = androidx.compose.ui.graphics.Color(0xFF141311),
            stdout = androidx.compose.ui.graphics.Color(0xFFD4D4D4),
            stderr = androidx.compose.ui.graphics.Color(0xFFFF8A80),
            system = androidx.compose.ui.graphics.Color(0xFF80CBC4),
        )
    } else {
        EditorPalette(
            consoleBg = androidx.compose.ui.graphics.Color(0xFFF6F2EF),
            stdout = androidx.compose.ui.graphics.Color(0xFF1F1F1F),
            stderr = androidx.compose.ui.graphics.Color(0xFFB3261E),
            system = androidx.compose.ui.graphics.Color(0xFF00695C),
        )
    }
    CompositionLocalProvider(LocalEditorPalette provides palette) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
