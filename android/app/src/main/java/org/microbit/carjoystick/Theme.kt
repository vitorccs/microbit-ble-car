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

    val actionA = Color(0xFFE84393)
    val actionB = Color(0xFFE74C3C)
    val actionC = Color(0xFF3498DB)
}

const val IDLE_GLYPH = "ᛒ"   // ᛒ
const val LIVE_GLYPH = "✓"   // ✓
