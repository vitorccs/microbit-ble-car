package org.microbit.carjoystick

import androidx.compose.ui.graphics.Color

/** The palette is the web page's CSS custom properties, one for one. */
object Palette {
    val bg = Color(0xFF202124)
    val panel = Color(0xFF292B2F)
    val panelDark = Color(0xFF1A1B1E)
    val text = Color(0xFFF5F5F5)
    val muted = Color(0xFFA9ADB5)
    val blue = Color(0xFF4285F4)
    val blueDark = Color(0xFF2F6EDB)
    val green = Color(0xFF34A853)
    val greenDark = Color(0xFF24883F)
    val red = Color(0xFFE74C3C)

    /** The circle marking the joystick's full throw. */
    val stickRing = Color(0xFF5B5F67)

    /** The direction pad: the cross plate it is drawn on, and an arrow that is
     *  not currently pointing anywhere. A lit arrow is [blue]. */
    val padPlate = Color(0xFF33363C)
    val padArrow = Color(0xFF6B6F78)

    /** The speed button's label: blue enough to read as "how the car moves". */
    val speedText = Color(0xFFCFE0FF)

    val actionA = Color(0xFFE84393)
    val actionB = Color(0xFFE74C3C)
    val actionC = Color(0xFF3498DB)
}

const val IDLE_GLYPH = "ᛒ"   // ᛒ
const val LIVE_GLYPH = "✓"   // ✓
