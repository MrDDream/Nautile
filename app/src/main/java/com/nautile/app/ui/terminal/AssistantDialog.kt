package com.nautile.app.ui.terminal

import com.nautile.i18n.tr

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nautile.app.NautileApp
import com.nautile.app.ai.AiAssistant
import com.nautile.app.ai.prettyModel
import com.nautile.app.session.ActiveSession
import com.nautile.app.ui.common.ProviderLogo
import com.nautile.app.ui.common.SectionLabel
import com.nautile.app.ui.theme.Nt
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Ask the AI for a command: pick the provider and the system prompt, write the request (or tap a
 * suggestion), then insert or run the answer. Nothing is typed until the user chooses.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantDialog(session: ActiveSession, screen: () -> String?, onDismiss: () -> Unit, onInsert: (String, Boolean) -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Nt.Surface) {
        AssistantPanel(session, screen, onDismiss, onInsert)
    }
}

/** The sheet's content; [initialRequest] and [initialResult] let the screenshots show an answer. */
@Composable
internal fun AssistantPanel(
    session: ActiveSession,
    screen: () -> String?,
    onDismiss: () -> Unit,
    onInsert: (String, Boolean) -> Unit,
    initialRequest: String = "",
    initialResult: AiAssistant.Suggestion? = null,
) {
    val app = LocalContext.current.applicationContext as NautileApp
    val ai = app.ai
    val scope = rememberCoroutineScope()
    val lines by app.terminalPrefs.aiLines.collectAsState()
    val prompts by ai.prompts.collectAsState()
    val promptId by ai.promptId.collectAsState()
    val ready = remember { ai.readyProviders() }
    var kind by remember { mutableStateOf(ready.firstOrNull()) }
    var request by remember { mutableStateOf(initialRequest) }
    var withScreen by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var result by remember { mutableStateOf(initialResult) }
    var error by remember { mutableStateOf<String?>(null) }
    val busy = job != null

    fun send(text: String = request, screenToo: Boolean = withScreen) {
        val k = kind ?: return
        if (text.isBlank() || busy) return
        error = null; result = null
        job = scope.launch {
            runCatching { ai.ask(text.trim(), "${session.address} (${session.protocolLabel})", if (screenToo) screen() else null, k) }
                .onSuccess { result = it }
                .onFailure { if (it !is kotlinx.coroutines.CancellationException) error = it.message ?: tr("Échec de la requête") }
            job = null
        }
    }

    run {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Title
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Nt.Accent.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = Nt.Accent)
                }
                Column(Modifier.weight(1f)) {
                    Text(tr("Assistant"), style = MaterialTheme.typography.titleLarge)
                    Text("${session.alias} · ${session.protocolLabel}", color = Nt.Muted, fontSize = 12.sp, fontFamily = Nt.Mono,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, contentDescription = tr("Fermer"), tint = Nt.TextSoft) }
            }

            if (ready.isEmpty()) {
                Notice(tr("Aucun fournisseur prêt. Ajoutez une clé API dans Réglages › Assistant IA."))
                return@Column
            }

            // Provider: only a choice when several are set up.
            if (ready.size > 1) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionLabel(tr("Fournisseur"))
                    ChipRow {
                        ready.forEach { k ->
                            Chip(k.shortLabel, prettyModel(ai.config(k).model), k == kind, logo = { ProviderLogo(k, 20.dp) }) { kind = k; ai.provider = k }
                        }
                    }
                }
            } else kind?.let { k ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProviderLogo(k, 16.dp, tint = Nt.TextSoft)
                    Text(tr("Via %1\$s · %2\$s", k.label, prettyModel(ai.config(k).model)), color = Nt.Muted, fontSize = 12.sp)
                }
            }

            // System prompt: only a choice when the user wrote some.
            if (prompts.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionLabel(tr("Prompt système"))
                    ChipRow {
                        Chip(tr("Par défaut"), null, prompts.none { it.id == promptId }) { ai.selectPrompt(AiAssistant.DEFAULT_ID) }
                        prompts.forEach { p -> Chip(p.name, null, p.id == promptId) { ai.selectPrompt(p.id) } }
                    }
                }
            }

            // Request
            OutlinedTextField(
                value = request, onValueChange = { request = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(tr("Que voulez-vous faire ?"), color = Nt.Placeholder) },
                minLines = 2, maxLines = 5,
                textStyle = TextStyle(fontSize = 15.sp, color = Nt.Text),
                shape = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                trailingIcon = {
                    val can = request.isNotBlank() && !busy
                    Box(
                        Modifier.padding(end = 6.dp).size(40.dp).clip(CircleShape)
                            .background(if (can) Nt.Accent else Nt.Border)
                            .clickable(enabled = can, role = Role.Button) { send() },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = tr("Demander"), tint = if (can) Nt.OnAccent else Nt.Muted, modifier = Modifier.size(18.dp)) }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = Nt.Bg, focusedContainerColor = Nt.Bg,
                    unfocusedBorderColor = Nt.Border, focusedBorderColor = Nt.Accent, cursorColor = Nt.Accent,
                ),
            )

            // Screen
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(role = Role.Switch) { withScreen = !withScreen }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(tr("Joindre l'écran"), fontSize = 14.sp, color = Nt.Text, fontWeight = FontWeight.Medium)
                    Text(tr("%1\$s dernières lignes, pour expliquer une erreur", lines), fontSize = 12.sp, color = Nt.Muted)
                }
                Switch(withScreen, { withScreen = it }, colors = SwitchDefaults.colors(checkedTrackColor = Nt.Accent, uncheckedTrackColor = Nt.Toggle))
            }

            // Ideas while nothing has been asked yet
            if (result == null && error == null && !busy && request.isBlank()) {
                ChipRow {
                    Idea(tr("Expliquer l'écran")) { withScreen = true; send(tr("Explique ce qui est affiché à l'écran et ce que je dois faire."), true) }
                    Idea(tr("Espace disque")) { send(tr("Montre l'espace disque utilisé et les plus gros dossiers")) }
                    Idea(tr("Ports en écoute")) { send(tr("Liste les ports en écoute et les programmes associés")) }
                    Idea(tr("Services en échec")) { send(tr("Liste les services systemd en échec")) }
                }
            }

            if (busy) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LinearProgressIndicator(color = Nt.Accent, trackColor = Nt.Border, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("%1\$s réfléchit…", kind?.shortLabel ?: ""), color = Nt.Muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text(tr("Annuler"), color = Nt.TextSoft, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { job?.cancel(); job = null }.padding(8.dp))
                }
            }
            error?.let { Notice(it) }
            result?.let { r -> Answer(r, onInsert) }
        }
    }
}

