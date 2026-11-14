package com.nautile.app.ui.terminal

import android.content.ClipboardManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nautile.app.NautileApp
import com.nautile.app.R
import com.nautile.app.ai.AiFiles
import com.nautile.app.session.ActiveSession
import com.nautile.app.session.LineTracker
import com.nautile.app.session.SessionManager
import com.nautile.app.session.SessionNotice
import com.nautile.app.session.SessionState
import com.nautile.app.session.TerminalCwd
import com.nautile.app.session.TerminalFonts
import com.nautile.app.session.aiKey
import com.nautile.app.ui.sftp.humanSize
import com.nautile.app.ui.LocalAppLocked
import com.nautile.app.ui.common.Centered
import com.nautile.app.ui.common.ConfirmCloseDialog
import com.nautile.app.ui.common.NtTextField
import com.nautile.app.ui.common.OsBrands
import com.nautile.app.ui.common.OsGlyph
import com.nautile.app.ui.theme.Nt
import com.nautile.app.ui.tools.RunSnippetDialog
import com.nautile.data.model.Snippet
import com.nautile.i18n.tr
import com.termux.view.TerminalView
import kotlinx.coroutines.launch

@Composable
fun TerminalScreen(
    initialSessionId: Long,
    onBack: () -> Unit,
    onNewSession: () -> Unit,
    onSftp: (Long) -> Unit,
    onMonitor: (Long) -> Unit,
    /** Opens the terminal settings (program clipboard); null turns that setting on directly. */
    onOpenTerminalSettings: (() -> Unit)? = null,
) {
    val app = LocalContext.current.applicationContext as NautileApp
    val manager = app.sessions
    val sessions by manager.sessions.collectAsStateWithLifecycle()
    val fontSize by manager.prefs.fontSize.collectAsStateWithLifecycle()
    val font by manager.prefs.font.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val typeface = remember(font) { TerminalFonts.typeface(context, font) }
    var currentId by rememberSaveable { mutableLongStateOf(initialSessionId) }
    val scope = rememberCoroutineScope()

    val current = sessions.firstOrNull { it.id == currentId } ?: sessions.lastOrNull()
    LaunchedEffect(sessions.isEmpty()) { if (sessions.isEmpty()) onBack() }
    if (current == null) return
    val state by current.state.collectAsStateWithLifecycle()

    // Lets the "session open" notification show; the service runs either way.
    val notifPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    var askPassword by remember { mutableStateOf(false) }
    var showAssistant by remember { mutableStateOf(false) }
    /** Session whose assistant conversation is shown in place of its terminal (its « IA » tab). */
    var aiTabFor by remember { mutableStateOf<Long?>(null) }
    // Windows of their own: left out while locked so they never show above the lock screen.
    val locked = LocalAppLocked.current
    if (showAssistant && !locked) AssistantDialog(
        current,
        screen = {
            current.terminal.emulator?.let { e ->
                // Lines above the screen come from the scrollback, as far as it goes.
                val from = (e.cursorRow - manager.prefs.aiLines.value).coerceAtLeast(-e.screen.activeTranscriptRows)
                e.screen.getSelectedText(0, from, e.mColumns, e.cursorRow).trimEnd()
            }
        },
        onDismiss = { showAssistant = false },
        onOpenConversation = { showAssistant = false; aiTabFor = current.id },
        // Files of a saved host are browsed over SFTP, from the terminal's directory (quick connections have no host to reopen).
        remote = if (current.isSaved) RemoteFiles(
            host = current.alias,
            start = {
                manager.sftp.with(current.hostId) { sftp ->
                    val home = sftp.home()
                    TerminalCwd.of(current.terminal.emulator)?.let { TerminalCwd.expand(it, home) } ?: home
                }
            },
            home = { manager.sftp.with(current.hostId) { it.home() } },
            list = { path -> manager.sftp.with(current.hostId) { it.list(path) } },
            read = { path -> manager.sftp.with(current.hostId) { sftp -> sftp.openRead(path).use { AiFiles.read(path, it) } } },
        ) else null,
        onInsert = { cmd, run ->
            showAssistant = false
            if (run) manager.run(current, cmd) else CommandInsert.insert(current.terminal, cmd)
        },
    )
    if (askPassword && !locked) SudoPasswordDialog(
        onDismiss = { askPassword = false },
        onSend = { pw, keep ->
            askPassword = false
            manager.typePassword(current, pw)
            if (keep) scope.launch { manager.rememberSudoPassword(current, pw) }
        },
    )

    val snippets by app.database.toolsDao().observeSnippets().collectAsStateWithLifecycle(initialValue = emptyList())
    val hostList by app.hostRepository.hosts.collectAsStateWithLifecycle(initialValue = emptyList())
    val typed by current.typed.collectAsStateWithLifecycle()
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(typed, current.hostId) {
        val t = typed
        suggestions = if (t == null || t.length < 2) emptyList()
        else app.database.toolsDao().suggestions(current.hostId, t).map { it.command }
    }
    var pick by remember { mutableStateOf<Snippet?>(null) }
    var showAllSnippets by remember { mutableStateOf(false) }

    val modifiers = remember { ModifierKeys() }
    val leftClient = remember { NtTerminalViewClient(manager.prefs, modifiers) }
    val rightClient = remember { NtTerminalViewClient(manager.prefs, modifiers) }
    // Split screen: `current` is always the focused session, `other` the second pane's.
    var otherId by rememberSaveable { mutableLongStateOf(-1L) }
    var otherOnRight by rememberSaveable { mutableStateOf(true) }
    val wide = LocalConfiguration.current.screenWidthDp >= 720
    val other = if (wide) sessions.firstOrNull { it.id == otherId && it.id != current.id } else null
    val leftSession = if (other == null || otherOnRight) current else other
    val rightSession = when { other == null -> null; otherOnRight -> other; else -> current }
    val client = if (other != null && !otherOnRight) rightClient else leftClient
    fun focus(s: ActiveSession) {
        if (other != null && s.id == other.id) { otherId = current.id; currentId = s.id; otherOnRight = !otherOnRight }
    }
    var panelOpen by rememberSaveable { mutableStateOf(false) }
    leftClient.onTap = { panelOpen = false; focus(leftSession) }
    rightClient.onTap = { panelOpen = false; rightSession?.let(::focus) }
    BackHandler(enabled = panelOpen) { panelOpen = false }
    val history by remember(current.hostId) { app.database.toolsDao().observeHistory(current.hostId) }.collectAsStateWithLifecycle(initialValue = emptyList())
    var confirmPassword by remember { mutableStateOf<String?>(null) }
    fun typePassword() {
        scope.launch {
            val pw = manager.sudoPassword(current)
            when {
                pw == null -> askPassword = true
                // Never type a password at a shell prompt: it would land in the command line / history.
                looksLikePasswordPrompt(current) -> manager.typePassword(current, pw)
                else -> confirmPassword = pw
            }
        }
    }
    confirmPassword?.let { pw ->
        AlertDialog(
            onDismissRequest = { confirmPassword = null },
            containerColor = Nt.Surface,
            title = { Text(tr("Aucune invite de mot de passe")) },
            text = {
                Text(
                    tr("La ligne actuelle ne demande pas de mot de passe. S'il est tapé maintenant, il apparaîtra en clair dans le terminal ") +
                        tr("et dans l'historique du shell. Lancez d'abord la commande (sudo…), puis touchez la clé à l'invite « Password »."),
                    color = Nt.TextSoft, fontSize = 13.sp,
                )
            },
            dismissButton = { TextButton(onClick = { confirmPassword = null }) { Text(tr("Annuler"), color = Nt.TextSoft) } },
            confirmButton = { TextButton(onClick = { manager.typePassword(current, pw); confirmPassword = null }) { Text(tr("Taper quand même"), color = Nt.Pink) } },
        )
    }
    val keyTarget = KeyTarget(client, modifiers) { tool ->
        when (tool) {
            ToolKind.AI -> showAssistant = true
            ToolKind.PASSWORD -> typePassword()
            ToolKind.PASTE -> context.getSystemService(ClipboardManager::class.java)?.primaryClip
                ?.getItemAt(0)?.coerceToText(context)?.toString()?.let(client::paste)
        }
    }

    var closing by remember { mutableStateOf<ActiveSession?>(null) }
    /** Ends [s], after asking when it is still connected and the setting is on. */
    fun requestClose(s: ActiveSession) {
        if (manager.prefs.confirmClose.value && s.state.value == SessionState.Connected) closing = s else manager.close(s)
    }
    closing?.let { s ->
        ConfirmCloseDialog(s.alias, onDismiss = { closing = null }) { dontAsk ->
            if (dontAsk) manager.prefs.setConfirmClose(false)
            closing = null
            manager.close(s)
        }
    }

    Column(Modifier.fillMaxSize().background(Nt.Bg).imePadding()) {
        Header(
            session = current,
            state = state,
            onBack = onBack,
            onClose = { requestClose(current) },
            onReconnect = {
                scope.launch {
                    manager.reopen(current).onSuccess { fresh ->
                        manager.close(current)
                        currentId = fresh.id
                    }
                }
            },
            onKeyboard = client::showKeyboard,
            splitAvailable = wide && sessions.size >= 2,
            splitActive = other != null,
            onToggleSplit = { otherId = if (other != null) -1L else sessions.first { it.id != current.id }.id; otherOnRight = true },
            onSftp = { onSftp(current.hostId) },
            onMonitor = { onMonitor(current.hostId) },
            onAssistant = { showAssistant = true },
            onPassword = ::typePassword,
        )
        Tabs(sessions, current.id, osOf = { id -> hostList.firstOrNull { it.id == id }?.osId }, onPick = { id ->
            aiTabFor = null
            val target = sessions.firstOrNull { it.id == id }
            if (target != null && other != null && id == other.id) focus(target) else currentId = id
        }, onClose = ::requestClose, onNew = onNewSession,
            aiShown = aiTabFor, onPickAi = { id -> currentId = id; aiTabFor = id })

        val showingAi = aiTabFor == current.id
        if (showingAi) Box(Modifier.weight(1f).fillMaxWidth()) {
            ConversationContent(
                current, embedded = true,
                onClose = { aiTabFor = null },
                onInsert = { cmd, run ->
                    aiTabFor = null
                    if (run) manager.run(current, cmd) else CommandInsert.insert(current.terminal, cmd)
                },
            )
        } else Box(Modifier.weight(1f).fillMaxWidth()) {
            Row(Modifier.fillMaxSize()) {
                TerminalPane(leftSession, leftClient, manager, fontSize, typeface, focused = rightSession == null || leftSession === current,
                    split = rightSession != null, modifier = Modifier.weight(1f))
                if (rightSession != null) {
                    Box(Modifier.fillMaxHeight().width(1.dp).background(Nt.Border))
                    TerminalPane(rightSession, rightClient, manager, fontSize, typeface, focused = rightSession === current,
                        split = true, modifier = Modifier.weight(1f))
                }
            }
            val notice by current.notice.collectAsStateWithLifecycle()
            notice?.let { n ->
                NoticeBanner(
                    n, Modifier.align(Alignment.TopCenter),
                    onDismiss = { current.dismissNotice(n) },
                    onAllow = {
                        current.dismissNotice(n)
                        if (onOpenTerminalSettings != null) onOpenTerminalSettings() else manager.prefs.setRemoteClipboard(true)
                    },
                )
                // A refused input is transient; the clipboard notice stays until answered.
                if (n is SessionNotice.InputRefused) LaunchedEffect(n) {
                    kotlinx.coroutines.delay(8_000)
                    current.dismissNotice(n)
                }
            }
            if (state is SessionState.Closed) {
                ClosedBanner(state as SessionState.Closed, Modifier.align(Alignment.BottomCenter), onReconnect = {
                    scope.launch {
                        manager.reopen(current).onSuccess { fresh ->
                            manager.close(current)
                            currentId = fresh.id
                        }
                    }
                }, onClose = { manager.close(current) })
            }
        }
        if (!showingAi && suggestions.isNotEmpty()) SuggestionBar(
            typed.orEmpty(), suggestions,
            onPick = { full ->
                val bytes = full.removePrefix(typed.orEmpty()).toByteArray()
                current.terminal.write(bytes, 0, bytes.size)
            },
            onRun = { full ->
                val bytes = (full.removePrefix(typed.orEmpty()) + "\r").toByteArray()
                current.terminal.write(bytes, 0, bytes.size)
            },
        )
        if (!showingAi) KeyboardArea(
            target = keyTarget,
            prefs = manager.prefs,
            expanded = panelOpen,
            onExpandedChange = { panelOpen = it },
            snippets = snippets,
            history = history,
            onSnippet = { pick = it },
            onHistory = { cmd -> val b = cmd.toByteArray(); current.terminal.write(b, 0, b.size) },
        )
    }

    pick?.let { sn ->
        if (sn.variables.isEmpty()) {
            LaunchedEffect(sn) { manager.run(current, sn.command); app.database.toolsDao().countRun(sn.id); pick = null }
        } else RunSnippetDialog(sn, emptyList(), emptyList(), singleTarget = true, onDismiss = { pick = null }) { cmd, _, _ ->
            manager.run(current, cmd)
            scope.launch { app.database.toolsDao().countRun(sn.id) }
            pick = null
        }
    }
    if (showAllSnippets) AlertDialog(
        onDismissRequest = { showAllSnippets = false },
        containerColor = Nt.Surface,
        title = { Text(tr("Snippets")) },
        text = {
            LazyColumn {
                if (snippets.isEmpty()) item { Text(tr("Aucun snippet : créez-en dans l'onglet Snippets."), color = Nt.Muted) }
                items(snippets, key = { it.id }) { sn ->
                    Column(Modifier.fillMaxWidth().clickable { showAllSnippets = false; pick = sn }.padding(vertical = 10.dp)) {
                        Text(sn.name, fontWeight = FontWeight.SemiBold)
                        Text(sn.command.lineSequence().first(), fontFamily = Nt.Mono, fontSize = 12.sp, color = Nt.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { showAllSnippets = false }) { Text(tr("Fermer"), color = Nt.TextSoft) } },
    )

    DisposableEffect(Unit) {
        onDispose {
            listOf(leftClient, rightClient).forEach { c -> c.view?.let(manager.views::remove); c.view = null }
        }
    }
}

@Composable
private fun Header(
    session: ActiveSession,
    state: SessionState,
    onBack: () -> Unit,
    onClose: () -> Unit,
    onReconnect: () -> Unit,
    onKeyboard: () -> Unit,
    onPassword: () -> Unit,
    onSftp: () -> Unit,
    splitAvailable: Boolean,
    splitActive: Boolean,
    onToggleSplit: () -> Unit,
    onMonitor: () -> Unit,
    onAssistant: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().background(Nt.Bg).padding(start = 4.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("Retour aux hôtes")) }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(stateColor(state)))
                Text(session.alias, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                "${session.address} · ${session.protocolLabel}" + when (state) {
                    is SessionState.Connecting -> tr(" · connexion…")
                    is SessionState.Closed -> tr(" · déconnecté")
                    SessionState.Connected -> ""
                },
                fontFamily = Nt.Mono, fontSize = 11.sp, color = Nt.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (session.isSaved) IconButton(onClick = onSftp) { Icon(Icons.Outlined.Folder, contentDescription = tr("Ouvrir SFTP"), tint = Nt.TextSoft) }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = tr("Plus d'options"), tint = Nt.TextSoft) }
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                if (session.isSaved) DropdownMenuItem(text = { Text(tr("Moniteur du serveur")) }, onClick = { menu = false; onMonitor() })
                DropdownMenuItem(text = { Text(tr("Reconnecter")) }, onClick = { menu = false; onReconnect() })
                if (splitAvailable || splitActive) DropdownMenuItem(
                    text = { Text(if (splitActive) tr("Fermer l'écran partagé") else tr("Écran partagé")) },
                    onClick = { menu = false; onToggleSplit() },
                )
                DropdownMenuItem(text = { Text(tr("Fermer la session")) }, onClick = { menu = false; onClose() })
            }
        }
    }
}

