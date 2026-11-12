package com.nautile.app.ui.terminal

import com.nautile.i18n.tr

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nautile.app.NautileApp
import com.nautile.app.ai.AiTurn
import com.nautile.app.ai.prettyModel
import com.nautile.app.session.ActiveSession
import com.nautile.app.session.aiKey
import com.nautile.app.ui.common.ProviderLogo
import com.nautile.app.ui.theme.Nt
import kotlinx.coroutines.launch

/** The whole conversation with the assistant for this session, full screen, with follow-ups and export. */
@Composable
fun AssistantConversation(session: ActiveSession, onClose: () -> Unit, onInsert: (String, Boolean) -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = Nt.Bg) {
            ConversationContent(session, onClose, onInsert)
        }
    }
}

@Composable
internal fun ConversationContent(session: ActiveSession, onClose: () -> Unit, onInsert: (String, Boolean) -> Unit, embedded: Boolean = false) {
    val app = LocalContext.current.applicationContext as NautileApp
    val context = LocalContext.current
    val ai = app.ai
    val turns by ai.conversation(session.aiKey).collectAsState()
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    LaunchedEffect(turns.size, busy) { if (turns.isNotEmpty()) list.animateScrollToItem(turns.size - 1 + if (busy) 1 else 0) }

    fun markdown() = ai.markdown(turns, session.alias)
    val save = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/markdown"),
    ) { uri -> uri?.let { u -> runCatching { context.contentResolver.openOutputStream(u)?.use { it.write(markdown().toByteArray()) } } } }

    fun send() {
        val text = draft.trim()
        if (text.isEmpty() || busy) return
        val kind = ai.readyProviders().firstOrNull() ?: run { error = tr("Aucun fournisseur prêt. Ajoutez une clé API dans Réglages › Assistant IA."); return }
        busy = true; error = null; draft = ""
        scope.launch {
            runCatching { ai.ask(text, "${session.address} (${session.protocolLabel})", null, kind, conversation = session.aiKey) }
                .onFailure { if (it !is kotlinx.coroutines.CancellationException) { error = it.message ?: tr("Échec de la requête"); draft = text } }
            busy = false
        }
    }

    Column(if (embedded) Modifier.fillMaxSize() else Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        // Top bar
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!embedded) IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("Retour"), tint = Nt.Text) }
            Column(Modifier.weight(1f).padding(start = if (embedded) 12.dp else 0.dp)) {
                Text(tr("Conversation"), style = if (embedded) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge)
                Text(
                    "${session.alias} · " + if (turns.size == 1) tr("1 échange") else tr("%1\$s échanges", turns.size),
                    color = Nt.Muted, fontSize = 12.sp,
                )
            }
            Box {
                IconButton(onClick = { menu = true }, enabled = turns.isNotEmpty()) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = tr("Exporter"), tint = if (turns.isEmpty()) Nt.Muted else Nt.Text)
                }
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(tr("Copier en Markdown")) }, leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
                        onClick = {
                            menu = false
                            context.getSystemService(android.content.ClipboardManager::class.java)
                                ?.setPrimaryClip(android.content.ClipData.newPlainText(tr("Conversation"), markdown()))
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(tr("Partager…")) }, leadingIcon = { Icon(Icons.Outlined.IosShare, null) },
                        onClick = {
                            menu = false
                            val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/markdown")
                                .putExtra(android.content.Intent.EXTRA_SUBJECT, tr("Assistant Nautile — %1\$s", session.alias))
                                .putExtra(android.content.Intent.EXTRA_TEXT, markdown())
                            context.startActivity(android.content.Intent.createChooser(send, tr("Partager la conversation")))
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(tr("Enregistrer en .md…")) }, leadingIcon = { Icon(Icons.Outlined.SaveAlt, null) },
                        onClick = {
                            menu = false
                            save.launch("nautile-" + session.alias.replace(Regex("[^\\w.-]"), "_") + "-" +
                                java.text.SimpleDateFormat("yyyy-MM-dd-HHmm", java.util.Locale.ROOT).format(java.util.Date()) + ".md")
                        },
                    )
                    HorizontalDivider(color = Nt.Border)
                    DropdownMenuItem(
                        text = { Text(tr("Effacer la conversation"), color = Nt.Pink) }, leadingIcon = { Icon(Icons.Outlined.DeleteSweep, null, tint = Nt.Pink) },
                        onClick = { menu = false; ai.clearConversation(session.aiKey) },
                    )
                }
            }
        }
        HorizontalDivider(color = Nt.Border)

        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(), state = list,
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (turns.isEmpty() && !busy) item {
                Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Outlined.Forum, contentDescription = null, tint = Nt.Muted, modifier = Modifier.size(40.dp))
                    Text(tr("Pas encore d'échange"), color = Nt.TextSoft, fontWeight = FontWeight.SemiBold)
                    Text(tr("Posez une question : l'assistant garde le fil des réponses précédentes."), color = Nt.Muted, fontSize = 13.sp)
                }
            }
            items(turns, key = { it.at }) { t -> TurnView(t, onInsert) }
            if (busy) item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    LinearProgressIndicator(color = Nt.Accent, trackColor = Nt.Border, modifier = Modifier.fillMaxWidth())
                    Text(tr("%1\$s réfléchit…", ai.readyProviders().firstOrNull()?.shortLabel ?: ""), color = Nt.Muted, fontSize = 13.sp)
                }
            }
        }

        error?.let { Text(it, color = Nt.Pink, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
        HorizontalDivider(color = Nt.Border)
        Row(Modifier.fillMaxWidth().background(Nt.Surface).padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = draft, onValueChange = { draft = it }, modifier = Modifier.weight(1f),
                placeholder = { Text(if (turns.isEmpty()) tr("Que voulez-vous faire ?") else tr("Question de suivi…"), color = Nt.Placeholder) },
                maxLines = 5, textStyle = TextStyle(fontSize = 15.sp, color = Nt.Text), shape = RoundedCornerShape(22.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { send() }),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = Nt.Bg, focusedContainerColor = Nt.Bg,
                    unfocusedBorderColor = Nt.Border, focusedBorderColor = Nt.Accent, cursorColor = Nt.Accent,
                ),
            )
            val can = draft.isNotBlank() && !busy
            IconButton(
                onClick = ::send, enabled = can,
                modifier = Modifier.size(48.dp).clip(CircleShape).background(if (can) Nt.Accent else Nt.Border),
            ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = tr("Demander"), tint = if (can) Nt.OnAccent else Nt.Muted) }
        }
    }
}

