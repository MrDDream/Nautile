package com.nautile.app.ui.settings

import com.nautile.i18n.tr

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nautile.app.backup.AutoBackup
import com.nautile.app.backup.BackupDestination
import com.nautile.app.backup.BackupFrequency
import com.nautile.app.backup.BackupTarget
import com.nautile.app.ui.hosts.relativeTime
import com.nautile.app.ui.theme.Nt

internal fun destinationIcon(d: BackupDestination?): ImageVector = when (d) {
    is BackupDestination.Folder -> Icons.Outlined.Folder
    is BackupDestination.WebDav -> Icons.Outlined.Dns
    is BackupDestination.S3 -> Icons.Outlined.Inventory2
    else -> Icons.Outlined.Cloud
}

/** Where the file is, as shown to the user. */
internal fun destinationLabel(d: BackupDestination): String = when (d) {
    is BackupDestination.Document -> d.label
    is BackupDestination.WebDav -> AutoBackup.webDavFileUrl(d.url).substringAfter("://")
    is BackupDestination.S3 -> "s3://${d.bucket}/${d.key}"
    is BackupDestination.Folder -> d.label + " › " + AutoBackup.FILE_NAME
}

private fun kindLabel(d: BackupDestination): String = when (d) {
    is BackupDestination.Document -> d.label.substringBefore(" › ").ifBlank { tr("Fichier") }
    is BackupDestination.Folder -> tr("Dossier")
    is BackupDestination.WebDav -> "Nextcloud / WebDAV"
    is BackupDestination.S3 -> "S3 · " + d.endpoint.substringAfter("://").substringBefore('/')
}

/** « Chaque semaine · dimanche 03:00 » */
internal fun scheduleSummary(t: BackupTarget): String {
    val f = frequencyLabel(t.frequency).replaceFirstChar { it.uppercase() }
    if (!t.frequency.atTime) return f
    val clock = "%02d:%02d".format(java.util.Locale.ROOT, t.time / 60, t.time % 60)
    return if (t.frequency == BackupFrequency.WEEK) {
        val day = java.time.DayOfWeek.of(t.weekday).getDisplayName(java.time.format.TextStyle.FULL, if (com.nautile.i18n.I18n.english) java.util.Locale.ENGLISH else java.util.Locale.FRENCH)
        tr("%1\$s · %2\$s %3\$s", f, day, clock)
    } else tr("%1\$s · %2\$s", f, clock)
}

