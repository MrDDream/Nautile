package com.nautile.app.ui.terminal

import com.nautile.i18n.tr

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nautile.app.NautileApp
import com.nautile.app.ai.AiAssistant
import com.nautile.app.session.ActiveSession
import com.nautile.app.ui.common.NtTextField
import com.nautile.app.ui.theme.Nt
import kotlinx.coroutines.launch

/** Ask the AI for a command; nothing is typed until the user chooses Insert or Run. */
@Composable
fun AssistantDialog(session: ActiveSession, screen: () -> String?, onDismiss: () -> Unit, onInsert: (String, Boolean) -> Unit) {
    val ai = (LocalContext.current.applicationContext as NautileApp).ai
    val scope = rememberCoroutineScope()
    var request by remember { mutableStateOf("") }
    var withScreen by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<AiAssistant.Suggestion?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = Nt.Surface,
        title = { Text("Assistant · ${session.alias}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!ai.configured) Text(tr("Configurez d'abord un fournisseur dans Réglages › Assistant IA."), color = Nt.Pink, fontSize = 13.sp)
                else Text("Via ${ai.providerLabel} · ${ai.config().model}", color = Nt.Muted, fontSize = 12.sp)
                NtTextField(request, { request = it }, tr("Que voulez-vous faire ?"), singleLine = false,
                    placeholder = tr("Trouver les 10 plus gros fichiers de /var"))
                Row(Modifier.clickable { withScreen = !withScreen }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(withScreen, { withScreen = it }, colors = CheckboxDefaults.colors(checkedColor = Nt.Accent))
                    val lines by (LocalContext.current.applicationContext as com.nautile.app.NautileApp).terminalPrefs.aiLines.collectAsState()
                    Text(tr("Joindre l'écran (%1\$s dernières lignes) — pour expliquer une erreur", lines), fontSize = 13.sp, color = Nt.TextSoft)
                }
                if (busy) LinearProgressIndicator(color = Nt.Accent, trackColor = Nt.Border, modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, color = Nt.Pink, fontSize = 13.sp) }
                result?.let { r ->
                    r.command?.let { cmd ->
                        Text(cmd, fontFamily = Nt.Mono, fontSize = 13.sp, color = Nt.Text,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Nt.Bg).padding(12.dp))
                    }
                    if (r.explanation.isNotBlank()) Text(r.explanation, fontSize = 13.sp, color = Nt.TextSoft)
                }
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss, enabled = !busy) { Text(tr("Fermer"), color = Nt.TextSoft) }
                result?.command?.let { cmd -> TextButton(onClick = { onInsert(cmd, false) }) { Text(tr("Insérer"), color = Nt.TextSoft) } }
            }
        },
        confirmButton = {
            val cmd = result?.command
            if (cmd != null) TextButton(onClick = { onInsert(cmd, true) }) { Text(tr("Exécuter"), color = Nt.Accent, fontWeight = FontWeight.SemiBold) }
            else TextButton(
                enabled = !busy && ai.configured && request.isNotBlank(),
                onClick = {
                    busy = true; error = null; result = null
                    scope.launch {
                        runCatching { ai.ask(request.trim(), "${session.address} (${session.protocolLabel})", if (withScreen) screen() else null) }
                            .onSuccess { result = it }
                            .onFailure { error = it.message ?: tr("Échec de la requête") }
                        busy = false
                    }
                },
            ) { Text(tr("Demander"), color = Nt.Accent, fontWeight = FontWeight.SemiBold) }
        },
    )
}
