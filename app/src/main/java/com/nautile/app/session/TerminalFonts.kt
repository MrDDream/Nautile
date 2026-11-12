package com.nautile.app.session

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import com.nautile.app.R
import java.io.File

/**
 * A terminal font: bundled (Nerd Font Mono variants, single-width glyphs), the system monospace,
 * or a .ttf/.otf the user imported (copied to `files/fonts/<id>.ttf`).
 */
data class TerminalFont(
    val id: String,
    val name: String,
    /** Bundled font resource; null for the system monospace and imported fonts. */
    val res: Int? = null,
    /** Has the Powerline / Nerd Font glyphs (zsh themes). */
    val nerd: Boolean = false,
    val imported: Boolean = false,
)

object TerminalFonts {
    const val SYSTEM_ID = "system"
    const val DEFAULT_ID = "jetbrains"

    val builtIn = listOf(
        TerminalFont(DEFAULT_ID, "JetBrains Mono", R.font.jetbrains_mono_nerd_regular, nerd = true),
        TerminalFont("fira", "Fira Code", R.font.fira_code_nerd_regular, nerd = true),
        TerminalFont("hack", "Hack", R.font.hack_nerd_regular, nerd = true),
        TerminalFont("source", "Source Code Pro", R.font.source_code_pro_nerd_regular, nerd = true),
        TerminalFont("cascadia", "Cascadia Code", R.font.cascadia_code_nerd_regular, nerd = true),
        TerminalFont(SYSTEM_ID, "Monospace", null),
    )

    private val cache = HashMap<String, Typeface>()

    fun dir(context: Context) = File(context.filesDir, "fonts")
    fun file(context: Context, id: String) = File(dir(context), "$id.ttf")

    /** Loads [font]; falls back to the system monospace when the file is missing or unreadable. */
    fun typeface(context: Context, font: TerminalFont): Typeface = synchronized(cache) {
        cache.getOrPut(font.id) {
            when {
                font.res != null -> runCatching { ResourcesCompat.getFont(context, font.res) }.getOrNull()
                font.imported -> load(file(context, font.id))
                else -> null
            } ?: Typeface.MONOSPACE
        }
    }

    fun forget(id: String) = synchronized(cache) { cache.remove(id) }

    /** Null when Android cannot parse the file as a font. */
    fun load(file: File): Typeface? =
        if (!file.isFile) null else runCatching { Typeface.Builder(file).build() }.getOrNull()

    /** The terminal draws on a fixed grid: a proportional font leaves gaps or overlaps. */
    fun isMonospace(typeface: Typeface): Boolean {
        val p = Paint().apply { this.typeface = typeface; textSize = 48f }
        val widths = listOf("i", "W", "m", "1", ".").map { p.measureText(it) }
        return widths.max() - widths.min() < 0.5f
    }
}
