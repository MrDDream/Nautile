package com.nautile.app.session

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Terminal appearance settings shared by every session. */
class TerminalPrefs(private val context: Context) {
    private val prefs = context.getSharedPreferences("nautile_terminal", Context.MODE_PRIVATE)

    /** 12sp in px: Termux sizes text in raw pixels. */
    val defaultFontSize: Int = (12 * context.resources.displayMetrics.scaledDensity).toInt()

    private val _fontSize = MutableStateFlow(prefs.getInt(KEY_FONT, defaultFontSize))
    /** Text size in px handed to TerminalView. */
    val fontSize: StateFlow<Int> = _fontSize.asStateFlow()

    private val _custom = MutableStateFlow(loadCustom())
    /** Themes imported by the user (.itermcolors, Xresources, Alacritty). */
    val customThemes: StateFlow<List<TerminalTheme>> = _custom.asStateFlow()

    private val _theme = MutableStateFlow(TerminalThemes.byId(prefs.getString(KEY_THEME, null)))
    val theme: StateFlow<TerminalTheme> = _theme.asStateFlow()

    private val _importedFonts = MutableStateFlow(loadImportedFonts())
    /** Fonts imported by the user (.ttf / .otf), stored under files/fonts. */
    val importedFonts: StateFlow<List<TerminalFont>> = _importedFonts.asStateFlow()

    private val _font = MutableStateFlow(resolveFont(
        // Before the font picker, a switch chose between JetBrains Mono Nerd Font and the system monospace.
        prefs.getString(KEY_FONT_ID, null)
            ?: if (prefs.getBoolean(KEY_NERD, true)) TerminalFonts.DEFAULT_ID else TerminalFonts.SYSTEM_ID,
    ))
    /** Font used by every terminal. */
    val font: StateFlow<TerminalFont> = _font.asStateFlow()

    fun setFont(font: TerminalFont) {
        _font.value = font
        prefs.edit().putString(KEY_FONT_ID, font.id).apply()
    }

    private fun resolveFont(id: String): TerminalFont =
        (TerminalFonts.builtIn + _importedFonts.value).firstOrNull { it.id == id } ?: TerminalFonts.builtIn.first()

    /** Copies [bytes] to app storage; throws when they are not a usable font. Returns the new font, now selected. */
    fun importFont(name: String, bytes: ByteArray): TerminalFont {
        val id = "imp_" + java.util.UUID.randomUUID().toString().take(8)
        val file = TerminalFonts.file(context, id)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        val tf = TerminalFonts.load(file)
        if (tf == null) { file.delete(); error("not a font") }
        val font = TerminalFont(id, name, imported = true)
        saveImportedFonts(_importedFonts.value + font)
        setFont(font)
        return font
    }

    fun removeImportedFont(font: TerminalFont) {
        saveImportedFonts(_importedFonts.value.filter { it.id != font.id })
        TerminalFonts.forget(font.id)
        TerminalFonts.file(context, font.id).delete()
        if (_font.value.id == font.id) setFont(TerminalFonts.builtIn.first())
    }

    private fun saveImportedFonts(list: List<TerminalFont>) {
        _importedFonts.value = list
        val json = kotlinx.serialization.json.buildJsonArray {
            list.forEach { f ->
                add(kotlinx.serialization.json.buildJsonObject {
                    put("id", kotlinx.serialization.json.JsonPrimitive(f.id)); put("name", kotlinx.serialization.json.JsonPrimitive(f.name))
                })
            }
        }
        prefs.edit().putString(KEY_FONTS, json.toString()).apply()
    }

    private fun loadImportedFonts(): List<TerminalFont> = runCatching {
        val arr = kotlinx.serialization.json.Json.parseToJsonElement(prefs.getString(KEY_FONTS, null) ?: return@runCatching emptyList())
            as kotlinx.serialization.json.JsonArray
        arr.map { e ->
            val o = e as kotlinx.serialization.json.JsonObject
            TerminalFont(
                (o["id"] as kotlinx.serialization.json.JsonPrimitive).content,
                (o["name"] as kotlinx.serialization.json.JsonPrimitive).content,
                imported = true,
            )
        }.filter { TerminalFonts.file(context, it.id).isFile }
    }.getOrDefault(emptyList())

    private val _history = MutableStateFlow(prefs.getBoolean(KEY_HISTORY, true))
    /** Remember echoed commands per host for autocompletion. */
    val history: StateFlow<Boolean> = _history.asStateFlow()
    fun setHistory(enabled: Boolean) { _history.value = enabled; prefs.edit().putBoolean(KEY_HISTORY, enabled).apply() }

    private val _haptic = MutableStateFlow(prefs.getBoolean(KEY_HAPTIC, true))
    /** Vibrate briefly when a key of the bar is pressed. */
    val haptic: StateFlow<Boolean> = _haptic.asStateFlow()
    fun setHaptic(enabled: Boolean) { _haptic.value = enabled; prefs.edit().putBoolean(KEY_HAPTIC, enabled).apply() }

    private val _aiLines = MutableStateFlow(prefs.getInt(KEY_AI_LINES, DEFAULT_AI_LINES))
    /** How many terminal lines « Joindre l'écran » sends to the AI assistant (scrollback included). */
    val aiLines: StateFlow<Int> = _aiLines.asStateFlow()
    fun setAiLines(n: Int) { _aiLines.value = n; prefs.edit().putInt(KEY_AI_LINES, n).apply() }

