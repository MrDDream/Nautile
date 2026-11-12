package com.nautile.app.ui.terminal

import com.nautile.i18n.tr

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nautile.app.ai.AiFile
import com.nautile.app.ai.AiFiles
import com.nautile.app.ui.theme.Nt
import com.nautile.ssh.RemoteEntry
import kotlinx.coroutines.launch

/** The server's files, over SFTP: where to start (the terminal's directory), listing and reading. */
class RemoteFiles(
    val host: String,
    /** The terminal's current directory, or the home directory. */
    val start: suspend () -> String,
    val home: suspend () -> String,
    val list: suspend (String) -> List<RemoteEntry>,
    val read: suspend (String) -> AiFile,
)

/** Browse the server from the terminal's directory and pick a text file to attach. */
@Composable
internal fun RemoteFilePicker(files: RemoteFiles, onPhone: () -> Unit, onDismiss: () -> Unit, onRead: (AiFile) -> Unit) {
    val scope = rememberCoroutineScope()
    var path by remember { mutableStateOf<String?>(null) }
    var typed by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<RemoteEntry>?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var reading by remember { mutableStateOf<String?>(null) }

    fun open(p: String) {
        problem = null; entries = null
        scope.launch {
            runCatching { files.list(p) }
                .onSuccess { list ->
                    path = p; typed = p
                    entries = list.filter { it.name != "." && it.name != ".." }
                        .sortedWith(compareBy<RemoteEntry>({ !it.isDirectory }, { it.name.startsWith(".") }, { it.name.lowercase() }))
                }
                .onFailure { problem = it.message ?: tr("Dossier illisible"); entries = emptyList(); typed = path ?: p }
        }
    }

    fun pick(e: RemoteEntry) {
        if (e.isDirectory) { open(e.path); return }
        reading = e.path; problem = null
        scope.launch {
            runCatching { files.read(e.path) }.onSuccess(onRead).onFailure { problem = it.message ?: tr("Lecture impossible") }
            reading = null
        }
    }

    LaunchedEffect(Unit) { open(runCatching { files.start() }.getOrElse { "/" }) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.86f), shape = RoundedCornerShape(24.dp), color = Nt.Surface) {
            Column(Modifier.padding(top = 16.dp)) {
                Row(Modifier.padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("Joindre un fichier"), style = MaterialTheme.typography.titleLarge)
                        Text(tr("Sur %1\$s, en SFTP", files.host), color = Nt.Muted, fontSize = 12.sp)
                    }
                    IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, contentDescription = tr("Fermer"), tint = Nt.TextSoft) }
                }
                // Path: up, editable path, home
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { path?.let { open(com.nautile.ssh.SftpSession.parent(it)) } }, enabled = path != null && path != "/") {
                        Icon(Icons.Outlined.ArrowUpward, contentDescription = tr("Dossier parent"), tint = Nt.TextSoft)
                    }
                    OutlinedTextField(
                        value = typed, onValueChange = { typed = it }, singleLine = true, modifier = Modifier.weight(1f),
                        textStyle = TextStyle(fontFamily = Nt.Mono, fontSize = 13.sp, color = Nt.Text), shape = RoundedCornerShape(12.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { if (typed.isNotBlank()) open(typed.trim()) }),
                        trailingIcon = {
                            if (typed != path && typed.isNotBlank()) IconButton(onClick = { open(typed.trim()) }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = tr("Aller"), tint = Nt.Accent)
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedContainerColor = Nt.Bg, focusedContainerColor = Nt.Bg,
                            unfocusedBorderColor = Nt.Border, focusedBorderColor = Nt.Accent, cursorColor = Nt.Accent,
                        ),
                    )
                    IconButton(onClick = { scope.launch { open(runCatching { files.home() }.getOrElse { "/" }) } }) {
                        Icon(Icons.Outlined.Home, contentDescription = tr("Dossier personnel"), tint = Nt.TextSoft)
                    }
                }
                problem?.let { Text(it, color = Nt.Pink, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) }
                HorizontalDivider(color = Nt.Border)
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    val list = entries
                    when {
                        list == null -> item { LinearProgressIndicator(color = Nt.Accent, trackColor = Nt.Border, modifier = Modifier.fillMaxWidth().padding(20.dp)) }
                        list.isEmpty() && problem == null -> item { Text(tr("Dossier vide"), color = Nt.Muted, fontSize = 13.sp, modifier = Modifier.padding(20.dp)) }
                    }
                    items(list.orEmpty(), key = { it.path }) { e -> EntryRow(e, loading = reading == e.path) { pick(e) } }
                }
                HorizontalDivider(color = Nt.Border)
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("Texte seulement, %1\$s au plus", AiFiles.sizeLabel(AiFiles.MAX_BYTES.toLong())),
                        color = Nt.Muted, fontSize = 12.sp, modifier = Modifier.weight(1f).padding(start = 8.dp))
                    TextButton(onClick = onPhone) {
                        Icon(Icons.Outlined.PhoneAndroid, contentDescription = null, tint = Nt.Accent, modifier = Modifier.size(18.dp))
                        Text(" " + tr("Du téléphone"), color = Nt.Accent)
                    }
                }
            }
        }
    }
}

@Composable
private fun EntryRow(e: RemoteEntry, loading: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)).background(if (e.isDirectory) Nt.Accent.copy(alpha = 0.14f) else Nt.Bg), contentAlignment = Alignment.Center) {
            Icon(if (e.isDirectory) Icons.Outlined.Folder else Icons.Outlined.Description, contentDescription = null,
                tint = if (e.isDirectory) Nt.Accent else Nt.TextSoft, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(e.name, fontSize = 15.sp, fontWeight = if (e.isDirectory) FontWeight.SemiBold else FontWeight.Normal,
                color = if (e.name.startsWith(".")) Nt.TextSoft else Nt.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!e.isDirectory) Text(AiFiles.sizeLabel(e.size), fontSize = 11.sp, color = Nt.Muted)
        }
        if (loading) CircularProgressIndicator(Modifier.size(20.dp), color = Nt.Accent, strokeWidth = 2.dp)
    }
}
