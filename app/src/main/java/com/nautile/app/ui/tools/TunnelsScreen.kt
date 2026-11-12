package com.nautile.app.ui.tools

import kotlinx.coroutines.flow.map
import com.nautile.i18n.tr

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nautile.app.NautileApp
import com.nautile.app.session.TunnelState
import com.nautile.app.ui.common.NtCard
import com.nautile.app.ui.common.NtTextField
import com.nautile.app.ui.common.Segmented
import com.nautile.app.ui.theme.Nt
import com.nautile.app.ui.common.primaryBorder
import com.nautile.data.model.ForwardKind
import com.nautile.data.model.Host
import com.nautile.data.model.PortForward
import kotlinx.coroutines.launch

@Composable
fun TunnelsScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as NautileApp
    val dao = app.database.toolsDao()
    val rules by dao.observeForwards().collectAsState(initial = emptyList())
    // Port forwarding needs an SSH connection.
    val hosts by androidx.compose.runtime.remember { app.hostRepository.hosts.map { list -> list.filter { it.protocol == com.nautile.data.model.Protocol.SSH || it.protocol == com.nautile.data.model.Protocol.MOSH } } }
        .collectAsState(initial = emptyList())
    val states by app.sessions.tunnels.states.collectAsState()
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<PortForward?>(null) }
    var creating by remember { mutableStateOf(false) }
    val active = states.values.count { it is TunnelState.Active }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Column(Modifier.padding(bottom = 4.dp)) {
                    com.nautile.app.ui.common.PageHeader(tr("Tunnels"), onBack = onBack)
                    Text(tr("%1\$s actif%2\$s · redirections locales, distantes et SOCKS", active, if (active > 1) "s" else ""), color = Nt.Muted, fontSize = 13.sp)
                }
            }
            if (rules.isEmpty()) item {
                Text(tr("Accédez à un service du serveur depuis le téléphone (locale), exposez un service local sur le serveur (distante) ou passez par le serveur comme proxy (SOCKS)."),
                    color = Nt.Muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 40.dp))
            }
            items(rules, key = { it.id }) { r ->
                val state = states[r.id]
                val on = state is TunnelState.Active || state is TunnelState.Starting
                val color = when (r.kind) { ForwardKind.LOCAL -> Nt.Accent; ForwardKind.DYNAMIC -> Nt.Blue; ForwardKind.REMOTE -> Nt.Green }
                val via = hosts.firstOrNull { it.id == r.hostId }?.alias ?: "?"
                Surface(
                    onClick = { editing = r }, shape = RoundedCornerShape(16.dp), color = Nt.Surface,
                    border = BorderStroke(1.dp, if (on) Nt.Accent.copy(alpha = 0.35f) else Nt.Border),
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                                Text(r.kind.name.take(1).let { if (r.kind == ForwardKind.DYNAMIC) "D" else it }, color = color, fontFamily = Nt.Mono, fontSize = 15.sp)
                            }
                            Column(Modifier.weight(1f)) {
                                Text(r.name, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                                val status = when (state) {
                                    is TunnelState.Starting -> tr("connexion…")
                                    is TunnelState.Active -> tr("actif sur le port %1\$s", state.port)
                                    is TunnelState.Failed -> state.reason
                                    null -> if (r.autoStart) tr("démarre avec la session") else tr("arrêté")
                                }
                                Text("${label(r.kind)} · via $via · $status", fontSize = 12.sp, color = if (state is TunnelState.Failed) Nt.Pink else Nt.Muted, maxLines = 2)
                            }
                            Switch(
                                checked = on,
                                onCheckedChange = { if (it) app.sessions.tunnels.start(r) else app.sessions.tunnels.stop(r.id) },
                                colors = SwitchDefaults.colors(checkedTrackColor = Nt.Accent, uncheckedTrackColor = Nt.Toggle),
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Endpoint(fromLabel(r), Modifier.weight(1f))
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
                            Endpoint(toLabel(r), Modifier.weight(1f))
                        }
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { creating = true }, containerColor = Nt.PrimaryContainer, contentColor = Nt.OnPrimaryContainer, shape = RoundedCornerShape(18.dp),
            icon = { Icon(Icons.Filled.Add, contentDescription = null) }, text = { Text(tr("Nouvelle règle"), fontWeight = FontWeight.SemiBold) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp).primaryBorder(RoundedCornerShape(18.dp)),
        )
    }

    if (creating) ForwardDialog(null, hosts, { creating = false }, { scope.launch { dao.upsertForward(it) }; creating = false }, null)
    editing?.let { r ->
        ForwardDialog(r, hosts, { editing = null }, {
            app.sessions.tunnels.stop(r.id)
            scope.launch { dao.upsertForward(it) }; editing = null
        }) { app.sessions.tunnels.stop(r.id); scope.launch { dao.deleteForward(r) }; editing = null }
    }
}

private fun label(k: ForwardKind) = when (k) { ForwardKind.LOCAL -> tr("Locale"); ForwardKind.REMOTE -> tr("Distante"); ForwardKind.DYNAMIC -> "SOCKS5" }
private fun fromLabel(r: PortForward) = if (r.kind == ForwardKind.REMOTE) tr("serveur:%1\$s", r.bindPort) else "${r.bindHost}:${r.bindPort}"
private fun toLabel(r: PortForward) = if (r.kind == ForwardKind.DYNAMIC) "dynamique" else "${r.destHost}:${r.destPort}"

