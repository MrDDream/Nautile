package com.nautile.app.ui.tools

import com.nautile.i18n.tr

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nautile.app.session.ActiveSession
import com.nautile.app.ui.common.NtTextField
import com.nautile.app.ui.common.SectionLabel
import com.nautile.app.ui.theme.Nt
import com.nautile.data.model.Host
import com.nautile.data.model.Snippet

@Composable
fun SnippetEditDialog(initial: Snippet?, onDismiss: () -> Unit, onSave: (Snippet) -> Unit, onDelete: (() -> Unit)?) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var tag by remember { mutableStateOf(initial?.tag.orEmpty()) }
    var cmd by remember { mutableStateOf(initial?.command.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Nt.Surface,
        title = { Text(if (initial == null) tr("Nouveau snippet") else tr("Modifier le snippet")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NtTextField(name, { name = it }, tr("Nom"), placeholder = tr("Mettre à jour la stack"))
                NtTextField(tag, { tag = it }, tr("Catégorie"), placeholder = tr("Docker"))
                NtTextField(cmd, { cmd = it }, "Commande", mono = true, singleLine = false,
                    placeholder = "docker logs -f --tail 200 \${SERVICE}", modifier = Modifier.heightIn(min = 110.dp))
                Text(tr("%1\$s devient une variable demandée à chaque exécution. Plusieurs lignes = plusieurs commandes.", "\${NOM}"), color = Nt.Muted, fontSize = 12.sp)
                if (onDelete != null) TextButton(onClick = onDelete) { Text(tr("Supprimer le snippet"), color = Nt.Pink) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Annuler"), color = Nt.TextSoft) } },
        confirmButton = {
            TextButton(
                onClick = { onSave((initial ?: Snippet(name = "", command = "")).copy(name = name.trim().ifEmpty { cmd.lineSequence().first().take(40) }, tag = tag.trim(), command = cmd.trimEnd())) },
                enabled = cmd.isNotBlank(),
            ) { Text(tr("Enregistrer"), color = Nt.Accent, fontWeight = FontWeight.SemiBold) }
        },
    )
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
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(snippet.fill(values), fontFamily = Nt.Mono, fontSize = 12.sp, color = Nt.TextSoft)
                vars.forEach { v -> NtTextField(values[v].orEmpty(), { values[v] = it }, v, mono = true) }
                if (!singleTarget) {
                    if (sessions.isNotEmpty()) {
                        SectionLabel(tr("Sessions ouvertes"))
                        sessions.forEach { s -> Check(s.alias, s.id in selSessions) { if (it) selSessions += s.id else selSessions -= s.id } }
                    }
                    SectionLabel(tr("Nouvelles connexions"))
                    hosts.forEach { h -> Check(h.alias, h.id in selHosts) { if (it) selHosts += h.id else selHosts -= h.id } }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Annuler"), color = Nt.TextSoft) } },
        confirmButton = {
            val ready = vars.all { !values[it].isNullOrEmpty() } && (singleTarget || selSessions.isNotEmpty() || selHosts.isNotEmpty())
            val n = selSessions.size + selHosts.size
            TextButton(onClick = { onRun(snippet.fill(values), selSessions.toList(), selHosts.toList()) }, enabled = ready) {
                Text(if (singleTarget || n <= 1) tr("Exécuter") else tr("Exécuter sur %1\$s", n), color = Nt.Accent, fontWeight = FontWeight.SemiBold)
            }
        },
    )
}

@Composable
private fun Check(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onChange, colors = CheckboxDefaults.colors(checkedColor = Nt.Accent))
        Text(label, fontSize = 14.sp)
    }
}
