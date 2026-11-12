package com.nautile.app.ui.terminal

import com.nautile.i18n.tr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.nautile.app.session.PendingPrompt
import com.nautile.app.ui.common.NtTextField
import com.nautile.app.ui.theme.Nt

/** Questions raised by SSH connections (host key, password, passphrase). */
@Composable
fun PromptDialog(prompt: PendingPrompt) {
    when (prompt) {
        is PendingPrompt.HostKey -> HostKeyDialog(prompt)
        is PendingPrompt.Secret -> SecretDialog(prompt)
        is PendingPrompt.Certificate -> CertificateDialog(prompt)
        is PendingPrompt.Login -> LoginDialog(prompt)
    }
}

@Composable
private fun CertificateDialog(p: PendingPrompt.Certificate) {
    val changed = p.previous != null
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        containerColor = Nt.Surface,
        title = { Text(if (changed) tr("⚠ Le certificat du serveur a changé") else tr("Certificat du serveur"), color = if (changed) Nt.Pink else Nt.Text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    if (changed) tr("Le certificat présenté par %1\$s ne correspond plus à celui enregistré. ", p.address) +
                        tr("Cela peut venir d'un renouvellement du certificat… ou d'une attaque de l'homme du milieu.")
                    else tr("Première connexion à %1\$s (%2\$s). Windows utilise souvent un certificat auto-signé : vérifiez l'empreinte avant de faire confiance.", p.address, p.sessionAlias),
                    color = Nt.TextSoft, fontSize = 14.sp,
                )
                if (p.cert.mismatch) Text(tr("Le nom du certificat ne correspond pas à l'adresse."), color = Nt.Pink, fontSize = 13.sp)
                Text(tr("Nom"), color = Nt.Muted, fontSize = 12.sp)
                Text(p.cert.commonName.ifEmpty { p.cert.subject }, fontSize = 13.sp, color = Nt.Text)
                if (p.cert.issuer.isNotEmpty() && p.cert.issuer != p.cert.subject) {
                    Text(tr("Émetteur"), color = Nt.Muted, fontSize = 12.sp)
                    Text(p.cert.issuer, fontSize = 13.sp, color = Nt.Text)
                }
                Text("SHA-256", color = Nt.Muted, fontSize = 12.sp)
                Text(p.fingerprint, fontFamily = Nt.Mono, fontSize = 12.sp, color = Nt.Text)
                if (changed) {
                    Text(tr("Ancienne empreinte"), color = Nt.Muted, fontSize = 12.sp)
                    Text(p.previous!!, fontFamily = Nt.Mono, fontSize = 12.sp, color = Nt.Muted)
                }
            }
        },
        dismissButton = { TextButton(onClick = { p.answer.complete(false) }) { Text(tr("Refuser"), color = Nt.TextSoft) } },
        confirmButton = {
            TextButton(onClick = { p.answer.complete(true) }) {
                Text(if (changed) tr("Remplacer le certificat") else tr("Faire confiance"), color = if (changed) Nt.Pink else Nt.Accent, fontWeight = FontWeight.SemiBold)
            }
        },
    )
}

@Composable
private fun LoginDialog(p: PendingPrompt.Login) {
    var user by remember(p) { mutableStateOf(p.username) }
    var domain by remember(p) { mutableStateOf(p.domain) }
    var password by remember(p) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { p.answer.complete(null) },
        containerColor = Nt.Surface,
        title = { Text(if (p.gateway) tr("Connexion à la passerelle") else tr("Connexion à Windows")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(p.sessionAlias, fontFamily = Nt.Mono, fontSize = 13.sp, color = Nt.Muted)
                NtTextField(user, { user = it }, tr("Utilisateur"), mono = true)
                NtTextField(domain, { domain = it }, tr("Domaine (optionnel)"), mono = true)
                NtTextField(
                    password, { password = it }, tr("Mot de passe"),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
            }
        },
        dismissButton = { TextButton(onClick = { p.answer.complete(null) }) { Text(tr("Annuler"), color = Nt.TextSoft) } },
        confirmButton = {
            TextButton(onClick = { p.answer.complete(com.nautile.rdp.RdpCredentials(user.trim(), domain.trim(), password)) }, enabled = user.isNotBlank()) {
                Text(tr("Se connecter"), color = Nt.Accent, fontWeight = FontWeight.SemiBold)
            }
        },
    )
}

@Composable
private fun HostKeyDialog(p: PendingPrompt.HostKey) {
    val changed = p.previous != null
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        containerColor = Nt.Surface,
        title = { Text(if (changed) tr("⚠ La clé de l'hôte a changé") else tr("Nouvel hôte"), color = if (changed) Nt.Pink else Nt.Text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (changed) tr("La clé présentée par %1\$s:%2\$s ne correspond plus à celle enregistrée. ", p.info.host, p.info.port) +
                        tr("Cela peut venir d'une réinstallation du serveur… ou d'une attaque de l'homme du milieu.")
                    else tr("Première connexion à %1\$s:%2\$s (%3\$s). Vérifiez l'empreinte avant de faire confiance.", p.info.host, p.info.port, p.sessionAlias),
                    color = Nt.TextSoft, fontSize = 14.sp,
                )
                Text(p.info.keyType, color = Nt.Muted, fontSize = 12.sp)
                Text(p.info.fingerprint, fontFamily = Nt.Mono, fontSize = 12.sp, color = Nt.Text)
                if (changed) {
                    Text(tr("Ancienne empreinte"), color = Nt.Muted, fontSize = 12.sp)
                    Text(p.previous!!.fingerprint, fontFamily = Nt.Mono, fontSize = 12.sp, color = Nt.Muted)
                }
            }
        },
        dismissButton = { TextButton(onClick = { p.answer.complete(false) }) { Text(tr("Refuser"), color = Nt.TextSoft) } },
        confirmButton = {
            TextButton(onClick = { p.answer.complete(true) }) {
                Text(if (changed) tr("Remplacer la clé") else tr("Faire confiance"), color = if (changed) Nt.Pink else Nt.Accent, fontWeight = FontWeight.SemiBold)
            }
        },
    )
}

@Composable
private fun SecretDialog(p: PendingPrompt.Secret) {
    var value by remember(p) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { p.answer.complete(null) },
        containerColor = Nt.Surface,
        title = { Text(p.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(p.label, fontFamily = Nt.Mono, fontSize = 13.sp, color = Nt.Muted)
                NtTextField(
                    value, { value = it }, p.title,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
            }
        },
        dismissButton = { TextButton(onClick = { p.answer.complete(null) }) { Text(tr("Annuler"), color = Nt.TextSoft) } },
        confirmButton = {
            TextButton(onClick = { p.answer.complete(value) }, enabled = value.isNotEmpty()) {
                Text(tr("Se connecter"), color = Nt.Accent, fontWeight = FontWeight.SemiBold)
            }
        },
    )
}
