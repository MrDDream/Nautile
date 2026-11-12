package com.nautile.app.ui.connections

import com.nautile.i18n.tr

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.Close
import com.nautile.app.ui.common.HostAvatar
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nautile.app.NautileApp
import com.nautile.app.session.SessionState
import com.nautile.app.ui.common.NtCard
import com.nautile.app.ui.common.PageHeader
import com.nautile.app.ui.common.SectionLabel
import com.nautile.app.ui.hosts.QuickTarget
import com.nautile.app.ui.theme.Nt
import com.nautile.data.model.ConnectionLog
import com.nautile.data.model.Protocol
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

/** « Connexions »: what is open now (terminals and desktops) and every past connection, to go back to one. */
@Composable
fun ConnectionsScreen(
    onOpenTerminal: (Long) -> Unit,
    onOpenDesktop: (Long) -> Unit,
    onConnect: (Long) -> Unit,
    onQuickConnect: (QuickTarget) -> Unit,
    onSave: (QuickTarget) -> Unit,
) {
    val app = LocalContext.current.applicationContext as NautileApp
    val dao = app.database.toolsDao()
    val terminals by app.sessions.sessions.collectAsState()
    val desktops by app.sessions.rdp.list.collectAsState()
    val history by remember { dao.observeConnections() }.collectAsState(initial = emptyList())
    val hosts by remember { app.hostRepository.hosts }.collectAsState(initial = emptyList())
    val byId = hosts.associateBy { it.id }
    var query by rememberSaveable { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    var confirmClear by remember { mutableStateOf(false) }
    val open = terminals.size + desktops.size

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { PageHeader(tr("Connexions")) }

        item { SectionLabel(if (open > 0) tr("Sessions actives · %1\$s", open) else tr("Sessions actives")) }
        item {
            if (open == 0) Text(tr("Aucune session ouverte. Touchez un hôte ou une ligne de l'historique pour vous connecter."),
                color = Nt.Muted, fontSize = 13.sp)
            else NtCard {
                terminals.forEachIndexed { i, s ->
                    if (i > 0) HorizontalDivider(color = Nt.Border)
                    val state by s.state.collectAsState()
                    val host = byId[s.hostId]
                    SessionRow(
                        avatar = { Avatar(host?.osId ?: (if (s.protocolLabel == tr("local")) "android" else null), s.alias, host?.color, Icons.Outlined.Terminal) },
                        title = s.alias, detail = "${s.address} · ${s.protocolLabel}", state = state,
                        onOpen = { onOpenTerminal(s.id) }, onClose = { app.sessions.close(s) },
                    )
                }
                desktops.forEachIndexed { i, d ->
                    if (i > 0 || terminals.isNotEmpty()) HorizontalDivider(color = Nt.Border)
                    val state by d.state.collectAsState()
                    SessionRow(
                        { Avatar(byId[d.hostId]?.osId ?: "windows", d.alias, byId[d.hostId]?.color, Icons.Outlined.DesktopWindows) },
                        d.alias, "${d.address} · rdp", state, onOpen = { onOpenDesktop(d.id) }, onClose = { app.sessions.rdp.close(d) },
                    )
                }
            }
        }

        item {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                SectionLabel(tr("Historique"), Modifier.weight(1f))
                if (history.isNotEmpty()) TextButton(onClick = { confirmClear = true }) { Text(tr("Effacer"), color = Nt.TextSoft, fontSize = 13.sp) }
            }
        }
        if (history.isEmpty()) item {
            Text(tr("Vos connexions apparaîtront ici, y compris les connexions rapides tapées dans la recherche des hôtes."),
                color = Nt.Muted, fontSize = 13.sp, textAlign = TextAlign.Start)
        }
        if (history.size > 3) item {
            androidx.compose.material3.OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, tint = Nt.Muted) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, contentDescription = tr("Effacer la recherche"), tint = Nt.Muted) } },
                placeholder = { Text(tr("Rechercher dans l'historique"), color = Nt.Placeholder, fontSize = 14.sp, maxLines = 1) },
                shape = RoundedCornerShape(14.dp),
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = Nt.Surface, focusedContainerColor = Nt.Surface,
                    unfocusedBorderColor = Nt.Border, focusedBorderColor = Nt.Accent, cursorColor = Nt.Accent,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        val q = query.trim()
        val shown = if (q.isEmpty()) history else history.filter { e ->
            listOf(e.alias, e.hostname, e.username, "${e.username}@${e.hostname}", e.protocol.name).any { it.contains(q, ignoreCase = true) }
        }
        if (q.isNotEmpty() && shown.isEmpty()) item { Text(tr("Aucune connexion ne correspond à « %1\$s ».", q), color = Nt.Muted, fontSize = 13.sp) }
        val byDay = shown.groupBy { dayLabel(it.at) }
        byDay.forEach { (day, entries) ->
            item(key = "day-$day") { Text(day, color = Nt.Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp)) }
            item(key = "list-$day") {
                NtCard {
                    entries.forEachIndexed { i, e ->
                        if (i > 0) HorizontalDivider(color = Nt.Border)
                        val host = e.hostId?.let(byId::get)
                        val saved = host != null
                        HistoryRow(
                            e, saved, host?.osId ?: if (e.protocol == Protocol.RDP) "windows" else null, host?.color,
                            onOpen = {
                                if (saved) onConnect(e.hostId!!)
                                else onQuickConnect(QuickTarget(e.protocol, e.username, e.hostname, e.port))
                            },
                            onSave = { onSave(QuickTarget(e.protocol, e.username, e.hostname, e.port)) },
                            onRemove = { scope.launch { dao.deleteConnection(e.id) } },
                        )
                    }
                }
            }
        }
    }

    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false }, containerColor = Nt.Surface,
        title = { Text(tr("Effacer l'historique des connexions ?")) },
        text = { Text(tr("Les hôtes enregistrés ne sont pas touchés."), color = Nt.TextSoft) },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(tr("Annuler"), color = Nt.TextSoft) } },
        confirmButton = { TextButton(onClick = { confirmClear = false; scope.launch { dao.clearConnections() } }) { Text(tr("Effacer"), color = Nt.Pink, fontWeight = FontWeight.SemiBold) } },
    )
}