@Composable
private fun Answer(r: AiAssistant.Suggestion, onInsert: (String, Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        r.command?.let { cmd ->
            val clipboard = LocalClipboardManager.current
            var copied by remember(cmd) { mutableStateOf(false) }
            LaunchedEffect(copied) { if (copied) { delay(1500); copied = false } }
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Nt.Bg).border(1.dp, Nt.Border, RoundedCornerShape(14.dp)),
            ) {
                Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("Commande"), color = Nt.Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    IconButton(onClick = { clipboard.setText(AnnotatedString(cmd)); copied = true }, modifier = Modifier.size(36.dp)) {
                        Icon(if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy, contentDescription = tr("Copier"),
                            tint = if (copied) Nt.Green else Nt.TextSoft, modifier = Modifier.size(18.dp))
                    }
                }
                Row(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp)) {
                    Text("$ ", fontFamily = Nt.Mono, fontSize = 13.sp, color = Nt.Accent)
                    Text(cmd, fontFamily = Nt.Mono, fontSize = 13.sp, lineHeight = 19.sp, color = Nt.Text)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { onInsert(cmd, false) }, modifier = Modifier.weight(1f).height(46.dp),
                    border = BorderStroke(1.dp, Nt.Border), shape = RoundedCornerShape(12.dp),
                ) { Text(tr("Insérer"), color = Nt.Text, fontWeight = FontWeight.SemiBold) }
                Button(
                    onClick = { onInsert(cmd, true) }, modifier = Modifier.weight(1f).height(46.dp), shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Nt.Accent, contentColor = Nt.OnAccent),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(tr("Exécuter"), fontWeight = FontWeight.SemiBold)
                }
            }
        }
        if (r.explanation.isNotBlank()) Text(markdownLite(r.explanation), fontSize = 14.sp, lineHeight = 21.sp, color = Nt.TextSoft)
    }
}

@Composable
private fun Notice(text: String) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Nt.Pink.copy(alpha = 0.10f)).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = Nt.Pink, modifier = Modifier.size(20.dp))
        Text(text, color = Nt.Pink, fontSize = 13.sp, lineHeight = 18.sp)
    }
}

/** Chips that wrap onto the next line instead of hiding behind the edge. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

/** A choice (provider or prompt); [detail] is the model, in small monospace. */
@Composable
private fun Chip(label: String, detail: String?, selected: Boolean, logo: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.heightIn(min = 40.dp).clip(shape)
            .background(if (selected) Nt.Accent.copy(alpha = 0.14f) else Color.Transparent)
            .border(1.dp, if (selected) Nt.Accent else Nt.Border, shape)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(start = if (logo != null) 10.dp else 12.dp, end = 12.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        logo?.invoke()
        Column(verticalArrangement = Arrangement.Center) {
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (selected) Nt.Accent else Nt.Text, maxLines = 1)
            if (detail != null) Text(detail, fontSize = 11.5.sp, color = Nt.Muted, maxLines = 1)
        }
    }
}

@Composable
private fun Idea(label: String, onClick: () -> Unit) {
    Box(
        Modifier.height(36.dp).clip(RoundedCornerShape(18.dp)).background(Nt.Bg).border(1.dp, Nt.Border, RoundedCornerShape(18.dp))
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, fontSize = 13.sp, color = Nt.TextSoft) }
}

/** Just enough Markdown for chat answers: **bold**, `code`, headings and bullets. */
internal fun markdownLite(text: String): AnnotatedString = buildAnnotatedString {
    val inline = Regex("\\*\\*(.+?)\\*\\*|`([^`]+)`")
    text.lines().forEachIndexed { i, raw ->
        if (i > 0) append('\n')
        var line = raw
        val heading = line.trimStart().startsWith("#")
        if (heading) line = line.trimStart().trimStart('#').trimStart()
        Regex("^(\\s*)[-*] ").find(line)?.let { line = it.groupValues[1] + "• " + line.substring(it.range.last + 1) }
        val start = length
        var at = 0
        inline.findAll(line).forEach { m ->
            append(line.substring(at, m.range.first))
            if (m.groupValues[1].isNotEmpty()) withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = Nt.Text)) { append(m.groupValues[1]) }
            else withStyle(SpanStyle(fontFamily = Nt.Mono, fontSize = 12.5.sp, color = Nt.Text, background = Nt.Bg)) { append(m.groupValues[2]) }
            at = m.range.last + 1
        }
        append(line.substring(at))
        if (heading) addStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = Nt.Text), start, length)
    }
}
