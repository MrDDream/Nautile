package com.nautile.app.ui.settings

import com.nautile.i18n.tr

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.Mouse
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nautile.app.NautileApp
import com.nautile.app.rdp.RdpTouchMode
import com.nautile.app.ui.common.NtCard
import com.nautile.app.ui.common.SectionLabel
import com.nautile.app.ui.common.Segmented
import com.nautile.app.ui.theme.Nt
import com.nautile.rdp.RdpQuality

/** « Bureau à distance »: defaults for every RDP session. */
@Composable
fun RdpSettingsScreen(onBack: () -> Unit) {
    val prefs = (LocalContext.current.applicationContext as NautileApp).sessions.rdp.prefs
    val mode by prefs.touchMode.collectAsState()
    val quality by prefs.quality.collectAsState()
    val clipboard by prefs.clipboard.collectAsState()
    val immersive by prefs.immersive.collectAsState()
    val keyBar by prefs.keyBar.collectAsState()
    val pointer by prefs.pointer.collectAsState()

    SettingsPage(tr("Bureau à distance"), onBack) {
        SectionLabel(tr("Contrôle tactile"))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ModeCard(
                tr("Tactile direct"), tr("Le doigt est le pointeur : on touche là où on clique."),
                Icons.Outlined.TouchApp, mode == RdpTouchMode.DIRECT, Modifier.weight(1f),
            ) { prefs.setTouchMode(RdpTouchMode.DIRECT) }
            ModeCard(
                tr("Pavé tactile"), tr("Le doigt déplace une flèche, comme sur un portable."),
                Icons.Outlined.Mouse, mode == RdpTouchMode.TOUCHPAD, Modifier.weight(1f),
            ) { prefs.setTouchMode(RdpTouchMode.TOUCHPAD) }
        }
        NtCard {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                gestures(mode).forEach { (gesture, action) ->
                    Row {
                        Text(gesture, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(130.dp))
                        Text(action, fontSize = 13.sp, color = Nt.TextSoft)
                    }
                }
            }
        }

        SectionLabel(tr("Qualité d'image"), Modifier.padding(top = 8.dp))
        Segmented(listOf(RdpQuality.AUTO to tr("Auto"), RdpQuality.FAST to tr("Rapide"), RdpQuality.HIGH to tr("Haute")), quality, prefs::setQuality)
        Hint(
            when (quality) {
                RdpQuality.AUTO -> tr("S'adapte au réseau : H.264 matériel quand le serveur le permet.")
                RdpQuality.FAST -> tr("Couleurs réduites et effets coupés, pour la 4G ou un tunnel lent.")
                RdpQuality.HIGH -> tr("Fond d'écran, animations et meilleure image : à réserver au Wi-Fi.")
            },
        )

        SectionLabel(tr("Session"), Modifier.padding(top = 8.dp))
        NtCard {
            ToggleRow(tr("Presse-papier partagé"), tr("Copier sur le PC, coller sur le téléphone, et inversement"), clipboard, onChange = prefs::setClipboard)
            HorizontalDivider(color = Nt.Border)
            ToggleRow(tr("Plein écran"), tr("Masque les barres d'Android pendant la session"), immersive, onChange = prefs::setImmersive)
            HorizontalDivider(color = Nt.Border)
            ToggleRow(tr("Barre de touches avec le clavier"), tr("Ctrl, Alt, ⊞, flèches, F1–F12"), keyBar, onChange = prefs::setKeyBar)
            HorizontalDivider(color = Nt.Border)
            ToggleRow(
                tr("Afficher le pointeur Windows"),
                if (mode == RdpTouchMode.TOUCHPAD) tr("Toujours affiché avec le pavé tactile") else tr("Utile pour viser en tactile direct"),
                pointer || mode == RdpTouchMode.TOUCHPAD, enabled = mode == RdpTouchMode.DIRECT, onChange = prefs::setPointer,
            )
        }
        Hint(tr("Valeurs par défaut pour tous les hôtes RDP ; la qualité et le presse-papier s'appliquent à la prochaine connexion. Résolution, sécurité et son se règlent dans la fiche de chaque hôte."))
    }
}

private fun gestures(mode: RdpTouchMode): List<Pair<String, String>> = when (mode) {
    RdpTouchMode.DIRECT -> listOf(
        tr("Toucher") to tr("Clic gauche"),
        tr("Appui long") to tr("Clic droit, ou glisser si le doigt bouge"),
        tr("Deux doigts") to tr("Molette · toucher = clic droit"),
        tr("Pincer") to tr("Zoom"),
        tr("Un doigt (zoomé)") to tr("Déplacer la vue"),
    )
    RdpTouchMode.TOUCHPAD -> listOf(
        tr("Glisser") to tr("Déplacer la flèche"),
        tr("Toucher") to tr("Clic gauche sous la flèche"),
        tr("Appui long") to tr("Clic droit, ou glisser-déposer si le doigt bouge"),
        tr("Deux doigts") to tr("Molette · toucher = clic droit"),
        tr("Pincer") to tr("Zoom (la vue suit la flèche)"),
    )
}

@Composable
private fun ModeCard(title: String, detail: String, icon: androidx.compose.ui.graphics.vector.ImageVector, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick, shape = RoundedCornerShape(14.dp), color = Nt.Surface,
        modifier = modifier.border(2.dp, if (on) Nt.Accent else Nt.Border, RoundedCornerShape(14.dp))
            .semantics { selected = on; role = Role.RadioButton },
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, contentDescription = null, tint = if (on) Nt.Accent else Nt.Muted, modifier = Modifier.size(26.dp))
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(detail, fontSize = 12.sp, color = Nt.Muted, lineHeight = 16.sp)
        }
    }
}
