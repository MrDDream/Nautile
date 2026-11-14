package com.nautile.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nautile.app.NautileApp
import com.nautile.app.backup.lan.LanProtocol
import com.nautile.app.backup.lan.LanSync
import com.nautile.app.ui.common.HostAvatar
import com.nautile.app.ui.common.InfoDialog
import com.nautile.app.ui.common.NtTextField
import com.nautile.app.ui.common.OsBrands
import com.nautile.app.ui.hosts.relativeTime
import com.nautile.app.ui.theme.Nt
import com.nautile.i18n.tr
import kotlinx.coroutines.launch

private enum class PairStep { CHOOSE, SHOW, JOIN }

/** Synchronisation over the local network: on/off, paired devices, pairing with a 6-digit code. */
@Composable
internal fun LanSyncSection() {
    val app = LocalContext.current.applicationContext as NautileApp
    val lan = app.lanSync
    val scope = rememberCoroutineScope()
    val enabled by lan.enabled.collectAsStateWithLifecycle()
    val peers by lan.peers.collectAsStateWithLifecycle()
    val status by lan.status.collectAsStateWithLifecycle()
    val running by lan.running.collectAsStateWithLifecycle()
    val found by lan.found.collectAsStateWithLifecycle()
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
                        peers.size == 1 -> tr("1 appareil associé")
                        else -> tr("%1\$s appareils associés", peers.size)
                    },
                    color = Nt.Muted, fontSize = 12.sp,
                )
            }
            Switch(enabled, { lan.setEnabled(it) }, colors = SwitchDefaults.colors(checkedTrackColor = Nt.Accent, uncheckedTrackColor = Nt.Toggle))
        }
        if (enabled) {
            if (peers.isNotEmpty()) Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Nt.Bg)) {
                peers.forEachIndexed { i, p ->
                    if (i > 0) HorizontalDivider(color = Nt.Border, modifier = Modifier.padding(start = 64.dp))
                    PeerRow(p, near = found.any { it.id == p.id }, onForget = { lan.forget(p) })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val padding = PaddingValues(horizontal = 12.dp)
                OutlinedButton(onClick = { pairing = PairStep.CHOOSE }, modifier = Modifier.weight(1f).height(44.dp), contentPadding = padding) {
                    Icon(Icons.Outlined.Add, contentDescription = null, tint = Nt.Text, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(tr("Associer"), color = Nt.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (peers.isNotEmpty()) OutlinedButton(
                    onClick = { scope.launch { lan.syncAll() } }, enabled = !running,
                    modifier = Modifier.weight(1f).height(44.dp), contentPadding = padding,
                ) {
                    if (running) CircularProgressIndicator(color = Nt.Accent, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    else Icon(Icons.Outlined.Sync, contentDescription = null, tint = Nt.Text, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (running) tr("Synchronisation…") else tr("Synchroniser"), color = if (running) Nt.Muted else Nt.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            status?.let { Text(it, color = Nt.Muted, fontSize = 12.sp, modifier = Modifier.padding(start = 2.dp)) }
        }
    }

    pairing?.let { step -> PairDialog(lan, step, onStep = { pairing = it }, onDismiss = { lan.cancelCode(); pairing = null }) }
    if (info) InfoDialog(
        tr("La synchro sur le Wi-Fi"),
        listOf(
            tr("Vos appareils échangent directement quand ils sont sur le même réseau : rien ne passe par Internet ni par un cloud."),
            tr("Associez-les une fois avec un code à 6 chiffres ; ensuite ils se retrouvent tout seuls, à l'ouverture de l'app et après chaque modification."),
            tr("L'association utilise SPAKE2 : le code ne peut pas être deviné en écoutant le réseau. La liaison est chiffrée (AES-256-GCM) et seuls les appareils associés sont acceptés."),
            tr("Elle se combine avec la synchro par fichier : la modification la plus récente l'emporte partout."),
        ),
    ) { info = false }
}

/** A paired device: its system's logo, its name, whether it is on the network now and its last synchronisation. */
@Composable
private fun PeerRow(p: LanSync.Peer, near: Boolean, onForget: () -> Unit) {
    val brand = OsBrands.of(p.platform)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (brand != null) HostAvatar(p.platform, "", brand.color, size = 40.dp, corner = 12.dp)
        else Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Nt.Blue.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Devices, contentDescription = null, tint = Nt.Blue, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(p.name, fontSize = 15.sp, color = Nt.Text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (near) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(Nt.Green))
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    listOfNotNull(
                        brand?.name,
                        tr("à portée").takeIf { near },
                        when {
                            // Paired with an older version: no secret of its own yet.
                            !p.linked -> tr("À associer à nouveau")
                            p.lastSync > 0 -> tr("Synchro %1\$s", relativeTime(p.lastSync))
                            else -> tr("Pas encore synchronisé")
                        },
                    ).joinToString(" · "),
                    fontSize = 12.sp, color = Nt.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onForget) { Icon(Icons.Outlined.Close, contentDescription = tr("Oublier %1\$s", p.name), tint = Nt.Muted, modifier = Modifier.size(20.dp)) }
    }
}

@Composable
private fun PairDialog(lan: LanSync, step: PairStep, onStep: (PairStep) -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val code by lan.code.collectAsStateWithLifecycle()
    val found by lan.found.collectAsStateWithLifecycle()
    val peers by lan.peers.collectAsStateWithLifecycle()
    val lastPaired by lan.lastPaired.collectAsStateWithLifecycle()
    var target by remember { mutableStateOf<LanSync.Found?>(null) }
    var typed by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val pairedBefore = remember(step) { lan.lastPaired.value }
    LaunchedEffect(step) { if (step == PairStep.JOIN) lan.discover() }
    // Shown code used: the other device paired with this one (maybe again). Only a pairing closes it, not a
    // synchronisation updating the list meanwhile.
    val justPaired = lastPaired != null && lastPaired != pairedBefore
    LaunchedEffect(justPaired) { if (step == PairStep.SHOW && justPaired) onDismiss() }

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
                        val shown = code
                        if (shown == null && !justPaired) {
                            // Dropped after too many wrong tries.
                            Text(tr("Trop d'essais, affichez un nouveau code"), color = Nt.Pink, fontSize = 14.sp)
                            ChoiceRow(tr("Afficher un code"), tr("Cet appareil attend l'autre")) { lan.showCode() }
                        } else {
                            Text(tr("Sur l'autre appareil : Sauvegarde et synchro › Sur le Wi-Fi › Associer › Saisir un code."), color = Nt.TextSoft, fontSize = 14.sp)
                            Text(
                                shown?.chunked(3)?.joinToString(" ") ?: "— — —",
                                fontFamily = Nt.Mono, fontSize = 40.sp, fontWeight = FontWeight.Bold, color = Nt.Accent,
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Nt.Bg).padding(vertical = 18.dp),
                                textAlign = TextAlign.Center,
                            )
                            LinearProgressIndicator(color = Nt.Accent, trackColor = Nt.Border, modifier = Modifier.fillMaxWidth())
                            Text(tr("En attente… Les deux appareils doivent être sur le même Wi-Fi."), color = Nt.Muted, fontSize = 12.sp)
                        }
                    }
                    PairStep.JOIN -> {
                        // Devices to pair again (older version) are offered too.
                        val candidates = found.filter { f -> peers.none { it.id == f.id && it.linked } }
                        if (target == null) {
                            if (candidates.isEmpty()) {
                                LinearProgressIndicator(color = Nt.Accent, trackColor = Nt.Border, modifier = Modifier.fillMaxWidth())
                                Text(tr("Recherche des appareils Nautile sur le réseau… Sur l'autre, affichez un code."), color = Nt.Muted, fontSize = 13.sp)
                            }
                            candidates.forEach { f ->
                                val detail = if (f.version < LanProtocol.VERSION) tr("Mettez à jour Nautile sur l'autre appareil") else f.host.hostAddress.orEmpty()
                                ChoiceRow(f.name, detail) { target = f }
                            }
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
                            .onFailure { problem = (it as? LanProtocol.LanException)?.message ?: tr("Association impossible") }
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
