package com.nautile.app.ui.theme

import com.nautile.i18n.tr

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AppTheme(val label: String) { SYSTEM(tr("Système")), DARK(tr("Sombre")), LIGHT(tr("Clair")), OLED(tr("Noir OLED")) }

/** App language; changing it restarts the app. */
enum class AppLanguage(val label: String) { SYSTEM(tr("Système")), FR("Français"), EN("English") }

enum class ButtonStyle(val label: String) { FILLED(tr("Plein")), TONAL(tr("Tonal")), OUTLINE(tr("Contour")) }

/** Button colours from the design; light variants are darker so text stays readable on a light background. */
enum class AccentColor(val label: String, val dark: Long, val darkInk: Long, val light: Long, val lightInk: Long) {
    AMBRE(tr("Ambre"), 0xFFFFB547, 0xFF1A1205, 0xFF9A5B00, 0xFFFFFFFF),
    LAGON(tr("Lagon"), 0xFF3DD6C6, 0xFF04201D, 0xFF0F766E, 0xFFFFFFFF),
    CORAIL(tr("Corail"), 0xFFFF8A6B, 0xFF2A0D05, 0xFFB4432A, 0xFFFFFFFF),
    ALGUE(tr("Algue"), 0xFF7ED957, 0xFF0E2205, 0xFF3F7D1F, 0xFFFFFFFF),
    ABYSSE(tr("Abysse"), 0xFF7AA2F7, 0xFF0A1633, 0xFF2E4FA3, 0xFFFFFFFF),
    NACRE(tr("Nacre"), 0xFFF3A6C8, 0xFF2E0B1C, 0xFFA3255F, 0xFFFFFFFF),
    LAVANDE(tr("Lavande"), 0xFFB39DFF, 0xFF170F38, 0xFF5B3FC4, 0xFFFFFFFF),
    /** Material You: follows the wallpaper (Android 12+), falls back to Ambre. */
    DYNAMIQUE(tr("Dynamique"), 0xFFD9C08C, 0xFF231A06, 0xFF7A5F1E, 0xFFFFFFFF),
}

/** App appearance (not the terminal's colours, which have their own setting). */
class AppearancePrefs(context: Context) {
    private val prefs = context.getSharedPreferences("nautile_appearance", Context.MODE_PRIVATE)

    private fun <E : Enum<E>> read(key: String, values: Array<E>, default: E) =
        prefs.getString(key, null)?.let { v -> values.firstOrNull { it.name == v } } ?: default

    private val _theme = MutableStateFlow(read("theme", AppTheme.entries.toTypedArray(), AppTheme.DARK))
    val theme: StateFlow<AppTheme> = _theme.asStateFlow()
    private val _accent = MutableStateFlow(read("accent", AccentColor.entries.toTypedArray(), AccentColor.AMBRE))
    val accent: StateFlow<AccentColor> = _accent.asStateFlow()
    private val _style = MutableStateFlow(read("style", ButtonStyle.entries.toTypedArray(), ButtonStyle.FILLED))
    val style: StateFlow<ButtonStyle> = _style.asStateFlow()

    val language: AppLanguage get() = read("language", AppLanguage.entries.toTypedArray(), AppLanguage.SYSTEM)
    /** Saved synchronously: the app restarts right after. */
    fun setLanguage(l: AppLanguage) { prefs.edit().putString("language", l.name).commit() }

    fun setTheme(t: AppTheme) { _theme.value = t; prefs.edit().putString("theme", t.name).apply() }
    fun setAccent(a: AccentColor) { _accent.value = a; prefs.edit().putString("accent", a.name).apply() }
    fun setStyle(s: ButtonStyle) { _style.value = s; prefs.edit().putString("style", s.name).apply() }
}
