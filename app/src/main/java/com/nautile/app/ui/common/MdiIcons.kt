package com.nautile.app.ui.common

import android.content.Context
import android.graphics.Paint
import android.graphics.Rect
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.res.ResourcesCompat
import com.nautile.app.R
import com.nautile.app.ui.theme.Nt
import kotlin.math.min

/**
 * Material Design Icons (assets/mdi-icons.txt, shared with the desktop app): their names and their glyphs in the
 * Nerd Font, which carries them all. A group can show one instead of its colour dot.
 */
object MdiIcons {
    @Volatile private var table: Map<String, String>? = null

    /** Name → glyph, in the order of the file (alphabetical). */
    fun all(context: Context): Map<String, String> = table ?: synchronized(this) {
        table ?: context.applicationContext.assets.open("mdi-icons.txt").bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() && !it.startsWith("#") }.mapNotNull { l ->
                val (name, cp) = l.split('\t').takeIf { it.size == 2 } ?: return@mapNotNull null
                name to String(Character.toChars(cp.toInt(16)))
            }.toMap(LinkedHashMap())
        }.also { table = it }
    }

    /** « mdi:server », « server » and « Server » are the same icon. */
    fun normalize(name: String): String = name.trim().lowercase().removePrefix("mdi:").trim()

    fun glyph(context: Context, name: String?): String? = name?.let { all(context)[normalize(it)] }

    /** Shown before any search: what servers and their places often look like. */
    val SUGGESTED = listOf(
        "server", "server-network", "server-security", "database", "nas", "harddisk", "cloud", "cloud-outline", "web", "earth",
        "home", "home-outline", "office-building", "domain", "briefcase", "factory", "school", "router-wireless", "router",
        "lan", "access-point", "wifi", "ethernet", "raspberry-pi", "chip", "memory", "desktop-tower", "monitor", "laptop",
        "cellphone", "television", "printer", "camera", "shield-lock", "lock", "key", "security", "test-tube", "flask",
        "code-braces", "console", "git", "bug", "rocket-launch", "robot", "gamepad-variant", "music", "movie", "star",
        "heart", "flag", "fire", "lightning-bolt", "leaf", "account", "account-group", "folder", "archive", "package-variant",
    )

    /** Names containing every word of [query], those starting with it first; at most [limit]. */
    fun search(context: Context, query: String, limit: Int = 240): List<String> {
        val words = normalize(query).split(' ', '-').filter { it.isNotBlank() }
        val names = all(context).keys
        if (words.isEmpty()) return SUGGESTED.filter { it in names }
        val hits = names.filter { n -> words.all { it in n } }
        val first = words.first()
        return (hits.filter { it.startsWith(first) } + hits.filterNot { it.startsWith(first) }).take(limit)
    }
}

/**
 * One glyph of the Nerd Font drawn to fill [modifier]'s box, centred on its ink: set as text, the « Mono »
 * icons are one narrow cell wide with uneven margins, so they look small and off-centre.
 */
@Composable
fun FontGlyph(glyph: String, color: Color, modifier: Modifier) {
    val context = LocalContext.current
    val typeface = remember { runCatching { ResourcesCompat.getFont(context, R.font.jetbrains_mono_nerd_regular) }.getOrNull() }
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG) }
    val bounds = remember { Rect() }
    Canvas(modifier) {
        paint.typeface = typeface
        paint.color = color.toArgb()
        paint.textSize = 100f
        paint.getTextBounds(glyph, 0, glyph.length, bounds)
        if (bounds.isEmpty) return@Canvas
        paint.textSize = 100f * min(size.width / bounds.width(), size.height / bounds.height())
        paint.getTextBounds(glyph, 0, glyph.length, bounds)
        val x = (size.width - bounds.width()) / 2f - bounds.left
        val y = (size.height - bounds.height()) / 2f - bounds.top
        drawContext.canvas.nativeCanvas.drawText(glyph, x, y, paint)
    }
}

/** A group's mark: its icon in its colour when it has one, else its colour dot ([color] null: a grey dot). */
@Composable
fun GroupMark(icon: String?, color: Color?, iconSize: Dp = 18.dp, dotSize: Dp = 10.dp) {
    val glyph = MdiIcons.glyph(LocalContext.current, icon)
    val tint = color ?: Nt.Border
    if (glyph != null) FontGlyph(glyph, tint, Modifier.size(iconSize))
    else Box(Modifier.size(dotSize).clip(CircleShape).background(tint))
}
