package com.nautile.app.ui.sftp

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nautile.app.NautileApp
import com.nautile.app.session.Transfer
import com.nautile.app.ui.common.NtTextField
import com.nautile.app.ui.theme.Nt
import com.nautile.i18n.tr
import com.nautile.ssh.RemoteEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SftpScreen(onBack: () -> Unit, onEdit: (Long, String) -> Unit, vm: SftpViewModel = viewModel(factory = SftpViewModel.Factory)) {
    val app = LocalContext.current.applicationContext as NautileApp
    val state by vm.state.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val transfers by vm.transfers.collectAsStateWithLifecycle()
    val canGoBack by vm.canGoBack.collectAsStateWithLifecycle()
    val hosts by app.hostRepository.hosts.collectAsStateWithLifecycle(initialValue = emptyList())
    val snackbar = remember { SnackbarHostState() }
    var selected by remember { mutableStateOf<RemoteEntry?>(null) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var downloading by remember { mutableStateOf<RemoteEntry?>(null) }

    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }
    val pickUpload = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> vm.upload(uris) }
    val pickDest = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val e = downloading
        if (uri != null && e != null) vm.download(e, uri)
        downloading = null
    }
    val shown = state.entries.filter { state.showHidden || !it.name.startsWith(".") }
    val picked = shown.filter { it.path in state.selected }
    val selecting = picked.isNotEmpty()
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) vm.downloadMany(picked, uri) }
    // The system back button walks back through the folders visited, then leaves; it first ends a selection.
    BackHandler(enabled = canGoBack) { vm.back() }
    BackHandler(enabled = selecting) { vm.clearSelection() }

    Box(Modifier.fillMaxSize().background(Nt.Bg)) {
        Column(Modifier.fillMaxSize()) {
            if (selecting) SelectionBar(
                picked, total = shown.size,
                onClose = vm::clearSelection, onSelectAll = { vm.selectAll(shown) },
                onDownload = {
                    val files = picked.filter { !it.isDirectory }
                    if (files.size == 1 && picked.size == 1) { downloading = files[0]; vm.clearSelection(); pickDest.launch(files[0].name) }
                    else pickFolder.launch(null)
                },
                onDelete = { dialog = "delete-sel" },
                onCopy = { dialog = "copy-sel" },
                onChmod = { dialog = "chmod-sel" },
                onRename = { e -> vm.clearSelection(); dialog = "rename:" + e.path },
                onEdit = { e -> vm.clearSelection(); onEdit(vm.hostId, e.path) },
            ) else Row(Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("Retour")) }
                Column(Modifier.weight(1f)) {
                    Text("SFTP · ${state.hostAlias}", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Text(state.path, fontFamily = Nt.Mono, fontSize = 11.sp, color = Nt.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = vm::toggleHidden) {
                    Icon(if (state.showHidden) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff, contentDescription = tr("Fichiers cachés"), tint = Nt.TextSoft)
                }
                IconButton(onClick = vm::refresh) { Icon(Icons.Outlined.Refresh, contentDescription = tr("Actualiser"), tint = Nt.TextSoft) }
            }
            if (!selecting) Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ToolButton(Icons.Outlined.ArrowUpward, tr("Parent"), enabled = state.path != "/") { vm.up() }
                ToolButton(Icons.Outlined.CreateNewFolder, tr("Dossier")) { dialog = "mkdir" }
                ToolButton(Icons.Outlined.Upload, tr("Envoyer")) { pickUpload.launch(arrayOf("*/*")) }
            }
            HorizontalDivider(color = Nt.Border)
            Box(Modifier.weight(1f)) {
                when {
                    state.loading && state.entries.isEmpty() -> CircularProgressIndicator(color = Nt.Accent, modifier = Modifier.align(Alignment.Center))
                    state.error != null -> Text(state.error!!, color = Nt.Pink, modifier = Modifier.padding(20.dp))
                    else -> LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                        if (shown.isEmpty()) item { Text(tr("Dossier vide"), color = Nt.Muted, modifier = Modifier.padding(20.dp)) }
                        items(shown, key = { it.path }) { e ->
                            Box {
                                EntryRow(e, isPicked = e.path in state.selected, onLongClick = { vm.toggleSelected(e) }) {
                                    when {
                                        selecting -> vm.toggleSelected(e)
                                        e.isDirectory -> vm.open(e.path)
                                        else -> selected = e
                                    }
                                }
                                DropdownMenu(selected == e, onDismissRequest = { selected = null }) {
                                    if (!e.isDirectory) {
                                        DropdownMenuItem(text = { Text(tr("Télécharger")) }, onClick = { selected = null; downloading = e; pickDest.launch(e.name) })
                                        DropdownMenuItem(text = { Text(tr("Modifier")) }, onClick = { selected = null; onEdit(vm.hostId, e.path) })
                                        DropdownMenuItem(text = { Text(tr("Copier vers un autre hôte…")) }, onClick = { selected = null; dialog = "copy:" + e.path })
                                    }
                                    DropdownMenuItem(text = { Text(tr("Renommer")) }, onClick = { selected = null; dialog = "rename:" + e.path })
                                    DropdownMenuItem(text = { Text(tr("Permissions")) }, onClick = { selected = null; dialog = "chmod:" + e.path })
                                    DropdownMenuItem(text = { Text(tr("Supprimer"), color = Nt.Pink) }, onClick = { selected = null; dialog = "delete:" + e.path })
                                }
                            }
                        }
                    }
                }
            }
            TransfersPanel(transfers, onCancel = app.sessions.transfers::cancel, onClear = app.sessions.transfers::clearFinished)
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp))
    }

    // Dialogs opened from a row's menu (key « action:path ») or from the selection bar (« action-sel »).
    val target = dialog?.substringAfter(':', "")?.let { p -> state.entries.firstOrNull { it.path == p } }
    when {
        dialog == "mkdir" -> NameDialog(tr("Nouveau dossier"), "") { n -> dialog = null; n?.let(vm::mkdir) }
        dialog?.startsWith("rename:") == true && target != null -> NameDialog(tr("Renommer"), target.name) { n -> dialog = null; n?.let { vm.rename(target, it) } }
        dialog?.startsWith("chmod:") == true && target != null ->
            NameDialog(tr("Permissions (octal)"), permissionsText(target.permissions), digitsOnly = true) { n ->
                dialog = null
                if (n != null && n.matches(Regex("[0-7]{3,4}"))) vm.chmod(target, n)
            }
        dialog?.startsWith("delete:") == true && target != null -> AlertDialog(
            onDismissRequest = { dialog = null }, containerColor = Nt.Surface,
            title = { Text(tr("Supprimer « %1\$s » ?", target.name)) },
            text = { Text(if (target.isDirectory) tr("Le dossier et tout son contenu seront supprimés.") else tr("Cette action est définitive."), color = Nt.TextSoft) },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text(tr("Annuler"), color = Nt.TextSoft) } },
            confirmButton = { TextButton(onClick = { dialog = null; vm.delete(target) }) { Text(tr("Supprimer"), color = Nt.Pink) } },
        )
        dialog?.startsWith("copy:") == true && target != null -> CopyDialog(hosts.filter { it.id != vm.hostId }.map { it.id to it.alias }) { dest, dir ->
            dialog = null
            if (dest != null) vm.copyTo(listOf(target), dest.first, dir, dest.second)
        }
        dialog == "copy-sel" && selecting -> CopyDialog(hosts.filter { it.id != vm.hostId }.map { it.id to it.alias }) { dest, dir ->
            dialog = null
            if (dest != null) vm.copyTo(picked, dest.first, dir, dest.second)
        }
        dialog == "chmod-sel" && selecting ->
            NameDialog(tr("Permissions (octal)"), picked.map { permissionsText(it.permissions) }.distinct().singleOrNull().orEmpty(), digitsOnly = true) { n ->
                dialog = null
                if (n != null && n.matches(Regex("[0-7]{3,4}"))) vm.chmodMany(picked, n)
            }
        dialog == "delete-sel" && selecting -> AlertDialog(
            onDismissRequest = { dialog = null }, containerColor = Nt.Surface,
            title = { Text(if (picked.size == 1) tr("Supprimer « %1\$s » ?", picked[0].name) else tr("Supprimer %1\$s éléments ?", picked.size)) },
            text = {
                Text(
                    if (picked.any { it.isDirectory }) tr("Les dossiers sélectionnés seront supprimés avec tout leur contenu.") else tr("Cette action est définitive."),
                    color = Nt.TextSoft,
                )
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text(tr("Annuler"), color = Nt.TextSoft) } },
            confirmButton = { TextButton(onClick = { dialog = null; vm.deleteMany(picked) }) { Text(tr("Supprimer"), color = Nt.Pink) } },
        )
    }
}

