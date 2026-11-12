package com.nautile.app.ui.settings

import com.nautile.i18n.tr

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nautile.app.NautileApp
import com.nautile.app.ai.AiConfig
import com.nautile.app.ai.AiPrompt
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Edit
import com.nautile.app.ai.AiProviderKind
import com.nautile.app.ui.common.NtCard
import com.nautile.app.ui.common.NtTextField
import com.nautile.app.ui.common.SectionLabel
import com.nautile.app.ui.theme.Nt
import kotlinx.coroutines.launch

/** « Assistant IA »: pick a provider (with its status), set it up, test it and make it the active one. */
@Composable
fun AiSettingsScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as NautileApp
    val ai = app.ai
    val aiLines by app.terminalPrefs.aiLines.collectAsState()
    val scope = rememberCoroutineScope()
    /** Bumped after any save so the badges re-read the stored configuration. */
    var revision by remember { mutableIntStateOf(0) }
    var active by remember { mutableStateOf(ai.provider) }
    var kind by remember { mutableStateOf(ai.provider) }
    val cfg = remember(kind, revision) { ai.config(kind) }
    var key by remember(kind) { mutableStateOf("") }
    var model by remember(kind) { mutableStateOf(cfg.model) }
    var url by remember(kind) { mutableStateOf(cfg.baseUrl.orEmpty()) }
    var models by remember(kind) { mutableStateOf<List<String>>(emptyList()) }
    var modelMenu by remember { mutableStateOf(false) }
    var status by remember(kind) { mutableStateOf<Pair<Boolean, String>?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun current() = AiConfig(kind, key.trim().ifEmpty { cfg.apiKey }, model.trim(), url.trim().ifEmpty { null } ?: kind.defaultBaseUrl)

    SettingsPage(tr("Assistant IA"), onBack) {
        Text(tr("Décrivez ce que vous voulez faire dans le terminal, l'assistant propose la commande. Rien n'est exécuté sans votre accord."),
            color = Nt.TextSoft, fontSize = 13.sp, lineHeight = 19.sp)

        SectionLabel(tr("Fournisseur"), Modifier.padding(top = 4.dp))
        NtCard {
            AiProviderKind.entries.forEachIndexed { i, k ->
                if (i > 0) HorizontalDivider(color = Nt.Border)
                val c = remember(k, revision) { ai.config(k) }
                val ready = remember(k, revision) { ai.isReady(c) }
                val (badge, color) = when {
                    k == active && ready -> tr("Actif") to Nt.Green
                    c.apiKey != null -> tr("Clé enregistrée") to Nt.TextSoft
                    !k.needsKey -> tr("Sans clé") to Nt.TextSoft
                    else -> tr("À configurer") to Nt.Accent
                }
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = Role.RadioButton) { kind = k }
                        .background(if (k == kind) Nt.Accent.copy(alpha = 0.06f) else Color.Transparent).padding(end = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(k == kind, onClick = null, modifier = Modifier.padding(start = 12.dp, end = 10.dp), colors = RadioButtonDefaults.colors(selectedColor = Nt.Accent))
                    Box(Modifier.padding(end = 12.dp)) { com.nautile.app.ui.common.ProviderLogo(k, 22.dp) }
                    Text(k.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Box(Modifier.height(24.dp).clip(RoundedCornerShape(12.dp)).background(color.copy(alpha = 0.13f)).padding(horizontal = 9.dp), contentAlignment = Alignment.Center) {
                        Text(badge, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        NtCard {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    com.nautile.app.ui.common.ProviderLogo(kind, 22.dp)
                    Text(kind.label, style = MaterialTheme.typography.titleMedium)
                }
                if (kind.editableUrl) NtTextField(url, { url = it }, tr("Adresse de l'API"), mono = true, placeholder = kind.defaultBaseUrl ?: "https://…/v1")
                if (kind == AiProviderKind.LOCAL)
                    Hint(tr("Ollama : http://IP-du-PC:11434/v1 (lancer avec OLLAMA_HOST=0.0.0.0). LM Studio : http://IP-du-PC:1234/v1."))
                NtTextField(
                    key, { key = it },
                    when {
                        cfg.apiKey != null -> tr("Clé API (enregistrée — saisir pour remplacer)")
                        kind.needsKey -> tr("Clé API")
                        else -> tr("Clé API (facultative)")
                    },
                    mono = true, placeholder = kind.keyHint, visualTransformation = PasswordVisualTransformation(),
                )
                Box {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NtTextField(model, { model = it }, tr("Modèle"), Modifier.weight(1f), mono = true)
                        TextButton(enabled = !busy, onClick = {
                            busy = true; status = null
                            scope.launch {
                                runCatching { ai.models(current()) }
                                    .onSuccess { models = it; modelMenu = it.isNotEmpty(); if (it.isEmpty()) status = false to tr("Aucun modèle renvoyé") }
                                    .onFailure { status = false to (it.message ?: tr("Échec")) }
                                busy = false
                            }
                        }) { Text(tr("Liste"), color = Nt.Accent) }
                    }
                    DropdownMenu(modelMenu, { modelMenu = false }, modifier = Modifier.heightIn(max = 360.dp)) {
                        models.forEach { m -> DropdownMenuItem(text = { Text(m, fontFamily = Nt.Mono, fontSize = 13.sp) }, onClick = { model = m; modelMenu = false }) }
                    }
                }
                Button(
                    onClick = {
                        busy = true; status = null
                        scope.launch {
                            val c = current()
                            ai.save(kind, key.trim().ifEmpty { null }, model, url)
                            key = ""
                            status = runCatching { ai.test(c) }.fold(
                                onSuccess = { answer ->
                                    ai.provider = kind; active = kind
                                    true to tr("Connexion OK · %1\$s est le fournisseur actif. Réponse : %2\$s", kind.label, answer.take(80))
                                },
                                onFailure = { false to tr("Échec : %1\$s (réglages enregistrés, fournisseur actif inchangé)", it.message ?: "") },
                            )
                            revision++
                            busy = false
                        }
                    },
                    enabled = !busy && model.isNotBlank(), modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Nt.PrimaryContainer, contentColor = Nt.OnPrimaryContainer),
                    border = Nt.PrimaryBorder?.let { BorderStroke(2.dp, it) },
                ) { Text(if (kind == active) tr("Tester") else tr("Tester et utiliser"), fontWeight = FontWeight.SemiBold) }
                if (busy) LinearProgressIndicator(color = Nt.Accent, trackColor = Nt.Border, modifier = Modifier.fillMaxWidth())
                status?.let { (ok, text) -> Text(text, color = if (ok) Nt.Green else Nt.Pink, fontSize = 13.sp) }
            }
        }

        PromptsSection(ai)

        SectionLabel(tr("Ce qui est envoyé"), Modifier.padding(top = 4.dp))
        NtCard {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("✓ Votre demande, l'adresse et le protocole de l'hôte"), fontSize = 13.sp, color = Nt.TextSoft)
                Text(tr("✓ Les %1\$s dernières lignes du terminal, seulement si « Joindre l'écran » est coché", aiLines), fontSize = 13.sp, color = Nt.TextSoft)
                Text(tr("✕ Jamais vos mots de passe, vos clés ni votre historique"), fontSize = 13.sp, color = Nt.TextSoft)
            }
        }

        SectionLabel(tr("Lignes du terminal jointes"), Modifier.padding(top = 4.dp))
        com.nautile.app.ui.common.Segmented(
            com.nautile.app.session.TerminalPrefs.AI_LINE_CHOICES.map { it to "$it" }, aiLines, app.terminalPrefs::setAiLines,
        )
        Hint(tr("Plus de lignes donnent plus de contexte pour expliquer une erreur, mais la requête est plus longue et plus coûteuse. L'historique de défilement est inclus."))
        Hint(tr("Les clés API restent chiffrées sur l'appareil (Android Keystore)."))
        if (cfg.apiKey != null) OutlinedButton(
            onClick = { ai.forgetKey(kind); revision++ }, modifier = Modifier.fillMaxWidth(),
            border = BorderStroke(1.dp, Nt.Pink.copy(alpha = 0.5f)),
        ) { Text(tr("Oublier la clé %1\$s", kind.label), color = Nt.Pink) }
    }
}

