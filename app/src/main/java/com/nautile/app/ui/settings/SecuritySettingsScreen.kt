package com.nautile.app.ui.settings

import com.nautile.data.sync.SyncPart
import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nautile.app.CrashReporter
import com.nautile.app.MainActivity
import com.nautile.app.NautileApp
import com.nautile.app.ai.AiFiles
import com.nautile.app.backup.AutoBackup
import com.nautile.app.backup.BackupDestination
import com.nautile.app.backup.BackupPrefs
import com.nautile.app.backup.BackupStores
import com.nautile.app.backup.BackupTarget
import com.nautile.app.backup.BackupVersions
import com.nautile.app.backup.CleartextGuard
import com.nautile.app.readText
import com.nautile.app.ui.common.InfoDialog
import com.nautile.app.ui.common.NtCard
import com.nautile.app.ui.common.NtTextField
import com.nautile.app.ui.common.SectionLabel
import com.nautile.app.ui.hosts.relativeTime
import com.nautile.app.ui.theme.Nt
import com.nautile.i18n.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What a backup password is asked for. */
private enum class Ask { EXPORT_FILE, BACKUP_NOW, ENABLE_AUTO, IMPORT, START_SYNC, JOIN_SYNC }

/** « Sécurité »: app lock, recent-apps privacy, trusted hosts. */
@Composable
fun SecuritySettingsScreen(onBack: () -> Unit, onOpenKnownHosts: () -> Unit) {
    SettingsPage(tr("Sécurité"), onBack) { LockSection(onOpenKnownHosts) }
}

