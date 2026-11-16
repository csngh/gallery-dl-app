package app.gallerydl.ui.components

import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp

/**
 * A clear ring around whatever has the focus, for the D-pad of a TV remote (or a keyboard). The system draws a faint
 * highlight on its own, which is easy to lose on a TV across the room; this one is not. Put it before the part that takes
 * the focus (before `clickable`), and it draws nothing for a touch.
 */
fun Modifier.focusRing(shape: Shape): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val color = MaterialTheme.colorScheme.primary
    onFocusChanged { focused = it.isFocused }.then(if (focused) Modifier.border(3.dp, color, shape) else Modifier)
}

/**
 * What a long press does for a remote: holding the centre button, or pressing Menu, runs [action]. A touch long press is
 * handled by the element itself; this is for the keys. Put it before `clickable`, so the click that the button's release
 * would start is swallowed after a long press.
 *
 * The action runs when the button is let go, not when the hold is recognised: a dialog opened while the button is still
 * down would receive its release and press whatever button it has in focus.
 */
fun Modifier.onRemoteLongPress(action: () -> Unit): Modifier = composed {
    var handled by remember { mutableStateOf(false) }
    onPreviewKeyEvent { event ->
        val select = event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter
        when {
            event.key == Key.Menu && event.type == KeyEventType.KeyUp -> {
                action()
                true
            }
            // Held down: the hold is noted, and the key's repeats are kept away from the element.
            select && event.type == KeyEventType.KeyDown && (handled || event.nativeKeyEvent.isLongPress) -> {
                handled = true
                true
            }
            select && event.type == KeyEventType.KeyUp && handled -> {
                handled = false
                action()
                true
            }
            else -> false
        }
    }
}