/** Top bar while entries are picked: how many, select all, and what can be done to all of them at once. */
@Composable
private fun SelectionBar(
    picked: List<RemoteEntry>, total: Int,
    onClose: () -> Unit, onSelectAll: () -> Unit, onDownload: () -> Unit, onDelete: () -> Unit,
    onCopy: () -> Unit, onChmod: () -> Unit, onRename: (RemoteEntry) -> Unit, onEdit: (RemoteEntry) -> Unit,
) {
    var more by remember { mutableStateOf(false) }
    val hasFiles = picked.any { !it.isDirectory }
    val single = picked.singleOrNull()
    Row(Modifier.fillMaxWidth().background(Nt.Accent.copy(alpha = 0.10f)).padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = tr("Annuler la sélection")) }
        Text(
            if (picked.size == 1) tr("1 sélectionné") else tr("%1\$s sélectionnés", picked.size),
            fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.weight(1f),
        )
        if (picked.size < total) IconButton(onClick = onSelectAll) { Icon(Icons.Outlined.SelectAll, contentDescription = tr("Tout sélectionner"), tint = Nt.TextSoft) }
        if (hasFiles) IconButton(onClick = onDownload) { Icon(Icons.Outlined.Download, contentDescription = tr("Télécharger"), tint = Nt.TextSoft) }
        IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, contentDescription = tr("Supprimer"), tint = Nt.Pink) }
        Box {
            IconButton(onClick = { more = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = tr("Plus d'actions"), tint = Nt.TextSoft) }
            DropdownMenu(more, onDismissRequest = { more = false }) {
                if (hasFiles) DropdownMenuItem(text = { Text(tr("Copier vers un autre hôte…")) }, onClick = { more = false; onCopy() })
                DropdownMenuItem(text = { Text(tr("Permissions")) }, onClick = { more = false; onChmod() })
                if (single != null) {
                    DropdownMenuItem(text = { Text(tr("Renommer")) }, onClick = { more = false; onRename(single) })
                    if (!single.isDirectory) DropdownMenuItem(text = { Text(tr("Modifier")) }, onClick = { more = false; onEdit(single) })
                }
            }
        }
    }
}

