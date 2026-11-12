package com.nautile.app.ui.tools

import com.nautile.i18n.tr

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FileCopy
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nautile.app.NautileApp
import com.nautile.app.ui.common.PageHeader
import com.nautile.app.ui.common.SectionLabel
import com.nautile.app.ui.common.primaryBorder
import com.nautile.app.ui.theme.Nt
import com.nautile.data.model.Snippet
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Ready-made snippets offered on an empty page. */
private val STARTERS = listOf(
    Snippet(name = tr("Espace disque"), command = "df -h", tag = tr("Système")),
    Snippet(name = tr("Mises à jour (Debian, Ubuntu)"), command = "sudo apt update && sudo apt upgrade -y", tag = tr("Système")),
    Snippet(name = tr("Conteneurs Docker"), command = "docker ps -a --format 'table {{.Names}}\\t{{.Status}}'", tag = "Docker"),
    Snippet(name = tr("Journal d'un service"), command = "journalctl -u \${SERVICE} -n 100 --no-pager", tag = tr("Système")),
    Snippet(name = tr("Redémarrer un service"), command = "sudo systemctl restart \${SERVICE}", tag = tr("Système")),
)

/** Saved commands: search, filter by tag, run on open sessions or new ones, copy, edit. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SnippetsScreen(onOpenSession: (Long) -> Unit) {
    val app = LocalContext.current.applicationContext as NautileApp
    val dao = app.database.toolsDao()
    val snippets by dao.observeSnippets().collectAsState(initial = emptyList())
    val sessions by app.sessions.sessions.collectAsState()
    // Remote desktops have no shell to run snippets in.
    val hosts by remember { app.hostRepository.hosts.map { list -> list.filter { it.protocol != com.nautile.data.model.Protocol.RDP } } }
        .collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var tag by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var editing by remember { mutableStateOf<Snippet?>(null) }
    var creating by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf<Snippet?>(null) }
    val tags = snippets.map { it.tag }.filter { it.isNotBlank() }.distinct().sorted()
    val q = query.trim()
    val shown = snippets.filter { (tag == null || it.tag == tag) && (q.isEmpty() || it.name.contains(q, true) || it.command.contains(q, true)) }

    fun copy(s: Snippet) {
        app.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(s.name, s.command))
        scope.launch { snackbar.showSnackbar(tr("« %1\$s » copié", s.name)) }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { PageHeader(tr("Snippets")) }
            if (snippets.isNotEmpty()) item {
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, tint = Nt.Muted) },
                    trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, contentDescription = tr("Effacer la recherche"), tint = Nt.Muted) } },
                    placeholder = { Text(tr("Rechercher un snippet"), color = Nt.Placeholder, fontSize = 13.sp) },
                    singleLine = true, shape = RoundedCornerShape(14.dp),
                    textStyle = TextStyle(fontSize = 14.sp, color = Nt.Text),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = Nt.Surface, focusedContainerColor = Nt.Surface,
                        unfocusedBorderColor = Nt.Border, focusedBorderColor = Nt.Accent, cursorColor = Nt.Accent,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (tags.isNotEmpty()) item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip(tr("Tous · %1\$s", snippets.size), tag == null) { tag = null }
                    tags.forEach { t -> Chip("$t · ${snippets.count { it.tag == t }}", tag == t) { tag = if (tag == t) null else t } }
                }
            }
            if (snippets.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(top = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(tr("Vos commandes fréquentes, en un geste"), style = MaterialTheme.typography.titleMedium)
                    Text(
                        tr("Enregistrez une commande, lancez-la sur une ou plusieurs sessions, ou ouvrez les hôtes choisis et exécutez-la. ") +
                            tr("Les variables comme \${SERVICE} sont demandées au lancement."),
                        color = Nt.Muted, textAlign = TextAlign.Center, fontSize = 13.sp,
                    )
                    OutlinedButton(onClick = { scope.launch { STARTERS.forEach { dao.upsertSnippet(it) } } }) {
                        Text(tr("Ajouter quelques exemples"), color = Nt.Accent)
                    }
                }
            } else if (shown.isEmpty()) item {
                Text(tr("Aucun snippet ne correspond."), color = Nt.Muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 24.dp))
            }
            if (shown.isNotEmpty() && q.isEmpty() && tag == null && shown.any { it.runCount > 0 }) item { SectionLabel(tr("Les plus utilisés d'abord")) }
            items(shown, key = { it.id }) { s ->
                SnippetCard(
                    s,
                    onRun = { running = s },
                    onCopy = { copy(s) },
                    onEdit = { editing = s },
                    onDuplicate = { scope.launch { dao.upsertSnippet(s.copy(id = 0, name = tr("%1\$s (copie)", s.name), runCount = 0, createdAt = System.currentTimeMillis())) } },
                    onDelete = { scope.launch { dao.deleteSnippet(s) } },
                )
            }
        }
        FloatingActionButton(
            onClick = { creating = true }, containerColor = Nt.PrimaryContainer, contentColor = Nt.OnPrimaryContainer, shape = CircleShape,
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp).size(60.dp).primaryBorder(CircleShape),
        ) { Icon(Icons.Filled.Add, contentDescription = tr("Nouveau snippet"), modifier = Modifier.size(28.dp)) }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 90.dp))
    }

    if (creating) SnippetEditDialog(null, { creating = false }, { scope.launch { dao.upsertSnippet(it) }; creating = false }, null)
    editing?.let { s ->
        SnippetEditDialog(s, { editing = null }, { scope.launch { dao.upsertSnippet(it) }; editing = null }) {
            scope.launch { dao.deleteSnippet(s) }; editing = null
        }
    }
    running?.let { s ->
        RunSnippetDialog(s, sessions, hosts, singleTarget = false, onDismiss = { running = null }) { cmd, sessionIds, hostIds ->
            running = null
            scope.launch {
                dao.countRun(s.id)
                sessionIds.mapNotNull(app.sessions::find).forEach { app.sessions.run(it, cmd) }
                var last: Long? = sessionIds.lastOrNull()
                hostIds.forEach { h -> app.sessions.open(h, extraCommand = cmd).onSuccess { last = it.id } }
                last?.let(onOpenSession)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SnippetCard(s: Snippet, onRun: () -> Unit, onCopy: () -> Unit, onEdit: () -> Unit, onDuplicate: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(16.dp), color = Nt.Surface, border = BorderStroke(1.dp, Nt.Border),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(s.name, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val meta = listOfNotNull(
                        s.tag.ifBlank { null },
                        if (s.runCount > 0) tr("%1\$s exécution%2\$s", s.runCount, if (s.runCount > 1) "s" else "") else tr("jamais lancé"),
                    ).joinToString(" · ")
                    Text(meta, fontSize = 12.sp, color = Nt.Muted)
                }
                IconButton(onClick = onCopy) { Icon(Icons.Outlined.ContentCopy, contentDescription = tr("Copier %1\$s", s.name), tint = Nt.TextSoft) }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = tr("Plus d'actions"), tint = Nt.TextSoft) }
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(tr("Modifier")) }, leadingIcon = { Icon(Icons.Outlined.Edit, null) }, onClick = { menu = false; onEdit() })
                        DropdownMenuItem(text = { Text(tr("Dupliquer")) }, leadingIcon = { Icon(Icons.Outlined.FileCopy, null) }, onClick = { menu = false; onDuplicate() })
                        DropdownMenuItem(text = { Text(tr("Supprimer"), color = Nt.Pink) }, leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = Nt.Pink) }, onClick = { menu = false; onDelete() })
                    }
                }
            }
            Text(
                s.command, fontFamily = Nt.Mono, fontSize = 12.sp, color = Nt.TextSoft, maxLines = 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(end = 8.dp).clip(RoundedCornerShape(10.dp)).background(Nt.Bg)
                    .clickable(role = Role.Button, onClickLabel = tr("Modifier"), onClick = onEdit).padding(horizontal = 12.dp, vertical = 10.dp),
            )
            Row(Modifier.padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    s.variables.forEach { v ->
                        Box(Modifier.height(24.dp).clip(RoundedCornerShape(8.dp)).background(Nt.Accent.copy(alpha = 0.12f)).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                            Text("\${$v}", fontFamily = Nt.Mono, fontSize = 11.sp, color = Nt.Accent)
                        }
                    }
                }
                Surface(
                    onClick = onRun, shape = RoundedCornerShape(12.dp),
                    color = Nt.PrimaryContainer, contentColor = Nt.OnPrimaryContainer, modifier = Modifier.height(40.dp),
                ) {
                    Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                        Text(tr("Exécuter"), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick, shape = RoundedCornerShape(18.dp),
        color = if (selected) Nt.Accent.copy(alpha = 0.12f) else Nt.Surface,
        border = BorderStroke(1.dp, if (selected) Nt.Accent else Nt.Border), modifier = Modifier.height(36.dp),
    ) {
        Box(Modifier.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (selected) Nt.Accent else Nt.TextSoft)
        }
    }
}
