package com.nautile.app.ui.hosts

import com.nautile.i18n.tr

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nautile.app.ui.common.NtCard
import com.nautile.app.ui.common.NtTextField
import com.nautile.app.ui.common.PageHeader
import com.nautile.app.ui.common.SectionLabel
import com.nautile.app.ui.common.primaryBorder
import com.nautile.app.ui.theme.Nt
import com.nautile.data.model.HOST_COLORS
import com.nautile.data.model.Host
import com.nautile.data.model.HostGroup
import com.nautile.data.model.Protocol

@Composable
fun HostsScreen(
    onConnect: (Long) -> Unit,
    onQuickConnect: (QuickTarget) -> Unit,
    onEditHost: (Long) -> Unit,
    onSftp: (Long) -> Unit,
    onNewHost: (Protocol) -> Unit,
    onKeys: () -> Unit,
    onTunnels: () -> Unit,
    vm: HostsViewModel = viewModel(factory = HostsViewModel.Factory),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var newHost by remember { mutableStateOf(false) }
    var manageGroups by remember { mutableStateOf(false) }
    val quick = QuickConnect.parse(state.query)?.takeIf { QuickConnect.looksLikeTarget(state.query) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::import) }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { PageHeader(tr("Hôtes")) }
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = state.query,
                        onValueChange = vm::setQuery,
                        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, tint = Nt.Muted) },
                        trailingIcon = {
                            if (state.query.isNotEmpty()) IconButton(onClick = { vm.setQuery("") }) {
                                Icon(Icons.Outlined.Close, contentDescription = tr("Effacer la recherche"), tint = Nt.Muted)
                            }
                        },
                        placeholder = { Text(tr("Hôte ou user@ip"), color = Nt.Placeholder, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        singleLine = true,
                        textStyle = TextStyle(fontFamily = Nt.Mono, fontSize = 13.sp, color = Nt.Text),
                        shape = RoundedCornerShape(14.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = if (quick != null) ImeAction.Go else ImeAction.Search),
                        keyboardActions = KeyboardActions(onGo = { quick?.let(onQuickConnect) }),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedContainerColor = Nt.Surface, focusedContainerColor = Nt.Surface,
                            unfocusedBorderColor = Nt.Border, focusedBorderColor = Nt.Accent, cursorColor = Nt.Accent,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    HeaderIcon(Icons.Outlined.Key, tr("Trousseau de clés"), onKeys)
                    HeaderIcon(Icons.Outlined.SwapHoriz, tr("Tunnels"), onTunnels)
                }
            }
            quick?.let { target -> item(key = "quick") { QuickConnectCard(target, onQuickConnect) } }
            if (state.groups.isNotEmpty() || state.groupNames.isNotEmpty()) item {
                // Groups scroll; « Gérer » stays at hand on the right.
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        com.nautile.app.ui.common.CountChip(tr("Tous"), state.total, state.selectedGroup == null, null) { vm.selectGroup(null) }
                        state.groupNames.forEach { name ->
                            val color = state.groups.firstOrNull { it.name == name }?.color
                            com.nautile.app.ui.common.CountChip(tr(name), state.nameCounts[name] ?: 0, state.selectedGroup == name, color) {
                                vm.selectGroup(if (state.selectedGroup == name) null else name)
                            }
                        }
                    }
                    Surface(
                        onClick = { manageGroups = true }, shape = RoundedCornerShape(12.dp), color = Nt.Surface,
                        border = BorderStroke(1.dp, Nt.Border), modifier = Modifier.size(40.dp).semantics { contentDescription = tr("Gérer les groupes") },
                    ) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Tune, contentDescription = null, tint = Nt.TextSoft, modifier = Modifier.size(20.dp)) } }
                }
            }
            if (state.isEmpty) item {
                Column(Modifier.fillMaxWidth().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(tr("Aucun hôte pour l'instant."), color = Nt.TextSoft, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(tr("Touchez + pour en ajouter un, importez votre ~/.ssh/config, ou tapez root@adresse dans la recherche pour vous connecter sans l'enregistrer."),
                        color = Nt.Muted, textAlign = TextAlign.Center, fontSize = 13.sp)
                }
            } else if (state.sections.isEmpty() && quick == null) item {
                Text(tr("Aucun hôte ne correspond à « %1\$s ».", state.query), color = Nt.Muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 24.dp))
            }
            items(state.sections, key = { it.title }) { section ->
                Section(section, state.groups, onConnect, onEditHost, onSftp, onMove = vm::moveHost, onDelete = vm::deleteHost)
            }
        }

        FloatingActionButton(
            onClick = { newHost = true },
            containerColor = Nt.PrimaryContainer,
            contentColor = Nt.OnPrimaryContainer,
            shape = CircleShape,
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp).size(60.dp).primaryBorder(CircleShape),
        ) { Icon(Icons.Filled.Add, contentDescription = tr("Nouvel hôte"), modifier = Modifier.size(28.dp)) }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 90.dp))
    }

    if (newHost) NewHostSheet(
        onDismiss = { newHost = false },
        onPick = { newHost = false; onNewHost(it) },
        onImport = { newHost = false; picker.launch(arrayOf("*/*")) },
    )
    if (manageGroups) GroupsDialog(
        groups = state.groups, counts = state.counts, onDismiss = { manageGroups = false },
        onSave = vm::saveGroup, onDelete = vm::deleteGroup, onMove = vm::moveGroup,
    )
}

