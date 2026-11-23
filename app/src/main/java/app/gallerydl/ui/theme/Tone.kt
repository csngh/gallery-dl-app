package app.gallerydl.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * The fixed set of accent tones for icons. Each tone is a pair: the icon colour and a soft tint for its tile.
 * Tone follows meaning and stays consistent: a neutral count is blue, success green, a warning or a partial
 * result amber, failure red, a pending item violet.
 */
enum class Tone(private val darkIcon: Color, private val darkTint: Color, private val lightIcon: Color, private val lightTint: Color) {
    Blue(Color(0xFF6DB0F8), Color(0xFF14253B), Color(0xFF215B91), Color(0xFFDCEEFF)),
    Green(Color(0xFF5FD096), Color(0xFF112A1C), Color(0xFF246440), Color(0xFFDBF5E4)),
    Amber(Color(0xFFF0B45A), Color(0xFF33260C), Color(0xFF794B06), Color(0xFFFFEDD0)),
    Red(Color(0xFFFF8A7E), Color(0xFF3A1815), Color(0xFF99352B), Color(0xFFFFE3DE)),
    Violet(Color(0xFFC4B5FD), Color(0xFF2A1F45), Color(0xFF6046A1), Color(0xFFEDE4FF)),
    Teal(Color(0xFF5FCDE0), Color(0xFF0F2D35), Color(0xFF176475), Color(0xFFD7F4FA)),
    Pink(Color(0xFFF59AC4), Color(0xFF3D1830), Color(0xFF8D3C64), Color(0xFFFBE3EF));

    val icon: Color
        @Composable get() = if (LocalDarkAppearance.current) darkIcon else lightIcon
    val tint: Color
        @Composable get() = if (LocalDarkAppearance.current) darkTint else lightTint
}
