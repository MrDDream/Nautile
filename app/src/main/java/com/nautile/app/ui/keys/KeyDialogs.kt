package com.nautile.app.ui.keys

import com.nautile.i18n.tr

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.selection.selectable
import com.nautile.app.ui.common.NtTextField
import com.nautile.app.ui.theme.Nt
import com.nautile.data.model.Identity
import com.nautile.data.model.SshKey
import com.nautile.ssh.KeyAlgorithm

@Composable
fun GenerateKeyDialog(busy: Boolean, onDismiss: () -> Unit, onGenerate: (String, KeyAlgorithm) -> Unit) {
    var name by remember { mutableStateOf("id_ed25519 · ${android.os.Build.MODEL}") }
    var algo by remember { mutableStateOf(KeyAlgorithm.ED25519) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = Nt.Surface,
        title = { Text(tr("Générer une clé")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NtTextField(name, { name = it }, "Nom")
                KeyAlgorithm.entries.forEach { a ->
                    Row(
                        Modifier.fillMaxWidth().selectable(a == algo, role = Role.RadioButton) { algo = a },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(a == algo, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = Nt.Accent))
                        Text(a.label + if (a == KeyAlgorithm.ED25519) tr("  (recommandé)") else "", modifier = Modifier.padding(start = 8.dp))
                    }
                }
                if (busy) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(color = Nt.Accent, strokeWidth = 2.dp, modifier = Modifier.heightIn(max = 20.dp))
                    Text(tr("Génération…"), color = Nt.Muted)
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(tr("Annuler"), color = Nt.TextSoft) } },
        confirmButton = {
            TextButton(onClick = { onGenerate(name.trim(), algo) }, enabled = !busy && name.isNotBlank()) {
                Text(tr("Générer"), color = Nt.Accent, fontWeight = FontWeight.SemiBold)
            }
        },
    )
}

@Composable
fun ImportKeyDialog(
    initialText: String,
    initialName: String,
    needsPassphrase: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onImport: (name: String, text: String, passphrase: String?, remember: Boolean) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var text by remember { mutableStateOf(initialText) }
    var pass by remember { mutableStateOf("") }
    var keep by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Nt.Surface,
        title = { Text(tr("Importer une clé")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NtTextField(name, { name = it }, "Nom")
                NtTextField(text, { text = it }, tr("Clé privée (OpenSSH, PEM, PuTTY)"), mono = true, singleLine = false,
                    modifier = Modifier.heightIn(min = 120.dp, max = 220.dp))
                if (needsPassphrase) {
                    NtTextField(pass, { pass = it }, tr("Phrase de passe"), visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                    Row(Modifier.clickable { keep = !keep }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(keep, onCheckedChange = { keep = it }, colors = CheckboxDefaults.colors(checkedColor = Nt.Accent))
                        Text(tr("Mémoriser (sinon demandée à chaque connexion)"), fontSize = 13.sp, color = Nt.TextSoft)
                    }
                }
                error?.let { Text(it, color = Nt.Pink, fontSize = 13.sp) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Annuler"), color = Nt.TextSoft) } },
        confirmButton = {
            TextButton(
                onClick = { onImport(name.trim().ifEmpty { tr("Clé importée") }, text, pass.ifEmpty { null }, keep) },
                enabled = text.isNotBlank() && (!needsPassphrase || pass.isNotEmpty()),
            ) { Text(tr("Importer"), color = Nt.Accent, fontWeight = FontWeight.SemiBold) }
        },
    )
}

@Composable
fun ExportKeyDialog(key: SshKey, canEncrypt: Boolean, onDismiss: () -> Unit, onExport: (String?) -> Unit) {
    var pass by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Nt.Surface,
        title = { Text(tr("Exporter la clé privée")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    tr("Toute personne qui obtient ce fichier peut se connecter à vos serveurs. Protégez-le par une phrase de passe."),
                    color = Nt.TextSoft, fontSize = 13.sp,
                )
                if (canEncrypt) {
                    NtTextField(pass, { pass = it }, tr("Phrase de passe (recommandée)"), visualTransformation = PasswordVisualTransformation())
                    NtTextField(confirm, { confirm = it }, "Confirmer", visualTransformation = PasswordVisualTransformation(), isError = confirm != pass)
                    if (pass.isEmpty()) Text(tr("Sans phrase de passe, le fichier sera en clair."), color = Nt.Pink, fontSize = 12.sp)
                } else Text(tr("Cette clé est chiffrée : elle sera exportée telle quelle, avec sa phrase de passe d'origine."), color = Nt.Muted, fontSize = 13.sp)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Annuler"), color = Nt.TextSoft) } },
        confirmButton = {
            TextButton(onClick = { onExport(pass.ifEmpty { null }) }, enabled = !canEncrypt || pass == confirm) {
                Text(tr("Enregistrer le fichier"), color = Nt.Accent, fontWeight = FontWeight.SemiBold)
            }
        },
    )
}

@Composable
fun RenameDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Nt.Surface,
        title = { Text(title) },
        text = { NtTextField(name, { name = it }, "Nom") },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Annuler"), color = Nt.TextSoft) } },
        confirmButton = { TextButton(onClick = { onSave(name.trim()) }, enabled = name.isNotBlank()) { Text(tr("Enregistrer"), color = Nt.Accent) } },
    )
}

@Composable
fun ConfirmDialog(title: String, message: String, action: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Nt.Surface,
        title = { Text(title) },
        text = { Text(message, color = Nt.TextSoft) },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Annuler"), color = Nt.TextSoft) } },
        confirmButton = { TextButton(onClick = onConfirm) { Text(action, color = Nt.Pink, fontWeight = FontWeight.SemiBold) } },
    )
}

