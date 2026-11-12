package com.nautile.app.ui.tools

import com.nautile.i18n.tr

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nautile.app.session.ActiveSession
import com.nautile.app.ui.common.NtTextField
import com.nautile.app.ui.common.SectionLabel
import com.nautile.app.ui.theme.Nt
import com.nautile.data.model.Host
import com.nautile.data.model.Snippet

/** New or edited snippet, in a sheet: name, command with quick inserts, category. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SnippetEditDialog(initial: Snippet?, tags: List<String>, onDismiss: () -> Unit, onSave: (Snippet) -> Unit, onDelete: (() -> Unit)?) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Nt.Surface) {
        SnippetEditorContent(initial, tags, onDismiss, onSave, onDelete)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SnippetEditorContent(initial: Snippet?, tags: List<String>, onDismiss: () -> Unit, onSave: (Snippet) -> Unit, onDelete: (() -> Unit)?) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var tag by remember { mutableStateOf(initial?.tag.orEmpty()) }
    var cmd by remember { mutableStateOf(TextFieldValue(initial?.command.orEmpty())) }
    var confirmDelete by remember { mutableStateOf(false) }
    val vars = Snippet(name = "", command = cmd.text).variables

    /** Inserts [text] at the cursor; [select] selects that part of it (a variable's name to type over). */
    fun insert(text: String, select: IntRange? = null) {
        val start = cmd.selection.min
        val newText = cmd.text.replaceRange(start, cmd.selection.max, text)
        cmd = TextFieldValue(newText, select?.let { TextRange(start + it.first, start + it.last + 1) } ?: TextRange(start + text.length))
    }

    fun save() = onSave(
        (initial ?: Snippet(name = "", command = "")).copy(
            name = name.trim().ifEmpty { cmd.text.lineSequence().first().take(40) }, tag = tag.trim(), command = cmd.text.trimEnd(),
        ),
    )

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Nt.Accent.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Terminal, contentDescription = null, tint = Nt.Accent)
            }
            Text(if (initial == null) tr("Nouveau snippet") else tr("Modifier le snippet"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, contentDescription = tr("Fermer"), tint = Nt.TextSoft) }
        }

        NtTextField(name, { name = it }, tr("Nom"), placeholder = tr("Mettre à jour la stack"))

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = cmd, onValueChange = { cmd = it },
                label = { Text(tr("Commande")) },
                placeholder = { Text("docker logs -f --tail 200 \${SERVICE}", color = Nt.Placeholder, fontFamily = Nt.Mono) },
                modifier = Modifier.fillMaxWidth(), minLines = 4, maxLines = 12,
                textStyle = TextStyle(fontFamily = Nt.Mono, fontSize = 14.sp, lineHeight = 20.sp, color = Nt.Text),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = Nt.Bg, focusedContainerColor = Nt.Bg,
                    unfocusedBorderColor = Nt.Border, focusedBorderColor = Nt.Accent,
                    unfocusedLabelColor = Nt.TextSoft, focusedLabelColor = Nt.Accent, cursorColor = Nt.Accent,
                ),
            )
            // Quick inserts: what is awkward to type on a phone keyboard.
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                InsertKey(tr("+ Variable"), accent = true) { insert("\${NOM}", 2..4) }
                listOf("|", "&&", ">", "~/", "sudo ", "\"", "'", "$", "\\").forEach { k -> InsertKey(k.trim()) { insert(k) } }
            }
        }

        if (vars.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(tr("Demandées à chaque exécution"), color = Nt.Muted, fontSize = 12.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                vars.forEach { v ->
                    Box(Modifier.height(26.dp).clip(RoundedCornerShape(8.dp)).background(Nt.Accent.copy(alpha = 0.12f)).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                        Text("\${$v}", fontFamily = Nt.Mono, fontSize = 12.sp, color = Nt.Accent)
                    }
                }
            }
        } else Text(tr("Astuce : %1\$s devient une valeur demandée à chaque exécution. Plusieurs lignes = plusieurs commandes.", "\${NOM}"),
            color = Nt.Muted, fontSize = 12.sp, lineHeight = 16.sp)

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            NtTextField(tag, { tag = it }, tr("Catégorie"), placeholder = tr("Docker"))
            if (tags.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                tags.forEach { t ->
                    val on = t.equals(tag.trim(), ignoreCase = true)
                    Box(
                        Modifier.height(32.dp).clip(RoundedCornerShape(16.dp))
                            .background(if (on) Nt.Accent.copy(alpha = 0.14f) else Color.Transparent)
                            .border(1.dp, if (on) Nt.Accent else Nt.Border, RoundedCornerShape(16.dp))
                            .clickable(role = Role.RadioButton) { tag = if (on) "" else t }.padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text(t, fontSize = 13.sp, color = if (on) Nt.Accent else Nt.TextSoft) }
                }
            }
        }

        Button(
            onClick = ::save, enabled = cmd.text.isNotBlank(), modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Nt.Accent, contentColor = Nt.OnAccent),
        ) { Text(tr("Enregistrer"), fontWeight = FontWeight.SemiBold) }

        if (onDelete != null) {
            if (!confirmDelete) TextButton(onClick = { confirmDelete = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Icon(Icons.Outlined.Delete, contentDescription = null, tint = Nt.Pink, modifier = Modifier.size(18.dp))
                Text("  " + tr("Supprimer le snippet"), color = Nt.Pink)
            } else TextButton(onClick = onDelete, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(tr("Touchez à nouveau pour supprimer"), color = Nt.Pink, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun InsertKey(label: String, accent: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.height(36.dp).clip(RoundedCornerShape(10.dp))
            .background(if (accent) Nt.Accent.copy(alpha = 0.14f) else Nt.KeyBg)
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, fontFamily = if (accent) null else Nt.Mono, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (accent) Nt.Accent else Nt.Text) }
}

/**
 * Asks for the snippet's variables and, unless [singleTarget], where to run it: open sessions
 * (broadcast) and/or hosts to connect to.
 */
@Composable
fun RunSnippetDialog(
    snippet: Snippet,
    sessions: List<ActiveSession>,
    hosts: List<Host>,
    singleTarget: Boolean,
    onDismiss: () -> Unit,
    onRun: (command: String, sessionIds: List<Long>, hostIds: List<Long>) -> Unit,
) {
    val values = remember { mutableStateMapOf<String, String>() }
    val selSessions = remember { mutableStateListOf<Long>().apply { sessions.lastOrNull()?.let { add(it.id) } } }
    val selHosts = remember { mutableStateListOf<Long>() }
    val vars = snippet.variables
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Nt.Surface,
        title = { Text(snippet.name) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Nt.Bg).padding(12.dp)) {
                    Text("$ ", fontFamily = Nt.Mono, fontSize = 12.sp, color = Nt.Accent)
                    Text(snippet.fill(values), fontFamily = Nt.Mono, fontSize = 12.sp, lineHeight = 17.sp, color = Nt.Text)
                }
                vars.forEach { v -> NtTextField(values[v].orEmpty(), { values[v] = it }, v, mono = true) }
                if (!singleTarget) {
                    if (sessions.isNotEmpty()) {
                        SectionLabel(tr("Sessions ouvertes"), Modifier.padding(top = 4.dp))
                        sessions.forEach { s -> Check(s.alias, s.address, s.id in selSessions) { if (it) selSessions += s.id else selSessions -= s.id } }
                    }
                    SectionLabel(tr("Nouvelles connexions"), Modifier.padding(top = 4.dp))
                    hosts.forEach { h -> Check(h.alias, h.address, h.id in selHosts) { if (it) selHosts += h.id else selHosts -= h.id } }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Annuler"), color = Nt.TextSoft) } },
        confirmButton = {
            val ready = vars.all { !values[it].isNullOrEmpty() } && (singleTarget || selSessions.isNotEmpty() || selHosts.isNotEmpty())
            val n = selSessions.size + selHosts.size
            Button(
                onClick = { onRun(snippet.fill(values), selSessions.toList(), selHosts.toList()) }, enabled = ready, shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Nt.Accent, contentColor = Nt.OnAccent),
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(" " + if (singleTarget || n <= 1) tr("Exécuter") else tr("Exécuter sur %1\$s", n), fontWeight = FontWeight.SemiBold)
            }
        },
    )
}

@Composable
private fun Check(label: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (checked) Nt.Accent.copy(alpha = 0.08f) else Color.Transparent)
            .clickable(role = Role.Checkbox) { onChange(!checked) }.padding(end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked, onChange, colors = CheckboxDefaults.colors(checkedColor = Nt.Accent))
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(detail, fontSize = 11.sp, fontFamily = Nt.Mono, color = Nt.Muted, maxLines = 1)
        }
    }
}