@Composable
private fun HeaderIcon(icon: ImageVector, description: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick, shape = RoundedCornerShape(14.dp), color = Nt.Surface, border = BorderStroke(1.dp, Nt.Border),
        modifier = Modifier.size(54.dp).semantics { contentDescription = description },
    ) { Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null, tint = Nt.TextSoft) } }
}

/** « Se connecter à root@192.168.1.2 » for what is typed in the search bar, with the protocol to use. */
@Composable
private fun QuickConnectCard(parsed: QuickTarget, onConnect: (QuickTarget) -> Unit) {
    var protocol by remember(parsed) { mutableStateOf(parsed.protocol) }
    val target = if (protocol == parsed.protocol) parsed
    else parsed.copy(protocol = protocol, port = if (parsed.port == parsed.protocol.defaultPortOrSsh()) protocol.defaultPortOrSsh() else parsed.port)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Nt.Accent.copy(alpha = 0.08f))
            .border(1.dp, Nt.Accent.copy(alpha = 0.5f), RoundedCornerShape(16.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(tr("Connexion rapide"), color = Nt.Muted, fontSize = 12.sp)
                Text(target.label, fontFamily = Nt.Mono, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Surface(
                onClick = { onConnect(target) }, shape = RoundedCornerShape(12.dp),
                color = Nt.PrimaryContainer, contentColor = Nt.OnPrimaryContainer, modifier = Modifier.height(44.dp),
            ) {
                Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.AutoMirrored.Outlined.Login, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(tr("Connecter"), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(Protocol.SSH to "SSH", Protocol.MOSH to "Mosh", Protocol.TELNET to "Telnet", Protocol.RDP to "RDP").forEach { (p, label) ->
                Chip(label, protocol == p) { protocol = p }
            }
        }
        Text(tr("Pas enregistrée : retrouvez-la dans Connexions › Historique pour l'enregistrer."), color = Nt.Muted, fontSize = 12.sp)
    }
}

private fun Protocol.defaultPortOrSsh() = when (this) { Protocol.TELNET -> 23; Protocol.RDP -> 3389; else -> 22 }

@Composable
private fun Chip(label: String, selected: Boolean, dot: Long? = null, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = if (selected) Nt.Accent.copy(alpha = 0.12f) else Nt.Surface,
        border = BorderStroke(1.dp, if (selected) Nt.Accent else Nt.Border),
        modifier = Modifier.height(36.dp),
    ) {
        Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            dot?.let { Box(Modifier.size(8.dp).clip(CircleShape).background(Color(it))) }
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (selected) Nt.Accent else Nt.TextSoft)
        }
    }
}

