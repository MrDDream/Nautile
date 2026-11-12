package com.nautile.app.ui.settings

import com.nautile.i18n.tr

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nautile.app.NautileApp
import com.nautile.app.ai.AiConfig
import com.nautile.app.ai.AiProviderKind
import com.nautile.app.ai.prettyModel
import com.nautile.app.ui.common.NtTextField
import com.nautile.app.ui.common.ProviderLogo
import com.nautile.app.ui.theme.Nt
import kotlinx.coroutines.launch

/**
 * Set up one provider: address (when it has one), API key, model (picked from the provider's list),
 * then test or save. [onSaved] tells whether the saved configuration answered (or is complete).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProviderSheet(kind: AiProviderKind, onSaved: (working: Boolean) -> Unit, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Nt.Surface) {
        ProviderSheetContent(kind, onSaved, onDismiss)
    }
}

/** The sheet's content, also drawn by the screenshots. */
@Composable
internal fun ProviderSheetContent(kind: AiProviderKind, onSaved: (working: Boolean) -> Unit, onDismiss: () -> Unit) {
    val ai = (LocalContext.current.applicationContext as NautileApp).ai
    val scope = rememberCoroutineScope()
    val saved = remember { ai.config(kind) }
    var keySaved by remember { mutableStateOf(saved.apiKey != null) }
    var key by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    var model by remember { mutableStateOf(saved.model) }
    var url by remember { mutableStateOf(saved.baseUrl.orEmpty()) }
    var status by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }

    fun current() = AiConfig(kind, key.trim().ifEmpty { ai.config(kind).apiKey }, model.trim(), url.trim().ifEmpty { null } ?: kind.defaultBaseUrl)
    fun save() {
        ai.save(kind, key.trim().ifEmpty { null }, model, url)
        if (key.isNotBlank()) keySaved = true
        key = ""
    }
    val canSave = model.isNotBlank() && (!kind.needsKey || keySaved || key.isNotBlank())

    run {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(Nt.Bg), contentAlignment = Alignment.Center) {
                    ProviderLogo(kind, 26.dp)
                }
                Column(Modifier.weight(1f)) {
                    Text(kind.label, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        when {
                            !kind.needsKey -> tr("Sans clé API")
                            keySaved -> tr("Clé enregistrée")
                            else -> tr("Clé API requise")
                        },
                        color = if (kind.needsKey && !keySaved) Nt.Accent else Nt.Muted, fontSize = 12.sp,
                    )
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, contentDescription = tr("Fermer"), tint = Nt.TextSoft) }
            }

            if (kind.editableUrl) {
                NtTextField(url, { url = it }, tr("Adresse de l'API"), mono = true, placeholder = kind.defaultBaseUrl ?: "https://…/v1")
                if (kind == AiProviderKind.LOCAL)
                    Hint(tr("Ollama : http://IP-du-PC:11434/v1 (lancer avec OLLAMA_HOST=0.0.0.0). LM Studio : http://IP-du-PC:1234/v1."))
            }

            OutlinedTextField(
                value = key, onValueChange = { key = it },
                label = {
                    Text(when {
                        keySaved -> tr("Nouvelle clé API (facultatif)")
                        kind.needsKey -> tr("Clé API")
                        else -> tr("Clé API (facultative)")
                    })
                },
                placeholder = { Text(if (keySaved) "••••••••••••" else kind.keyHint, color = Nt.Placeholder, fontFamily = Nt.Mono) },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showKey = !showKey }) {
                        Icon(if (showKey) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            contentDescription = if (showKey) tr("Masquer") else tr("Afficher"), tint = Nt.TextSoft)
                    }
                },
                textStyle = TextStyle(fontFamily = Nt.Mono, fontSize = 14.sp, color = Nt.Text),
                shape = RoundedCornerShape(12.dp),
                colors = fieldColors(),
            )

            // Model: a selector, not a text field (the picker also accepts a name typed by hand).
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(tr("Modèle"), color = Nt.TextSoft, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 58.dp).clip(RoundedCornerShape(12.dp))
                        .border(1.dp, Nt.Border, RoundedCornerShape(12.dp))
                        .clickable(role = Role.DropdownList) { picking = true }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        if (model.isBlank()) Text(tr("Choisir un modèle"), color = Nt.Placeholder, fontSize = 15.sp)
                        else {
                            Text(prettyModel(model), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Nt.Text)
                            Text(model, fontSize = 11.sp, fontFamily = Nt.Mono, color = Nt.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Icon(Icons.Outlined.ExpandMore, contentDescription = null, tint = Nt.TextSoft)
                }
            }

            if (busy) LinearProgressIndicator(color = Nt.Accent, trackColor = Nt.Border, modifier = Modifier.fillMaxWidth())
            status?.let { (ok, text) ->
                Text(text, color = if (ok) Nt.Green else Nt.Pink, fontSize = 13.sp, lineHeight = 18.sp,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .background((if (ok) Nt.Green else Nt.Pink).copy(alpha = 0.10f)).padding(10.dp))
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    enabled = !busy && canSave, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Nt.Border),
                    onClick = {
                        busy = true; status = null
                        val c = current()
                        save()
                        scope.launch {
                            status = runCatching { ai.test(c) }.fold(
                                onSuccess = { answer -> onSaved(true); true to tr("Connexion OK. Réponse : %1\$s", answer.take(80)) },
                                onFailure = { false to tr("Échec : %1\$s", it.message ?: "") },
                            )
                            busy = false
                        }
                    },
                ) { Text(tr("Tester"), color = Nt.Text, fontWeight = FontWeight.SemiBold) }
                Button(
                    enabled = !busy && canSave, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Nt.Accent, contentColor = Nt.OnAccent),
                    onClick = { save(); onSaved(ai.isConfigured(kind)); onDismiss() },
                ) { Text(tr("Enregistrer"), fontWeight = FontWeight.SemiBold) }
            }

            if (keySaved) TextButton(onClick = { ai.forgetKey(kind); keySaved = false; ai.setEnabled(kind, false) }) {
                Text(tr("Oublier la clé %1\$s", kind.label), color = Nt.Pink)
            }
        }
    }

    if (picking) ModelPicker(
        current = model,
        load = { ai.models(current()) },
        onPick = { model = it; picking = false },
        onDismiss = { picking = false },
    )
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    unfocusedContainerColor = Nt.Surface, focusedContainerColor = Nt.Surface,
    unfocusedBorderColor = Nt.Border, focusedBorderColor = Nt.Accent,
    unfocusedLabelColor = Nt.TextSoft, focusedLabelColor = Nt.Accent, cursorColor = Nt.Accent,
)

