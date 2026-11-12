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
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Forum
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
import com.nautile.app.ai.AiFile
import com.nautile.app.ai.AiFiles
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.TextButton
import com.nautile.app.ai.prettyModel
import com.nautile.app.session.ActiveSession
import com.nautile.app.session.aiKey
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
fun AssistantDialog(
    session: ActiveSession,
    screen: () -> String?,
    onDismiss: () -> Unit,
    onInsert: (String, Boolean) -> Unit,
    remote: RemoteFiles? = null,
    onOpenConversation: (() -> Unit)? = null,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Nt.Surface) {
        AssistantPanel(session, screen, onDismiss, onInsert, remote = remote, onOpenConversation = onOpenConversation)
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
    remote: RemoteFiles? = null,
    initialFiles: List<AiFile> = emptyList(),
    onOpenConversation: (() -> Unit)? = null,
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
    var files by remember { mutableStateOf(initialFiles) }
    val turns by ai.conversation(session.aiKey).collectAsState()
    var showConversation by remember { mutableStateOf(false) }
    var serverDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    var job by remember { mutableStateOf<Job?>(null) }
    // Reopened: the conversation goes on, its last answer in view.
    var result by remember { mutableStateOf(initialResult ?: ai.conversation(session.aiKey).value.lastOrNull()?.answer) }
    var error by remember { mutableStateOf<String?>(null) }
    val busy = job != null

    fun send(text: String = request, screenToo: Boolean = withScreen) {
        val k = kind ?: return
        if (text.isBlank() || busy) return
        error = null; result = null
        job = scope.launch {
            runCatching { ai.ask(text.trim(), "${session.address} (${session.protocolLabel})", if (screenToo) screen() else null, k, files = files, conversation = session.aiKey) }
                .onSuccess { result = it }
                .onFailure { if (it !is kotlinx.coroutines.CancellationException) error = it.message ?: tr("Échec de la requête") }
            job = null
        }
    }

    // Voice: Android's own speech recognition screen (no microphone permission needed here).
    val speech = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { r ->
        val heard = r.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim()
        if (!heard.isNullOrEmpty()) request = if (request.isBlank()) heard.replaceFirstChar { it.uppercase() } else request.trimEnd() + " " + heard
    }
    // Files: any document of the phone, read as text.
    val pickFile = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val name = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: uri.lastPathSegment.orEmpty()
                    context.contentResolver.openInputStream(uri)?.use { AiFiles.read(name, it) } ?: error(tr("Fichier illisible"))
                }
            }.onSuccess { f -> files = files.filterNot { it.name == f.name } + f }
                .onFailure { error = it.message ?: tr("Fichier illisible") }
        }
    }

    fun dictate() {
        val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, if (com.nautile.i18n.I18n.english) "en-US" else "fr-FR")
            .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, tr("Que voulez-vous faire ?"))
        try { speech.launch(intent) } catch (e: android.content.ActivityNotFoundException) {
            error = tr("Aucune reconnaissance vocale sur cet appareil (installez Google ou une app de dictée).")
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
                // The whole conversation, full screen, with its export.
                Box {
                    IconButton(onClick = { onOpenConversation?.invoke() ?: run { showConversation = true } }) {
                        Icon(Icons.Outlined.Forum, contentDescription = tr("Ouvrir la conversation"), tint = Nt.TextSoft)
                    }
                    if (turns.isNotEmpty()) Box(
                        Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 6.dp).size(16.dp).clip(CircleShape).background(Nt.Accent),
                        contentAlignment = Alignment.Center,
                    ) { Text("${turns.size}", color = Nt.OnAccent, fontSize = 9.sp, fontWeight = FontWeight.Bold) }
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, contentDescription = tr("Fermer"), tint = Nt.TextSoft) }
            }

            if (turns.isNotEmpty()) Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Nt.Bg).padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (turns.size == 1) tr("Suite d'une conversation (1 échange)") else tr("Suite d'une conversation (%1\$s échanges)", turns.size),
                    color = Nt.TextSoft, fontSize = 13.sp, modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { ai.clearConversation(session.aiKey); result = null }) { Text(tr("Nouvelle"), color = Nt.Accent, fontSize = 13.sp) }
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
                    Row(Modifier.padding(end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = ::dictate, enabled = !busy) {
                            Icon(Icons.Outlined.Mic, contentDescription = tr("Dicter"), tint = if (request.isBlank()) Nt.Accent else Nt.TextSoft)
                        }
                        Box(
                            Modifier.size(40.dp).clip(CircleShape)
                                .background(if (can) Nt.Accent else Nt.Border)
                                .clickable(enabled = can, role = Role.Button) { send() },
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = tr("Demander"), tint = if (can) Nt.OnAccent else Nt.Muted, modifier = Modifier.size(18.dp)) }
                    }
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

            // Files
            ChipRow {
                files.forEach { f ->
                    FileChip(f) { files = files - f }
                }
                if (files.size < MAX_FILES) Row(
                    Modifier.height(36.dp).clip(RoundedCornerShape(18.dp)).border(1.dp, Nt.Border, RoundedCornerShape(18.dp))
                        .clickable(role = Role.Button) { if (remote == null) pickFile.launch(arrayOf("*/*")) else serverDialog = true }
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(Icons.Outlined.AttachFile, contentDescription = null, tint = Nt.Accent, modifier = Modifier.size(18.dp))
                    Text(tr("Joindre un fichier"), fontSize = 13.sp, color = Nt.Accent, fontWeight = FontWeight.Medium)
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

    if (showConversation) AssistantConversation(session, onClose = { showConversation = false }, onInsert = onInsert)
    if (serverDialog && remote != null) RemoteFilePicker(
        remote,
        onPhone = { serverDialog = false; pickFile.launch(arrayOf("*/*")) },
        onDismiss = { serverDialog = false },
        onRead = { f -> serverDialog = false; files = files.filterNot { it.name == f.name } + f },
    )
}

private const val MAX_FILES = 3

@Composable
private fun FileChip(f: AiFile, onRemove: () -> Unit) {
    Row(
        Modifier.height(36.dp).clip(RoundedCornerShape(18.dp)).background(Nt.Accent.copy(alpha = 0.12f)).padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Icons.Outlined.Description, contentDescription = null, tint = Nt.Accent, modifier = Modifier.size(16.dp))
        Text(f.name.substringAfterLast('/'), fontSize = 13.sp, color = Nt.Text, fontWeight = FontWeight.Medium, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp))
        Text(
            if (f.cut) tr("%1\$s · coupé", AiFiles.sizeLabel(f.size)) else AiFiles.sizeLabel(f.size),
            fontSize = 11.sp, color = if (f.cut) Nt.Accent else Nt.Muted,
        )
        Box(Modifier.size(28.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onRemove), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Close, contentDescription = tr("Retirer %1\$s", f.name), tint = Nt.TextSoft, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
internal fun Answer(r: AiAssistant.Suggestion, onInsert: (String, Boolean) -> Unit) {
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
        if (r.explanation.isNotBlank()) MarkdownView(r.explanation)
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
