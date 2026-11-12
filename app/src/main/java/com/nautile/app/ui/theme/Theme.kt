package com.nautile.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.nautile.app.NautileApp

/** Every colour of the app; the active one is [Nt.palette]. */
data class Palette(
    val isLight: Boolean,
    val bg: Color, val bar: Color, val surface: Color, val border: Color, val keyBg: Color,
    val text: Color, val textSoft: Color, val muted: Color, val placeholder: Color,
    val accent: Color, val onAccent: Color,
    val green: Color, val blue: Color, val pink: Color, val grey: Color, val toggle: Color,
    val buttonStyle: ButtonStyle = ButtonStyle.FILLED,
)

private fun dark(accent: Color, ink: Color) = Palette(
    false, Color(0xFF0B0E12), Color(0xFF10141A), Color(0xFF141920), Color(0xFF252E39), Color(0xFF1B222B),
    Color(0xFFE8EDF2), Color(0xFFC5CED7), Color(0xFF94A1AE), Color(0xFF6B7784),
    accent, ink, Color(0xFF5BD69A), Color(0xFF7AA2F7), Color(0xFFF38BA8), Color(0xFF5A6672), Color(0xFF2C3642),
)

private fun oled(accent: Color, ink: Color) = Palette(
    false, Color(0xFF000000), Color(0xFF000000), Color(0xFF0B0B0B), Color(0xFF1E1E1E), Color(0xFF141414),
    Color(0xFFF2F4F7), Color(0xFFC9CED5), Color(0xFF9AA3AD), Color(0xFF6E767F),
    accent, ink, Color(0xFF5BD69A), Color(0xFF7AA2F7), Color(0xFFF38BA8), Color(0xFF3A3F45), Color(0xFF262626),
)

private fun light(accent: Color, ink: Color) = Palette(
    true, Color(0xFFF6F4EF), Color(0xFFFFFFFF), Color(0xFFFFFFFF), Color(0xFFE2DED4), Color(0xFFEFEBE3),
    Color(0xFF1A1F26), Color(0xFF3F4852), Color(0xFF5A636E), Color(0xFF6A7480),
    accent, ink, Color(0xFF1F7A4A), Color(0xFF2E4FA3), Color(0xFFA3254A), Color(0xFF9AA3AD), Color(0xFFD9D4C9),
)

/** The design's tokens, backed by the active [Palette] (changing it recomposes the UI). */
object Nt {
    var palette by mutableStateOf(dark(Color(0xFFFFB547), Color(0xFF1A1205)))

    val Bg get() = palette.bg
    val Bar get() = palette.bar
    val Surface get() = palette.surface
    val Border get() = palette.border
    val KeyBg get() = palette.keyBg
    val Text get() = palette.text
    val TextSoft get() = palette.textSoft
    val Muted get() = palette.muted
    val Placeholder get() = palette.placeholder
    val Accent get() = palette.accent
    val OnAccent get() = palette.onAccent
    val Green get() = palette.green
    val Blue get() = palette.blue
    val Pink get() = palette.pink
    val Grey get() = palette.grey
    val Toggle get() = palette.toggle

    /** Main action buttons (Connecter, Nouvel hôte…) follow the chosen style. */
    val PrimaryContainer get() = when (palette.buttonStyle) {
        ButtonStyle.FILLED -> palette.accent
        ButtonStyle.TONAL -> palette.accent.copy(alpha = 0.16f)
        ButtonStyle.OUTLINE -> Color.Transparent
    }
    val OnPrimaryContainer get() = if (palette.buttonStyle == ButtonStyle.FILLED) palette.onAccent else palette.accent
    /** Border of main buttons (Contour style only). */
    val PrimaryBorder: Color? get() = if (palette.buttonStyle == ButtonStyle.OUTLINE) palette.accent else null

    /** JetBrains Mono (Nerd Font patched), as in the design. */
    val Mono = FontFamily(androidx.compose.ui.text.font.Font(com.nautile.app.R.font.jetbrains_mono_nerd_regular))
}

private val typography = Typography(
    headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 26.sp, letterSpacing = (-0.5).sp),
    titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 22.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 12.sp, letterSpacing = 1.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp),
)

@Composable
fun NautileTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val prefs = (context.applicationContext as NautileApp).appearance
    val theme by prefs.theme.collectAsState()
    val accentChoice by prefs.accent.collectAsState()
    val style by prefs.style.collectAsState()
    val systemDark = isSystemInDarkTheme()
    val isLight = theme == AppTheme.LIGHT || (theme == AppTheme.SYSTEM && !systemDark)

    val palette = remember(theme, accentChoice, style, isLight) {
        var accent = Color(if (isLight) accentChoice.light else accentChoice.dark)
        var ink = Color(if (isLight) accentChoice.lightInk else accentChoice.darkInk)
        if (accentChoice == AccentColor.DYNAMIQUE && Build.VERSION.SDK_INT >= 31) {
            val dyn = if (isLight) dynamicLightColorScheme(context) else dynamicDarkColorScheme(context)
            accent = dyn.primary; ink = dyn.onPrimary
        }
        when {
            isLight -> light(accent, ink)
            theme == AppTheme.OLED -> oled(accent, ink)
            else -> dark(accent, ink)
        }.copy(buttonStyle = style)
    }
    // Written before the content reads it, in the same composition.
    if (Nt.palette != palette) Nt.palette = palette

    LaunchedEffect(isLight, palette.bar) {
        (context as? ComponentActivity)?.enableEdgeToEdge(
            statusBarStyle = if (isLight) SystemBarStyle.light(Color.Transparent.toArgb(), Color.Transparent.toArgb())
            else SystemBarStyle.dark(Color.Transparent.toArgb()),
            navigationBarStyle = if (isLight) SystemBarStyle.light(palette.bar.toArgb(), palette.bar.toArgb())
            else SystemBarStyle.dark(palette.bar.toArgb()),
        )
        (context as? Activity)?.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(palette.bg.toArgb()))
    }

    val p = palette
    val scheme = if (p.isLight) lightColorScheme(
        primary = p.accent, onPrimary = p.onAccent, secondary = p.blue, background = p.bg, onBackground = p.text,
        surface = p.surface, onSurface = p.text, surfaceVariant = p.surface, onSurfaceVariant = p.muted,
        surfaceContainer = p.bar, surfaceContainerHigh = p.surface, outline = p.border, outlineVariant = p.border, error = p.pink,
    ) else darkColorScheme(
        primary = p.accent, onPrimary = p.onAccent, secondary = p.blue, background = p.bg, onBackground = p.text,
        surface = p.surface, onSurface = p.text, surfaceVariant = p.surface, onSurfaceVariant = p.muted,
        surfaceContainer = p.bar, surfaceContainerHigh = p.surface, outline = p.border, outlineVariant = p.border, error = p.pink,
    )
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