@Composable
private fun TurnView(t: AiTurn, onInsert: (String, Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // The question, on the right
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                t.request, fontSize = 15.sp, color = Nt.Text, lineHeight = 21.sp,
                modifier = Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp))
                    .background(Nt.Accent.copy(alpha = 0.16f)).padding(horizontal = 14.dp, vertical = 10.dp),
            )
            if (t.files.isNotEmpty() || t.screen) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (t.screen) Meta(Icons.Outlined.Terminal, tr("Écran joint"))
                t.files.forEach { Meta(Icons.Outlined.Description, it) }
            }
        }
        // The answer, on the left
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp)).background(Nt.Surface).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProviderLogo(t.provider, 16.dp, tint = Nt.TextSoft)
                Text("${t.provider.shortLabel} · ${prettyModel(t.model)}", color = Nt.Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text(java.text.SimpleDateFormat("HH:mm", java.util.Locale.ROOT).format(java.util.Date(t.at)), color = Nt.Muted, fontSize = 11.sp)
            }
            Answer(t.answer, onInsert)
        }
    }
}

@Composable
private fun Meta(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(icon, contentDescription = null, tint = Nt.Muted, modifier = Modifier.size(14.dp))
        Text(label, color = Nt.Muted, fontSize = 12.sp)
    }
}