@Composable
private fun Endpoint(text: String, modifier: Modifier) {
    Text(
        text, fontFamily = Nt.Mono, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = modifier.clip(RoundedCornerShape(9.dp)).background(Nt.Bg).padding(horizontal = 10.dp, vertical = 8.dp),
    )
}

@Composable
private fun ForwardDialog(initial: PortForward?, hosts: List<Host>, onDismiss: () -> Unit, onSave: (PortForward) -> Unit, onDelete: (() -> Unit)?) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var hostId by remember { mutableStateOf(initial?.hostId ?: hosts.firstOrNull()?.id) }
    var kind by remember { mutableStateOf(initial?.kind ?: ForwardKind.LOCAL) }
    var bindHost by remember { mutableStateOf(initial?.bindHost ?: "127.0.0.1") }
    var bindPort by remember { mutableStateOf(initial?.bindPort?.toString().orEmpty()) }
    var destHost by remember { mutableStateOf(initial?.destHost ?: "127.0.0.1") }
    var destPort by remember { mutableStateOf(initial?.destPort?.takeIf { it > 0 }?.toString().orEmpty()) }
    var auto by remember { mutableStateOf(initial?.autoStart ?: false) }
    var hostMenu by remember { mutableStateOf(false) }
    val bp = bindPort.toIntOrNull()?.takeIf { it in 1..65535 }
    val dp = destPort.toIntOrNull()?.takeIf { it in 1..65535 }
    val valid = hostId != null && bp != null && (kind == ForwardKind.DYNAMIC || (dp != null && destHost.isNotBlank()))
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Nt.Surface,
        title = { Text(if (initial == null) tr("Nouvelle règle") else tr("Modifier la règle")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                NtTextField(name, { name = it }, tr("Nom"), placeholder = tr("Jellyfin"))
                Segmented(listOf(ForwardKind.LOCAL to "Locale", ForwardKind.REMOTE to "Distante", ForwardKind.DYNAMIC to "SOCKS"), kind, { kind = it })
                NtCard {
                    Box {
                        Column(Modifier.fillMaxWidth().clickable { hostMenu = true }.padding(horizontal = 14.dp, vertical = 12.dp)) {
                            Text(tr("Via l'hôte"), color = Nt.Muted, fontSize = 12.sp)
                            Text(hosts.firstOrNull { it.id == hostId }?.alias ?: tr("Choisir…"), fontWeight = FontWeight.SemiBold)
                        }
                        DropdownMenu(hostMenu, { hostMenu = false }) {
                            hosts.forEach { h -> DropdownMenuItem(text = { Text(h.alias) }, onClick = { hostId = h.id; hostMenu = false }) }
                        }
                    }
                }
                Text(
                    when (kind) {
                        ForwardKind.LOCAL -> tr("Écoute sur le téléphone, se connecte à la destination depuis le serveur.")
                        ForwardKind.REMOTE -> tr("Écoute sur le serveur, se connecte à la destination depuis le téléphone.")
                        ForwardKind.DYNAMIC -> tr("Proxy SOCKS5 sur le téléphone : les applis qui le configurent sortent par le serveur.")
                    },
                    color = Nt.Muted, fontSize = 12.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NtTextField(bindHost, { bindHost = it }, if (kind == ForwardKind.REMOTE) tr("Écoute (serveur)") else tr("Écoute"), Modifier.weight(2f), mono = true)
                    NtTextField(bindPort, { bindPort = it.filter(Char::isDigit).take(5) }, tr("Port"), Modifier.weight(1.2f), mono = true,
                        isError = bindPort.isNotEmpty() && bp == null, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
                if (kind != ForwardKind.DYNAMIC) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NtTextField(destHost, { destHost = it }, "Destination", Modifier.weight(2f), mono = true)
                    NtTextField(destPort, { destPort = it.filter(Char::isDigit).take(5) }, tr("Port"), Modifier.weight(1.2f), mono = true,
                        isError = destPort.isNotEmpty() && dp == null, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
                if (bp != null && bp < 1024 && kind != ForwardKind.REMOTE) Text(tr("Android n'autorise pas l'écoute sous le port 1024."), color = Nt.Pink, fontSize = 12.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("Démarrer avec chaque session sur cet hôte"), modifier = Modifier.weight(1f), fontSize = 14.sp)
                    Switch(auto, { auto = it }, colors = SwitchDefaults.colors(checkedTrackColor = Nt.Accent, uncheckedTrackColor = Nt.Toggle))
                }
                if (onDelete != null) TextButton(onClick = onDelete) { Text(tr("Supprimer la règle"), color = Nt.Pink) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Annuler"), color = Nt.TextSoft) } },
        confirmButton = {
            TextButton(onClick = {
                onSave((initial ?: PortForward(name = "", hostId = hostId!!, kind = kind, bindPort = bp!!)).copy(
                    name = name.trim().ifEmpty { "${label(kind)} ${bp}" }, hostId = hostId!!, kind = kind,
                    bindHost = bindHost.trim().ifEmpty { "127.0.0.1" }, bindPort = bp!!,
                    destHost = if (kind == ForwardKind.DYNAMIC) "" else destHost.trim(), destPort = if (kind == ForwardKind.DYNAMIC) 0 else dp!!,
                    autoStart = auto,
                ))
            }, enabled = valid) { Text(tr("Enregistrer"), color = Nt.Accent, fontWeight = FontWeight.SemiBold) }
        },
    )
}