private fun stateColor(state: SessionState) = when (state) {
    SessionState.Connected -> Nt.Green
    is SessionState.Connecting -> Nt.Accent
    is SessionState.Closed -> if (state.failed) Nt.Pink else Nt.Grey
}

@Composable
private fun Tabs(
    sessions: List<ActiveSession>, currentId: Long, osOf: (Long) -> String?, onPick: (Long) -> Unit, onClose: (ActiveSession) -> Unit, onNew: () -> Unit,
    aiShown: Long?, onPickAi: (Long) -> Unit,
) {
    val ai = (LocalContext.current.applicationContext as NautileApp).ai
    Column(Modifier.background(Nt.Bg)) {
        LazyRow(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            sessions.forEach { s ->
                item(key = s.id) {
                    val on = s.id == currentId
                    val st by s.state.collectAsStateWithLifecycle()
                    Surface(
                        onClick = { onPick(s.id) },
                        shape = RoundedCornerShape(9.dp),
                        color = if (on) Nt.Accent.copy(alpha = 0.12f) else Nt.Surface,
                        border = BorderStroke(1.dp, if (on) Nt.Accent else Nt.Border),
                        modifier = Modifier.height(32.dp),
                    ) {
                        Row(Modifier.padding(start = 10.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(Modifier.size(6.dp).clip(CircleShape).background(stateColor(st)))
                            OsBrands.of(osOf(s.hostId))?.takeIf { it.isGlyph }?.let { b ->
                                OsGlyph(b, Modifier.size(15.dp))
                            }
                            Text(s.alias, fontFamily = Nt.Mono, fontSize = 12.sp, color = if (on) Nt.Accent else Nt.TextSoft)
                            IconButton(onClick = { onClose(s) }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Filled.Close, contentDescription = tr("Fermer %1\$s", s.alias), tint = Nt.Muted, modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                }
            }
            item {
                Box(
                    Modifier.size(32.dp).clip(RoundedCornerShape(9.dp)).border(1.dp, Nt.Border, RoundedCornerShape(9.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    IconButton(onClick = onNew) { Icon(Icons.Filled.Add, contentDescription = tr("Nouvelle session"), tint = Nt.Muted, modifier = Modifier.size(16.dp)) }
                }
            }
        }
        // Sub-tabs of the selected session: its terminal and its assistant conversation.
        sessions.firstOrNull { it.id == currentId }?.let { cur ->
            val turns by remember(cur.aiKey) { ai.conversationFlow(cur.aiKey) }.collectAsStateWithLifecycle(ai.loadedConversation(cur.aiKey).orEmpty())
            if (turns.isNotEmpty() || aiShown == cur.id) Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // A small corner, so the row reads as belonging to the tab above.
                val line = Nt.Border
                Canvas(Modifier.size(width = 12.dp, height = 28.dp)) {
                    val w = 1.5.dp.toPx(); val r = 6.dp.toPx(); val mid = size.height / 2
                    val path = Path().apply {
                        moveTo(w / 2, 0f); lineTo(w / 2, mid - r)
                        quadraticTo(w / 2, mid, w / 2 + r, mid); lineTo(size.width, mid)
                    }
                    drawPath(path, line, style = Stroke(width = w))
                }
                SubTab(Icons.Outlined.Terminal, tr("Terminal"), null, selected = aiShown != cur.id) { onPick(cur.id) }
                SubTab(Icons.Outlined.AutoAwesome, tr("Assistant IA"), turns.size.takeIf { it > 0 }, selected = aiShown == cur.id) { onPickAi(cur.id) }
            }
        }
        HorizontalDivider(color = Nt.Border)
    }
}

@Composable
private fun SubTab(icon: ImageVector, label: String, count: Int?, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick, shape = RoundedCornerShape(8.dp),
        color = if (selected) Nt.Accent.copy(alpha = 0.12f) else Color.Transparent,
        border = BorderStroke(1.dp, if (selected) Nt.Accent.copy(alpha = 0.6f) else Nt.Border),
        modifier = Modifier.height(28.dp).semantics { this.selected = selected },
    ) {
        Row(Modifier.padding(horizontal = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Icon(icon, contentDescription = null, tint = if (selected) Nt.Accent else Nt.TextSoft, modifier = Modifier.size(13.dp))
            Text(label, fontSize = 12.sp, color = if (selected) Nt.Accent else Nt.TextSoft, fontWeight = FontWeight.SemiBold)
            count?.let {
                Box(
                    Modifier.clip(RoundedCornerShape(7.dp)).background(if (selected) Nt.Accent.copy(alpha = 0.25f) else Nt.Surface).padding(horizontal = 5.dp, vertical = 1.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("$it", style = Centered.copy(fontSize = 10.sp), color = if (selected) Nt.Text else Nt.Muted) }
            }
        }
    }
}

@Composable
private fun NoticeBanner(notice: SessionNotice, modifier: Modifier, onDismiss: () -> Unit, onAllow: () -> Unit) {
    val text = when (notice) {
        is SessionNotice.InputRefused -> if (notice.tooLarge) {
            tr("Collage refusé : %1\$s, c'est trop pour l'envoyer d'un coup au terminal.", humanSize(notice.bytes.toLong()))
        } else {
            tr("Saisie non envoyée : le serveur n'a pas encore lu ce qui lui a été envoyé avant.")
        }
        SessionNotice.RemoteClipboardBlocked ->
            tr("Un programme (tmux, vim…) a voulu copier dans le presse-papiers : c'est désactivé dans les réglages du terminal.")
    }
    Row(
        modifier.fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(14.dp)).background(Nt.Surface)
            .border(1.dp, Nt.Border, RoundedCornerShape(14.dp)).padding(start = 14.dp, end = 4.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = Nt.TextSoft, fontSize = 13.sp, modifier = Modifier.weight(1f).padding(vertical = 12.dp))
        when (notice) {
            is SessionNotice.InputRefused -> TextButton(onClick = onDismiss) { Text(tr("OK"), color = Nt.Accent) }
            SessionNotice.RemoteClipboardBlocked -> {
                TextButton(onClick = onDismiss) { Text(tr("Ignorer"), color = Nt.TextSoft) }
                TextButton(onClick = onAllow) { Text(tr("Autoriser"), color = Nt.Accent, fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

@Composable
private fun ClosedBanner(state: SessionState.Closed, modifier: Modifier, onReconnect: () -> Unit, onClose: () -> Unit) {
    Row(
        modifier.fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(14.dp)).background(Nt.Surface)
            .border(1.dp, if (state.failed) Nt.Pink else Nt.Border, RoundedCornerShape(14.dp)).padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(state.reason, color = if (state.failed) Nt.Pink else Nt.TextSoft, fontSize = 13.sp, modifier = Modifier.weight(1f).padding(vertical = 12.dp))
        TextButton(onClick = onClose) { Text(tr("Fermer"), color = Nt.TextSoft) }
        TextButton(onClick = onReconnect) { Text(tr("Reconnecter"), color = Nt.Accent, fontWeight = FontWeight.SemiBold) }
    }
}

/**
 * Commands from this host's history that start with what is being typed, best match first:
 * the part already typed is dimmed, the rest is what a tap adds; ⏎ adds it and runs it.
 */
@Composable
private fun SuggestionBar(typed: String, items: List<String>, onPick: (String) -> Unit, onRun: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Nt.Bar).horizontalScroll(androidx.compose.foundation.rememberScrollState())
            .padding(start = 10.dp, end = 8.dp, top = 6.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.History, contentDescription = tr("Suggestions de l'historique"), tint = Nt.Muted, modifier = Modifier.size(18.dp))
        items.forEachIndexed { i, cmd ->
            val best = i == 0
            Surface(
                onClick = { onPick(cmd) }, shape = RoundedCornerShape(10.dp),
                color = if (best) Nt.Accent.copy(alpha = 0.10f) else Nt.Surface,
                border = BorderStroke(1.dp, if (best) Nt.Accent.copy(alpha = 0.55f) else Nt.Border),
                modifier = Modifier.height(38.dp).semantics { contentDescription = tr("Compléter : %1\$s", cmd) },
            ) {
                Row(Modifier.padding(start = 12.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        androidx.compose.ui.text.buildAnnotatedString {
                            withStyle(SpanStyle(color = Nt.Muted)) { append(cmd.take(typed.length)) }
                            withStyle(SpanStyle(color = if (best) Nt.Accent else Nt.Text, fontWeight = FontWeight.SemiBold)) {
                                append(cmd.drop(typed.length))
                            }
                        },
                        fontFamily = Nt.Mono, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 240.dp),
                    )
                    Box(
                        Modifier.padding(start = 6.dp).size(32.dp).clip(RoundedCornerShape(8.dp))
                            .clickable(onClickLabel = tr("Exécuter")) { onRun(cmd) }
                            .semantics { contentDescription = tr("Exécuter %1\$s", cmd) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardReturn, contentDescription = null, tint = if (best) Nt.Accent else Nt.Muted, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun TerminalPane(
    session: ActiveSession,
    client: NtTerminalViewClient,
    manager: SessionManager,
    fontSize: Int,
    typeface: android.graphics.Typeface,
    focused: Boolean,
    split: Boolean,
    modifier: Modifier,
) {
    AndroidView(
        factory = { ctx ->
            TerminalView(ctx, null).apply {
                setTerminalViewClient(client)
                // setTextSize creates the renderer; setTypeface before it would dereference a null renderer.
                setTextSize(fontSize)
                setTypeface(typeface)
                tag = fontSize
                setTag(R.id.terminal_typeface, typeface)
                isFocusable = true
                isFocusableInTouchMode = true
                keepScreenOn = true
                client.view = this
                manager.views += this
            }
        },
        // A pane left (assistant tab, end of split screen) must not stay in the manager's views,
        // which outlive the screen: it would keep the Activity and get refreshed while detached.
        onRelease = { view ->
            manager.views -= view
            if (client.view === view) client.view = null
        },
        update = { view ->
            if (view.tag != fontSize) { view.setTextSize(fontSize); view.tag = fontSize }
            if (view.getTag(R.id.terminal_typeface) !== typeface) {
                view.setTypeface(typeface)
                view.setTag(R.id.terminal_typeface, typeface)
            }
            if (view.currentSession !== session.terminal) {
                view.attachSession(session.terminal)
                view.post { view.updateSize(); view.onScreenUpdated() }
            }
        },
        modifier = modifier.fillMaxHeight()
            .then(if (split && focused) Modifier.border(1.dp, Nt.Accent.copy(alpha = 0.5f)) else Modifier)
            .padding(horizontal = 4.dp),
    )
}

@Composable
private fun SudoPasswordDialog(onDismiss: () -> Unit, onSend: (String, Boolean) -> Unit) {
    var pw by remember { mutableStateOf("") }
    var keep by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Nt.Surface,
        title = { Text(tr("Mot de passe")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("Il sera tapé dans le terminal suivi d'Entrée (pour sudo, su…)."), color = Nt.TextSoft, fontSize = 13.sp)
                NtTextField(
                    pw, { pw = it }, tr("Mot de passe"),
                    visualTransformation = PasswordVisualTransformation(),
                )
                Row(Modifier.clickable { keep = !keep }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(keep, { keep = it }, colors = CheckboxDefaults.colors(checkedColor = Nt.Accent))
                    Text(tr("Enregistrer pour cet hôte (base chiffrée)"), fontSize = 13.sp, color = Nt.TextSoft)
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Annuler"), color = Nt.TextSoft) } },
        confirmButton = { TextButton(onClick = { onSend(pw, keep) }, enabled = pw.isNotEmpty()) { Text(tr("Envoyer"), color = Nt.Accent, fontWeight = FontWeight.SemiBold) } },
    )
}

/** True when the cursor's line looks like a password prompt (sudo, su, ssh, mysql…). */
private fun looksLikePasswordPrompt(session: ActiveSession): Boolean {
    val line = session.terminal.emulator?.let { e -> e.screen.getSelectedText(0, e.cursorRow, e.mColumns, e.cursorRow) }.orEmpty()
    return LineTracker.looksLikePasswordPrompt(line)
}
