package com.nautile.app.ui.hosts

import com.nautile.i18n.tr

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.AddHome
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nautile.app.ui.common.NtCard
import com.nautile.app.ui.common.NtTextField
import com.nautile.app.ui.common.SectionLabel
import com.nautile.app.ui.common.Segmented
import com.nautile.app.ui.theme.Nt
import com.nautile.data.model.AuthMethod
import kotlinx.coroutines.launch
import com.nautile.data.model.Protocol
import com.nautile.data.model.defaultPort
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import com.nautile.data.model.HOST_COLORS

@Composable
fun HostEditScreen(onDone: () -> Unit, onConnect: (Long) -> Unit, vm: HostEditViewModel = viewModel(factory = HostEditViewModel.Factory)) {
    val form by vm.form.collectAsStateWithLifecycle()
    val groups by vm.groups.collectAsStateWithLifecycle()
    val jumpHosts by vm.jumpCandidates.collectAsStateWithLifecycle()
    val keys by vm.keys.collectAsStateWithLifecycle()
    val identities by vm.identities.collectAsStateWithLifecycle()
    var proxyDialog by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val isNew = vm.hostId == 0L

    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); vm.consumeMessage() }
    }
    val keyPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.importKey(it) }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().imePadding()) {
            Row(Modifier.padding(start = 4.dp, end = 8.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("Retour")) }
                Text(
                    if (isNew) tr("Nouvel hôte %1\$s", kindLabel(form.kind)) else tr("Modifier l'hôte"),
                    style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f), maxLines = 1,
                )
                if (!isNew) {
                    val ctx = androidx.compose.ui.platform.LocalContext.current
                    val scope = androidx.compose.runtime.rememberCoroutineScope()
                    IconButton(onClick = {
                        scope.launch {
                            val h = (ctx.applicationContext as com.nautile.app.NautileApp).hostRepository.get(vm.hostId) ?: return@launch
                            if (!com.nautile.app.widget.HostShortcuts.pin(ctx, h)) snackbar.showSnackbar(tr("Le lanceur ne permet pas d'épingler de raccourci"))
                        }
                    }) { Icon(androidx.compose.material.icons.Icons.Outlined.AddHome, contentDescription = tr("Raccourci sur l'écran d'accueil"), tint = Nt.TextSoft) }
                    IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.Delete, contentDescription = tr("Supprimer l'hôte"), tint = Nt.Pink) }
                }
            }

            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                PreviewCard(form, groups.firstOrNull { it.name == form.group.trim() }?.color) { k ->
                    vm.update { f ->
                        val defaults = setOf("", "22", "23", "3389")
                        f.copy(
                            kind = k,
                            port = if (f.port in defaults && k.defaultPort > 0) k.defaultPort.toString() else f.port,
                            auth = if (k == Protocol.RDP && f.auth == AuthMethod.KEY) AuthMethod.PASSWORD else f.auth,
                        )
                    }
                }

                if (form.kind == Protocol.LOCAL) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(tr("Shell du téléphone (/system/bin/sh), sans connexion réseau."), color = Nt.Muted, fontSize = 13.sp)
                    NtTextField(form.alias, { v -> vm.update { it.copy(alias = v) } }, tr("Nom affiché"), placeholder = tr("Ce téléphone"))
                }
                if (form.kind == Protocol.SERIAL) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionLabel(tr("Liaison série"))
                    NtTextField(form.alias, { v -> vm.update { it.copy(alias = v) } }, tr("Nom affiché"), placeholder = tr("Routeur console"))
                    Segmented(
                        listOf("9600", "19200", "38400", "57600", "115200").map { it to it },
                        form.baud.takeIf { it in setOf("9600", "19200", "38400", "57600", "115200") } ?: "", { b -> vm.update { it.copy(baud = b) } },
                    )
                    NtTextField(form.baud, { v -> vm.update { it.copy(baud = v.filter(Char::isDigit).take(7)) } }, tr("Débit (bauds)"), mono = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    Text(tr("Adaptateur USB-OTG (FTDI, CP210x, CH34x, PL2303, CDC) : 8 bits, sans parité, 1 bit de stop."), color = Nt.Muted, fontSize = 12.sp)
                }

                if (form.needsAddress) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionLabel(tr("Adresse"))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        NtTextField(
                            form.hostname,
                            { v ->
                                // Pasting « user@host:port » fills the three fields at once.
                                val parsed = if ('@' in v || v.count { it == ':' } == 1) QuickConnect.parse(v, form.kind) else null
                                vm.update { f ->
                                    if (parsed == null) f.copy(hostname = v.trim())
                                    else f.copy(
                                        hostname = parsed.hostname,
                                        username = parsed.username.ifEmpty { f.username },
                                        port = if (':' in v.substringAfterLast('@').removePrefix("[").substringAfter(']')) parsed.port.toString() else f.port,
                                    )
                                }
                            },
                            tr("Adresse ou nom"), Modifier.weight(3f), mono = true,
                            placeholder = if (form.kind == Protocol.RDP) "pc-bureau.lan" else "192.168.1.20",
                            isError = form.hostname.isBlank() && form.port.isNotEmpty() && form.alias.isNotEmpty(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        )
                        NtTextField(form.port, { v -> vm.update { it.copy(port = v.filter(Char::isDigit).take(5)) } }, tr("Port"), Modifier.weight(1.2f), mono = true,
                            isError = form.portValue == null, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    }
                    if (form.portValue == null) Text(tr("Port entre 1 et 65535"), color = Nt.Pink, fontSize = 12.sp)
                    if (form.kind == Protocol.SSH || form.kind == Protocol.TELNET) {
                        if (form.auth != AuthMethod.IDENTITY) NtTextField(form.username, { v -> vm.update { it.copy(username = v.trim()) } }, tr("Utilisateur"), mono = true,
                            placeholder = if (form.kind == Protocol.SSH) "root" else null)
                    } else if (form.kind == Protocol.RDP && form.auth != AuthMethod.IDENTITY) {
                        NtTextField(form.username, { v -> vm.update { it.copy(username = v.trim()) } }, tr("Utilisateur Windows"), mono = true, placeholder = "Administrateur")
                    }
                    NtTextField(form.alias, { v -> vm.update { it.copy(alias = v) } }, tr("Nom affiché (facultatif)"),
                        placeholder = form.hostname.ifBlank { "docker-01" })
                    Text(tr("Astuce : collez « user@adresse:port » dans Adresse pour tout remplir."), color = Nt.Muted, fontSize = 12.sp)
                }

                if (form.kind == Protocol.RDP) RdpSection(form, identities, jumpHosts.map { it.id to it.alias }, vm::update, onProxy = { proxyDialog = true })

                if (form.kind == Protocol.SSH) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionLabel(tr("Authentification"))
                    Segmented(
                        options = listOf(AuthMethod.PASSWORD to tr("Mot de passe"), AuthMethod.KEY to tr("Clé"), AuthMethod.IDENTITY to tr("Identité")),
                        selected = form.auth,
                        onSelect = { a -> vm.update { it.copy(auth = a) } },
                    )
                    when (form.auth) {
                        AuthMethod.PASSWORD -> NtTextField(form.password, { v -> vm.update { it.copy(password = v) } }, tr("Mot de passe (demandé à la connexion si vide)"),
                            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                        AuthMethod.KEY -> {
                            NtCard {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.weight(1f)) { com.nautile.app.ui.keys.KeyPicker(keys, form.keyId, { id -> vm.update { it.copy(keyId = id) } }) }
                                    androidx.compose.material3.TextButton(onClick = { keyPicker.launch(arrayOf("*/*")) }) { Text(tr("Importer…"), color = Nt.Accent) }
                                }
                            }
                            if (keys.isEmpty()) Text(tr("Trousseau vide : générez une clé dans le Trousseau (icône clé des Hôtes) ou importez un fichier."), color = Nt.Muted, fontSize = 12.sp)
                        }
                        AuthMethod.IDENTITY -> NtCard { IdentityPicker(identities, form.identityId) { id -> vm.update { it.copy(identityId = id) } } }
                        AuthMethod.AGENT -> {}
                    }
                    if (form.auth != AuthMethod.PASSWORD) NtTextField(
                        form.password, { v -> vm.update { it.copy(password = v) } }, tr("Mot de passe sudo (facultatif)"),
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                    Text(tr("Le bouton clé du terminal le tape pour vous à une invite sudo."), color = Nt.Muted, fontSize = 12.sp)
                }

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionLabel(tr("Organisation"))
                    GroupChips(form.group, groups.map { it.name to it.color }) { g -> vm.update { it.copy(group = g) } }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(tr("Couleur"), color = Nt.TextSoft, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        HOST_COLORS.forEach { c ->
                            val chosen = c == form.color
                            Box(
                                Modifier.size(32.dp).clip(CircleShape).background(Color(c))
                                    .border(3.dp, if (chosen) Nt.Text else Nt.Bg, CircleShape)
                                    .clickable { vm.update { it.copy(color = c) } }
                                    .semantics { contentDescription = tr("Couleur"); selected = chosen },
                            )
                        }
                    }
                    NtTextField(form.tags, { v -> vm.update { it.copy(tags = v) } }, tr("Tags (séparés par des virgules)"), placeholder = "prod, docker")
                }

                if (form.kind == Protocol.SSH || form.kind == Protocol.TELNET) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val summary = listOfNotNull(
                        tr("Mosh").takeIf { form.mosh },
                        jumpHosts.firstOrNull { it.id == form.jumpHostId }?.let { tr("rebond via %1\$s", it.alias) },
                        form.proxyType?.let { tr("proxy %1\$s", it.name) },
                        tr("transfert d'agent").takeIf { form.agentForwarding },
                        tr("port knocking").takeIf { form.knock.isNotBlank() },
                        tr("commande au démarrage").takeIf { form.startupCommand.isNotBlank() },
                    )
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { advanced = !advanced }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            SectionLabel(tr("Options avancées"))
                            Text(if (summary.isEmpty()) tr("Aucune — connexion directe") else summary.joinToString(" · "), fontSize = 12.sp, color = Nt.Muted)
                        }
                        Icon(if (advanced) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = if (advanced) tr("Masquer") else tr("Afficher"), tint = Nt.Muted)
                    }
                    if (advanced) NtCard {
                        if (form.kind == Protocol.SSH) {
                            JumpHostRow(form.jumpHostId, jumpHosts.map { it.id to it.alias }) { id -> vm.update { it.copy(jumpHostId = id) } }
                            HorizontalDivider(color = Nt.Border)
                            Column(Modifier.fillMaxWidth().clickable { proxyDialog = true }.padding(horizontal = 14.dp, vertical = 12.dp)) {
                                Text(tr("Proxy"), style = MaterialTheme.typography.titleMedium, fontSize = 14.sp)
                                Text(
                                    form.proxyType?.let { "${it.name} ${form.proxyHost}" + (form.proxyPort.takeIf { p -> p.isNotEmpty() }?.let { p -> ":$p" } ?: "") }
                                        ?: tr("Aucun — connexion directe"),
                                    fontSize = 12.sp, color = Nt.Muted,
                                )
                            }
                            HorizontalDivider(color = Nt.Border)
                            SwitchRow(tr("Mosh"), tr("Session résistante aux coupures réseau (mosh-server requis sur le serveur)"), form.mosh) { v -> vm.update { it.copy(mosh = v) } }
                            HorizontalDivider(color = Nt.Border)
                            SwitchRow(tr("Transfert d'agent"), tr("Le serveur peut utiliser vos clés pour rebondir (ssh -A)"), form.agentForwarding) { v -> vm.update { it.copy(agentForwarding = v) } }
                            HorizontalDivider(color = Nt.Border)
                        }
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            NtTextField(form.knock, { v -> vm.update { it.copy(knock = v) } }, tr("Port knocking (facultatif)"), mono = true,
                                placeholder = "7000,8000,u:9000", isError = !form.knockValid)
                            if (form.kind == Protocol.SSH) NtTextField(form.startupCommand, { v -> vm.update { it.copy(startupCommand = v) } }, tr("Commande au démarrage"), mono = true,
                                placeholder = "tmux new -A -s main")
                        }
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().background(Nt.Bar).padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 22.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = { vm.save { onDone() } },
                    enabled = form.isValid,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f).height(52.dp),
                ) { Text(tr("Enregistrer"), fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Nt.Text) }
                Button(
                    onClick = { vm.save(onConnect) },
                    enabled = form.isValid,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Nt.PrimaryContainer, contentColor = Nt.OnPrimaryContainer),
                    border = Nt.PrimaryBorder?.let { androidx.compose.foundation.BorderStroke(2.dp, it) },
                    modifier = Modifier.weight(1f).height(52.dp),
                ) { Text(tr("Connecter"), fontWeight = FontWeight.SemiBold, fontSize = 15.sp) }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp))
    }
    if (proxyDialog) ProxyDialog(form, onDismiss = { proxyDialog = false }) { updated -> vm.update { updated }; proxyDialog = false }
    if (confirmDelete) androidx.compose.material3.AlertDialog(
        onDismissRequest = { confirmDelete = false }, containerColor = Nt.Surface,
        title = { Text(tr("Supprimer %1\$s ?", form.alias.ifBlank { form.hostname })) },
        text = { Text(tr("L'hôte et ses tunnels sont supprimés. Les clés du trousseau sont gardées."), color = Nt.TextSoft) },
        dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmDelete = false }) { Text(tr("Annuler"), color = Nt.TextSoft) } },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { confirmDelete = false; vm.delete(onDone) }) { Text(tr("Supprimer"), color = Nt.Pink, fontWeight = FontWeight.SemiBold) } },
    )
}