/** The system prompts: the built-in one (read-only, can be copied) and the user's own. */
@Composable
private fun PromptsSection(ai: com.nautile.app.ai.AiAssistant) {
    val prompts by ai.prompts.collectAsState()
    val selected by ai.promptId.collectAsState()
    val used = if (prompts.any { it.id == selected }) selected else com.nautile.app.ai.AiAssistant.DEFAULT_ID
    var editing by remember { mutableStateOf<AiPrompt?>(null) }
    var showDefault by remember { mutableStateOf(false) }

    SectionLabel(tr("Prompts système"), Modifier.padding(top = 4.dp))
    NtCard {
        PromptRow(tr("Par défaut"), tr("Une commande prête à coller, puis une courte explication"), used == com.nautile.app.ai.AiAssistant.DEFAULT_ID,
            onSelect = { ai.selectPrompt(com.nautile.app.ai.AiAssistant.DEFAULT_ID) }, onOpen = { showDefault = true })
        prompts.forEach { p ->
            HorizontalDivider(color = Nt.Border)
            PromptRow(p.name, p.text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty(), used == p.id,
                onSelect = { ai.selectPrompt(p.id) }, onOpen = { editing = p })
        }
        HorizontalDivider(color = Nt.Border)
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = Role.Button) {
                editing = AiPrompt(java.util.UUID.randomUUID().toString(), "", com.nautile.app.ai.AiAssistant.defaultPrompt)
            }.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Outlined.Add, contentDescription = null, tint = Nt.Accent)
            Text(tr("Nouveau prompt"), color = Nt.Accent, fontWeight = FontWeight.SemiBold)
        }
    }
    Hint(tr("Le prompt système donne ses consignes à l'IA (ton, langue, niveau de détail, règles de sécurité…). Vous le choisissez dans le terminal, au-dessus de la demande. Pour garder les boutons Insérer et Exécuter, demandez la commande dans un bloc ```bash```."))

    if (showDefault) androidx.compose.material3.AlertDialog(
        onDismissRequest = { showDefault = false },
        containerColor = Nt.Surface,
        title = { Text(tr("Prompt par défaut")) },
        text = {
            Text(com.nautile.app.ai.AiAssistant.defaultPrompt, fontFamily = Nt.Mono, fontSize = 12.sp, lineHeight = 17.sp, color = Nt.TextSoft,
                modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()).clip(RoundedCornerShape(10.dp)).background(Nt.Bg).padding(12.dp))
        },
        dismissButton = { TextButton(onClick = { showDefault = false }) { Text(tr("Fermer"), color = Nt.TextSoft) } },
        confirmButton = {
            TextButton(onClick = {
                showDefault = false
                editing = AiPrompt(java.util.UUID.randomUUID().toString(), tr("Mon prompt"), com.nautile.app.ai.AiAssistant.defaultPrompt)
            }) { Text(tr("Dupliquer et modifier"), color = Nt.Accent, fontWeight = FontWeight.SemiBold) }
        },
    )

    editing?.let { p ->
        val exists = prompts.any { it.id == p.id }
        var name by remember(p.id) { mutableStateOf(p.name) }
        var text by remember(p.id) { mutableStateOf(p.text) }
        var confirmDelete by remember(p.id) { mutableStateOf(false) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { editing = null },
            containerColor = Nt.Surface,
            title = { Text(if (exists) tr("Modifier le prompt") else tr("Nouveau prompt")) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    NtTextField(name, { name = it }, tr("Nom"), placeholder = tr("Pédagogue, Sécurité, Docker…"))
                    androidx.compose.material3.OutlinedTextField(
                        value = text, onValueChange = { text = it }, label = { Text(tr("Consignes")) },
                        modifier = Modifier.fillMaxWidth(), minLines = 6, maxLines = 14,
                        textStyle = androidx.compose.ui.text.TextStyle(fontFamily = Nt.Mono, fontSize = 12.sp, lineHeight = 17.sp, color = Nt.Text),
                        shape = RoundedCornerShape(12.dp),
                        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                            unfocusedContainerColor = Nt.Surface, focusedContainerColor = Nt.Surface,
                            unfocusedBorderColor = Nt.Border, focusedBorderColor = Nt.Accent, focusedLabelColor = Nt.Accent, cursorColor = Nt.Accent,
                        ),
                    )
                    TextButton(onClick = { text = com.nautile.app.ai.AiAssistant.defaultPrompt }) { Text(tr("Repartir du prompt par défaut"), color = Nt.TextSoft) }
                    if (exists) {
                        if (!confirmDelete) TextButton(onClick = { confirmDelete = true }) { Text(tr("Supprimer"), color = Nt.Pink) }
                        else TextButton(onClick = { ai.deletePrompt(p.id); editing = null }) { Text(tr("Supprimer « %1\$s » ?", p.name), color = Nt.Pink, fontWeight = FontWeight.SemiBold) }
                    }
                }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text(tr("Annuler"), color = Nt.TextSoft) } },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank() && text.isNotBlank(),
                    onClick = {
                        ai.savePrompt(p.copy(name = name.trim(), text = text.trim()))
                        if (!exists) ai.selectPrompt(p.id)
                        editing = null
                    },
                ) { Text(tr("Enregistrer"), color = Nt.Accent, fontWeight = FontWeight.SemiBold) }
            },
        )
    }
}

@Composable
private fun PromptRow(name: String, detail: String, selected: Boolean, onSelect: () -> Unit, onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onOpen).padding(end = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick = onSelect, modifier = Modifier.padding(horizontal = 4.dp), colors = RadioButtonDefaults.colors(selectedColor = Nt.Accent))
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text(detail, fontSize = 12.sp, color = Nt.Muted, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Outlined.Edit, contentDescription = tr("Modifier"), tint = Nt.Muted, modifier = Modifier.padding(start = 8.dp))
    }
}
