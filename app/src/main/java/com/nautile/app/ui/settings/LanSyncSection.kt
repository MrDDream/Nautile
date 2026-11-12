package com.nautile.app.ui.settings

import com.nautile.i18n.tr

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nautile.app.NautileApp
import com.nautile.app.backup.lan.LanSync
import com.nautile.app.ui.common.NtTextField
import com.nautile.app.ui.hosts.relativeTime
import com.nautile.app.ui.theme.Nt
import kotlinx.coroutines.launch

private enum class PairStep { CHOOSE, SHOW, JOIN }

/** Synchronisation over the local network: on/off, paired devices, pairing with a 6-digit code. */
@Composable
internal fun LanSyncSection() {
    val app = LocalContext.current.applicationContext as NautileApp
    val lan = app.lanSync
    val scope = rememberCoroutineScope()
    val enabled by lan.enabled.collectAsState()
    val peers by lan.peers.collectAsState()
    val status by lan.status.collectAsState()
    val running by lan.running.collectAsState()
    var pairing by remember { mutableStateOf<PairStep?>(null) }
    var info by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Nt.Surface)
            .border(1.dp, if (enabled && peers.isNotEmpty()) Nt.Green.copy(alpha = 0.45f) else Nt.Border, RoundedCornerShape(16.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconTile(Icons.Outlined.Devices, if (enabled) Nt.Green else Nt.Muted)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("Sur le Wi-Fi, sans cloud"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f, fill = false))
                    IconButton(onClick = { info = true }, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Outlined.Info, contentDescription = tr("Comment fonctionne la synchro sur le Wi-Fi"), tint = Nt.Muted, modifier = Modifier.size(18.dp))
                    }
                }
                Text(
                    when {
                        !enabled -> tr("Directement entre vos appareils, sur le même réseau")
                        peers.isEmpty() -> tr("Aucun appareil associé")
                        else -> tr("%1\$s appareil(s) associé(s)", peers.size)
                    },
                    color = Nt.Muted, fontSize = 12.sp,
                )
            }
            Switch(enabled, { lan.setEnabled(it) }, colors = SwitchDefaults.colors(checkedTrackColor = Nt.Accent, uncheckedTrackColor = Nt.Toggle))
        }
        if (enabled) {
            if (peers.isNotEmpty()) Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Nt.Bg)) {
                peers.forEachIndexed { i, p ->
                    if (i > 0) HorizontalDivider(color = Nt.Border)
                    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.PhoneAndroid, contentDescription = null, tint = Nt.TextSoft, modifier = Modifier.size(18.dp))
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(p.name, fontSize = 14.sp, color = Nt.Text, fontWeight = FontWeight.Medium)
                            Text(if (p.lastSync > 0) tr("Synchro %1\$s", relativeTime(p.lastSync)) else tr("Pas encore synchronisé"), fontSize = 12.sp, color = Nt.Muted)
                        }
                        IconButton(onClick = { lan.forget(p) }) { Icon(Icons.Outlined.Close, contentDescription = tr("Oublier %1\$s", p.name), tint = Nt.Muted) }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { pairing = PairStep.CHOOSE }, modifier = Modifier.weight(1f).height(42.dp)) {
                    Text(tr("Associer"), color = Nt.Text)
                }
                if (peers.isNotEmpty()) OutlinedButton(onClick = { scope.launch { lan.syncAll() } }, enabled = !running, modifier = Modifier.weight(1f).height(42.dp)) {
                    Icon(Icons.Outlined.Sync, contentDescription = null, tint = Nt.Text, modifier = Modifier.size(18.dp))
                    Text("  " + tr("Synchroniser"), color = Nt.Text)
                }
            }
            if (running) LinearProgressIndicator(color = Nt.Accent, trackColor = Nt.Border, modifier = Modifier.fillMaxWidth())
            status?.let { Text(it, color = Nt.TextSoft, fontSize = 13.sp) }
        }
    }

    pairing?.let { step -> PairDialog(lan, step, onStep = { pairing = it }, onDismiss = { lan.cancelCode(); pairing = null }) }
    if (info) AlertDialog(
        onDismissRequest = { info = false }, containerColor = Nt.Surface,
        title = { Text(tr("La synchro sur le Wi-Fi")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(
                    tr("Vos appareils échangent directement quand ils sont sur le même réseau : rien ne passe par Internet ni par un cloud."),
                    tr("Associez-les une fois avec un code à 6 chiffres ; ensuite ils se retrouvent tout seuls, à l'ouverture de l'app et après chaque modification."),
                    tr("La liaison est chiffrée (X25519, AES-256-GCM) et seuls les appareils associés sont acceptés."),
                    tr("Elle se combine avec la synchro par fichier : la modification la plus récente l'emporte partout."),
                ).forEach { line ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("•", color = Nt.Accent, fontSize = 14.sp)
                        Text(line, color = Nt.TextSoft, fontSize = 14.sp, lineHeight = 20.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { info = false }) { Text(tr("Compris"), color = Nt.Accent) } },
    )
}

@Composable
private fun PairDialog(lan: LanSync, step: PairStep, onStep: (PairStep) -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val code by lan.code.collectAsState()
    val found by lan.found.collectAsState()
    val peers by lan.peers.collectAsState()
    var target by remember { mutableStateOf<LanSync.Found?>(null) }
    var typed by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val pairedBefore = remember { peers.size }
    LaunchedEffect(step) { if (step == PairStep.JOIN) lan.discover() }
    // Shown code consumed: the other device paired with this one.
    LaunchedEffect(peers.size) { if (step == PairStep.SHOW && peers.size > pairedBefore) onDismiss() }

    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Nt.Surface,
        title = { Text(tr("Associer un appareil")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (step) {
                    PairStep.CHOOSE -> {
                        Text(tr("Sur l'un des deux appareils, affichez un code ; sur l'autre, choisissez cet appareil et saisissez le code."), color = Nt.TextSoft, fontSize = 14.sp)
                        ChoiceRow(tr("Afficher un code"), tr("Cet appareil attend l'autre")) { lan.showCode(); onStep(PairStep.SHOW) }
                        ChoiceRow(tr("Saisir un code"), tr("Rejoindre un appareil qui affiche un code")) { onStep(PairStep.JOIN) }
                    }
                    PairStep.SHOW -> {
                        Text(tr("Sur l'autre appareil : Sauvegarde et synchro › Sur le Wi-Fi › Associer › Saisir un code."), color = Nt.TextSoft, fontSize = 14.sp)
                        Text(
                            code?.chunked(3)?.joinToString(" ") ?: "— — —",
                            fontFamily = Nt.Mono, fontSize = 40.sp, fontWeight = FontWeight.Bold, color = Nt.Accent,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Nt.Bg).padding(vertical = 18.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                        LinearProgressIndicator(color = Nt.Accent, trackColor = Nt.Border, modifier = Modifier.fillMaxWidth())
                        Text(tr("En attente… Les deux appareils doivent être sur le même Wi-Fi."), color = Nt.Muted, fontSize = 12.sp)
                    }
                    PairStep.JOIN -> {
                        val candidates = found.filter { f -> peers.none { it.id == f.id } }
                        if (target == null) {
                            if (candidates.isEmpty()) {
                                LinearProgressIndicator(color = Nt.Accent, trackColor = Nt.Border, modifier = Modifier.fillMaxWidth())
                                Text(tr("Recherche des appareils Nautile sur le réseau… Sur l'autre, affichez un code."), color = Nt.Muted, fontSize = 13.sp)
                            }
                            candidates.forEach { f -> ChoiceRow(f.name, f.host.hostAddress.orEmpty()) { target = f } }
                        } else {
                            Text(tr("Code affiché sur %1\$s :", target!!.name), color = Nt.TextSoft, fontSize = 14.sp)
                            NtTextField(typed, { typed = it.filter(Char::isDigit).take(6) }, tr("Code à 6 chiffres"), mono = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                            if (busy) LinearProgressIndicator(color = Nt.Accent, trackColor = Nt.Border, modifier = Modifier.fillMaxWidth())
                            problem?.let { Text(it, color = Nt.Pink, fontSize = 13.sp) }
                        }
                    }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(if (step == PairStep.SHOW) tr("Annuler") else tr("Fermer"), color = Nt.TextSoft) } },
        confirmButton = {
            if (step == PairStep.JOIN && target != null) TextButton(
                enabled = typed.length == 6 && !busy,
                onClick = {
                    busy = true; problem = null
                    scope.launch {
                        lan.pair(target!!, typed)
                            .onSuccess { onDismiss() }
                            .onFailure { problem = it.message ?: tr("Association impossible") }
                        busy = false
                    }
                },
            ) { Text(tr("Associer"), color = Nt.Accent, fontWeight = FontWeight.SemiBold) }
        },
    )
}

@Composable
private fun ChoiceRow(title: String, detail: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Nt.Bg).clickable(role = Role.Button, onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, color = Nt.Text, fontWeight = FontWeight.SemiBold)
            Text(detail, fontSize = 12.sp, color = Nt.Muted)
        }
        Box(Modifier.size(8.dp))
    }
}
