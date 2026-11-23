package app.gallerydl.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Brand palette matching the launcher icon: deep navy surfaces with teal and violet accents. */
private val BrandDarkColors = darkColorScheme(
    primary = Color(0xFF2EE6C9),
    onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF005046),
    onPrimaryContainer = Color(0xFF8FF5E0),
    secondary = Color(0xFFB9C3FF),
    onSecondary = Color(0xFF1B2678),
    secondaryContainer = Color(0xFF2E3A8C),
    onSecondaryContainer = Color(0xFFDDE1FF),
    tertiary = Color(0xFFFFB86B),
    onTertiary = Color(0xFF4A2800),
    error = Color(0xFFEF5350),
    onError = Color(0xFF2B0606),
    background = Color(0xFF0B1020),
    onBackground = Color(0xFFE3E6F3),
    surface = Color(0xFF0B1020),
    onSurface = Color(0xFFE3E6F3),
    surfaceVariant = Color(0xFF1F2742),
    onSurfaceVariant = Color(0xFFA9B0C9),
    outline = Color(0xFF5A627F),
    outlineVariant = Color(0xFF2B3354),
    surfaceContainerLowest = Color(0xFF070B17),
    surfaceContainerLow = Color(0xFF10162A),
    surfaceContainer = Color(0xFF151B30),
    surfaceContainerHigh = Color(0xFF1B2238),
    surfaceContainerHighest = Color(0xFF232B45),
)

private val BrandLightColors = lightColorScheme(
    primary = Color(0xFF006B5D),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF9AF3DF),
    onPrimaryContainer = Color(0xFF00201B),
    secondary = Color(0xFF4654A0),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDDE1FF),
    onSecondaryContainer = Color(0xFF101A58),
    tertiary = Color(0xFF854E10),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDDBB),
    onTertiaryContainer = Color(0xFF2C1600),
    error = Color(0xFFAD302D),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF7F8FD),
    onBackground = Color(0xFF191D2C),
    surface = Color(0xFFF7F8FD),
    onSurface = Color(0xFF191D2C),
    surfaceVariant = Color(0xFFE2E6F2),
    onSurfaceVariant = Color(0xFF454C62),
    outline = Color(0xFF666E85),
    outlineVariant = Color(0xFFC4CBDE),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF0F2FA),
    surfaceContainer = Color(0xFFEAEDF6),
    surfaceContainerHigh = Color(0xFFE4E8F3),
    surfaceContainerHighest = Color(0xFFDEE3EF),
)

internal val LocalDarkAppearance = staticCompositionLocalOf { true }

@Composable
fun GalleryDlTheme(dark: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalDarkAppearance provides dark) {
        MaterialTheme(
            colorScheme = if (dark) BrandDarkColors else BrandLightColors,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}
