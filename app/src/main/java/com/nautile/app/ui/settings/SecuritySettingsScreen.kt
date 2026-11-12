package com.nautile.app.ui.settings

import com.nautile.i18n.tr

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.History
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.nautile.app.NautileApp
import com.nautile.app.backup.AutoBackup
import com.nautile.app.backup.BackupDestination
import com.nautile.app.backup.BackupFrequency
import com.nautile.app.backup.BackupPrefs
import com.nautile.app.ui.common.NtCard
import com.nautile.app.ui.common.NtTextField
import com.nautile.app.ui.common.SectionLabel
import com.nautile.app.ui.common.Segmented
import com.nautile.app.ui.hosts.relativeTime
import com.nautile.app.ui.theme.Nt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What a backup password is asked for. */
private enum class Ask { EXPORT_FILE, BACKUP_NOW, ENABLE_AUTO, IMPORT, START_SYNC, JOIN_SYNC }

/** « Sécurité »: app lock, recent-apps privacy, trusted hosts. */
@Composable
fun SecuritySettingsScreen(onBack: () -> Unit, onOpenKnownHosts: () -> Unit) {
    SettingsPage(tr("Sécurité"), onBack) { LockSection(onOpenKnownHosts) }
}

/** « Sauvegarde et synchro »: synchronisation, backup destination and schedule, export/import. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun BackupSettingsScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as NautileApp
    val prefs = app.backupPrefs
    val scope = rememberCoroutineScope()
    val last by prefs.lastExport.collectAsState()
    val lastError by prefs.lastError.collectAsState()
    val destination by prefs.destination.collectAsState()
    val auto by prefs.auto.collectAsState()
    val frequency by prefs.frequency.collectAsState()
    val hasPassword by prefs.hasPassword.collectAsState()
    val stale = BackupPrefs.isStale(last)

    var ask by remember { mutableStateOf<Ask?>(null) }
    var pendingPassword by remember { mutableStateOf("") }
    var importText by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var webDavDialog by remember { mutableStateOf(false) }
    var documentMenu by remember { mutableStateOf(false) }
    val syncOn by prefs.syncEnabled.collectAsState()
    val lastSync by prefs.lastSync.collectAsState()
    val syncError by prefs.syncError.collectAsState()
    val devices by prefs.devices.collectAsState()
    val syncing by app.sync.running.collectAsState()

    fun syncNow(password: String? = null) {
        scope.launch {
            app.sync.sync(password).onSuccess { st -> status = if (st.changed) tr("Synchronisé : %1\$s modification(s) reçue(s)", st.added + st.updated + st.deleted) else tr("Synchronisé") }
        }
    }

    /** Turns synchronisation on: join the existing file (its password) or start one (a new password). */
    fun enableSync() {
        if (destination == null) { status = tr("Choisissez d'abord une destination (Google Drive, OneDrive ou Nextcloud)."); return }
        busy = true
        scope.launch {
            val exists = app.sync.remoteExists()
            val stored = prefs.password()
            busy = false
            when {
                stored != null && (!exists || app.sync.checkPassword(stored).isSuccess) -> { prefs.setSyncEnabled(true); AutoBackup.schedule(app); syncNow() }
                exists -> ask = Ask.JOIN_SYNC
                else -> ask = Ask.START_SYNC
            }
        }
    }

    fun reschedule() = AutoBackup.schedule(app)

    fun backupNow(password: String?) {
        busy = true; status = null
        scope.launch {
            status = AutoBackup.run(app, password).fold({ tr("Sauvegarde envoyée") }, { tr("Échec : %1\$s", it.message) })
            busy = false
        }
    }

    val pickExisting = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }.onFailure { status = tr("Ce fichier ne peut pas être modifié par Nautile : choisissez-en un autre") }
            .onSuccess {
                prefs.setDestination(BackupDestination.Document(uri.toString(), AutoBackup.describe(app, uri)))
                reschedule()
                status = tr("Fichier choisi : activez la synchronisation pour le rejoindre.")
            }
    }
    val pickDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }.onFailure { status = tr("Ce stockage ne permet pas d'y revenir plus tard : choisissez-en un autre") }
            .onSuccess {
                prefs.setDestination(BackupDestination.Document(uri.toString(), AutoBackup.describe(app, uri)))
                reschedule()
                status = null
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
                runCatching { app.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } }.getOrNull()
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
                    Text(tr("Synchroniser mes appareils"), style = MaterialTheme.typography.titleMedium)
                    Text(
                        when {
                            !syncOn -> tr("Hôtes, clés, identités, snippets, tunnels et hôtes connus partagés entre vos téléphones et tablettes")
                            syncing -> tr("Synchronisation…")
                            lastSync != null -> tr("Dernière synchro %1\$s · %2\$s appareil(s)", relativeTime(lastSync!!), devices.size.coerceAtLeast(1))
                            else -> tr("Pas encore synchronisé")
                        },
                        color = Nt.Muted, fontSize = 12.sp,
                    )
                }
                androidx.compose.material3.Switch(
                    syncOn, { on -> if (on) enableSync() else { prefs.setSyncEnabled(false); reschedule() } },
                    enabled = !busy,
                    colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = Nt.Accent, uncheckedTrackColor = Nt.Toggle),
                )
            }
            if (syncOn) {
                syncError?.let { Text(tr("Échec : %1\$s", it), color = Nt.Pink, fontSize = 13.sp) }
                if (devices.isNotEmpty()) androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    devices.forEach { d ->
                        val me = d.id == prefs.deviceId
                        Box(Modifier.height(28.dp).clip(RoundedCornerShape(14.dp)).background(if (me) Nt.Accent.copy(alpha = 0.12f) else Nt.Bg).padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                            Text(if (me) tr("%1\$s (ce téléphone)", d.name) else tr("%1\$s · %2\$s", d.name, relativeTime(d.lastSeen)), fontSize = 12.sp, color = if (me) Nt.Accent else Nt.TextSoft)
                        }
                    }
                }
                destination?.let { d -> if (!AutoBackup.isLocal(d)) SyncNetworkRow(prefs, onChange = ::reschedule) }
                OutlinedButton(onClick = { syncNow() }, enabled = !syncing, modifier = Modifier.fillMaxWidth()) {
                    Text(if (syncing) tr("Synchronisation…") else tr("Synchroniser maintenant"), color = Nt.Text)
                }
                if (syncing) LinearProgressIndicator(color = Nt.Accent, trackColor = Nt.Border, modifier = Modifier.fillMaxWidth())
            }
            Hint(
                tr("Sur chaque appareil : la même destination ci-dessous et le même mot de passe. Pour Google Drive ou OneDrive, choisissez « Fichier existant » sur le deuxième appareil. ") +
                    tr("La modification la plus récente l'emporte, suppressions comprises. Les clés API et les réglages d'affichage restent propres à chaque appareil."),
            )
        }

        SectionLabel(tr("Sauvegarde"), Modifier.padding(top = 8.dp))
        run {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Nt.Surface)
                    .border(1.dp, if (stale || lastError != null) Nt.Accent.copy(alpha = 0.5f) else Nt.Border, RoundedCornerShape(16.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    IconTile(if (stale) Icons.Outlined.CloudUpload else Icons.Outlined.CloudDone, if (stale) Nt.Accent else Nt.Green)
                    Column(Modifier.weight(1f)) {
                        Text(
                            last?.let { tr("Dernière sauvegarde %1\$s", relativeTime(it)) } ?: tr("Aucune sauvegarde exportée"),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(if (stale) tr("Exportez-en une pour ne rien perdre en changeant de téléphone.") else tr("À jour"), color = Nt.Muted, fontSize = 12.sp)
                    }
                }
                lastError?.let { err ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = Nt.Pink)
                        Text(tr("Dernière tentative automatique : %1\$s", err), color = Nt.Pink, fontSize = 13.sp)
                    }
                }

                Text(tr("Destination"), color = Nt.Muted, fontSize = 12.sp)
                // Same height for both cards, whatever their text wraps to.
                Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        DestinationCard(
                            tr("Fichier ou cloud"), tr("Google Drive, OneDrive, téléphone…"), Icons.Outlined.Cloud,
                            destination is BackupDestination.Document, Modifier.fillMaxSize(),
                        ) { documentMenu = true }
                        androidx.compose.material3.DropdownMenu(documentMenu, onDismissRequest = { documentMenu = false }) {
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text(tr("Nouveau fichier")) },
                                onClick = { documentMenu = false; pickDocument.launch(AutoBackup.FILE_NAME) },
                            )
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text(tr("Fichier existant (autre appareil)")) },
                                onClick = { documentMenu = false; pickExisting.launch(arrayOf("*/*")) },
                            )
                        }
                    }
                    DestinationCard(
                        tr("Nextcloud"), tr("WebDAV, auto-hébergé"), Icons.Outlined.Dns,
                        destination is BackupDestination.WebDav, Modifier.weight(1f).fillMaxHeight(),
                    ) { webDavDialog = true }
                }
                destination?.let { d ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp)).background(Nt.Bg).padding(start = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            when (d) { is BackupDestination.Document -> d.label; is BackupDestination.WebDav -> AutoBackup.webDavFileUrl(d.url) },
                            fontFamily = Nt.Mono, fontSize = 12.sp, color = Nt.TextSoft, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { prefs.setDestination(null); reschedule() }) { Text(tr("Retirer"), color = Nt.Pink, fontSize = 13.sp) }
                    }
                }
                if (destination == null) Hint(tr("Choisissez Google Drive ou OneDrive dans le sélecteur d'Android (leur app doit être installée), ou un dossier WebDAV."))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(tr("Sauvegarde automatique"), style = MaterialTheme.typography.titleMedium)
                        Text(
                            when {
                                destination == null -> tr("Choisissez d'abord une destination")
                                syncOn -> tr("La synchronisation met le fichier à jour à chaque modification")
                                auto -> tr("Activée · %1\$s", frequencyLabel(frequency))
                                else -> tr("Seulement quand vous le demandez")
                            },
                            color = Nt.Muted, fontSize = 12.sp,
                        )
                    }
                    if (syncOn) Text(tr("Incluse"), color = Nt.Green, fontSize = 13.sp) else androidx.compose.material3.Switch(
                        auto, { on ->
                            when {
                                !on -> { prefs.setAuto(false); reschedule() }
                                hasPassword -> { prefs.setAuto(true); reschedule() }
                                else -> ask = Ask.ENABLE_AUTO
                            }
                        },
                        enabled = destination != null,
                        colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = Nt.Accent, uncheckedTrackColor = Nt.Toggle),
                    )
                }
                if (auto && !syncOn && destination != null) {
                    BackupOptions(prefs, needsNetwork = !AutoBackup.isLocal(destination!!), onChange = ::reschedule)
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            when {
                                destination == null -> ask = Ask.EXPORT_FILE
                                hasPassword -> backupNow(null)
                                else -> ask = Ask.BACKUP_NOW
                            }
                        },
                        enabled = !busy, modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Nt.PrimaryContainer, contentColor = Nt.OnPrimaryContainer),
                        border = Nt.PrimaryBorder?.let { BorderStroke(2.dp, it) },
                    ) { Text(tr("Sauvegarder"), fontWeight = FontWeight.SemiBold) }
                    OutlinedButton(onClick = { openFile.launch(arrayOf("*/*")) }, enabled = !busy, modifier = Modifier.weight(1f)) {
                        Text(tr("Restaurer…"), color = Nt.Text)
                    }
                }
                if (destination != null) TextButton(onClick = { ask = Ask.EXPORT_FILE }, enabled = !busy) {
                    Text(tr("Exporter aussi une copie dans un fichier…"), color = Nt.Accent, fontSize = 13.sp)
                }
                if (busy) LinearProgressIndicator(color = Nt.Accent, trackColor = Nt.Border, modifier = Modifier.fillMaxWidth())
                status?.let { Text(it, color = Nt.TextSoft, fontSize = 13.sp) }
                // The last backups kept on the phone (and on the server for WebDAV).
                val versions = remember(last) { com.nautile.app.backup.BackupVersions.list(app) }
                if (versions.isNotEmpty()) {
                    Text(tr("Versions précédentes"), color = Nt.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Nt.Bg)) {
                        versions.forEachIndexed { i, v ->
                            if (i > 0) androidx.compose.material3.HorizontalDivider(color = Nt.Border)
                            Row(Modifier.fillMaxWidth().padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.History, contentDescription = null, tint = Nt.Muted, modifier = Modifier.size(18.dp))
                                Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp)) {
                                    Text(pastText(v.at),
                                        fontSize = 14.sp, color = Nt.Text)
                                    Text(com.nautile.app.ai.AiFiles.sizeLabel(v.size) + if (i == 0) " · " + tr("la plus récente") else "", fontSize = 12.sp, color = Nt.Muted)
                                }
                                TextButton(onClick = {
                                    importText = runCatching { v.file.readText() }.getOrNull()
                                    if (importText == null) status = tr("Fichier illisible") else ask = Ask.IMPORT
                                }) { Text(tr("Restaurer"), color = Nt.Accent, fontSize = 13.sp) }
                            }
                        }
                    }
                    Hint(
                        tr("Les %1\$s dernières sauvegardes restent aussi sur ce téléphone.", com.nautile.app.backup.BackupVersions.KEEP) + " " +
                            when (destination) {
                                is BackupDestination.WebDav -> tr("Sur Nextcloud, les précédentes sont gardées à côté (nautile.backup.1 à .4).")
                                else -> tr("Google Drive et OneDrive gardent en plus l'historique des versions du fichier.")
                            },
                    )
                }
                Hint(tr("Chiffrée sur le téléphone avant l'envoi (Argon2id + AES-256-GCM) : le stockage ne voit qu'un fichier illisible. Contient hôtes, clés, identités, snippets, tunnels et hôtes connus."))
                if (hasPassword) TextButton(onClick = { prefs.setPassword(null); reschedule() }) {
                    Text(tr("Oublier le mot de passe de sauvegarde"), color = Nt.Pink, fontSize = 13.sp)
                }
            }
        }

    }

    ask?.let { mode ->
        BackupPasswordDialog(
            confirm = mode != Ask.IMPORT && mode != Ask.JOIN_SYNC,
            // Automatic backups need it stored; a manual one may remember it too.
            rememberMode = when (mode) { Ask.ENABLE_AUTO, Ask.START_SYNC, Ask.JOIN_SYNC -> true; Ask.BACKUP_NOW -> false; else -> null },
            onDismiss = { ask = null; importText = null },
        ) { pw, keep ->
            ask = null
            when (mode) {
                Ask.EXPORT_FILE -> {
                    pendingPassword = pw
                    exportFile.launch("nautile-" + java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(java.util.Date()) + ".nautile")
                }
                Ask.BACKUP_NOW -> { if (keep) { prefs.setPassword(pw); reschedule() }; backupNow(pw) }
                Ask.ENABLE_AUTO -> { prefs.setPassword(pw); prefs.setAuto(true); reschedule(); backupNow(null) }
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
    if (webDavDialog) WebDavDialog(
        initial = destination as? BackupDestination.WebDav,
        onDismiss = { webDavDialog = false },
    ) { url, user, password ->
        webDavDialog = false
        prefs.setDestination(BackupDestination.WebDav(url, user), password.ifEmpty { null })
        reschedule()
    }
}


@Composable
private fun DestinationCard(title: String, detail: String, icon: androidx.compose.ui.graphics.vector.ImageVector, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
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
    val activity = LocalContext.current as? com.nautile.app.MainActivity
    var enabled by remember { mutableStateOf(lock.enabled) }
    var delay by remember { mutableLongStateOf(lock.relockAfterMs) }
    var secure by remember { mutableStateOf(lock.hideInRecents) }

    SectionLabel(tr("Verrouillage"), Modifier.padding(top = 8.dp))
    NtCard {
        ToggleRow(
            tr("Verrouillage biométrique"),
            if (lock.available) tr("Empreinte ou code de l'appareil à l'ouverture") else tr("Aucune biométrie ni code configuré sur l'appareil"),
            enabled, enabled = lock.available,
        ) { enabled = it; lock.enabled = it }
        if (enabled) Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr("Redemander après"), color = Nt.Muted, fontSize = 12.sp)
            Segmented(
                listOf(0L to tr("Immédiat"), 60_000L to tr("1 min"), 300_000L to tr("5 min"), 900_000L to tr("15 min")),
                delay, { delay = it; lock.relockAfterMs = it },
            )
        }
        HorizontalDivider(color = Nt.Border)
        ToggleRow(tr("Masquer dans les apps récentes"), tr("Aperçu vide dans le sélecteur d'apps ; bloque aussi les captures d'écran"), secure) {
            secure = it; lock.hideInRecents = it; activity?.applySecureFlag()
        }
        HorizontalDivider(color = Nt.Border)
        LinkRow(tr("Hôtes connus"), tr("Clés SSH et certificats RDP de confiance"), "", onOpenKnownHosts)
    }
    Hint(tr("La base de données est chiffrée (SQLCipher, clé dans l'Android Keystore)."))

    SectionLabel(tr("Rapports de plantage"), Modifier.padding(top = 8.dp))
    var crashPrompt by remember { mutableStateOf(com.nautile.app.CrashReporter.promptEnabled(app)) }
    NtCard {
        ToggleRow(
            tr("Proposer de signaler les plantages"),
            tr("Au redémarrage, un rapport anonymisé à relire, puis à publier sur GitHub si vous le voulez"),
            crashPrompt,
        ) { crashPrompt = it; com.nautile.app.CrashReporter.setPromptEnabled(app, it) }
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

@Composable
private fun WebDavDialog(initial: BackupDestination.WebDav?, onDismiss: () -> Unit, onSave: (String, String, String) -> Unit) {
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
                    result = AutoBackup.testWebDav(url, user, password).fold({ tr("Connexion réussie : écriture possible") }, { tr("Échec : %1\$s", it.message) })
                    testing = false
                }
            }, enabled = valid && !testing) { Text(tr("Tester"), color = Nt.TextSoft) }
        },
        confirmButton = { TextButton(onClick = { onSave(url, user.trim(), password) }, enabled = valid) { Text(tr("Enregistrer"), color = Nt.Accent) } },
    )
}