/** Quick filters of the model list. */
internal enum class ModelFilter(val label: () -> String, private val match: Regex?) {
    CHAT({ tr("Texte") }, null),
    FAST({ tr("Rapides") }, Regex("mini|nano|flash|haiku|lite|small|tiny|instant|fast|\\b[1-9]b\\b|-[1-9]b")),
    STRONG({ tr("Puissants") }, Regex("opus|sonnet|pro\\b|-pro|large|ultra|max\\b|70b|405b|^gpt-5(\\.\\d)?$|^grok-4(-\\d+)?$")),
    REASONING({ tr("Raisonnement") }, Regex("^o\\d|reason|think|r1|qwq|magistral")),
    ALL({ tr("Tous") }, null);

    fun keep(id: String): Boolean {
        val m = id.lowercase()
        if (this == ALL) return true
        if (NON_CHAT.containsMatchIn(m)) return false
        return match?.containsMatchIn(m) ?: true
    }

    companion object {
        /** Embeddings, speech, images…: of no use to the assistant. */
        private val NON_CHAT = Regex("embed|tts|whisper|dall-e|dalle|moderation|transcri|image|audio|realtime|search|computer-use|davinci|babbage|imagen|veo|aqa|rerank|ocr|guard")
    }
}

/** The provider's models: search, quick filters, or a name typed by hand. */
@Composable
internal fun ModelPicker(current: String, load: suspend () -> List<String>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var models by remember { mutableStateOf<List<String>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }
    LaunchedEffect(attempt) {
        error = null
        runCatching { load() }.onSuccess { models = it.distinct().sortedDescending() }.onFailure { error = it.message ?: tr("Échec") }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.86f), shape = RoundedCornerShape(24.dp), color = Nt.Surface) {
            ModelPickerContent(models, error, current, onRetry = { attempt++ }, onPick = onPick, onClose = onDismiss)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ModelPickerContent(
    models: List<String>?,
    error: String?,
    current: String,
    onRetry: () -> Unit,
    onPick: (String) -> Unit,
    onClose: () -> Unit,
    initialQuery: String = "",
) {
    var query by remember { mutableStateOf(initialQuery) }
    var filter by remember { mutableStateOf(ModelFilter.CHAT) }
    val q = query.trim().lowercase()
    val all = models.orEmpty()
    val shown = all.filter { filter.keep(it) && (q.isEmpty() || it.lowercase().contains(q) || prettyModel(it).lowercase().contains(q)) }

    Column(Modifier.padding(top = 18.dp)) {
        Row(Modifier.padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(tr("Choisir un modèle"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, contentDescription = tr("Fermer"), tint = Nt.TextSoft) }
        }
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            placeholder = { Text(tr("Rechercher un modèle"), color = Nt.Placeholder) },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, tint = Nt.Muted) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, contentDescription = tr("Effacer"), tint = Nt.Muted) } },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            textStyle = TextStyle(fontSize = 15.sp, color = Nt.Text), shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor = Nt.Bg, focusedContainerColor = Nt.Bg,
                unfocusedBorderColor = Nt.Border, focusedBorderColor = Nt.Accent, cursorColor = Nt.Accent,
            ),
        )
        if (models != null) FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ModelFilter.entries.forEach { f ->
                val n = all.count { f.keep(it) }
                if (n > 0 || f == filter) {
                    val on = f == filter
                    Box(
                        Modifier.height(34.dp).clip(RoundedCornerShape(17.dp))
                            .background(if (on) Nt.Accent.copy(alpha = 0.14f) else Color.Transparent)
                            .border(1.dp, if (on) Nt.Accent else Nt.Border, RoundedCornerShape(17.dp))
                            .clickable(role = Role.RadioButton) { filter = f }.padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text("${f.label()} · $n", fontSize = 13.sp, color = if (on) Nt.Accent else Nt.TextSoft, fontWeight = FontWeight.Medium) }
                }
            }
        }
        HorizontalDivider(color = Nt.Border, modifier = Modifier.padding(top = 12.dp))
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            val typed = query.trim()
            if (typed.isNotEmpty() && all.none { it.equals(typed, ignoreCase = true) }) item {
                PickRow(Icons.Outlined.Edit, tr("Utiliser « %1\$s »", typed), tr("Nom saisi à la main"), selected = false) { onPick(typed) }
            }
            when {
                error != null && models == null -> item {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(tr("Impossible de charger la liste : %1\$s", error), color = Nt.Pink, fontSize = 13.sp)
                        Text(tr("Vérifiez la clé et l'adresse, ou saisissez le nom du modèle ci-dessus."), color = Nt.Muted, fontSize = 12.sp)
                        TextButton(onClick = onRetry) { Text(tr("Réessayer"), color = Nt.Accent) }
                    }
                }
                models == null -> item {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        LinearProgressIndicator(color = Nt.Accent, trackColor = Nt.Border, modifier = Modifier.fillMaxWidth())
                        Text(tr("Chargement des modèles…"), color = Nt.Muted, fontSize = 13.sp)
                    }
                }
                shown.isEmpty() -> item {
                    Text(tr("Aucun modèle ne correspond."), color = Nt.Muted, fontSize = 13.sp, modifier = Modifier.padding(20.dp))
                }
            }
            items(shown, key = { it }) { id ->
                PickRow(null, prettyModel(id), id, selected = id == current) { onPick(id) }
            }
        }
    }
}

@Composable
private fun PickRow(icon: androidx.compose.ui.graphics.vector.ImageVector?, title: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.RadioButton, onClick = onClick)
            .background(if (selected) Nt.Accent.copy(alpha = 0.08f) else Color.Transparent)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = Nt.Accent, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (selected) Nt.Accent else Nt.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, fontSize = 11.sp, fontFamily = Nt.Mono, color = Nt.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (selected) Icon(Icons.Outlined.Check, contentDescription = tr("Sélectionné"), tint = Nt.Accent)
    }
}