@Composable
private fun ToolButton(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled) {
        Icon(icon, contentDescription = null, tint = if (enabled) Nt.Accent else Nt.Muted, modifier = Modifier.size(18.dp))
        Text(" $label", color = if (enabled) Nt.TextSoft else Nt.Muted, fontSize = 13.sp)
    }
}

private val dateFormat = SimpleDateFormat("dd/MM/yy HH:mm", Locale.FRANCE)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryRow(e: RemoteEntry, isPicked: Boolean, onLongClick: () -> Unit, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .background(if (isPicked) Nt.Accent.copy(alpha = 0.12f) else Color.Transparent)
            .semantics { selected = isPicked }
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = tr("Sélectionner"))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background((if (isPicked) Nt.Accent else if (e.isDirectory) Nt.Accent else Nt.Blue).copy(alpha = if (isPicked) 0.9f else 0.14f)), contentAlignment = Alignment.Center) {
            when {
                isPicked -> Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Nt.OnAccent, modifier = Modifier.size(22.dp))
                else -> Icon(if (e.isDirectory) Icons.Outlined.Folder else Icons.Outlined.Description, contentDescription = null, tint = if (e.isDirectory) Nt.Accent else Nt.Blue, modifier = Modifier.size(20.dp))
            }
        }
        Column(Modifier.weight(1f)) {
            Text(e.name + if (e.isLink) " ↗" else "", fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(if (e.isDirectory) null else humanSize(e.size), dateFormat.format(Date(e.modified * 1000)), permissionsText(e.permissions)).joinToString(" · "),
                fontFamily = Nt.Mono, fontSize = 11.sp, color = Nt.Muted,
            )
        }
    }
}

fun humanSize(b: Long): String = when {
    b < 0 -> "?"
    b < 1024 -> "$b o"
    b < 1024 * 1024 -> "%.1f Ko".format(b / 1024.0)
    b < 1024L * 1024 * 1024 -> "%.1f Mo".format(b / 1024.0 / 1024)
    else -> "%.2f Go".format(b / 1024.0 / 1024 / 1024)
}

