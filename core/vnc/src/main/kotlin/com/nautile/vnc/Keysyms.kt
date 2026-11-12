package com.nautile.vnc

/** X11 keysyms, as VNC sends keys: from characters and from Windows virtual-key codes. */
object Keysyms {
    const val BACKSPACE = 0xFF08
    const val TAB = 0xFF09
    const val RETURN = 0xFF0D
    const val ESCAPE = 0xFF1B
    const val DELETE = 0xFFFF
    const val SHIFT_L = 0xFFE1
    const val CONTROL_L = 0xFFE3
    const val ALT_L = 0xFFE9
    const val SUPER_L = 0xFFEB

    /** A character: Latin-1 as itself, the rest as a Unicode keysym. */
    fun of(ch: Char): Int = when {
        ch == '\n' || ch == '\r' -> RETURN
        ch == '\t' -> TAB
        ch == '\b' -> BACKSPACE
        ch.code in 0x20..0x7E || ch.code in 0xA0..0xFF -> ch.code
        else -> 0x01000000 or ch.code
    }

    /** A Windows virtual-key code (as the remote desktop screen sends them), or null. */
    fun fromVk(vk: Int): Int? = when (vk) {
        0x08 -> BACKSPACE
        0x09 -> TAB
        0x0D -> RETURN
        0x10, 0xA0 -> SHIFT_L
        0xA1 -> 0xFFE2
        0x11, 0xA2 -> CONTROL_L
        0xA3 -> 0xFFE4
        0x12, 0xA4 -> ALT_L
        0xA5 -> 0xFFEA
        0x14 -> 0xFFE5 // Caps Lock
        0x1B -> ESCAPE
        0x20 -> 0x0020
        0x21 -> 0xFF55 // Page Up
        0x22 -> 0xFF56 // Page Down
        0x23 -> 0xFF57 // End
        0x24 -> 0xFF50 // Home
        0x25 -> 0xFF51 // Left
        0x26 -> 0xFF52 // Up
        0x27 -> 0xFF53 // Right
        0x28 -> 0xFF54 // Down
        0x2C -> 0xFF61 // Print
        0x2D -> 0xFF63 // Insert
        0x2E -> DELETE
        in 0x30..0x39 -> vk // digits
        in 0x41..0x5A -> vk + 0x20 // letters, lower case
        0x5B -> SUPER_L
        0x5C -> 0xFFEC
        0x5D -> 0xFF67 // Menu
        in 0x60..0x69 -> 0xFFB0 + (vk - 0x60) // keypad digits
        in 0x70..0x87 -> 0xFFBE + (vk - 0x70) // F1…F24
        0xBA -> ';'.code
        0xBB -> '='.code
        0xBC -> ','.code
        0xBD -> '-'.code
        0xBE -> '.'.code
        0xBF -> '/'.code
        0xC0 -> '`'.code
        0xDB -> '['.code
        0xDC -> '\\'.code
        0xDD -> ']'.code
        0xDE -> '\''.code
        else -> null
    }
}
