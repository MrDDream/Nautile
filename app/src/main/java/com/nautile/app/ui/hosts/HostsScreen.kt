package com.nautile.app.ui.hosts

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.automirrored.outlined.ScreenShare
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
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
import com.nautile.app.ui.common.Centered
import com.nautile.app.ui.common.ConfirmDialog
import com.nautile.app.ui.common.CountChip
import com.nautile.app.ui.common.FontGlyph
import com.nautile.app.ui.common.GroupMark
import com.nautile.app.ui.common.HostAvatar
import com.nautile.app.ui.common.MdiIcons
import com.nautile.app.ui.common.NtCard
import com.nautile.app.ui.common.NtChip
import com.nautile.app.ui.common.NtSearchField
import com.nautile.app.ui.common.NtTextField
import com.nautile.app.ui.common.PageHeader
import com.nautile.app.ui.common.SectionLabel
import com.nautile.app.ui.common.primaryBorder
import com.nautile.app.ui.theme.Nt
import com.nautile.data.model.HOST_COLORS
import com.nautile.data.model.Host
import com.nautile.data.model.HostGroup
import com.nautile.data.model.Protocol
import com.nautile.data.model.defaultPortOrSsh
import com.nautile.data.model.usesSsh
import com.nautile.i18n.tr

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
    val quick = remember(state.query) { QuickConnect.parse(state.query)?.takeIf { QuickConnect.looksLikeTarget(state.query) } }
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
                    NtSearchField(
                        state.query, vm::setQuery, tr("Hôte ou user@ip"), Modifier.weight(1f),
                        textStyle = TextStyle(fontFamily = Nt.Mono, fontSize = 13.sp, color = Nt.Text),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = if (quick != null) ImeAction.Go else ImeAction.Search),
                        keyboardActions = KeyboardActions(onGo = { quick?.let(onQuickConnect) }),
                    )
                    HeaderIcon(Icons.Outlined.Key, tr("Trousseau de clés"), onKeys)
                    HeaderIcon(Icons.Outlined.SwapHoriz, tr("Tunnels"), onTunnels)
                }
            }
            quick?.let { target -> item(key = "quick") { QuickConnectCard(target, onQuickConnect) } }
            if (state.groups.isNotEmpty() || state.groupFilters.isNotEmpty()) item {
                // One selector (« Tous » then each group); « Gérer » stays at hand on the right.
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GroupSelector(
                        // Only the fixed labels are translated, never a name the user typed.
                        options = listOf(Triple(null, tr("Tous"), state.total)) + state.groupFilters.map { id ->
                            Triple(id, state.groups.firstOrNull { it.id == id }?.name ?: tr("Sans groupe"), state.counts[id] ?: 0)
                        },
                        groupOf = { id -> state.groups.firstOrNull { it.id == id } },
                        selected = state.selectedGroup,
                        onSelect = vm::selectGroup,
                        modifier = Modifier.weight(1f),
                    )
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
            items(state.sections, key = { it.key }) { section ->
                Section(section, state.groups, onConnect, onEditHost, onSftp, onMove = vm::moveHost, onDelete = vm::deleteHost)
            }
        }

        FloatingActionButton(
            onClick = { newHost = true },
            containerColor = Nt.FabContainer,
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
    else parsed.copy(protocol = protocol, port = if (parsed.port == parsed.protocol.defaultPortOrSsh) protocol.defaultPortOrSsh else parsed.port)
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
            listOf(Protocol.SSH to "SSH", Protocol.MOSH to "Mosh", Protocol.TELNET to "Telnet", Protocol.RDP to "RDP", Protocol.VNC to "VNC").forEach { (p, label) ->
                NtChip(label, protocol == p) { protocol = p }
            }
        }
        Text(tr("Pas enregistrée : retrouvez-la dans Connexions › Historique pour l'enregistrer."), color = Nt.Muted, fontSize = 12.sp)
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
            section.color?.let { GroupMark(section.icon, Color(it), iconSize = 16.dp, dotSize = 8.dp) }
            SectionLabel(if (section.groupId == null) tr("Sans groupe") else section.title, Modifier.weight(1f))
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
            HostAvatar(host.osId, host.alias.take(1).uppercase(), tint)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(host.alias, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (host.protocol != Protocol.SSH) ProtocolBadge(host.protocol)
                }
                HostSubtitle(host.address, host.lastConnectedAt?.let { relativeTime(it, short = true) })
            }
            if (host.protocol.usesSsh)
                IconButton(onClick = onSftp) { Icon(Icons.Outlined.Folder, contentDescription = tr("Fichiers de %1\$s", host.alias), tint = Nt.Muted) }
        }
        DropdownMenu(menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(tr("Modifier")) }, leadingIcon = { Icon(Icons.Outlined.Edit, null) }, onClick = { menu = false; onEdit() })
            DropdownMenuItem(text = { Text(tr("Déplacer vers un groupe…")) }, leadingIcon = { Icon(Icons.AutoMirrored.Outlined.DriveFileMove, null) }, onClick = { menu = false; moving = true })
            DropdownMenuItem(text = { Text(tr("Supprimer"), color = Nt.Pink) }, leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = Nt.Pink) }, onClick = { menu = false; confirmDelete = true })
        }
    }
    if (moving) MoveDialog(host, groups, onDismiss = { moving = false }) { moving = false; onMove(it) }
    if (confirmDelete) DeleteHostDialog(host.alias, onDismiss = { confirmDelete = false }) { confirmDelete = false; onDelete() }
}