@Composable
private fun TransfersPanel(list: List<Transfer>, onCancel: (Long) -> Unit, onClear: () -> Unit) {
    if (list.isEmpty()) return
    Column(Modifier.fillMaxWidth().background(Nt.Bar).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("Transferts"), fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.weight(1f))
            if (list.any { it.state != Transfer.State.RUNNING }) TextButton(onClick = onClear) { Text(tr("Effacer terminés"), color = Nt.TextSoft, fontSize = 12.sp) }
        }
        list.takeLast(4).forEach { t ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                    Text(t.label, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val status = when (t.state) {
                        Transfer.State.RUNNING -> "${humanSize(t.done)}${if (t.total > 0) " / ${humanSize(t.total)}" else ""}"
                        Transfer.State.DONE -> tr("Terminé · %1\$s", humanSize(t.done))
                        Transfer.State.FAILED -> tr("Échec : %1\$s", t.error)
                        Transfer.State.CANCELLED -> tr("Annulé")
                    }
                    Text("${t.detail} · $status", fontSize = 11.sp, color = if (t.state == Transfer.State.FAILED) Nt.Pink else Nt.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (t.state == Transfer.State.RUNNING) {
                        if (t.total > 0) LinearProgressIndicator(progress = { t.progress }, color = Nt.Accent, trackColor = Nt.Border, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                        else LinearProgressIndicator(color = Nt.Accent, trackColor = Nt.Border, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                    }
                }
                if (t.state == Transfer.State.RUNNING) IconButton(onClick = { onCancel(t.id) }) { Icon(Icons.Filled.Close, contentDescription = tr("Annuler"), tint = Nt.Muted) }
            }
        }
    }
}

@Composable
private fun NameDialog(title: String, initial: String, digitsOnly: Boolean = false, onDone: (String?) -> Unit) {
    var v by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = { onDone(null) }, containerColor = Nt.Surface,
        title = { Text(title) },
        text = { NtTextField(v, { v = if (digitsOnly) it.filter(Char::isDigit).take(4) else it }, if (digitsOnly) "Ex. 644" else "Nom", mono = true) },
        dismissButton = { TextButton(onClick = { onDone(null) }) { Text(tr("Annuler"), color = Nt.TextSoft) } },
        confirmButton = { TextButton(onClick = { onDone(v.trim()) }, enabled = v.isNotBlank() && '/' !in v) { Text(tr("Valider"), color = Nt.Accent) } },
    )
}

@Composable
private fun CopyDialog(hosts: List<Pair<Long, String>>, onDone: (Pair<Long, String>?, String) -> Unit) {
    var dest by remember { mutableStateOf(hosts.firstOrNull()) }
    var dir by remember { mutableStateOf(".") }
    var open by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { onDone(null, "") }, containerColor = Nt.Surface,
        title = { Text(tr("Copier vers un autre hôte")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (hosts.isEmpty()) Text(tr("Aucun autre hôte enregistré."), color = Nt.Muted)
                Box {
                    Text(dest?.second ?: tr("Choisir…"), fontWeight = FontWeight.SemiBold, modifier = Modifier.fillMaxWidth().clickable { open = true }.padding(vertical = 8.dp))
                    DropdownMenu(open, { open = false }) { hosts.forEach { h -> DropdownMenuItem(text = { Text(h.second) }, onClick = { dest = h; open = false }) } }
                }
                NtTextField(dir, { dir = it }, tr("Dossier de destination"), mono = true, placeholder = "/home/user")
                Text(tr("« . » = dossier personnel. Le fichier transite par le téléphone."), color = Nt.Muted, fontSize = 12.sp)
            }
        },
        dismissButton = { TextButton(onClick = { onDone(null, "") }) { Text(tr("Annuler"), color = Nt.TextSoft) } },
        confirmButton = { TextButton(onClick = { onDone(dest, dir.trim().ifEmpty { "." }) }, enabled = dest != null) { Text(tr("Copier"), color = Nt.Accent) } },
    )
}

/** Octal mode with setuid / setgid / sticky when set (« 4755 », « 1777 »), so a chmod keeps them. */
internal fun permissionsText(mode: Int): String = (mode and 0xFFF).let { if (it > 0x1FF) "%04o".format(it) else "%03o".format(it) }