/** « Sauvegarde et synchro »: synchronisation, backup places (each with its schedule), export/import. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BackupSettingsScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as NautileApp
    val prefs = app.backupPrefs
    val scope = rememberCoroutineScope()
    val last by prefs.lastExport.collectAsStateWithLifecycle()
    val targets by prefs.targets.collectAsStateWithLifecycle()
    val syncTargetId by prefs.syncTargetId.collectAsStateWithLifecycle()
    val hasPassword by prefs.hasPassword.collectAsStateWithLifecycle()
    val stale = BackupPrefs.isStale(last)
    val anyError = targets.firstOrNull { it.error != null }

    var ask by remember { mutableStateOf<Ask?>(null) }
    /** The place whose automatic backup waits for a password. */
    var autoFor by remember { mutableStateOf<String?>(null) }
    var pendingPassword by remember { mutableStateOf("") }
    var importText by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    /** WebDAV / S3 dialog: adding (null target) or changing the access of a place. */
    var webDavDialog by remember { mutableStateOf<BackupTarget?>(null) }
    var webDavNew by remember { mutableStateOf(false) }
    var s3Dialog by remember { mutableStateOf<BackupTarget?>(null) }
    var s3New by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<String?>(null) }
    var choosingSync by remember { mutableStateOf(false) }
    val syncOn by prefs.syncEnabled.collectAsStateWithLifecycle()
    val lastSync by prefs.lastSync.collectAsStateWithLifecycle()
    val syncError by prefs.syncError.collectAsStateWithLifecycle()
    val devices by prefs.devices.collectAsStateWithLifecycle()
    val syncing by app.sync.running.collectAsStateWithLifecycle()
    var syncInfo by remember { mutableStateOf(false) }
    val syncTarget = targets.firstOrNull { it.id == syncTargetId }

    fun reschedule() = AutoBackup.schedule(app)

    fun syncNow(password: String? = null) {
        scope.launch {
            app.sync.sync(password).onSuccess { st -> status = if (st.changed) tr("Synchronisé : %1\$s modification(s) reçue(s)", st.added + st.updated + st.deleted) else tr("Synchronisé") }
        }
    }

    /** Turns synchronisation on: join the existing file (its password) or start one (a new password). */
    fun enableSync() {
        if (targets.isEmpty()) { status = tr("Ajoutez d'abord un emplacement ci-dessous : son fichier sera partagé entre vos appareils."); return }
        if (syncTarget == null) prefs.setSyncTarget(targets.first().id)
        busy = true
        scope.launch {
            // A network error is not « no file yet »: offering to start a new synchronisation would ask for a new password.
            val found = withContext(Dispatchers.IO) {
                runCatching { prefs.syncTarget?.let { BackupStores.of(app, it, prefs).read().text != null } ?: false }
            }
            val stored = prefs.password()
            busy = false
            val exists = found.getOrElse { e ->
                status = tr("Échec : %1\$s", e.message)
                return@launch
            }
            when {
                stored != null && (!exists || app.sync.checkPassword(stored).isSuccess) -> { prefs.setSyncEnabled(true); reschedule(); syncNow() }
                exists -> ask = Ask.JOIN_SYNC
                else -> ask = Ask.START_SYNC
            }
        }
    }

    /** To [targetId], or everywhere. */
    fun backupNow(targetId: String?, password: String?) {
        busy = true; status = null
        scope.launch {
            status = AutoBackup.run(app, targetId, password).fold(
                { if (targetId == null && targets.size > 1) tr("Sauvegarde envoyée vers %1\$s emplacements", targets.size) else tr("Sauvegarde envoyée") },
                { tr("Échec : %1\$s", it.message) },
            )
            busy = false
        }
    }

    fun added(t: BackupTarget) {
        reschedule()
        status = null
        editing = t.id
    }

    fun persist(uri: Uri, onFail: String, dest: () -> BackupDestination) {
        runCatching {
            app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }.onFailure { status = onFail }.onSuccess { added(prefs.addTarget(dest())) }
    }

    val pickExisting = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) persist(uri, tr("Ce fichier ne peut pas être modifié par Nautile : choisissez-en un autre")) {
            BackupDestination.Document(uri.toString(), AutoBackup.describe(app, uri))
        }
    }
    val pickDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        if (uri != null) persist(uri, tr("Ce stockage ne permet pas d'y revenir plus tard : choisissez-en un autre")) {
            BackupDestination.Document(uri.toString(), AutoBackup.describe(app, uri))
        }
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) persist(uri, tr("Ce dossier ne permet pas d'y revenir plus tard : choisissez-en un autre")) {
            BackupDestination.Folder(uri.toString(), AutoBackup.describeFolder(uri))
        }
    }
    val exportFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val pw = pendingPassword; pendingPassword = ""
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            status = runCatching {
                val text = app.backups.export(pw)
                withContext(Dispatchers.IO) {
                    app.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) } ?: error(tr("Destination inaccessible"))
                }
                prefs.markExported()
                tr("Sauvegarde exportée")
            }.getOrElse { tr("Export impossible : %1\$s", it.message) }
            busy = false
        }
    }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            importText = withContext(Dispatchers.IO) {
                runCatching { app.contentResolver.readText(uri) }.getOrNull()
            }
            if (importText == null) status = tr("Fichier illisible") else ask = Ask.IMPORT
        }
    }

    SettingsPage(tr("Sauvegarde et synchro"), onBack) {
        SectionLabel(tr("Synchronisation"))
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Nt.Surface)
                .border(1.dp, if (syncError != null && syncOn) Nt.Pink.copy(alpha = 0.5f) else if (syncOn) Nt.Green.copy(alpha = 0.45f) else Nt.Border, RoundedCornerShape(16.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconTile(Icons.Outlined.Sync, if (syncOn) Nt.Green else Nt.Muted)
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(tr("Synchroniser mes appareils"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f, fill = false))
                        IconButton(onClick = { syncInfo = true }, modifier = Modifier.size(30.dp)) {
                            Icon(Icons.Outlined.Info, contentDescription = tr("Comment fonctionne la synchronisation"), tint = Nt.Muted, modifier = Modifier.size(18.dp))
                        }
                    }
                    Text(
                        when {
                            !syncOn -> tr("Vos hôtes, clés et snippets sur tous vos appareils")
                            syncing -> tr("Synchronisation…")
                            lastSync != null -> tr("%1\$s · %2\$s appareil(s)", relativeTime(lastSync!!).replaceFirstChar { it.uppercase() }, devices.size.coerceAtLeast(1))
                            else -> tr("Pas encore synchronisé")
                        },
                        color = Nt.Muted, fontSize = 12.sp,
                    )
                }
                Switch(
                    syncOn, { on -> if (on) enableSync() else { prefs.setSyncEnabled(false); reschedule() } },
                    enabled = !busy,
                    colors = SwitchDefaults.colors(checkedTrackColor = Nt.Accent, uncheckedTrackColor = Nt.Toggle),
                )
            }
            if (syncOn) {
                syncError?.let { Text(tr("Échec : %1\$s", it), color = Nt.Pink, fontSize = 13.sp) }
                if (devices.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    devices.forEach { d ->
                        val me = d.id == prefs.deviceId
                        Box(Modifier.height(28.dp).clip(RoundedCornerShape(14.dp)).background(if (me) Nt.Accent.copy(alpha = 0.12f) else Nt.Bg).padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                            Text(if (me) tr("%1\$s (ce téléphone)", d.name) else tr("%1\$s · %2\$s", d.name, relativeTime(d.lastSeen)), fontSize = 12.sp, color = if (me) Nt.Accent else Nt.TextSoft)
                        }
                    }
                }
                // Which place holds the shared file.
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Nt.Bg).clickable { choosingSync = true }.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(destinationIcon(syncTarget?.dest), contentDescription = null, tint = Nt.TextSoft, modifier = Modifier.size(20.dp))
                    Column(Modifier.weight(1f)) {
                        Text(tr("Fichier partagé"), fontSize = 12.sp, color = Nt.Muted)
                        Text(syncTarget?.let { destinationLabel(it.dest) } ?: tr("À choisir"), fontSize = 13.sp, color = Nt.Text, fontFamily = Nt.Mono,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = Nt.Muted)
                }
                SyncOptions(prefs, needsNetwork = syncTarget?.let { !AutoBackup.isLocal(it.dest) } ?: true, onChange = ::reschedule)
                OutlinedButton(onClick = { syncNow() }, enabled = !syncing, modifier = Modifier.fillMaxWidth().height(42.dp)) {
                    Icon(Icons.Outlined.Sync, contentDescription = null, tint = Nt.Text, modifier = Modifier.size(18.dp))
                    Text("  " + if (syncing) tr("Synchronisation…") else tr("Synchroniser maintenant"), color = Nt.Text)
                }
                if (syncing) LinearProgressIndicator(color = Nt.Accent, trackColor = Nt.Border, modifier = Modifier.fillMaxWidth())
            }
        }
        if (syncInfo) InfoDialog(
            tr("La synchronisation"),
            listOf(
                tr("Hôtes, clés, identités, snippets, tunnels et hôtes connus sont partagés entre vos appareils."),
                tr("Sur chaque appareil : le même emplacement et le même mot de passe. Pour Google Drive ou OneDrive, choisissez « Fichier existant » sur le deuxième appareil."),
                tr("La modification la plus récente l'emporte, suppressions comprises."),
                tr("Le fichier synchronisé sert aussi de sauvegarde ; une copie par jour reste sur ce téléphone."),
                tr("Les clés API et les réglages d'affichage restent propres à chaque appareil."),
            ),
        ) { syncInfo = false }

        LanSyncSection()
        SyncPartsSection(syncOn)

        SectionLabel(tr("Sauvegarde"), Modifier.padding(top = 8.dp))
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Nt.Surface)
                .border(1.dp, if (stale || anyError != null) Nt.Warn.copy(alpha = 0.5f) else Nt.Border, RoundedCornerShape(16.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconTile(if (stale) Icons.Outlined.CloudUpload else Icons.Outlined.CloudDone, if (stale) Nt.Warn else Nt.Green)
                Column(Modifier.weight(1f)) {
                    Text(last?.let { tr("Dernière sauvegarde %1\$s", relativeTime(it)) } ?: tr("Aucune sauvegarde exportée"), style = MaterialTheme.typography.titleMedium)
                    Text(if (stale) tr("Exportez-en une pour ne rien perdre en changeant de téléphone.") else tr("À jour"), color = Nt.Muted, fontSize = 12.sp)
                }
            }

            Text(tr("Emplacements"), color = Nt.Muted, fontSize = 12.sp)
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Nt.Bg)) {
                targets.forEachIndexed { i, t ->
                    if (i > 0) HorizontalDivider(color = Nt.Border)
                    TargetRow(t, synced = syncOn && t.id == syncTargetId) { editing = t.id }
                }
                if (targets.isNotEmpty()) HorizontalDivider(color = Nt.Border)
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = Role.Button) { adding = true }.padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = null, tint = Nt.Accent)
                    Text(tr("Ajouter un emplacement"), color = Nt.Accent, fontWeight = FontWeight.SemiBold)
                }
            }
            if (targets.isEmpty()) Hint(tr("Google Drive, OneDrive, un dossier (Syncthing), Nextcloud ou un stockage S3 : ajoutez-en autant que vous voulez, chacun avec sa planification."))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        when {
                            targets.isEmpty() -> ask = Ask.EXPORT_FILE
                            hasPassword -> backupNow(null, null)
                            else -> ask = Ask.BACKUP_NOW
                        }
                    },
                    enabled = !busy, modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Nt.PrimaryContainer, contentColor = Nt.OnPrimaryContainer),
                    border = Nt.PrimaryBorder?.let { BorderStroke(2.dp, it) },
                ) { Text(if (targets.size > 1) tr("Sauvegarder partout") else tr("Sauvegarder"), fontWeight = FontWeight.SemiBold) }
                OutlinedButton(onClick = { openFile.launch(arrayOf("*/*")) }, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Text(tr("Restaurer…"), color = Nt.Text)
                }
            }
            if (targets.isNotEmpty()) TextButton(onClick = { ask = Ask.EXPORT_FILE }, enabled = !busy) {
                Text(tr("Exporter aussi une copie dans un fichier…"), color = Nt.Accent, fontSize = 13.sp)
            }
            if (busy) LinearProgressIndicator(color = Nt.Accent, trackColor = Nt.Border, modifier = Modifier.fillMaxWidth())
            status?.let { Text(it, color = Nt.TextSoft, fontSize = 13.sp) }
            // The last backups kept on the phone (read off the main thread). The lint check misses the assignment.
            @SuppressLint("ProduceStateDoesNotAssignValue")
            val versions by produceState(emptyList<BackupVersions.Version>(), last) {
                value = withContext(Dispatchers.IO) { BackupVersions.list(app) }
            }
            if (versions.isNotEmpty()) {
                Text(tr("Versions précédentes"), color = Nt.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Nt.Bg)) {
                    versions.forEachIndexed { i, v ->
                        if (i > 0) HorizontalDivider(color = Nt.Border)
                        Row(Modifier.fillMaxWidth().padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.History, contentDescription = null, tint = Nt.Muted, modifier = Modifier.size(18.dp))
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp)) {
                                Text(pastText(v.at), fontSize = 14.sp, color = Nt.Text)
                                Text(AiFiles.sizeLabel(v.size) + if (i == 0) " · " + tr("la plus récente") else "", fontSize = 12.sp, color = Nt.Muted)
                            }
                            TextButton(onClick = {
                                scope.launch {
                                    importText = withContext(Dispatchers.IO) { runCatching { v.file.readText() }.getOrNull() }
                                    if (importText == null) status = tr("Fichier illisible") else ask = Ask.IMPORT
                                }
                            }) { Text(tr("Restaurer"), color = Nt.Accent, fontSize = 13.sp) }
                        }
                    }
                }
                Hint(tr("Les %1\$s dernières sauvegardes restent aussi sur ce téléphone ; Nextcloud, S3 et les dossiers gardent les précédentes à côté (nautile.backup.1 à .4).", BackupVersions.KEEP))
            }
            Hint(tr("Chiffrée sur le téléphone avant l'envoi (Argon2id + AES-256-GCM) : le stockage ne voit qu'un fichier illisible. Contient hôtes, clés, identités, snippets, tunnels et hôtes connus."))
            if (hasPassword) TextButton(onClick = { prefs.setPassword(null); reschedule() }) {
                Text(tr("Oublier le mot de passe de sauvegarde"), color = Nt.Pink, fontSize = 13.sp)
            }
        }
    }

    // ── Sheets and dialogs ─────────────────────────────────────────────────
    if (adding) AddTargetSheet(
        onDismiss = { adding = false },
        onNewFile = { adding = false; pickDocument.launch(AutoBackup.FILE_NAME) },
        onExistingFile = { adding = false; pickExisting.launch(arrayOf("*/*")) },
        onFolder = { adding = false; pickFolder.launch(null) },
        onWebDav = { adding = false; webDavNew = true },
        onS3 = { adding = false; s3New = true },
    )
    editing?.let { id ->
        targets.firstOrNull { it.id == id }?.let { t ->
            TargetSheet(
                t, synced = syncOn && t.id == syncTargetId, hasPassword = hasPassword, busy = busy,
                onDismiss = { editing = null },
                onUpdate = { prefs.updateTarget(it); reschedule() },
                onAuto = { on ->
                    when {
                        !on -> { prefs.updateTarget(t.copy(auto = false)); reschedule() }
                        hasPassword -> { prefs.updateTarget(t.copy(auto = true)); reschedule() }
                        else -> { autoFor = t.id; ask = Ask.ENABLE_AUTO }
                    }
                },
                onBackup = { if (hasPassword || (syncOn && t.id == syncTargetId)) backupNow(t.id, null) else { autoFor = t.id; ask = Ask.BACKUP_NOW } },
                onAccess = { if (t.dest is BackupDestination.S3) s3Dialog = t else webDavDialog = t },
                onUseForSync = { prefs.setSyncTarget(t.id); editing = null; if (syncOn) { reschedule(); syncNow() } else enableSync() },
                onRemove = { editing = null; prefs.removeTarget(t.id); reschedule() },
            )
        }
    }
    if (choosingSync) AlertDialog(
        onDismissRequest = { choosingSync = false }, containerColor = Nt.Surface,
        title = { Text(tr("Fichier partagé")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                targets.forEach { t ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { choosingSync = false; prefs.setSyncTarget(t.id); reschedule(); syncNow() }.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        RadioButton(t.id == syncTargetId, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = Nt.Accent))
                        Text(destinationLabel(t.dest), fontSize = 13.sp, fontFamily = Nt.Mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Hint(tr("Tous vos appareils doivent utiliser le même fichier."))
            }
        },
        confirmButton = { TextButton(onClick = { choosingSync = false }) { Text(tr("Fermer"), color = Nt.TextSoft) } },
    )

    ask?.let { mode ->
        BackupPasswordDialog(
            confirm = mode != Ask.IMPORT && mode != Ask.JOIN_SYNC,
            // Automatic backups need it stored; a manual one may remember it too.
            rememberMode = when (mode) { Ask.ENABLE_AUTO, Ask.START_SYNC, Ask.JOIN_SYNC -> true; Ask.BACKUP_NOW -> false; else -> null },
            onDismiss = { ask = null; importText = null; autoFor = null },
        ) { pw, keep ->
            ask = null
            when (mode) {
                Ask.EXPORT_FILE -> {
                    pendingPassword = pw
                    exportFile.launch("nautile-" + SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date()) + ".nautile")
                }
                Ask.BACKUP_NOW -> { if (keep) { prefs.setPassword(pw); reschedule() }; backupNow(autoFor, pw); autoFor = null }
                Ask.ENABLE_AUTO -> {
                    prefs.setPassword(pw)
                    autoFor?.let { id -> prefs.target(id)?.let { prefs.updateTarget(it.copy(auto = true)) } }
                    reschedule(); backupNow(autoFor, null); autoFor = null
                }
                Ask.START_SYNC -> { prefs.setPassword(pw); prefs.setSyncEnabled(true); reschedule(); syncNow() }
                Ask.JOIN_SYNC -> scope.launch {
                    busy = true
                    app.sync.checkPassword(pw)
                        .onSuccess { prefs.setPassword(pw); prefs.setSyncEnabled(true); reschedule(); syncNow() }
                        .onFailure { status = tr("Ce mot de passe ne déchiffre pas le fichier existant : utilisez celui choisi sur l'autre appareil.") }
                    busy = false
                }
                Ask.IMPORT -> {
                    val text = importText ?: return@BackupPasswordDialog
                    importText = null
                    busy = true
                    scope.launch {
                        status = runCatching { tr("Importé : ") + app.backups.import(text, pw) }.getOrElse { it.message ?: tr("Import impossible") }
                        busy = false
                    }
                }
            }
        }
    }
    if (s3New || s3Dialog != null) S3Dialog(
        initial = s3Dialog?.dest as? BackupDestination.S3,
        savedSecret = s3Dialog?.id?.let(prefs::secret),
        onDismiss = { s3New = false; s3Dialog = null },
    ) { dest, secret ->
        val t = s3Dialog
        s3New = false; s3Dialog = null
        if (t == null) added(prefs.addTarget(dest, secret.ifEmpty { null })) else { prefs.updateTarget(t.copy(dest = dest), secret.ifEmpty { null }); reschedule() }
    }
    if (webDavNew || webDavDialog != null) WebDavDialog(
        initial = webDavDialog?.dest as? BackupDestination.WebDav,
        savedPassword = webDavDialog?.id?.let(prefs::secret),
        onDismiss = { webDavNew = false; webDavDialog = null },
    ) { url, user, password ->
        val t = webDavDialog
        webDavNew = false; webDavDialog = null
        val dest = BackupDestination.WebDav(url, user)
        if (t == null) added(prefs.addTarget(dest, password.ifEmpty { null })) else { prefs.updateTarget(t.copy(dest = dest), password.ifEmpty { null }); reschedule() }
    }
}

