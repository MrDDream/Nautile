package com.nautile.app.session

import com.nautile.i18n.tr

import com.termux.terminal.TerminalColors
import java.util.Properties

/** A terminal colour scheme: background, foreground, cursor and the 16 ANSI colours. */
data class TerminalTheme(
    val id: String,
    val name: String,
    val background: Long,
    val foreground: Long,
    val cursor: Long,
    val ansi: List<Long>,
) {
    fun toProperties() = Properties().apply {
        setProperty("background", hex(background))
        setProperty("foreground", hex(foreground))
        setProperty("cursor", hex(cursor))
        ansi.forEachIndexed { i, c -> setProperty("color$i", hex(c)) }
    }

    private fun hex(c: Long) = "#%06X".format(c and 0xFFFFFF)
}

object TerminalThemes {
    private fun lighten(c: Long, f: Double): Long {
        val r = ((c shr 16) and 0xFF).toInt(); val g = ((c shr 8) and 0xFF).toInt(); val b = (c and 0xFF).toInt()
        fun l(x: Int) = (x + (255 - x) * f).toInt().coerceIn(0, 255)
        return 0xFF000000 or (l(r).toLong() shl 16) or (l(g).toLong() shl 8) or l(b).toLong()
    }

    /** A theme from the design's swatches; magenta/cyan and bright colours are derived. */
    private fun design(
        id: String, name: String, bg: Long, fg: Long, red: Long, green: Long, yellow: Long, blue: Long,
        magenta: Long, cyan: Long, cursor: Long, black: Long, white: Long,
    ): TerminalTheme {
        val normal = listOf(black, red, green, yellow, blue, magenta, cyan, white)
        return TerminalTheme(id, name, bg, fg, cursor, normal + normal.map { lighten(it, 0.25) })
    }

    val Nuit = design("nautile", tr("Nautile Nuit"), 0xFF07090C, 0xFFD6DDE4, 0xFFFF7A6B, 0xFF5BD69A, 0xFFFFB547, 0xFF7AA2F7, 0xFFC792EA, 0xFF7FDBCA, 0xFFFFB547, 0xFF1B222B, 0xFFC5CED7)
    val Abysse = design("abysse", tr("Abysse"), 0xFF0A1424, 0xFFCFE0F5, 0xFFFF8A8A, 0xFF4FD1C5, 0xFFF5C26B, 0xFF6CB6FF, 0xFFC4A7FF, 0xFF5EE6E0, 0xFF6CB6FF, 0xFF152238, 0xFFCFE0F5)
    val Phosphore = design("phosphore", tr("Phosphore"), 0xFF030A05, 0xFF8EF5A8, 0xFFFF6B6B, 0xFF5BFF8A, 0xFFD4FF6B, 0xFF3FD8B0, 0xFF9BE89F, 0xFF6BFFD8, 0xFF5BFF8A, 0xFF0D1F12, 0xFF8EF5A8)
    val Corail = design("corail", tr("Corail"), 0xFF1A0F0E, 0xFFF3DED8, 0xFFFF7A6B, 0xFF9BD68A, 0xFFFFC46B, 0xFF8FB4F0, 0xFFE59BC4, 0xFF7FD4C8, 0xFFFF9C7A, 0xFF2A1A18, 0xFFF3DED8)
    val Nacre = design("nacre", tr("Nacre"), 0xFF1C1A22, 0xFFE8E2F0, 0xFFF38BA8, 0xFF9EE6B8, 0xFFF7D58B, 0xFFB39DFF, 0xFFF3A6C8, 0xFF94E2D5, 0xFFF3C4DA, 0xFF2A2732, 0xFFE8E2F0)
    val Papier = TerminalTheme(
        "papier", tr("Papier"), 0xFFF6F4EF, 0xFF2A2F36, 0xFF1A1F26,
        listOf(0xFF2A2F36, 0xFFB4322A, 0xFF1F7A4A, 0xFF8A5200, 0xFF2E4FA3, 0xFF8E3A8A, 0xFF1F6F7A, 0xFFD8D3C8,
            0xFF5A636E, 0xFFD0453B, 0xFF2A9A5E, 0xFFB06B00, 0xFF3F64C4, 0xFFAA4EA5, 0xFF2A8A96, 0xFFFFFFFF),
    )
    val Dracula = TerminalTheme(
        "dracula", "Dracula", 0xFF282A36, 0xFFF8F8F2, 0xFFF8F8F2,
        listOf(0xFF21222C, 0xFFFF5555, 0xFF50FA7B, 0xFFF1FA8C, 0xFFBD93F9, 0xFFFF79C6, 0xFF8BE9FD, 0xFFF8F8F2,
            0xFF6272A4, 0xFFFF6E6E, 0xFF69FF94, 0xFFFFFFA5, 0xFFD6ACFF, 0xFFFF92DF, 0xFFA4FFFF, 0xFFFFFFFF),
    )
    val SolarizedDark = TerminalTheme(
        "solarized-dark", tr("Solarized sombre"), 0xFF002B36, 0xFF839496, 0xFF93A1A1,
        listOf(0xFF073642, 0xFFDC322F, 0xFF859900, 0xFFB58900, 0xFF268BD2, 0xFFD33682, 0xFF2AA198, 0xFFEEE8D5,
            0xFF002B36, 0xFFCB4B16, 0xFF586E75, 0xFF657B83, 0xFF839496, 0xFF6C71C4, 0xFF93A1A1, 0xFFFDF6E3),
    )
    val Gruvbox = TerminalTheme(
        "gruvbox", "Gruvbox", 0xFF282828, 0xFFEBDBB2, 0xFFEBDBB2,
        listOf(0xFF282828, 0xFFCC241D, 0xFF98971A, 0xFFD79921, 0xFF458588, 0xFFB16286, 0xFF689D6A, 0xFFA89984,
            0xFF928374, 0xFFFB4934, 0xFFB8BB26, 0xFFFABD2F, 0xFF83A598, 0xFFD3869B, 0xFF8EC07C, 0xFFEBDBB2),
    )
    val Nord = TerminalTheme(
        "nord", "Nord", 0xFF2E3440, 0xFFD8DEE9, 0xFFD8DEE9,
        listOf(0xFF3B4252, 0xFFBF616A, 0xFFA3BE8C, 0xFFEBCB8B, 0xFF81A1C1, 0xFFB48EAD, 0xFF88C0D0, 0xFFE5E9F0,
            0xFF4C566A, 0xFFBF616A, 0xFFA3BE8C, 0xFFEBCB8B, 0xFF81A1C1, 0xFFB48EAD, 0xFF8FBCBB, 0xFFECEFF4),
    )

    /** The design's six themes first, then the classics. */
    val builtIn = listOf(Nuit, Abysse, Phosphore, Corail, Nacre, Papier, Dracula, SolarizedDark, Gruvbox, Nord)

    /** Imported themes, kept up to date by [TerminalPrefs]. */
    @Volatile var custom: List<TerminalTheme> = emptyList()

    val all: List<TerminalTheme> get() = builtIn + custom

    fun byId(id: String?) = all.firstOrNull { it.id == id } ?: Nuit

    /** Applies [theme] to the global scheme; callers then reset each emulator's colours. */
    fun apply(theme: TerminalTheme) = TerminalColors.COLOR_SCHEME.updateWith(theme.toProperties())
}