@Composable
private fun Section(
    section: HostSection, groups: List<HostGroup>,
    onConnect: (Long) -> Unit, onEdit: (Long) -> Unit, onSftp: (Long) -> Unit,
    onMove: (Host, String?) -> Unit, onDelete: (Host) -> Unit,
) {
    Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            section.color?.let { Box(Modifier.size(8.dp).clip(CircleShape).background(Color(it))) }
            SectionLabel(tr(section.title), Modifier.weight(1f))
            val n = section.hosts.size
            Text(if (n > 1) tr("%1\$s hôtes", n) else tr("1 hôte"), fontSize = 12.sp, color = Nt.Muted)
        }
        NtCard {
            section.hosts.forEachIndexed { i, host ->
                if (i > 0) HorizontalDivider(color = Nt.Border)
                HostRow(host, groups, onClick = { onConnect(host.id) }, onEdit = { onEdit(host.id) }, onSftp = { onSftp(host.id) },
                    onMove = { onMove(host, it) }, onDelete = { onDelete(host) })
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HostRow(host: Host, groups: List<HostGroup>, onClick: () -> Unit, onEdit: () -> Unit, onSftp: () -> Unit, onMove: (String?) -> Unit, onDelete: () -> Unit) {
    val tint = Color(host.color)
    var menu by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = { menu = true }, onClickLabel = tr("Se connecter"), onLongClickLabel = tr("Plus d'actions"))
                .padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            com.nautile.app.ui.common.HostAvatar(host.osId, host.alias.take(1).uppercase(), tint)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(host.alias, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (host.protocol != Protocol.SSH) ProtocolBadge(host.protocol)
                }
                Text(host.address, fontFamily = Nt.Mono, fontSize = 12.sp, color = Nt.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            host.lastConnectedAt?.let { Text(relativeTime(it), fontSize = 11.sp, color = Nt.Muted) }
            if (host.protocol == Protocol.SSH || host.protocol == Protocol.MOSH)
                IconButton(onClick = onSftp) { Icon(Icons.Outlined.Folder, contentDescription = tr("Fichiers de %1\$s", host.alias), tint = Nt.Muted) }
        }
        DropdownMenu(menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(tr("Modifier")) }, leadingIcon = { Icon(Icons.Outlined.Edit, null) }, onClick = { menu = false; onEdit() })
            DropdownMenuItem(text = { Text(tr("Déplacer vers un groupe…")) }, leadingIcon = { Icon(Icons.AutoMirrored.Outlined.DriveFileMove, null) }, onClick = { menu = false; moving = true })
            DropdownMenuItem(text = { Text(tr("Supprimer"), color = Nt.Pink) }, leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = Nt.Pink) }, onClick = { menu = false; confirmDelete = true })
        }
    }
    if (moving) MoveDialog(host, groups, onDismiss = { moving = false }) { moving = false; onMove(it) }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false }, containerColor = Nt.Surface,
        title = { Text(tr("Supprimer %1\$s ?", host.alias)) },
        text = { Text(tr("L'hôte et ses tunnels sont supprimés. Les clés du trousseau sont gardées."), color = Nt.TextSoft) },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(tr("Annuler"), color = Nt.TextSoft) } },
        confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text(tr("Supprimer"), color = Nt.Pink, fontWeight = FontWeight.SemiBold) } },
    )
}

@Composable
private fun ProtocolBadge(protocol: Protocol) {
    val label = when (protocol) {
        Protocol.RDP -> "RDP"; Protocol.MOSH -> "Mosh"; Protocol.TELNET -> "Telnet"; Protocol.SERIAL -> tr("Série"); Protocol.LOCAL -> tr("Local"); Protocol.SSH -> "SSH"
    }
    Box(Modifier.height(18.dp).clip(RoundedCornerShape(5.dp)).background(Nt.KeyBg).padding(horizontal = 5.dp), contentAlignment = Alignment.Center) {
        Text(label, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = Nt.TextSoft)
    }
}

@Composable
private fun MoveDialog(host: Host, groups: List<HostGroup>, onDismiss: () -> Unit, onMove: (String?) -> Unit) {
    var newName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Nt.Surface,
        title = { Text(tr("Déplacer %1\$s", host.alias)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                (groups.map { it.name to it.color } + (null to null)).forEach { (name, color) ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button) { onMove(name) }
                            .background(if ((name == null && host.groupId == null) || groups.firstOrNull { it.id == host.groupId }?.name == name) Nt.Accent.copy(alpha = 0.10f) else Color.Transparent)
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(color?.let { Color(it) } ?: Nt.Border))
                        Text(name ?: tr("Sans groupe"), fontSize = 15.sp)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    NtTextField(newName, { newName = it }, tr("Nouveau groupe"), Modifier.weight(1f))
                    TextButton(onClick = { onMove(newName) }, enabled = newName.isNotBlank()) { Text(tr("Créer"), color = Nt.Accent) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("Annuler"), color = Nt.TextSoft) } },
    )
}

/** Rename, recolour, reorder and delete groups. */
@Composable
private fun GroupsDialog(
    groups: List<HostGroup>, counts: Map<Long, Int>, onDismiss: () -> Unit,
    onSave: (HostGroup) -> Unit, onDelete: (HostGroup) -> Unit, onMove: (HostGroup, Int) -> Unit,
) {
    var editing by remember { mutableStateOf<HostGroup?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Nt.Surface,
        title = { Text(tr("Groupes")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (groups.isEmpty()) Text(tr("Aucun groupe. Créez-en un pour ranger vos hôtes."), color = Nt.Muted, fontSize = 13.sp)
                groups.forEachIndexed { i, g ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Row(
                            Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button) { editing = g }.padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Box(Modifier.size(12.dp).clip(CircleShape).background(Color(g.color)))
                            Column {
                                Text(g.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                Text(tr("%1\$s hôte(s)", counts[g.id] ?: 0), fontSize = 12.sp, color = Nt.Muted)
                            }
                        }
                        IconButton(onClick = { onMove(g, -1) }, enabled = i > 0) { Text("↑", fontSize = 18.sp, color = if (i > 0) Nt.Text else Nt.Border) }
                        IconButton(onClick = { onMove(g, 1) }, enabled = i < groups.lastIndex) { Text("↓", fontSize = 18.sp, color = if (i < groups.lastIndex) Nt.Text else Nt.Border) }
                    }
                }
            }
        },
        dismissButton = { TextButton(onClick = { editing = HostGroup(name = "") }) { Text(tr("Nouveau groupe"), color = Nt.Accent) } },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("Terminé"), color = Nt.Accent, fontWeight = FontWeight.SemiBold) } },
    )
    editing?.let { g ->
        GroupEditDialog(g, onDismiss = { editing = null }, onSave = { onSave(it); editing = null }, onDelete = if (g.id != 0L) ({ onDelete(g); editing = null }) else null)
    }
}