@Composable
private fun SessionRow(avatar: @Composable () -> Unit, title: String, detail: String, state: SessionState, onOpen: () -> Unit, onClose: () -> Unit) {
    val (color, label) = when (state) {
        SessionState.Connected -> Nt.Green to tr("connecté")
        is SessionState.Connecting -> Nt.Accent to tr("connexion…")
        is SessionState.Closed -> Nt.Pink to tr("déconnecté")
    }
    Row(
        Modifier.fillMaxWidth().combinedClickableCompat(onOpen).padding(start = 14.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        avatar()
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(color))
                Text("$label · $detail", fontFamily = Nt.Mono, fontSize = 11.sp, color = Nt.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, contentDescription = tr("Fermer %1\$s", title), tint = Nt.Muted) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryRow(e: ConnectionLog, saved: Boolean, osId: String?, color: Long?, onOpen: () -> Unit, onSave: () -> Unit, onRemove: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val address = buildString {
        if (e.username.isNotEmpty()) append(e.username).append('@')
        append(e.hostname)
        if (e.protocol != Protocol.LOCAL && e.protocol != Protocol.SERIAL) append(':').append(e.port)
    }
    Box {
        Row(
            Modifier.fillMaxWidth()
                .combinedClickable(onClick = onOpen, onLongClick = { menu = true }, onClickLabel = tr("Se reconnecter"), onLongClickLabel = tr("Plus d'actions"))
                .padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Avatar(osId, e.alias, color.takeIf { saved }, protocolIcon(e.protocol))
            Column(Modifier.weight(1f)) {
                Text(e.alias, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "$address · ${e.protocol.name.lowercase()}" + if (!saved) tr(" · non enregistré") else "",
                    fontFamily = Nt.Mono, fontSize = 11.sp, color = Nt.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(e.at)), fontSize = 12.sp, color = Nt.Muted)
            if (!saved) IconButton(onClick = onSave) { Icon(Icons.Outlined.BookmarkAdd, contentDescription = tr("Enregistrer comme hôte"), tint = Nt.Accent) }
        }
        DropdownMenu(menu, onDismissRequest = { menu = false }) {
            if (!saved) DropdownMenuItem(text = { Text(tr("Enregistrer comme hôte")) }, leadingIcon = { Icon(Icons.Outlined.BookmarkAdd, null) }, onClick = { menu = false; onSave() })
            DropdownMenuItem(text = { Text(tr("Retirer de l'historique")) }, leadingIcon = { Icon(Icons.Outlined.Delete, null) }, onClick = { menu = false; onRemove() })
        }
    }
}

/** The host's OS logo when known (as in the hosts list), else its initial in its colour, else the protocol icon. */
@Composable
private fun Avatar(osId: String?, name: String, color: Long?, fallback: ImageVector) {
    if (osId != null || color != null) HostAvatar(osId, name.take(1).uppercase(), color?.let { Color(it) } ?: Nt.TextSoft, size = 38.dp, corner = 10.dp)
    else Tile(fallback, Nt.TextSoft)
}

@Composable
private fun Tile(icon: ImageVector, tint: Color) {
    Box(Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(tint.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
    }
}

private fun protocolIcon(p: Protocol): ImageVector = when (p) {
    Protocol.RDP -> Icons.Outlined.DesktopWindows
    Protocol.TELNET -> Icons.Outlined.Lan
    Protocol.SERIAL -> Icons.Outlined.Usb
    Protocol.LOCAL -> Icons.Outlined.PhoneAndroid
    else -> Icons.Outlined.Terminal
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit) = this.then(Modifier.combinedClickable(onClick = onClick))

/** « Aujourd'hui », « Hier » or the date. */
private fun dayLabel(at: Long, now: Long = System.currentTimeMillis()): String {
    fun day(t: Long) = Calendar.getInstance().apply { timeInMillis = t }.let { it.get(Calendar.YEAR) * 1000 + it.get(Calendar.DAY_OF_YEAR) }
    val today = day(now)
    val d = day(at)
    return when {
        d == today -> tr("Aujourd'hui")
        d == day(now - 24 * 3600_000L) -> tr("Hier")
        else -> DateFormat.getDateInstance(DateFormat.FULL).format(Date(at)).replaceFirstChar { it.uppercase() }
    }
}