private fun kindLabel(k: Protocol) = when (k) {
    Protocol.SSH, Protocol.MOSH -> "SSH"; Protocol.RDP -> "RDP"; Protocol.TELNET -> "Telnet"; Protocol.SERIAL -> tr("série"); Protocol.LOCAL -> tr("local")
}

/** What the host will look like in the list, with its type (tap to change it). */
@Composable
private fun PreviewCard(form: HostForm, groupColor: Long?, onKind: (Protocol) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val title = form.alias.ifBlank { form.hostname }.ifBlank {
        when (form.kind) { Protocol.LOCAL -> tr("Ce téléphone"); Protocol.SERIAL -> tr("Port série"); else -> tr("Nouvel hôte") }
    }
    val address = when (form.kind) {
        Protocol.LOCAL -> tr("ce téléphone")
        Protocol.SERIAL -> tr("USB série · %1\$s bauds", form.baud.ifBlank { "115200" })
        else -> buildString {
            if (form.username.isNotBlank() && form.auth != AuthMethod.IDENTITY) append(form.username).append('@')
            append(form.hostname.ifBlank { "…" })
            if (form.portValue != null && form.portValue != form.kind.defaultPort) append(':').append(form.port)
        }
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Nt.Surface).border(1.dp, Nt.Border, RoundedCornerShape(16.dp)).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        com.nautile.app.ui.common.HostAvatar(if (form.kind == Protocol.RDP) "windows" else null, title.take(1).uppercase(), Color(form.color))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            Text(address, fontFamily = Nt.Mono, fontSize = 12.sp, color = Nt.Muted, maxLines = 1)
            if (form.group.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(groupColor?.let { Color(it) } ?: Nt.Border))
                Text(form.group, fontSize = 11.sp, color = Nt.Muted)
            }
        }
        Box {
            androidx.compose.material3.Surface(
                onClick = { menu = true }, shape = RoundedCornerShape(10.dp), color = Nt.KeyBg,
                modifier = Modifier.semantics { contentDescription = tr("Type d'hôte : %1\$s", kindLabel(form.kind)) },
            ) {
                Row(Modifier.padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (form.kind == Protocol.SSH && form.mosh) "Mosh" else kindLabel(form.kind), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Icon(Icons.Outlined.ExpandMore, contentDescription = null, tint = Nt.Muted, modifier = Modifier.size(18.dp))
                }
            }
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                listOf(Protocol.SSH to "SSH", Protocol.RDP to tr("Bureau à distance (RDP)"), Protocol.TELNET to "Telnet", Protocol.SERIAL to tr("Série USB"), Protocol.LOCAL to tr("Terminal local"))
                    .forEach { (k, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { menu = false; onKind(k) }) }
            }
        }
    }
}