@Composable
private fun GroupEditDialog(group: HostGroup, onDismiss: () -> Unit, onSave: (HostGroup) -> Unit, onDelete: (() -> Unit)?) {
    var name by remember { mutableStateOf(group.name) }
    var color by remember { mutableStateOf(group.color) }
    var confirm by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Nt.Surface,
        title = { Text(if (group.id == 0L) tr("Nouveau groupe") else tr("Modifier le groupe")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                NtTextField(name, { name = it }, tr("Nom"), placeholder = tr("Homelab"))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    HOST_COLORS.forEach { c ->
                        Box(
                            Modifier.size(34.dp).clip(CircleShape).background(Color(c)).border(3.dp, if (c == color) Nt.Text else Nt.Surface, CircleShape)
                                .clickable(role = Role.RadioButton) { color = c }.semantics { contentDescription = tr("Couleur") },
                        )
                    }
                }
                if (onDelete != null) {
                    if (!confirm) TextButton(onClick = { confirm = true }) { Text(tr("Supprimer le groupe"), color = Nt.Pink) }
                    else Text(tr("Ses hôtes passeront dans « Sans groupe ». Touchez à nouveau pour confirmer."), color = Nt.Pink, fontSize = 13.sp,
                        modifier = Modifier.clickable(role = Role.Button) { onDelete() })
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Annuler"), color = Nt.TextSoft) } },
        confirmButton = { TextButton(onClick = { onSave(group.copy(name = name, color = color)) }, enabled = name.isNotBlank()) { Text(tr("Enregistrer"), color = Nt.Accent) } },
    )
}

/** Step one of « + »: what kind of host, before the form. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewHostSheet(onDismiss: () -> Unit, onPick: (Protocol) -> Unit, onImport: () -> Unit) {
    // Fully open at once: the six choices fit on a phone screen, no need to drag the sheet up.
    val state = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = Nt.Surface) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(tr("Nouvel hôte"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 6.dp))
            TypeRow(Icons.Outlined.Terminal, Nt.Blue, "SSH", tr("Terminal sur un serveur Linux, macOS, routeur… (Mosh en option)")) { onPick(Protocol.SSH) }
            TypeRow(Icons.Outlined.DesktopWindows, Nt.Green, tr("Bureau à distance (RDP)"), tr("Bureau Windows ou serveur xrdp")) { onPick(Protocol.RDP) }
            TypeRow(Icons.Outlined.Lan, Nt.Accent, "Telnet", tr("Équipements réseau anciens, consoles")) { onPick(Protocol.TELNET) }
            TypeRow(Icons.Outlined.Usb, Nt.Pink, tr("Série USB"), tr("Câble console USB-OTG (FTDI, CP210x, CH34x…)")) { onPick(Protocol.SERIAL) }
            TypeRow(Icons.Outlined.PhoneAndroid, Nt.TextSoft, tr("Terminal local"), tr("Le shell de ce téléphone")) { onPick(Protocol.LOCAL) }
            HorizontalDivider(color = Nt.Border, modifier = Modifier.padding(vertical = 4.dp))
            TypeRow(Icons.Outlined.FileOpen, Nt.TextSoft, tr("Importer"), tr("~/.ssh/config ou CSV (hostname, alias, port, user, group, tags)"), onClick = onImport)
        }
    }
}

@Composable
private fun TypeRow(icon: ImageVector, tint: Color, title: String, detail: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(tint.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = tint)
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, fontSize = 12.sp, lineHeight = 16.sp, color = Nt.Muted)
        }
    }
}

internal fun relativeTime(at: Long, now: Long = System.currentTimeMillis()): String {
    val min = (now - at) / 60_000
    return when {
        min < 1 -> tr("à l'instant")
        min < 60 -> tr("il y a %1\$s min", min)
        min < 60 * 24 -> tr("il y a %1\$s h", min / 60)
        min < 60 * 24 * 2 -> tr("hier")
        min < 60 * 24 * 7 -> tr("%1\$s j", min / (60 * 24))
        min < 60 * 24 * 30 -> tr("%1\$s sem", min / (60 * 24 * 7))
        else -> tr("%1\$s mois", min / (60 * 24 * 30))
    }
}
