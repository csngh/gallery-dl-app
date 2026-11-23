package app.gallerydl.ui.theme

import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Semantic colours for opposing actions: green for the positive or start action, red for the negative or
 * end action. The foreground and fill use the same colour so it works both on its own and with a label.
 */
object ActionColors {
    val Positive: Color
        @Composable get() = if (LocalDarkAppearance.current) Color(0xFF66BB6A) else Color(0xFF25672C)
    val OnPositive: Color
        @Composable get() = if (LocalDarkAppearance.current) Color(0xFF0B2A10) else Color.White
    val Negative: Color
        @Composable get() = MaterialTheme.colorScheme.error
    val OnNegative: Color
        @Composable get() = MaterialTheme.colorScheme.onError

    @Composable
    fun positiveIcon(): IconButtonColors = iconColors(Positive)

    @Composable
    fun negativeIcon(): IconButtonColors = iconColors(Negative)

    /** An unavailable action keeps the muted disabled style instead of its semantic colour. */
    @Composable
    private fun iconColors(content: Color) = IconButtonDefaults.iconButtonColors(
        contentColor = content,
        disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
    )
}