/** Existing groups as chips, « Sans groupe », and a field for a new one. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun GroupChips(value: String, groups: List<Pair<String, Long>>, onChange: (String) -> Unit) {
    var creating by remember { mutableStateOf(value.isNotBlank() && groups.none { it.first == value }) }
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GroupChip(tr("Sans groupe"), null, value.isBlank() && !creating) { creating = false; onChange("") }
        groups.forEach { (name, color) -> GroupChip(name, color, value == name && !creating) { creating = false; onChange(name) } }
        GroupChip(tr("+ Nouveau"), null, creating) { creating = true; onChange("") }
    }
    if (creating) NtTextField(value, onChange, tr("Nom du nouveau groupe"), placeholder = tr("Homelab"))
}

@Composable
private fun GroupChip(label: String, color: Long?, selected: Boolean, onClick: () -> Unit) {
    androidx.compose.material3.Surface(
        onClick = onClick, shape = RoundedCornerShape(18.dp),
        color = if (selected) Nt.Accent.copy(alpha = 0.12f) else Nt.Surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, if (selected) Nt.Accent else Nt.Border),
        modifier = Modifier.height(36.dp).semantics { this.selected = selected },
    ) {
        Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            color?.let { Box(Modifier.size(8.dp).clip(CircleShape).background(Color(it))) }
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (selected) Nt.Accent else Nt.TextSoft)
        }
    }
}

/** Windows logon and display options of an RDP host. */
@Composable
private fun RdpSection(
    form: HostForm,
    identities: List<com.nautile.data.model.Identity>,
    jumpHosts: List<Pair<Long, String>>,
    update: ((HostForm) -> HostForm) -> Unit,
    onProxy: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel(tr("Session Windows"))
        Segmented(
            options = listOf(AuthMethod.PASSWORD to tr("Mot de passe"), AuthMethod.IDENTITY to tr("Identité")),
            selected = if (form.auth == AuthMethod.IDENTITY) AuthMethod.IDENTITY else AuthMethod.PASSWORD,
            onSelect = { a -> update { it.copy(auth = a) } },
        )
        if (form.auth == AuthMethod.IDENTITY) NtCard { IdentityPicker(identities, form.identityId) { id -> update { it.copy(identityId = id) } } }
        else NtTextField(form.password, { v -> update { it.copy(password = v) } }, tr("Mot de passe (demandé à la connexion si vide)"),
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        NtTextField(form.rdpDomain, { v -> update { it.copy(rdpDomain = v) } }, tr("Domaine (optionnel)"), mono = true, placeholder = "CORP")

        SectionLabel(tr("Affichage"))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ChoiceField(
                tr("Résolution"), form.rdpResolution,
                listOf("" to tr("Taille de l'écran"), "1280x720" to "1280 × 720", "1600x900" to "1600 × 900", "1920x1080" to "1920 × 1080", "2560x1440" to "2560 × 1440"),
                Modifier.weight(1f), isError = !form.rdpResolutionValid,
            ) { v -> update { it.copy(rdpResolution = v) } }
            ChoiceField(
                tr("Échelle (%)"), form.rdpScale,
                listOf("" to tr("Auto")) + listOf(100, 125, 150, 175, 200, 250, 300).map { "$it" to "$it %" },
                Modifier.weight(1f), isError = form.rdpScale.isNotBlank() && form.rdpScaleValue == null,
            ) { v -> update { it.copy(rdpScale = v) } }
        }
        Text(tr("« Taille de l'écran » suit aussi la rotation (Windows 8.1 ou plus récent)."), color = Nt.Muted, fontSize = 12.sp)

        SectionLabel(tr("Sécurité"))
        Segmented(
            listOf<Pair<String?, String>>(null to tr("Auto"), "NLA" to "NLA", "TLS" to "TLS", "RDP" to "RDP"),
            form.rdpSecurity, { v -> update { it.copy(rdpSecurity = v) } },
        )
        Text(tr("Auto négocie avec le serveur ; NLA vérifie le mot de passe avant d'ouvrir la session."), color = Nt.Muted, fontSize = 12.sp)

        NtCard {
            JumpHostRow(form.jumpHostId, jumpHosts, tunnel = true) { id -> update { it.copy(jumpHostId = id) } }
            HorizontalDivider(color = Nt.Border)
            Column(Modifier.fillMaxWidth().clickable(enabled = form.jumpHostId == null) { onProxy() }.padding(horizontal = 14.dp, vertical = 12.dp)) {
                Text(tr("Proxy"), style = MaterialTheme.typography.titleMedium, fontSize = 14.sp)
                Text(
                    when {
                        form.jumpHostId != null -> tr("Sans objet avec un tunnel SSH")
                        else -> form.proxyType?.let { "${it.name} ${form.proxyHost}" + (form.proxyPort.takeIf { p -> p.isNotEmpty() }?.let { p -> ":$p" } ?: "") }
                            ?: tr("Aucun — connexion directe")
                    },
                    fontSize = 12.sp, color = Nt.Muted,
                )
            }
            HorizontalDivider(color = Nt.Border)
            SwitchRow(tr("Son"), tr("Joue le son de Windows sur le téléphone"), form.rdpAudio) { v -> update { it.copy(rdpAudio = v) } }
            HorizontalDivider(color = Nt.Border)
            SwitchRow(tr("Session console"), tr("Ouvre la session console du serveur (mstsc /admin)"), form.rdpConsole) { v -> update { it.copy(rdpConsole = v) } }
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontSize = 14.sp)
            Text(subtitle, fontSize = 12.sp, color = Nt.Muted)
        }
        Switch(checked, onChange, colors = SwitchDefaults.colors(checkedTrackColor = Nt.Accent, uncheckedTrackColor = Nt.Toggle))
    }
}