/** One place in the list: what it is, how it backs up, how it went; the gear opens its settings. */
@Composable
internal fun TargetRow(t: BackupTarget, synced: Boolean, onSettings: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(onClick = onSettings).padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(Nt.Surface), contentAlignment = Alignment.Center) {
            Icon(destinationIcon(t.dest), contentDescription = null, tint = Nt.TextSoft, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(kindLabel(t.dest), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Nt.Text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false))
                if (synced) Box(Modifier.clip(RoundedCornerShape(6.dp)).background(Nt.Green.copy(alpha = 0.14f)).padding(horizontal = 6.dp, vertical = 1.dp)) {
                    Text(tr("Synchro"), color = Nt.Green, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Text(destinationLabel(t.dest), fontSize = 11.sp, fontFamily = Nt.Mono, color = Nt.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val when_ = t.last?.let { relativeTime(it) } ?: tr("jamais")
            Text(
                when {
                    t.error != null -> tr("Échec : %1\$s", t.error)
                    synced -> tr("Synchronisé · %1\$s", when_)
                    t.auto -> scheduleSummary(t) + " · " + when_
                    else -> tr("Manuelle · %1\$s", when_)
                },
                fontSize = 12.sp, color = if (t.error != null) Nt.Pink else if (t.auto || synced) Nt.TextSoft else Nt.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, contentDescription = tr("Réglages de cet emplacement"), tint = Nt.TextSoft) }
    }
}

/** A place's settings: automatic backup and its schedule, back up now, access, synchronisation, removal. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TargetSheet(
    t: BackupTarget, synced: Boolean, hasPassword: Boolean, busy: Boolean,
    onDismiss: () -> Unit, onUpdate: (BackupTarget) -> Unit, onAuto: (Boolean) -> Unit,
    onBackup: () -> Unit, onAccess: () -> Unit, onUseForSync: () -> Unit, onRemove: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Nt.Surface) {
        TargetSheetContent(t, synced, hasPassword, busy, onDismiss, onUpdate, onAuto, onBackup, onAccess, onUseForSync, onRemove)
    }
}

/** The sheet's content, also drawn by the screenshots. */
@Composable
internal fun TargetSheetContent(
    t: BackupTarget, synced: Boolean, hasPassword: Boolean, busy: Boolean,
    onDismiss: () -> Unit, onUpdate: (BackupTarget) -> Unit, onAuto: (Boolean) -> Unit,
    onBackup: () -> Unit, onAccess: () -> Unit, onUseForSync: () -> Unit, onRemove: () -> Unit,
) {
    var confirmRemove by remember { mutableStateOf(false) }
    run {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(Nt.Bg), contentAlignment = Alignment.Center) {
                    Icon(destinationIcon(t.dest), contentDescription = null, tint = Nt.Accent)
                }
                Column(Modifier.weight(1f)) {
                    Text(kindLabel(t.dest), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(destinationLabel(t.dest), fontSize = 12.sp, fontFamily = Nt.Mono, color = Nt.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, contentDescription = tr("Fermer"), tint = Nt.TextSoft) }
            }
            t.error?.let { Text(tr("Dernière tentative : %1\$s", it), color = Nt.Pink, fontSize = 13.sp) }

            if (synced) Text(
                tr("Ce fichier est celui de la synchronisation : il est mis à jour à chaque modification, pas sur un horaire."),
                color = Nt.TextSoft, fontSize = 13.sp, lineHeight = 18.sp,
            ) else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("Sauvegarde automatique"), style = MaterialTheme.typography.titleMedium)
                        Text(if (t.auto) scheduleSummary(t) else tr("Seulement quand vous le demandez"), color = Nt.Muted, fontSize = 12.sp)
                    }
                    Switch(t.auto, onAuto, colors = SwitchDefaults.colors(checkedTrackColor = Nt.Accent, uncheckedTrackColor = Nt.Toggle))
                }
                if (t.auto) BackupOptions(t, needsNetwork = !AutoBackup.isLocal(t.dest), onUpdate = onUpdate)
            }

            Button(
                onClick = onBackup, enabled = !busy, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Nt.Accent, contentColor = Nt.OnAccent),
            ) {
                Icon(if (synced) Icons.Outlined.Sync else Icons.Outlined.Upload, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("  " + if (synced) tr("Synchroniser maintenant") else tr("Sauvegarder ici maintenant"), fontWeight = FontWeight.SemiBold)
            }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Nt.Bg)) {
                if (t.dest is BackupDestination.S3 || t.dest is BackupDestination.WebDav) {
                    ActionRow(Icons.Outlined.Key, tr("Modifier l'accès"), onAccess)
                }
                if (!synced) ActionRow(Icons.Outlined.Sync, tr("Utiliser pour la synchronisation"), onUseForSync)
            }
            if (!confirmRemove) TextButton(onClick = { confirmRemove = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(tr("Retirer cet emplacement"), color = Nt.Pink)
            } else Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(tr("Le fichier déjà envoyé reste en place ; Nautile n'y écrira plus."), color = Nt.Muted, fontSize = 13.sp)
                TextButton(onClick = onRemove) { Text(tr("Confirmer le retrait"), color = Nt.Pink, fontWeight = FontWeight.SemiBold) }
            }
            if (!hasPassword && !synced) Hint(tr("La première sauvegarde demandera un mot de passe : il chiffre le fichier et sera nécessaire pour restaurer."))
        }
    }
}

@Composable
private fun ActionRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 50.dp).clickable(onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = Nt.TextSoft, modifier = Modifier.size(20.dp))
        Text(label, fontSize = 15.sp, color = Nt.Text)
    }
}

/** The kinds of place, to add one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddTargetSheet(
    onDismiss: () -> Unit, onNewFile: () -> Unit, onExistingFile: () -> Unit, onFolder: () -> Unit, onWebDav: () -> Unit, onS3: () -> Unit,
) {
    var fileMenu by remember { mutableStateOf(false) }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Nt.Surface) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(tr("Ajouter un emplacement"), style = MaterialTheme.typography.titleLarge)
            if (!fileMenu) {
                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DestinationCard(tr("Fichier ou cloud"), tr("Google Drive, OneDrive, téléphone…"), Icons.Outlined.Cloud, false, Modifier.weight(1f).fillMaxHeight()) { fileMenu = true }
                    DestinationCard(tr("Dossier"), tr("Syncthing, stockage du téléphone…"), Icons.Outlined.Folder, false, Modifier.weight(1f).fillMaxHeight(), onFolder)
                }
                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DestinationCard(tr("Nextcloud"), tr("WebDAV, auto-hébergé"), Icons.Outlined.Dns, false, Modifier.weight(1f).fillMaxHeight(), onWebDav)
                    DestinationCard(tr("Stockage S3"), tr("Backblaze B2, Cloudflare R2, MinIO…"), Icons.Outlined.Inventory2, false, Modifier.weight(1f).fillMaxHeight(), onS3)
                }
                Hint(tr("Google Drive ou OneDrive passent par le sélecteur d'Android (leur app doit être installée). Un dossier synchronisé par Syncthing, Nextcloud ou S3 sert aussi pour un ordinateur."))
            } else {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Nt.Bg)) {
                    ActionRow(Icons.Outlined.Upload, tr("Nouveau fichier"), onNewFile)
                    ActionRow(Icons.Outlined.Sync, tr("Fichier existant (autre appareil)"), onExistingFile)
                }
                TextButton(onClick = { fileMenu = false }) { Text(tr("Retour"), color = Nt.TextSoft) }
            }
        }
    }
}