@Composable
internal fun DestinationCard(title: String, detail: String, icon: ImageVector, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick, shape = RoundedCornerShape(12.dp), color = if (on) Nt.Accent.copy(alpha = 0.07f) else Nt.Bg,
        modifier = modifier.border(2.dp, if (on) Nt.Accent else Nt.Border, RoundedCornerShape(12.dp)).semantics { selected = on; role = Role.RadioButton },
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, contentDescription = null, tint = if (on) Nt.Accent else Nt.Muted)
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(detail, fontSize = 11.sp, color = Nt.Muted, lineHeight = 14.sp)
        }
    }
}

/** App lock: biometrics, how soon it asks again, recent-apps privacy, trusted hosts. */
@Composable
private fun LockSection(onOpenKnownHosts: () -> Unit) {
    val app = LocalContext.current.applicationContext as NautileApp
    val lock = app.appLock
    val activity = LocalActivity.current as? MainActivity
    var secure by remember { mutableStateOf(lock.hideInRecents) }

    SectionLabel(tr("Verrouillage"), Modifier.padding(top = 8.dp))
    NtCard {
        AppLockCard(lock)
        HorizontalDivider(color = Nt.Border)
        ToggleRow(tr("Masquer dans les apps récentes"), tr("Aperçu vide dans le sélecteur d'apps ; bloque aussi les captures d'écran"), secure) {
            secure = it; lock.hideInRecents = it; activity?.applySecureFlag()
        }
        HorizontalDivider(color = Nt.Border)
        LinkRow(tr("Hôtes connus"), tr("Clés SSH et certificats RDP de confiance"), "", onOpenKnownHosts)
    }
    Hint(tr("La base de données est chiffrée (SQLCipher, clé dans l'Android Keystore)."))

    SectionLabel(tr("Rapports de plantage"), Modifier.padding(top = 8.dp))
    var crashPrompt by remember { mutableStateOf(CrashReporter.promptEnabled(app)) }
    NtCard {
        ToggleRow(
            tr("Proposer de signaler les plantages"),
            tr("Au redémarrage, un rapport anonymisé à relire, puis à publier sur GitHub si vous le voulez"),
            crashPrompt,
        ) { crashPrompt = it; CrashReporter.setPromptEnabled(app, it) }
    }
    Hint(tr("Le rapport ne garde que le type d'erreur et les lignes de code concernées : adresses, noms d'hôtes, utilisateurs, chemins et mots de passe sont masqués. Rien n'est envoyé sans votre clic."))
}

