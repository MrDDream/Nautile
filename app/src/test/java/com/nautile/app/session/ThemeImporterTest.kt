package com.nautile.app.session

import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeImporterTest {
    @Test fun xresources() {
        val t = ThemeImporter.parse(
            "! comment\n*.foreground: #c5c8c6\n*.background: #1d1f21\n*.cursorColor: #ff0000\n" +
                (0..15).joinToString("\n") { "*.color$it: #0000%02x".format(it) },
            "Mon thème",
        )
        assertEquals(0xFF1D1F21, t.background); assertEquals(0xFFC5C8C6, t.foreground); assertEquals(0xFFFF0000, t.cursor)
        assertEquals(0xFF00000F, t.ansi[15]); assertEquals("custom-mon-th-me", t.id)
    }

    @Test fun alacrittyToml() {
        val toml = """
            [colors.primary]
            background = '#282828'
            foreground = "#ebdbb2"
            [colors.normal]
            black = '#000000'
            red = '0xcc241d'
            green = '#98971a'
            yellow = '#d79921'
            blue = '#458588'
            magenta = '#b16286'
            cyan = '#689d6a'
            white = '#a89984'
        """.trimIndent()
        val t = ThemeImporter.parse(toml, "gruv")
        assertEquals(0xFF282828, t.background)
        assertEquals(0xFFCC241D, t.ansi[1])
        assertEquals("bright falls back to normal", 0xFFCC241D, t.ansi[9])
    }

    @Test fun alacrittyYaml() {
        val yaml = """
            colors:
              primary:
                background: '#1d1f21'
                foreground: '#c5c8c6'
              normal:
                black:   '#1d1f21'
                red:     '#cc6666'
                green:   '#b5bd68'
                yellow:  '#f0c674'
                blue:    '#81a2be'
                magenta: '#b294bb'
                cyan:    '#8abeb7'
                white:   '#c5c8c6'
              bright:
                black:   '#666666'
                red:     '#d54e53'
                green:   '#b9ca4a'
                yellow:  '#e7c547'
                blue:    '#7aa6da'
                magenta: '#c397d8'
                cyan:    '#70c0b1'
                white:   '#eaeaea'
        """.trimIndent()
        val t = ThemeImporter.parse(yaml, "tomorrow")
        assertEquals(0xFF1D1F21, t.background); assertEquals(0xFFCC6666, t.ansi[1]); assertEquals(0xFFEAEAEA, t.ansi[15])
    }

    @Test fun itermcolors() {
        fun dict(r: Double, g: Double, b: Double) = "<dict><key>Blue Component</key><real>$b</real><key>Green Component</key><real>$g</real><key>Red Component</key><real>$r</real></dict>"
        val plist = "<plist><dict>" + (0..15).joinToString("") { "<key>Ansi $it Color</key>" + dict(1.0, 0.0, 0.0) } +
            "<key>Background Color</key>" + dict(0.0, 0.0, 0.0) + "<key>Foreground Color</key>" + dict(1.0, 1.0, 1.0) + "</dict></plist>"
        val t = ThemeImporter.parse(plist, "iterm")
        assertEquals(0xFF000000, t.background); assertEquals(0xFFFFFFFF, t.foreground); assertEquals(0xFFFF0000, t.ansi[3])
    }
}