    private val _logSessions = MutableStateFlow(prefs.getBoolean(KEY_LOGS, false))
    /** Record each session's output to a log file. */
    val logSessions: StateFlow<Boolean> = _logSessions.asStateFlow()
    fun setLogSessions(enabled: Boolean) { _logSessions.value = enabled; prefs.edit().putBoolean(KEY_LOGS, enabled).apply() }

    private val _compactKeys = MutableStateFlow(
        prefs.getString(KEY_COMPACT, null)?.split(',')?.filter { it.isNotBlank() && it != "joystick" } ?: DEFAULT_COMPACT_KEYS,
    )
    /** Ids of the keys shown in the bar above the system keyboard, in order. */
    val compactKeys: StateFlow<List<String>> = _compactKeys.asStateFlow()
    fun setCompactKeys(ids: List<String>) { _compactKeys.value = ids; prefs.edit().putString(KEY_COMPACT, ids.joinToString(",")).apply() }

    fun setFontSize(px: Int) {
        val v = px.coerceIn(MIN_FONT, MAX_FONT)
        _fontSize.value = v
        prefs.edit().putInt(KEY_FONT, v).apply()
    }

    private val _cursor = MutableStateFlow(prefs.getInt(KEY_CURSOR, 0))
    /** Termux cursor style: 0 block, 1 underline, 2 bar. */
    val cursorStyle: StateFlow<Int> = _cursor.asStateFlow()
    fun setCursorStyle(style: Int) { _cursor.value = style; prefs.edit().putInt(KEY_CURSOR, style).apply() }

    fun addCustomTheme(theme: TerminalTheme) {
        val list = _custom.value.filter { it.id != theme.id } + theme
        saveCustom(list); setTheme(theme)
    }

    fun removeCustomTheme(theme: TerminalTheme) {
        saveCustom(_custom.value.filter { it.id != theme.id })
        if (_theme.value.id == theme.id) setTheme(TerminalThemes.Nuit)
    }

    private fun saveCustom(list: List<TerminalTheme>) {
        _custom.value = list
        TerminalThemes.custom = list
        val json = kotlinx.serialization.json.buildJsonArray {
            list.forEach { t ->
                add(kotlinx.serialization.json.buildJsonObject {
                    put("id", kotlinx.serialization.json.JsonPrimitive(t.id)); put("name", kotlinx.serialization.json.JsonPrimitive(t.name))
                    put("bg", kotlinx.serialization.json.JsonPrimitive(t.background)); put("fg", kotlinx.serialization.json.JsonPrimitive(t.foreground))
                    put("cursor", kotlinx.serialization.json.JsonPrimitive(t.cursor))
                    put("ansi", kotlinx.serialization.json.JsonArray(t.ansi.map { kotlinx.serialization.json.JsonPrimitive(it) }))
                })
            }
        }
        prefs.edit().putString(KEY_CUSTOM, json.toString()).apply()
    }

    private fun loadCustom(): List<TerminalTheme> = runCatching {
        val arr = kotlinx.serialization.json.Json.parseToJsonElement(prefs.getString(KEY_CUSTOM, null) ?: return@runCatching emptyList()).let { it as kotlinx.serialization.json.JsonArray }
        arr.map { e ->
            val o = e as kotlinx.serialization.json.JsonObject
            fun l(k: String) = (o[k] as kotlinx.serialization.json.JsonPrimitive).content.toLong()
            TerminalTheme(
                (o["id"] as kotlinx.serialization.json.JsonPrimitive).content, (o["name"] as kotlinx.serialization.json.JsonPrimitive).content,
                l("bg"), l("fg"), l("cursor"), (o["ansi"] as kotlinx.serialization.json.JsonArray).map { (it as kotlinx.serialization.json.JsonPrimitive).content.toLong() },
            )
        }
    }.getOrDefault(emptyList()).also { TerminalThemes.custom = it }

    fun setTheme(theme: TerminalTheme) {
        _theme.value = theme
        prefs.edit().putString(KEY_THEME, theme.id).apply()
    }

    companion object {
        const val MIN_FONT = 12
        const val MAX_FONT = 96
        private const val KEY_FONT = "font_px"
        /** Legacy boolean from before the font picker, read once for migration. */
        private const val KEY_NERD = "nerd_font"
        private const val KEY_FONT_ID = "font_id"
        private const val KEY_FONTS = "imported_fonts"
        private const val KEY_HISTORY = "history"
        private const val KEY_LOGS = "log_sessions"
        private const val KEY_HAPTIC = "haptic"
        private const val KEY_AI_LINES = "ai_lines"
        const val DEFAULT_AI_LINES = 100
        val AI_LINE_CHOICES = listOf(40, 100, 200, 500)
        private const val KEY_COMPACT = "compact_keys"
        val DEFAULT_COMPACT_KEYS = listOf("ai", "password", "paste", "ctrl", "esc", "tab", "-", "/", "|", "~", "left", "up", "down", "right", "home", "end", "ctrl_c")
        private const val KEY_THEME = "theme"
        private const val KEY_CURSOR = "cursor_style"
        private const val KEY_CUSTOM = "custom_themes"
    }
}
