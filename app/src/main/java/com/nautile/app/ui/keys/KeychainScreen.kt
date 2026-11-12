package com.nautile.app.ui.keys

import com.nautile.i18n.tr

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nautile.app.ui.common.NtCard
import com.nautile.app.ui.common.Segmented
import com.nautile.app.ui.theme.Nt
import com.nautile.app.ui.common.primaryBorder
import com.nautile.data.model.Identity
import com.nautile.data.model.SshKey

private enum class KTab(val label: String) { Keys(tr("Clés")), Identities(tr("Identités")), Known(tr("Hôtes connus")) }

@Composable
fun KeychainScreen(onBack: () -> Unit, vm: KeychainViewModel = viewModel(factory = KeychainViewModel.Factory)) {
    val context = LocalContext.current
    val keys by vm.keys.collectAsStateWithLifecycle()
    val usage by vm.usage.collectAsStateWithLifecycle()
    val identities by vm.identities.collectAsStateWithLifecycle()
    val known by vm.knownHosts.collectAsStateWithLifecycle()
    val generating by vm.generating.collectAsStateWithLifecycle()
    val draft by vm.draft.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableStateOf(KTab.Keys) }
    val appForTab = androidx.compose.ui.platform.LocalContext.current.applicationContext as com.nautile.app.NautileApp
    val showKnown by appForTab.showKnownHosts.collectAsState()
    androidx.compose.runtime.LaunchedEffect(showKnown) { if (showKnown) { tab = KTab.Known; appForTab.showKnownHosts.value = false } }
    var showGenerate by remember { mutableStateOf(false) }
    var importMenu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<SshKey?>(null) }
    var deleting by remember { mutableStateOf<SshKey?>(null) }
    var exporting by remember { mutableStateOf<SshKey?>(null) }
    var exportPass by remember { mutableStateOf<String?>(null) }
    var editingIdentity by remember { mutableStateOf<Identity?>(null) }
    var creatingIdentity by remember { mutableStateOf(false) }

    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }

    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importFile) }
    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val k = exporting
        if (uri != null && k != null) vm.exportPrivate(k, exportPass, uri)
        exporting = null; exportPass = null
    }
    var askExport by remember { mutableStateOf<SshKey?>(null) }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                com.nautile.app.ui.common.PageHeader(tr("Trousseau"), onBack = onBack) {
                    Box {
                        OutlinedButton(onClick = { importMenu = true }, shape = RoundedCornerShape(12.dp)) { Text(tr("Importer"), color = Nt.TextSoft, fontSize = 13.sp) }
                        DropdownMenu(importMenu, onDismissRequest = { importMenu = false }) {
                            DropdownMenuItem(text = { Text(tr("Depuis un fichier")) }, onClick = { importMenu = false; openFile.launch(arrayOf("*/*")) })
                            DropdownMenuItem(text = { Text(tr("Coller le texte")) }, onClick = { importMenu = false; vm.startPaste() })
                        }
                    }
                }
            }
            item { Segmented(KTab.entries.map { it to it.label }, tab, { tab = it }) }

            when (tab) {
                KTab.Keys -> {
                    item {
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Nt.Green.copy(alpha = 0.08f))
                                .border(1.dp, Nt.Green.copy(alpha = 0.28f), RoundedCornerShape(14.dp)).padding(14.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Outlined.Lock, contentDescription = null, tint = Nt.Green)
                            Text(
                                tr("Clés privées stockées dans la base chiffrée, dont la clé est protégée par l'Android Keystore. Elles ne quittent l'appareil que si vous les exportez."),
                                fontSize = 13.sp, color = Nt.TextSoft,
                            )
                        }
                    }
                    if (keys.isEmpty()) item { Empty(tr("Aucune clé. Générez-en une ou importez votre clé existante.")) }
                    items(keys, key = { it.id }) { k ->
                        KeyCard(
                            key = k, used = usage[k.id] ?: 0,
                            onCopy = {
                                context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(tr("Clé publique"), k.publicKey))
                            },
                            onShare = {
                                context.startActivity(Intent.createChooser(
                                    Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, k.publicKey), tr("Partager la clé publique"),
                                ))
                            },
                            onExport = { askExport = k },
                            onRename = { renaming = k },
                            onForget = { vm.forgetPassphrase(k) },
                            onDelete = { deleting = k },
                        )
                    }
                }
                KTab.Identities -> {
                    item { Text(tr("Une identité regroupe un utilisateur et ses identifiants, réutilisables sur plusieurs hôtes."), color = Nt.Muted, fontSize = 13.sp) }
                    if (identities.isEmpty()) item { Empty(tr("Aucune identité.")) }
                    else item {
                        NtCard {
                            identities.forEachIndexed { i, id ->
                                if (i > 0) HorizontalDivider(color = Nt.Border)
                                Row(
                                    Modifier.fillMaxWidth().clickable { editingIdentity = id }.padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Icon(Icons.Outlined.Person, contentDescription = null, tint = Nt.Blue)
                                    Column(Modifier.weight(1f)) {
                                        Text(id.name, fontWeight = FontWeight.SemiBold)
                                        val keyName = keys.firstOrNull { it.id == id.keyId }?.name
                                        Text(
                                            id.username + (keyName?.let { tr(" · clé %1\$s", it) } ?: if (id.password != null) tr(" · mot de passe") else ""),
                                            fontFamily = Nt.Mono, fontSize = 12.sp, color = Nt.Muted,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                KTab.Known -> {
                    item { Text(tr("Empreintes enregistrées à la première connexion. Supprimez-en une si un serveur a été réinstallé."), color = Nt.Muted, fontSize = 13.sp) }
                    if (known.isEmpty()) item { Empty(tr("Aucun hôte connu pour l'instant.")) }
                    else item {
                        NtCard {
                            known.forEachIndexed { i, k ->
                                if (i > 0) HorizontalDivider(color = Nt.Border)
                                Row(Modifier.padding(start = 14.dp, end = 2.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(if (k.port == 22) k.host else "${k.host}:${k.port}", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                        Text("${k.keyType} · ${k.fingerprint}", fontFamily = Nt.Mono, fontSize = 11.sp, color = Nt.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    IconButton(onClick = { vm.forgetHost(k) }) {
                                        Icon(Icons.Outlined.Delete, contentDescription = tr("Oublier la clé de %1\$s", k.host), tint = Nt.Muted)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (tab != KTab.Known) ExtendedFloatingActionButton(
            onClick = { if (tab == KTab.Keys) showGenerate = true else creatingIdentity = true },
            containerColor = Nt.PrimaryContainer, contentColor = Nt.OnPrimaryContainer, shape = RoundedCornerShape(18.dp),
            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
            text = { Text(if (tab == KTab.Keys) tr("Générer une clé") else tr("Nouvelle identité"), fontWeight = FontWeight.SemiBold) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp).primaryBorder(RoundedCornerShape(18.dp)),
        )
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 80.dp))
    }

    if (showGenerate) GenerateKeyDialog(generating, onDismiss = { showGenerate = false }) { name, algo ->
        vm.generate(name, algo) { showGenerate = false }
    }
    draft?.let { d ->
        androidx.compose.runtime.key(d) {
            ImportKeyDialog(d.text, d.name, d.needsPassphrase, d.error, onDismiss = vm::cancelImport, onImport = vm::submitImport)
        }
    }
    renaming?.let { k -> RenameDialog(tr("Renommer la clé"), k.name, { renaming = null }) { vm.rename(k, it); renaming = null } }
    deleting?.let { k ->
        val n = usage[k.id] ?: 0
        ConfirmDialog(
            tr("Supprimer « %1\$s » ?", k.name),
            (if (n > 0) tr("Elle est utilisée par %1\$s hôte(s), qui ne pourront plus s'y connecter avec. ", n) else "") + tr("Cette action est définitive."),
            tr("Supprimer"), { deleting = null },
        ) { vm.delete(k); deleting = null }
    }
    askExport?.let { k ->
        ExportKeyDialog(k, vm.canReencrypt(k), onDismiss = { askExport = null }) { pass ->
            exporting = k; exportPass = pass; askExport = null
            saveFile.launch(k.name.replace(Regex("[^A-Za-z0-9._-]+"), "_"))
        }
    }
    if (creatingIdentity) IdentityDialog(null, keys, { creatingIdentity = false }, { vm.saveIdentity(it); creatingIdentity = false }, null)
    editingIdentity?.let { id ->
        IdentityDialog(id, keys, { editingIdentity = null }, { vm.saveIdentity(it); editingIdentity = null }) {
            vm.deleteIdentity(id); editingIdentity = null
        }
    }
}

@Composable
private fun Empty(text: String) {
    Text(text, color = Nt.Muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 32.dp))
}

@Composable
private fun KeyCard(
    key: SshKey, used: Int,
    onCopy: () -> Unit, onShare: () -> Unit, onExport: () -> Unit, onRename: () -> Unit, onForget: () -> Unit, onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val tint = when {
        key.keyType.contains("ed25519") -> Nt.Accent
        key.keyType.contains("rsa") -> Nt.Blue
        else -> Nt.Green
    }
    NtCard {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)).background(tint.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Key, contentDescription = null, tint = tint)
                }
                Column(Modifier.weight(1f)) {
                    Text(key.name, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${key.description} · ${if (used == 0) tr("inutilisée") else if (used == 1) tr("1 hôte") else tr("%1\$s hôtes", used)}", fontSize = 12.sp, color = Nt.Muted)
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = tr("Options de %1\$s", key.name), tint = Nt.Muted) }
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        if (key.publicKey.isNotEmpty()) {
                            DropdownMenuItem(text = { Text(tr("Copier la clé publique")) }, onClick = { menu = false; onCopy() })
                            DropdownMenuItem(text = { Text(tr("Partager la clé publique")) }, onClick = { menu = false; onShare() })
                        }
                        DropdownMenuItem(text = { Text(tr("Exporter la clé privée…")) }, onClick = { menu = false; onExport() })
                        DropdownMenuItem(text = { Text(tr("Renommer")) }, onClick = { menu = false; onRename() })
                        if (key.savedPassphrase != null) DropdownMenuItem(text = { Text(tr("Oublier la phrase de passe")) }, onClick = { menu = false; onForget() })
                        DropdownMenuItem(text = { Text(tr("Supprimer"), color = Nt.Pink) }, onClick = { menu = false; onDelete() })
                    }
                }
            }
            Text(
                key.fingerprint.takeUnless { it.startsWith("legacy-") } ?: tr("Empreinte inconnue (clé chiffrée importée au jalon 2)"),
                fontFamily = Nt.Mono, fontSize = 11.sp, color = Nt.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(Nt.Bg).padding(horizontal = 10.dp, vertical = 8.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Badge(if (key.generated) tr("Générée ici") else tr("Importée"))
                if (key.encrypted) Badge(if (key.savedPassphrase != null) tr("Phrase de passe mémorisée") else tr("Phrase de passe"))
            }
        }
    }
}

@Composable
private fun Badge(text: String) {
    Box(Modifier.height(24.dp).clip(RoundedCornerShape(12.dp)).background(Nt.KeyBg).padding(horizontal = 9.dp), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Nt.TextSoft)
    }
}
