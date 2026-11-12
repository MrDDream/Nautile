package com.nautile.app.session

import com.nautile.i18n.tr

/** Reads terminal colour schemes from iTerm2 (.itermcolors), Xresources and Alacritty (TOML or YAML). */
object ThemeImporter {
    private val NAMES = listOf("black", "red", "green", "yellow", "blue", "magenta", "cyan", "white")

    fun parse(text: String, name: String): TerminalTheme {
        val c = when {
            text.contains("<plist") || text.contains("Ansi 0 Color") -> iterm(text)
            Regex("""(?m)^\s*\S*[*.]color\d+\s*:""").containsMatchIn(text) -> xresources(text)
            else -> alacritty(text)
        }
        val ansi = (0 until 16).map { i -> c["color$i"] ?: c["color${i % 8}"] ?: throw IllegalArgumentException(tr("Couleur %1\$s absente du thème", i)) }
        val bg = c["background"] ?: ansi[0]
        val fg = c["foreground"] ?: ansi[7]
        val id = "custom-" + name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        return TerminalTheme(id, name, bg, fg, c["cursor"] ?: fg, ansi)
    }

    private fun hex(v: String): Long? {
        val h = v.trim().trim('\'', '"').removePrefix("#").removePrefix("0x").removePrefix("0X")
        if (h.length != 6 || !h.all { it.isLetterOrDigit() }) return null
        return h.toLongOrNull(16)?.let { 0xFF000000 or it }
    }

    private fun iterm(text: String): Map<String, Long> {
        val out = mutableMapOf<String, Long>()
        val entry = Regex("""<key>([^<]+)</key>\s*<dict>(.*?)</dict>""", RegexOption.DOT_MATCHES_ALL)
        for (m in entry.findAll(text)) {
            val key = m.groupValues[1].trim()
            fun comp(n: String) = Regex("""<key>$n Component</key>\s*<real>([\d.eE+-]+)</real>""").find(m.groupValues[2])?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
            val rgb = 0xFF000000 or ((comp("Red") * 255).toLong().coerceIn(0, 255) shl 16) or
                ((comp("Green") * 255).toLong().coerceIn(0, 255) shl 8) or (comp("Blue") * 255).toLong().coerceIn(0, 255)
            when {
                key.startsWith("Ansi ") -> key.removePrefix("Ansi ").substringBefore(' ').toIntOrNull()?.let { out["color$it"] = rgb }
                key == "Background Color" -> out["background"] = rgb
                key == "Foreground Color" -> out["foreground"] = rgb
                key == "Cursor Color" -> out["cursor"] = rgb
            }
        }
        return out
    }

    private fun xresources(text: String): Map<String, Long> {
        val out = mutableMapOf<String, Long>()
        val line = Regex("""^\s*[\w.*-]*?(color\d+|foreground|background|cursorColor)\s*:\s*(\S+)""")
        text.lines().forEach { l ->
            if (l.trimStart().startsWith("!")) return@forEach
            val m = line.find(l) ?: return@forEach
            val key = if (m.groupValues[1] == "cursorColor") "cursor" else m.groupValues[1]
            hex(m.groupValues[2])?.let { out[key] = it }
        }
        return out
    }

    /** Alacritty: `[colors.normal]` + `red = '#…'` (TOML) or nested `colors: normal: red: '0x…'` (YAML). */
    private fun alacritty(text: String): Map<String, Long> {
        val out = mutableMapOf<String, Long>()
        var section = ""
        val yamlStack = ArrayDeque<Pair<Int, String>>()
        for (raw in text.lines()) {
            val l = raw.substringBefore('#').let { if (raw.contains("'#") || raw.contains("\"#")) raw else it }
            if (l.isBlank()) continue
            val t = l.trim()
            if (t.startsWith("[")) { section = t.trim('[', ']').removePrefix("colors."); continue }
            val indent = l.length - l.trimStart().length
            val sep = if ('=' in t) '=' else ':'
            val key = t.substringBefore(sep).trim().trim('"')
            val value = t.substringAfter(sep, "").trim()
            if (sep == ':' && value.isEmpty()) {
                while (yamlStack.isNotEmpty() && yamlStack.last().first >= indent) yamlStack.removeLast()
                yamlStack.addLast(indent to key); continue
            }
            if (sep == ':') while (yamlStack.isNotEmpty() && yamlStack.last().first >= indent) yamlStack.removeLast()
            val group = if (sep == '=') section else yamlStack.lastOrNull()?.second.orEmpty()
            val color = hex(value.trim(',')) ?: continue
            when {
                group == "normal" && key in NAMES -> out["color${NAMES.indexOf(key)}"] = color
                group == "bright" && key in NAMES -> out["color${NAMES.indexOf(key) + 8}"] = color
                group == "primary" && key in listOf("background", "foreground") -> out[key] = color
                group == "cursor" && key == "cursor" -> out["cursor"] = color
            }
        }
        return out
    }
}
