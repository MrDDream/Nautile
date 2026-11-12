package com.nautile.app.ui.appearance

import com.nautile.i18n.tr

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nautile.app.NautileApp
import com.nautile.app.ui.common.SectionLabel
import com.nautile.app.ui.common.Segmented
import com.nautile.app.ui.theme.AccentColor
import com.nautile.app.ui.theme.ButtonStyle
import com.nautile.app.ui.theme.Nt

/** « Couleur des boutons » from the design: 8 colours and 3 button styles, with a live preview. */
@Composable
fun AccentScreen(onBack: () -> Unit) {
    val prefs = (LocalContext.current.applicationContext as NautileApp).appearance
    val accent by prefs.accent.collectAsState()
    val style by prefs.style.collectAsState()
    val light = Nt.palette.isLight

    Column(Modifier.fillMaxSize().background(Nt.Bg)) {
        Row(Modifier.padding(start = 4.dp, top = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("Retour aux réglages")) }
            Text(tr("Couleur des boutons"), style = MaterialTheme.typography.titleLarge)
        }
        // Live preview (uses the active palette).
        Column(
            Modifier.padding(horizontal = 20.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Nt.Surface)
                .border(1.dp, Nt.Border, RoundedCornerShape(18.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(tr("Homelab"), true); Chip("VPS", false)
                Box(Modifier.weight(1f))
                Switch(true, {}, colors = SwitchDefaults.colors(checkedTrackColor = Nt.Accent))
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)).background(Nt.Accent.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                    Text("D", color = Nt.Accent, fontWeight = FontWeight.Bold)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("docker-01", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    LinearProgressIndicator(progress = { 0.64f }, color = Nt.Accent, trackColor = Nt.Border, modifier = Modifier.fillMaxWidth())
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f).height(46.dp).clip(RoundedCornerShape(13.dp)).border(1.dp, Nt.Border, RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
                    Text(tr("Enregistrer"), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                }
                Box(
                    Modifier.weight(1f).height(46.dp).clip(RoundedCornerShape(13.dp)).background(Nt.PrimaryContainer)
                        .then(Nt.PrimaryBorder?.let { Modifier.border(2.dp, it, RoundedCornerShape(13.dp)) } ?: Modifier),
                    contentAlignment = Alignment.Center,
                ) { Text(tr("Connecter"), color = Nt.OnPrimaryContainer, fontWeight = FontWeight.SemiBold, fontSize = 14.sp) }
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            SectionLabel(tr("Couleur · %1\$s", accent.label))
            AccentColor.entries.chunked(4).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { a ->
                        val on = a == accent
                        val swatch = Color(if (light) a.light else a.dark)
                        Column(
                            Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(Nt.Surface)
                                .border(2.dp, if (on) Nt.Text else Nt.Border, RoundedCornerShape(14.dp))
                                .clickable { prefs.setAccent(a) }.semantics { contentDescription = a.label; selected = on }
                                .padding(top = 10.dp, bottom = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Box(
                                Modifier.size(34.dp).clip(CircleShape).then(
                                    if (a == AccentColor.DYNAMIQUE) Modifier.background(
                                        Brush.sweepGradient(listOf(Color(0xFFFFB547), Color(0xFFF3A6C8), Color(0xFF7AA2F7), Color(0xFF3DD6C6), Color(0xFF7ED957), Color(0xFFFFB547))),
                                    ) else Modifier.background(swatch),
                                ),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (on) Icon(Icons.Filled.Check, contentDescription = null, tint = Color(if (light) a.lightInk else a.darkInk), modifier = Modifier.size(16.dp))
                            }
                            Text(a.label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Nt.TextSoft)
                        }
                    }
                }
            }
            SectionLabel(tr("Style des boutons"))
            Segmented(ButtonStyle.entries.map { it to it.label }, style, prefs::setStyle)
            Text(
                tr("« Dynamique » reprend la couleur de votre fond d'écran (Material You, Android 12+). Le texte des boutons s'ajuste pour rester lisible."),
                color = Nt.Muted, fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun Chip(label: String, on: Boolean) {
    Box(
        Modifier.height(32.dp).clip(RoundedCornerShape(16.dp))
            .background(if (on) Nt.Accent.copy(alpha = 0.15f) else Color.Transparent)
            .border(BorderStroke(1.dp, if (on) Nt.Accent else Nt.Border), RoundedCornerShape(16.dp)).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (on) Nt.Accent else Nt.TextSoft, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
}