/** Confirmation before deleting the host [name], from the list or from its edit screen. */
@Composable
internal fun DeleteHostDialog(name: String, onDismiss: () -> Unit, onConfirm: () -> Unit) = ConfirmDialog(
    tr("Supprimer %1\$s ?", name), tr("L'hôte et ses tunnels sont supprimés. Les clés du trousseau sont gardées."), tr("Supprimer"),
    onDismiss, onConfirm,
)

@Composable
private fun ProtocolBadge(protocol: Protocol) {
    val label = when (protocol) {
        Protocol.RDP -> "RDP"; Protocol.VNC -> "VNC"; Protocol.MOSH -> "Mosh"; Protocol.TELNET -> "Telnet"; Protocol.SERIAL -> tr("Série"); Protocol.LOCAL -> tr("Local"); Protocol.SSH -> "SSH"
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
                (groups.map { Triple(it.name, it.color, it.icon) } + Triple(null, null, null)).forEach { (name, color, icon) ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button) { onMove(name) }
                            .background(if ((name == null && host.groupId == null) || groups.firstOrNull { it.id == host.groupId }?.name == name) Nt.Accent.copy(alpha = 0.10f) else Color.Transparent)
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        GroupMark(icon, color?.let { Color(it) })
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

/** Groups: their order, and a tap to rename, recolour or delete one; « + Nouveau groupe » at the bottom. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupsDialog(
    groups: List<HostGroup>, counts: Map<Long, Int>, onDismiss: () -> Unit,
    onSave: (HostGroup) -> Unit, onDelete: (HostGroup) -> Unit, onMove: (HostGroup, Int) -> Unit,
) {
    val state = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = Nt.Surface) {
        GroupsSheetContent(groups, counts, onDismiss, onSave, onDelete, onMove)
    }
}

@Composable
internal fun GroupsSheetContent(
    groups: List<HostGroup>, counts: Map<Long, Int>, onDismiss: () -> Unit,
    onSave: (HostGroup) -> Unit, onDelete: (HostGroup) -> Unit, onMove: (HostGroup, Int) -> Unit,
    initialEditing: HostGroup? = null,
) {
    var editing by remember { mutableStateOf(initialEditing) }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        val g = editing
        if (g != null) GroupEditor(
            g, count = counts[g.id] ?: 0,
            onBack = { editing = null },
            onSave = { onSave(it); editing = null },
            onDelete = if (g.id != 0L) ({ onDelete(g); editing = null }) else null,
        ) else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tr("Groupes"), style = MaterialTheme.typography.titleLarge)
                    Text(tr("Touchez un groupe pour le modifier"), color = Nt.Muted, fontSize = 13.sp)
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, contentDescription = tr("Fermer"), tint = Nt.TextSoft) }
            }
            if (groups.isEmpty()) Text(tr("Aucun groupe. Créez-en un pour ranger vos hôtes."), color = Nt.Muted, fontSize = 14.sp)
            else NtCard {
                groups.forEachIndexed { i, grp ->
                    if (i > 0) HorizontalDivider(color = Nt.Border)
                    Row(
                        Modifier.fillMaxWidth().clickable(role = Role.Button) { editing = grp }.padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(Color(grp.color).copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                            GroupMark(grp.icon, Color(grp.color), iconSize = 20.dp, dotSize = 12.dp)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(grp.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val n = counts[grp.id] ?: 0
                            Text(if (n > 1) tr("%1\$s hôtes", n) else if (n == 1) tr("1 hôte") else tr("Vide"), fontSize = 12.sp, color = Nt.Muted)
                        }
                        // Order: one pill, two halves
                        Row(Modifier.clip(RoundedCornerShape(10.dp)).background(Nt.Bg)) {
                            IconButton(onClick = { onMove(grp, -1) }, enabled = i > 0, modifier = Modifier.size(40.dp)) {
                                Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = tr("Monter %1\$s", grp.name), tint = if (i > 0) Nt.Text else Nt.Border)
                            }
                            IconButton(onClick = { onMove(grp, 1) }, enabled = i < groups.lastIndex, modifier = Modifier.size(40.dp)) {
                                Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = tr("Descendre %1\$s", grp.name), tint = if (i < groups.lastIndex) Nt.Text else Nt.Border)
                            }
                        }
                    }
                }
            }
            Surface(
                onClick = { editing = HostGroup(name = "", color = HOST_COLORS[groups.size % HOST_COLORS.size]) },
                shape = RoundedCornerShape(14.dp), color = Nt.Accent.copy(alpha = 0.10f), border = BorderStroke(1.dp, Nt.Accent.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Add, contentDescription = null, tint = Nt.Accent)
                    Text("  " + tr("Nouveau groupe"), color = Nt.Accent, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                }
            }
        }
    }
}

/** Name and colour of a group, with a preview of its chip. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GroupEditor(group: HostGroup, count: Int, onBack: () -> Unit, onSave: (HostGroup) -> Unit, onDelete: (() -> Unit)?) {
    var name by remember(group) { mutableStateOf(group.name) }
    var color by remember(group) { mutableLongStateOf(group.color) }
    var icon by remember(group) { mutableStateOf(group.icon) }
    var picking by remember(group) { mutableStateOf(false) }
    var confirm by remember(group) { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("Retour"), tint = Nt.Text) }
        Text(if (group.id == 0L) tr("Nouveau groupe") else tr("Modifier le groupe"), style = MaterialTheme.typography.titleLarge)
    }
    NtTextField(name, { name = it }, tr("Nom"), placeholder = tr("Homelab"))
    Text(tr("Couleur"), color = Nt.TextSoft, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HOST_COLORS.forEach { c ->
            val on = c == color
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(Color(c))
                    .border(if (on) 3.dp else 0.dp, if (on) Nt.Text else Color.Transparent, CircleShape)
                    .clickable(role = Role.RadioButton) { color = c }.semantics { contentDescription = tr("Couleur") },
                contentAlignment = Alignment.Center,
            ) { if (on) Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp)) }
        }
    }
    Text(tr("Icône"), color = Nt.TextSoft, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Nt.Bg).clickable(role = Role.Button) { picking = true }.padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)).background(Color(color).copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
            GroupMark(icon, Color(color), iconSize = 24.dp, dotSize = 12.dp)
        }
        Column(Modifier.weight(1f)) {
            Text(icon?.let { "mdi:$it" } ?: tr("Aucune"), fontFamily = if (icon != null) Nt.Mono else null, fontSize = 14.sp, color = Nt.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (icon != null) tr("Touchez pour en changer") else tr("Le point de couleur ; touchez pour choisir une icône"), fontSize = 12.sp, color = Nt.Muted)
        }
        if (icon != null) IconButton(onClick = { icon = null }) { Icon(Icons.Outlined.Close, contentDescription = tr("Retirer l'icône"), tint = Nt.Muted) }
    }
    if (picking) IconPicker(Color(color), icon, onDismiss = { picking = false }) { icon = it; picking = false }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(tr("Aperçu"), color = Nt.Muted, fontSize = 13.sp)
        CountChip(name.ifBlank { tr("Groupe") }, count, selected = true, color = color, icon = icon) {}
    }
    Button(
        onClick = { onSave(group.copy(name = name.trim(), color = color, icon = icon)) }, enabled = name.isNotBlank(),
        modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Nt.Accent, contentColor = Nt.OnAccent),
    ) { Text(tr("Enregistrer"), fontWeight = FontWeight.SemiBold) }
    if (onDelete != null) Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (!confirm) TextButton(onClick = { confirm = true }) { Text(tr("Supprimer le groupe"), color = Nt.Pink) }
        else {
            Text(tr("Ses hôtes passeront dans « Sans groupe »."), color = Nt.Muted, fontSize = 13.sp)
            TextButton(onClick = onDelete) { Text(tr("Confirmer la suppression"), color = Nt.Pink, fontWeight = FontWeight.SemiBold) }
        }
    }
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
            TypeRow(Icons.AutoMirrored.Outlined.ScreenShare, Nt.Blue, tr("Bureau à distance (VNC)"), tr("Linux, macOS, Raspberry Pi… (x11vnc, TigerVNC, partage d'écran)")) { onPick(Protocol.VNC) }
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

/**
 * The address under a host's name, then « · 22 min » when it was used. When short of room the user part is cut
 * (« dwcloud_a…@203.0.113.42 »), never the host: the IP or name is what tells two machines apart.
 */
@Composable
private fun HostSubtitle(address: String, lastUsed: String?) {
    val at = address.lastIndexOf('@')
    val user = if (at > 0) address.substring(0, at) else ""
    val host = if (at > 0) address.substring(at) else address
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (user.isNotEmpty())
            Text(user, fontFamily = Nt.Mono, fontSize = 12.sp, color = Nt.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        // Not weighted, so measured before the user part and given what it needs; a host too long for the whole
        // row (an IPv6, a long DNS name) is cut in its middle, both ends staying readable.
        Text(host, fontFamily = Nt.Mono, fontSize = 12.sp, color = Nt.Muted, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
        lastUsed?.let { Text("  ·  $it", fontSize = 12.sp, color = Nt.Muted, maxLines = 1, softWrap = false) }
    }
}

/** « il y a 22 min » in a sentence; [short] (« 22 min ») where room is scarce, like a host's row. */
internal fun relativeTime(at: Long, now: Long = System.currentTimeMillis(), short: Boolean = false): String {
    val min = (now - at) / 60_000
    return when {
        min < 1 -> tr("à l'instant")
        min < 60 -> if (short) tr("%1\$s min", min) else tr("il y a %1\$s min", min)
        min < 60 * 24 -> if (short) tr("%1\$s h", min / 60) else tr("il y a %1\$s h", min / 60)
        min < 60 * 24 * 2 -> tr("hier")
        min < 60 * 24 * 7 -> tr("%1\$s j", min / (60 * 24))
        min < 60 * 24 * 30 -> tr("%1\$s sem", min / (60 * 24 * 7))
        else -> tr("%1\$s mois", min / (60 * 24 * 30))
    }
}

/** The group shown: a drop-down with « Tous » first, then each group with its icon (or colour) and its number of hosts. */
@Composable
private fun GroupSelector(
    options: List<Triple<Long?, String, Int>>,
    groupOf: (Long) -> HostGroup?,
    selected: Long?,
    onSelect: (Long?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val current = options.firstOrNull { it.first == selected } ?: options.first()
    val group = current.first?.let(groupOf)
    val tint = group?.let { Color(it.color) }
    Box(modifier) {
        Surface(
            onClick = { open = true }, shape = RoundedCornerShape(12.dp),
            color = if (tint != null) tint.copy(alpha = 0.12f) else Nt.Surface,
            border = BorderStroke(1.dp, tint?.copy(alpha = 0.6f) ?: Nt.Border),
            modifier = Modifier.fillMaxWidth().height(40.dp).semantics { contentDescription = tr("Groupe affiché : %1\$s", current.second) },
        ) {
            Row(Modifier.padding(start = 12.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OptionMark(current.first, group)
                Text(current.second, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Nt.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                CountBadge(current.third)
                Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = null, tint = Nt.TextSoft, modifier = Modifier.size(20.dp))
            }
        }
        DropdownMenu(
            open, onDismissRequest = { open = false }, containerColor = Nt.Surface,
            shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, Nt.Border),
            modifier = Modifier.widthIn(min = 240.dp),
        ) {
            options.forEachIndexed { i, (key, label, count) ->
                // « Tous » apart from the groups; a line between each group.
                if (i == 1) HorizontalDivider(thickness = 2.dp, color = Nt.Border, modifier = Modifier.padding(vertical = 4.dp))
                else if (i > 1) HorizontalDivider(color = Nt.Border, modifier = Modifier.padding(horizontal = 12.dp))
                val on = key == selected
                DropdownMenuItem(
                    text = { Text(label, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, color = if (on) Nt.Text else Nt.TextSoft, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) { OptionMark(key, key?.let(groupOf)) } },
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CountBadge(count)
                            Icon(Icons.Filled.Check, contentDescription = null, tint = if (on) Nt.Accent else Color.Transparent, modifier = Modifier.size(18.dp))
                        }
                    },
                    modifier = Modifier.background(if (on) Nt.Accent.copy(alpha = 0.08f) else Color.Transparent),
                    onClick = { open = false; onSelect(key) },
                )
            }
        }
    }
}

/** « Tous »: a grid; a group: its icon, else its colour; « Sans groupe »: a grey dot. */
@Composable
private fun OptionMark(key: Long?, group: HostGroup?) = when {
    key == null -> GroupMark("view-grid-outline", Nt.Accent)
    group != null -> GroupMark(group.icon, Color(group.color))
    else -> GroupMark(null, null)
}

@Composable
private fun CountBadge(count: Int) {
    Box(Modifier.defaultMinSize(minWidth = 24.dp).clip(RoundedCornerShape(11.dp)).background(Nt.Bg).padding(horizontal = 7.dp, vertical = 3.dp), contentAlignment = Alignment.Center) {
        Text("$count", style = Centered.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold), color = Nt.TextSoft)
    }
}

/** Choosing a Material Design Icon for a group: a search over the ~6 600 names, suggestions first. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IconPicker(color: Color, current: String?, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    val results = remember(query) { MdiIcons.search(context, query, limit = 120) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Nt.Surface,
        title = { Text(tr("Icône du groupe")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                NtTextField(query, { query = it }, tr("Rechercher"), placeholder = tr("server, home, cloud…"))
                Text(
                    if (query.isBlank()) tr("Suggestions · plus de 6 600 icônes Material Design (mdi:…) en cherchant en anglais")
                    else if (results.isEmpty()) tr("Aucune icône ne correspond")
                    else if (results.size == 1) tr("1 résultat") else tr("%1\$s résultats", results.size),
                    fontSize = 12.sp, color = Nt.Muted,
                )
                // Not lazy: a dialog measures its content by intrinsics, which lazy grids refuse.
                Column(Modifier.fillMaxWidth().height(330.dp).verticalScroll(rememberScrollState())) {
                    FlowRow(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        results.forEach { name ->
                            val glyph = MdiIcons.glyph(context, name) ?: return@forEach
                            val on = name == current
                            Column(
                                Modifier.width(68.dp).clip(RoundedCornerShape(10.dp)).background(if (on) color.copy(alpha = 0.22f) else Nt.Bg)
                                    .clickable(role = Role.Button) { onPick(name) }.padding(vertical = 8.dp, horizontal = 2.dp).semantics { contentDescription = name },
                                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                FontGlyph(glyph, color, Modifier.size(26.dp))
                                Text(name, fontSize = 9.sp, lineHeight = 11.sp, color = Nt.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("Fermer"), color = Nt.TextSoft) } },
    )
}