/** A text field with suggested values: type your own or pick one with the arrow. Empty shows the first choice's label. */
@Composable
private fun ChoiceField(label: String, value: String, choices: List<Pair<String, String>>, modifier: Modifier, isError: Boolean, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        NtTextField(value, onChange, label, mono = true, isError = isError, placeholder = choices.firstOrNull { it.first.isEmpty() }?.second)
        IconButton(onClick = { open = true }, modifier = Modifier.align(Alignment.CenterEnd).padding(top = 6.dp)) {
            Icon(androidx.compose.material.icons.Icons.Filled.ArrowDropDown, contentDescription = tr("Choisir : %1\$s", label), tint = Nt.Muted)
        }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            choices.forEach { (v, text) -> DropdownMenuItem(text = { Text(text) }, onClick = { onChange(v); open = false }) }
        }
    }
}

@Composable
private fun IdentityPicker(identities: List<com.nautile.data.model.Identity>, selectedId: Long?, onSelect: (Long?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val sel = identities.firstOrNull { it.id == selectedId }
    Box {
        Column(Modifier.fillMaxWidth().clickable { open = true }.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(tr("Identité"), color = Nt.Muted, fontSize = 12.sp)
            Text(sel?.let { "${it.name} (${it.username})" } ?: if (identities.isEmpty()) tr("Créez-en une dans Clés › Identités") else tr("Choisir…"),
                fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            identities.forEach { id -> DropdownMenuItem(text = { Text("${id.name} (${id.username})") }, onClick = { onSelect(id.id); open = false }) }
        }
    }
}

@Composable
private fun ProxyDialog(form: HostForm, onDismiss: () -> Unit, onSave: (HostForm) -> Unit) {
    var f by remember { mutableStateOf(form) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Nt.Surface,
        title = { Text(tr("Proxy")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Segmented(
                    listOf<Pair<com.nautile.data.model.ProxyKind?, String>>(null to tr("Aucun"), com.nautile.data.model.ProxyKind.SOCKS5 to "SOCKS5", com.nautile.data.model.ProxyKind.HTTP to "HTTP"),
                    f.proxyType, { f = f.copy(proxyType = it) },
                )
                if (f.proxyType != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        NtTextField(f.proxyHost, { f = f.copy(proxyHost = it) }, tr("Adresse"), Modifier.weight(3f), mono = true)
                        NtTextField(f.proxyPort, { v -> f = f.copy(proxyPort = v.filter(Char::isDigit).take(5)) }, tr("Port"), Modifier.weight(1.4f), mono = true,
                            placeholder = if (f.proxyType == com.nautile.data.model.ProxyKind.HTTP) "8080" else "1080",
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    }
                    NtTextField(f.proxyUsername, { f = f.copy(proxyUsername = it) }, tr("Utilisateur (optionnel)"))
                    NtTextField(f.proxyPassword, { f = f.copy(proxyPassword = it) }, tr("Mot de passe (optionnel)"), visualTransformation = PasswordVisualTransformation())
                    Text(tr("Le nom du serveur est résolu par le proxy. Avec un hôte de rebond, le proxy de l'hôte de rebond s'applique."), color = Nt.Muted, fontSize = 12.sp)
                }
            }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text(tr("Annuler"), color = Nt.TextSoft) } },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { onSave(f) }, enabled = f.proxyType == null || f.proxyHost.isNotBlank()) {
                Text(tr("Valider"), color = Nt.Accent, fontWeight = FontWeight.SemiBold)
            }
        },
    )
}

@Composable
private fun JumpHostRow(selectedId: Long?, candidates: List<Pair<Long, String>>, tunnel: Boolean = false, onSelect: (Long?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val name = candidates.firstOrNull { it.first == selectedId }?.second
    Box {
        Column(Modifier.fillMaxWidth().clickable(enabled = candidates.isNotEmpty()) { open = true }.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(if (tunnel) tr("Tunnel SSH") else tr("Hôte de rebond"), style = MaterialTheme.typography.titleMedium, fontSize = 14.sp)
            Text(
                when {
                    name != null && tunnel -> tr("Via %1\$s (redirection de port)", name)
                    name != null -> "ProxyJump via $name"
                    candidates.isEmpty() -> tr("Aucun autre hôte SSH disponible")
                    else -> tr("Aucun — connexion directe")
                },
                fontSize = 12.sp, color = Nt.Muted,
            )
        }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(tr("Aucun")) }, onClick = { onSelect(null); open = false })
            candidates.forEach { (id, alias) -> DropdownMenuItem(text = { Text(alias) }, onClick = { onSelect(id); open = false }) }
        }
    }
}
