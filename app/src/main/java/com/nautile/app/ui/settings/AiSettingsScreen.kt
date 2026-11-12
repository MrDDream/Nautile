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
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults

/** « Assistant IA »: switch providers on or off, set each one up (gear), then the prompts and what is sent. */
@Composable
fun AiSettingsScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as NautileApp
    val ai = app.ai
    val aiLines by app.terminalPrefs.aiLines.collectAsState()
    val enabled by ai.enabled.collectAsState()
    /** Bumped after a provider sheet closes, so the rows re-read the stored configuration. */
    var revision by remember { mutableIntStateOf(0) }
    var configuring by remember { mutableStateOf<AiProviderKind?>(null) }
    /** Switched on while not set up yet: turn it on once the sheet saved a working configuration. */
    var enableAfter by remember { mutableStateOf(false) }

    SettingsPage(tr("Assistant IA"), onBack) {
        Text(tr("Décrivez ce que vous voulez faire dans le terminal, l'assistant propose la commande. Rien n'est exécuté sans votre accord."),
            color = Nt.TextSoft, fontSize = 13.sp, lineHeight = 19.sp)

        SectionLabel(tr("Fournisseurs"), Modifier.padding(top = 4.dp))
        NtCard {
            AiProviderKind.entries.forEachIndexed { i, k ->
                if (i > 0) HorizontalDivider(color = Nt.Border)
                val configured = remember(k, revision) { ai.isConfigured(k) }
                val model = remember(k, revision) { ai.config(k).model }
                val on = enabled[k] == true && configured
                ProviderRow(
                    kind = k,
                    detail = when {
                        configured -> com.nautile.app.ai.prettyModel(model)
                        else -> tr("Non configuré")
                    },
                    configured = configured,
                    on = on,
                    isDefault = on && ai.readyProviders().firstOrNull() == k,
                    onToggle = { want ->
                        if (want && !configured) { enableAfter = true; configuring = k } else ai.setEnabled(k, want)
                    },
                    onConfigure = { enableAfter = false; configuring = k },
                )
            }
        }
        Hint(tr("Les fournisseurs activés sont proposés dans l'assistant du terminal ; le dernier utilisé passe en premier. La roue dentée règle la clé, l'adresse et le modèle."))

        PromptsSection(ai)

        SectionLabel(tr("Lignes du terminal jointes"), Modifier.padding(top = 4.dp))
        com.nautile.app.ui.common.Segmented(
            com.nautile.app.session.TerminalPrefs.AI_LINE_CHOICES.map { it to "$it" }, aiLines, app.terminalPrefs::setAiLines,
        )
        Hint(tr("Plus de lignes donnent plus de contexte pour expliquer une erreur, mais la requête est plus longue et plus coûteuse. L'historique de défilement est inclus."))

        SectionLabel(tr("Ce qui est envoyé"), Modifier.padding(top = 4.dp))
        NtCard {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("✓ Votre demande, l'adresse et le protocole de l'hôte"), fontSize = 13.sp, color = Nt.TextSoft)
                Text(tr("✓ Les %1\$s dernières lignes du terminal, seulement si « Joindre l'écran » est coché", aiLines), fontSize = 13.sp, color = Nt.TextSoft)
                Text(tr("✓ Les fichiers que vous joignez (texte, %1\$s au plus chacun)", com.nautile.app.ai.AiFiles.sizeLabel(com.nautile.app.ai.AiFiles.MAX_BYTES.toLong())), fontSize = 13.sp, color = Nt.TextSoft)
                Text(tr("✕ Jamais vos mots de passe, vos clés ni votre historique"), fontSize = 13.sp, color = Nt.TextSoft)
            }
        }
        Hint(tr("Les clés API restent chiffrées sur l'appareil (Android Keystore)."))
    }

    configuring?.let { k ->
        ProviderSheet(
            kind = k,
            onSaved = { working -> if (enableAfter && working) ai.setEnabled(k, true) },
            onDismiss = { configuring = null; enableAfter = false; revision++ },
        )
    }
}

@Composable
private fun ProviderRow(
    kind: AiProviderKind,
    detail: String,
    configured: Boolean,
    on: Boolean,
    isDefault: Boolean,
    onToggle: (Boolean) -> Unit,
    onConfigure: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(onClick = onConfigure).padding(start = 14.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)).background(Nt.Bg), contentAlignment = Alignment.Center) {
            com.nautile.app.ui.common.ProviderLogo(kind, 22.dp, tint = if (on) Nt.Text else Nt.TextSoft)
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(kind.label, style = MaterialTheme.typography.titleMedium, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (isDefault) Box(Modifier.clip(RoundedCornerShape(6.dp)).background(Nt.Green.copy(alpha = 0.14f)).padding(horizontal = 6.dp, vertical = 1.dp)) {
                    Text(tr("Par défaut"), color = Nt.Green, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Text(detail, fontSize = 12.sp, color = if (configured) Nt.Muted else Nt.Accent, maxLines = 1)
        }
        IconButton(onClick = onConfigure) {
            Icon(Icons.Outlined.Settings, contentDescription = tr("Configurer %1\$s", kind.label), tint = Nt.TextSoft)
        }
        Switch(on, onToggle, colors = SwitchDefaults.colors(checkedTrackColor = Nt.Accent, uncheckedTrackColor = Nt.Toggle))
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