@Composable
private fun BackupPasswordDialog(confirm: Boolean, rememberMode: Boolean?, onDismiss: () -> Unit, onOk: (String, Boolean) -> Unit) {
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    var keep by remember { mutableStateOf(rememberMode == true) }
    val ok = pw.length >= (if (confirm) 8 else 1) && (!confirm || pw == pw2)
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Nt.Surface,
        title = { Text(if (confirm) tr("Mot de passe de la sauvegarde") else tr("Mot de passe")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (confirm) Text(tr("Il sera demandé à l'import. Sans lui, la sauvegarde est irrécupérable (8 caractères minimum)."), color = Nt.TextSoft, fontSize = 13.sp)
                NtTextField(pw, { pw = it }, tr("Mot de passe"), visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                if (confirm) NtTextField(pw2, { pw2 = it }, tr("Confirmer"), isError = pw2.isNotEmpty() && pw2 != pw,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                when (rememberMode) {
                    true -> Hint(tr("Gardé chiffré sur l'appareil (Android Keystore) pour les sauvegardes automatiques."))
                    false -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(keep, { keep = it }, colors = CheckboxDefaults.colors(checkedColor = Nt.Accent))
                        Text(tr("Mémoriser (chiffré dans l'Android Keystore)"), fontSize = 13.sp, color = Nt.TextSoft)
                    }
                    null -> {}
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Annuler"), color = Nt.TextSoft) } },
        confirmButton = { TextButton(onClick = { onOk(pw, keep) }, enabled = ok) { Text(if (confirm) tr("Valider") else tr("Importer"), color = Nt.Accent) } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun S3Dialog(initial: BackupDestination.S3?, savedSecret: String? = null, onDismiss: () -> Unit, onSave: (BackupDestination.S3, String) -> Unit) {
    val scope = rememberCoroutineScope()
    var endpoint by remember { mutableStateOf(initial?.endpoint.orEmpty()) }
    var region by remember { mutableStateOf(initial?.region ?: "us-east-1") }
    var bucket by remember { mutableStateOf(initial?.bucket.orEmpty()) }
    var key by remember { mutableStateOf(initial?.key ?: AutoBackup.FILE_NAME) }
    var access by remember { mutableStateOf(initial?.accessKey.orEmpty()) }
    var secret by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    fun dest() = BackupDestination.S3(endpoint.trim(), region.trim().ifEmpty { "us-east-1" }, bucket.trim(), key.trim().trimStart('/').ifEmpty { AutoBackup.FILE_NAME }, access.trim())
    val valid = endpoint.isNotBlank() && bucket.isNotBlank() && access.isNotBlank() && (secret.isNotBlank() || initial != null)
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Nt.Surface,
        title = { Text(tr("Stockage S3")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Presets: the endpoint and region formats of the common services.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        Triple("Backblaze B2", "s3.eu-central-003.backblazeb2.com", "eu-central-003"),
                        Triple("Cloudflare R2", "<compte>.r2.cloudflarestorage.com", "auto"),
                        Triple("AWS", "s3.eu-west-3.amazonaws.com", "eu-west-3"),
                        Triple("MinIO", "http://192.168.1.10:9000", "us-east-1"),
                    ).forEach { (name, e, r) ->
                        SuggestionChip(onClick = { endpoint = e; region = r; result = null }, label = { Text(name, fontSize = 12.sp) })
                    }
                }
                NtTextField(endpoint, { endpoint = it.trim(); result = null }, tr("Adresse (endpoint)"), mono = true, placeholder = "s3.eu-central-003.backblazeb2.com",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                cleartextWarning(endpoint)?.let { Text(it, color = Nt.Pink, fontSize = 12.sp) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NtTextField(region, { region = it.trim(); result = null }, tr("Région"), Modifier.weight(1f), mono = true)
                    NtTextField(bucket, { bucket = it.trim(); result = null }, tr("Bucket"), Modifier.weight(1f), mono = true)
                }
                NtTextField(key, { key = it.trim(); result = null }, tr("Chemin du fichier"), mono = true, placeholder = AutoBackup.FILE_NAME)
                NtTextField(access, { access = it.trim(); result = null }, tr("Clé d'accès (ID)"), mono = true)
                NtTextField(secret, { secret = it.trim(); result = null },
                    if (initial != null) tr("Clé secrète (enregistrée — saisir pour remplacer)") else tr("Clé secrète"),
                    mono = true, visualTransformation = PasswordVisualTransformation())
                Hint(tr("Créez une clé limitée à ce bucket. Le fichier est chiffré avant l'envoi ; activez les versions du bucket pour un historique côté serveur."))
                if (testing) LinearProgressIndicator(color = Nt.Accent, trackColor = Nt.Border, modifier = Modifier.fillMaxWidth())
                result?.let { Text(it, fontSize = 13.sp, color = Nt.TextSoft) }
            }
        },
        dismissButton = {
            TextButton(onClick = {
                testing = true; result = null
                scope.launch {
                    val usedSecret = secret.ifEmpty { savedSecret.orEmpty() }
                    result = AutoBackup.testS3(dest(), usedSecret).fold({ tr("Connexion réussie : écriture possible") }, { tr("Échec : %1\$s", it.message) })
                    testing = false
                }
            }, enabled = valid && !testing) { Text(tr("Tester"), color = Nt.TextSoft) }
        },
        confirmButton = { TextButton(onClick = { onSave(dest(), secret) }, enabled = valid) { Text(tr("Enregistrer"), color = Nt.Accent) } },
    )
}

/**
 * Shown while typing: plain http is refused unless the address is on the local network (the check itself is
 * [com.nautile.app.backup.CleartextGuard], on the address actually connected to). Only IP literals are judged
 * here: a name would need a DNS lookup.
 */
private fun cleartextWarning(url: String): String? {
    if (!url.trim().startsWith("http://", ignoreCase = true)) return null
    val host = runCatching { URI(url.trim()).host }.getOrNull()?.trim('[', ']')?.takeIf { it.isNotEmpty() } ?: return null
    val literal = IPV4.matches(host) || ':' in host
    if (!literal) return tr("http non chiffré : accepté seulement si %1\$s est sur le réseau local", host)
    val local = runCatching { CleartextGuard.isLocal(InetAddress.getByName(host)) }.getOrDefault(false)
    return if (local) null else tr("Connexion non chiffrée (http) refusée vers %1\$s : utilisez https, ou une adresse du réseau local.", host)
}

private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")

@Composable
private fun WebDavDialog(initial: BackupDestination.WebDav?, savedPassword: String? = null, onDismiss: () -> Unit, onSave: (String, String, String) -> Unit) {
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(initial?.url ?: "https://") }
    var user by remember { mutableStateOf(initial?.username.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    val valid = url.startsWith("https://") || url.startsWith("http://")
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Nt.Surface,
        title = { Text(tr("Nextcloud / WebDAV")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NtTextField(url, { url = it.trim(); result = null }, tr("Adresse du dossier"), mono = true,
                    placeholder = "https://cloud.exemple.fr/remote.php/dav/files/moi/Nautile/",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), isError = !valid)
                cleartextWarning(url)?.let { Text(it, color = Nt.Pink, fontSize = 12.sp) }
                NtTextField(user, { user = it; result = null }, tr("Utilisateur"), mono = true)
                NtTextField(password, { password = it; result = null }, tr("Mot de passe d'application"), visualTransformation = PasswordVisualTransformation())
                Hint(tr("Nextcloud : Paramètres › Sécurité › mot de passe d'application. Une adresse finissant par / est un dossier ; le fichier s'appellera nautile.backup."))
                if (testing) LinearProgressIndicator(color = Nt.Accent, trackColor = Nt.Border, modifier = Modifier.fillMaxWidth())
                result?.let { Text(it, fontSize = 13.sp, color = Nt.TextSoft) }
            }
        },
        dismissButton = {
            TextButton(onClick = {
                testing = true; result = null
                scope.launch {
                    // An existing destination is tested with its saved password unless a new one is typed.
                    result = AutoBackup.testWebDav(url, user, password.ifEmpty { savedPassword.orEmpty() }).fold({ tr("Connexion réussie : écriture possible") }, { tr("Échec : %1\$s", it.message) })
                    testing = false
                }
            }, enabled = valid && !testing) { Text(tr("Tester"), color = Nt.TextSoft) }
        },
        confirmButton = { TextButton(onClick = { onSave(url, user.trim(), password) }, enabled = valid) { Text(tr("Enregistrer"), color = Nt.Accent) } },
    )
}

/** What this phone synchronises, by theme (the shared file and the Wi-Fi alike); shown once one of them is on. */
@Composable
private fun SyncPartsSection(syncOn: Boolean) {
    val app = LocalContext.current.applicationContext as NautileApp
    val prefs = app.backupPrefs
    val lanOn by app.lanSync.enabled.collectAsStateWithLifecycle()
    val skip by prefs.syncSkip.collectAsStateWithLifecycle()
    if (!syncOn && !lanOn) return
    val left = SyncPart.effective(skip)
    val hostsOn = SyncPart.HOSTS !in left
    fun set(part: SyncPart, on: Boolean) {
        prefs.setSyncPart(part, on)
        if (on) {
            if (syncOn) app.sync.requestSoon(1_000)
            if (lanOn) app.lanSync.requestSoon(1_000)
        }
    }
    SectionLabel(tr("Ce qui est synchronisé"), Modifier.padding(top = 8.dp))
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Nt.Surface).border(1.dp, Nt.Border, RoundedCornerShape(16.dp))) {
        ToggleRow(tr("Hôtes et trousseau"), tr("Hôtes, groupes, clés SSH et identités"), hostsOn) { set(SyncPart.HOSTS, it) }
        ToggleRow(
            tr("Tunnels"),
            if (hostsOn) tr("Redirections de ports") else tr("Suivent les hôtes, dont ils dépendent"),
            SyncPart.TUNNELS !in left,
            enabled = hostsOn,
        ) { set(SyncPart.TUNNELS, it) }
        ToggleRow(tr("Snippets"), tr("Commandes enregistrées"), SyncPart.SNIPPETS !in left) { set(SyncPart.SNIPPETS, it) }
        ToggleRow(tr("Serveurs connus"), tr("Empreintes des clés d'hôtes déjà acceptées"), SyncPart.KNOWN_HOSTS !in left) { set(SyncPart.KNOWN_HOSTS, it) }
    }
    Hint(tr("Par le fichier partagé comme par le Wi-Fi. Un thème décoché n'est ni reçu ni envoyé : les autres appareils gardent le leur."))
}