/** Dropdown to pick a key of the keychain (or none). */
@Composable
fun KeyPicker(keys: List<SshKey>, selectedId: Long?, onSelect: (Long?) -> Unit, label: String = tr("Clé")) {
    var open by remember { mutableStateOf(false) }
    val selected = keys.firstOrNull { it.id == selectedId }
    Box {
        Column(
            Modifier.fillMaxWidth().clickable { open = true }.padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Text(label, color = Nt.Muted, fontSize = 12.sp)
            Text(selected?.let { "${it.name} · ${it.description}" } ?: tr("Aucune"), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(tr("Aucune")) }, onClick = { onSelect(null); open = false })
            keys.forEach { k ->
                DropdownMenuItem(text = { Text("${k.name} · ${k.description}") }, onClick = { onSelect(k.id); open = false })
            }
        }
    }
}

@Composable
fun IdentityDialog(initial: Identity?, keys: List<SshKey>, onDismiss: () -> Unit, onSave: (Identity) -> Unit, onDelete: (() -> Unit)?) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var user by remember { mutableStateOf(initial?.username.orEmpty()) }
    var pass by remember { mutableStateOf(initial?.password.orEmpty()) }
    var keyId by remember { mutableStateOf(initial?.keyId) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Nt.Surface,
        title = { Text(if (initial == null) tr("Nouvelle identité") else tr("Modifier l'identité")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NtTextField(name, { name = it }, tr("Nom"), placeholder = "admin homelab")
                NtTextField(user, { user = it }, tr("Utilisateur"), mono = true)
                com.nautile.app.ui.common.NtCard { KeyPicker(keys, keyId, { keyId = it }) }
                NtTextField(pass, { pass = it }, if (keyId == null) tr("Mot de passe (optionnel)") else tr("Mot de passe de secours (optionnel)"),
                    visualTransformation = PasswordVisualTransformation())
                if (onDelete != null) TextButton(onClick = onDelete) { Text(tr("Supprimer l'identité"), color = Nt.Pink) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Annuler"), color = Nt.TextSoft) } },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave((initial ?: Identity(name = "", username = "")).copy(
                        name = name.trim().ifEmpty { user.trim() }, username = user.trim(), password = pass.ifEmpty { null }, keyId = keyId,
                    ))
                },
                enabled = user.isNotBlank(),
            ) { Text(tr("Enregistrer"), color = Nt.Accent, fontWeight = FontWeight.SemiBold) }
        },
    )
}
