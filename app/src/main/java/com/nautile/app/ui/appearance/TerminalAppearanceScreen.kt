package com.nautile.app.ui.appearance

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nautile.app.NautileApp
import com.nautile.app.readText
import com.nautile.app.session.TerminalFont
import com.nautile.app.session.TerminalFonts
import com.nautile.app.session.TerminalPrefs
import com.nautile.app.session.TerminalTheme
import com.nautile.app.session.TerminalThemes
import com.nautile.app.session.ThemeImporter
import com.nautile.app.ui.common.ConfirmDialog
import com.nautile.app.ui.common.NtCard
import com.nautile.app.ui.common.SectionLabel
import com.nautile.app.ui.common.Segmented
import com.nautile.app.ui.settings.LinkRow
import com.nautile.app.ui.settings.ToggleRow
import com.nautile.app.ui.terminal.KeyCatalog
import com.nautile.app.ui.terminal.KeyFace
import com.nautile.app.ui.terminal.ReorderableKeyRow
import com.nautile.app.ui.theme.Nt
import com.nautile.i18n.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MAX_FONT_BYTES = 30 * 1024 * 1024

@Composable
private fun fontLabel(f: TerminalFont): String = if (f.id == TerminalFonts.SYSTEM_ID) tr("Monospace du système") else f.name

/** « Terminal » settings: theme, cursor, text size, font, history and session logs. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TerminalAppearanceScreen(onBack: () -> Unit, onOpenLogs: () -> Unit) {
    val app = LocalContext.current.applicationContext as NautileApp
    val prefs = app.terminalPrefs
    val theme by prefs.theme.collectAsStateWithLifecycle()
    val custom by prefs.customThemes.collectAsStateWithLifecycle()
    val cursor by prefs.cursorStyle.collectAsStateWithLifecycle()
    val size by prefs.fontSize.collectAsStateWithLifecycle()
    val font by prefs.font.collectAsStateWithLifecycle()
    val importedFonts by prefs.importedFonts.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var fontMessage by remember { mutableStateOf<String?>(null) }
    val density = LocalDensity.current
    val sp = (size / density.fontScale / density.density).toInt()
    val previewFamily = remember(font) { FontFamily(TerminalFonts.typeface(context, font)) }

    val pickFont = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            fontMessage = runCatching {
                val f = withContext(Dispatchers.IO) {
                    val name = app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) c.getString(0) else null
                    }?.substringBeforeLast('.') ?: tr("Police importée")
                    val bytes = app.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error(tr("Fichier illisible"))
                    if (bytes.size > MAX_FONT_BYTES) error(tr("fichier trop volumineux (30 Mo max.)"))
                    runCatching { prefs.importFont(name, bytes) }.getOrElse { error(tr("ce fichier n'est pas une police TrueType ou OpenType")) }
                }
                if (TerminalFonts.isMonospace(TerminalFonts.typeface(app, f))) tr("Police « %1\$s » importée et appliquée", f.name)
                else tr("Police « %1\$s » appliquée, mais elle n'est pas à chasse fixe : les colonnes risquent d'être décalées.", f.name)
            }.getOrElse { tr("Import impossible : %1\$s", it.message) }
        }
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            message = runCatching {
                val (name, text) = withContext(Dispatchers.IO) {
                    val n = app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) c.getString(0) else null
                    } ?: tr("Thème importé")
                    n.substringBeforeLast('.') to (app.contentResolver.readText(uri) ?: error(tr("Fichier illisible")))
                }
                val t = ThemeImporter.parse(text, name)
                prefs.addCustomTheme(t)
                tr("Thème « %1\$s » importé et appliqué", t.name)
            }.getOrElse { tr("Import impossible : %1\$s", it.message) }
        }
    }

    Column(Modifier.fillMaxSize().background(Nt.Bg)) {
        Row(Modifier.padding(start = 4.dp, top = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("Retour aux réglages")) }
            Text(tr("Terminal"), style = MaterialTheme.typography.titleLarge)
        }
        Preview(theme, cursor, sp, previewFamily)
        Box(Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp)) {
            Segmented(listOf(0 to tr("Aspect"), 1 to tr("Clavier"), 2 to tr("Sessions")), tab, { tab = it })
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
          if (tab == 0) {
            SectionLabel(tr("Thème"))
            (TerminalThemes.builtIn + custom).chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { t ->
                        val on = t.id == theme.id
                        val isCustom = custom.any { it.id == t.id }
                        Column(
                            Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(Nt.Surface)
                                .border(2.dp, if (on) Nt.Accent else Nt.Border, RoundedCornerShape(12.dp))
                                .combinedClickable(
                                    onClick = { prefs.setTheme(t) },
                                    onLongClick = { if (isCustom) prefs.removeCustomTheme(t) },
                                    onLongClickLabel = if (isCustom) tr("Supprimer ce thème") else null,
                                )
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(
                                Modifier.fillMaxWidth().height(28.dp).clip(RoundedCornerShape(7.dp)).background(Color(t.background)).padding(horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                listOf(2, 4, 3, 1).forEach { i -> Box(Modifier.size(10.dp).clip(CircleShape).background(Color(t.ansi[i]))) }
                            }
                            Text(t.name + if (isCustom) tr(" · importé") else "", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        }
                    }
                    if (row.size == 1) Box(Modifier.weight(1f))
                }
            }
            OutlinedButton(onClick = { pick.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                Text(tr("Importer un thème (.itermcolors, Xresources, Alacritty)"), color = Nt.Text, fontSize = 13.sp)
            }
            if (custom.isNotEmpty()) Text(tr("Appui long sur un thème importé pour le supprimer."), color = Nt.Muted, fontSize = 12.sp)
            message?.let { Text(it, color = Nt.TextSoft, fontSize = 13.sp) }

            SectionLabel(tr("Curseur"))
            Segmented(listOf(0 to tr("Bloc"), 2 to tr("Barre"), 1 to tr("Souligné")), cursor, prefs::setCursorStyle)

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(tr("Taille du texte"), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                SizeButton("A−", tr("Réduire")) { prefs.setFontSize(size - with(density) { 1.sp.toPx() }.toInt().coerceAtLeast(1)) }
                Text("$sp px", fontFamily = Nt.Mono, fontSize = 14.sp, modifier = Modifier.width(52.dp), textAlign = TextAlign.Center)
                SizeButton("A+", tr("Agrandir")) { prefs.setFontSize(size + with(density) { 1.sp.toPx() }.toInt().coerceAtLeast(1)) }
            }

            SectionLabel(tr("Police · %1\$s", fontLabel(font)))
            val cells: List<TerminalFont?> = TerminalFonts.builtIn + importedFonts + listOf(null)
            cells.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { f ->
                        if (f == null) {
                            // Last cell: import a .ttf / .otf.
                            Column(
                                Modifier.weight(1f).height(84.dp).clip(RoundedCornerShape(12.dp))
                                    .border(1.dp, Nt.Border, RoundedCornerShape(12.dp))
                                    .combinedClickable(onClick = { pickFont.launch(arrayOf("font/*", "application/x-font-ttf", "application/x-font-otf", "application/octet-stream", "*/*")) })
                                    .padding(10.dp),
                                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text("+", fontSize = 22.sp, color = Nt.Accent)
                                Text(tr("Importer .ttf / .otf"), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Nt.TextSoft, maxLines = 1)
                            }
                        } else {
                            val on = f.id == font.id
                            val ff = remember(f.id) { FontFamily(TerminalFonts.typeface(context, f)) }
                            Column(
                                Modifier.weight(1f).height(84.dp).clip(RoundedCornerShape(12.dp)).background(Nt.Surface)
                                    .border(2.dp, if (on) Nt.Accent else Nt.Border, RoundedCornerShape(12.dp))
                                    .combinedClickable(
                                        onClick = { prefs.setFont(f) },
                                        onLongClick = { if (f.imported) prefs.removeImportedFont(f) },
                                        onLongClickLabel = if (f.imported) tr("Supprimer cette police") else null,
                                    )
                                    .semantics { selected = on }
                                    .padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Box(
                                    Modifier.fillMaxWidth().height(30.dp).clip(RoundedCornerShape(7.dp)).background(Color(theme.background)).padding(horizontal = 8.dp),
                                    contentAlignment = Alignment.CenterStart,
                                ) {
                                    Text(if (f.nerd) "0Oo il1 {} \uE0B0" else "0Oo il1 {}", fontFamily = ff, fontSize = 15.sp, color = Color(theme.foreground), maxLines = 1)
                                }
                                Text(
                                    fontLabel(f) + when { f.imported -> tr(" · importée"); !f.nerd -> tr(" · sans Powerline"); else -> "" },
                                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                                )
                            }
                        }
                    }
                    if (row.size == 1) Box(Modifier.weight(1f))
                }
            }
            Hint(tr("Toutes les polices fournies sont des Nerd Fonts : symboles Powerline pour Powerlevel10k et Starship.") +
                if (importedFonts.isNotEmpty()) tr(" Appui long sur une police importée pour la supprimer.") else "")
            fontMessage?.let { Text(it, color = Nt.TextSoft, fontSize = 13.sp) }

            Text(tr("Le terminal garde ses propres couleurs : l'app peut être en clair et le terminal en sombre."), color = Nt.Muted, fontSize = 12.sp)
          }
            if (tab == 1) KeyboardSettings()
            if (tab == 2) HistorySection(onOpenLogs)

        }
    }
}

@Composable
private fun SizeButton(label: String, description: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick, shape = RoundedCornerShape(12.dp), color = Nt.Surface, border = BorderStroke(1.dp, Nt.Border),
        modifier = Modifier.size(44.dp).semantics { contentDescription = description },
    ) { Box(contentAlignment = Alignment.Center) { Text(label, fontSize = 16.sp) } }
}

@Composable
private fun Preview(t: TerminalTheme, cursor: Int, sp: Int, family: FontFamily) {
    fun c(i: Int) = Color(t.ansi[i])
    val fg = Color(t.foreground)
    val lines = listOf(
        buildAnnotatedString { withStyle(SpanStyle(color = c(2))) { append("mrd@docker-01") }; append(":"); withStyle(SpanStyle(color = c(4))) { append("~") }; append("$ docker compose ps") },
        buildAnnotatedString { append("jellyfin    "); withStyle(SpanStyle(color = c(2))) { append("Up 3 days") } },
        buildAnnotatedString { append("papote-api  "); withStyle(SpanStyle(color = c(3))) { append("Restarting") } },
        buildAnnotatedString { withStyle(SpanStyle(color = c(1))) { append("Error: ECONNREFUSED :5432") } },
    )
    Column(
        Modifier.padding(horizontal = 20.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(t.background))
            .border(1.dp, Nt.Border, RoundedCornerShape(16.dp)).padding(14.dp),
    ) {
        lines.forEach { Text(it, color = fg, fontFamily = family, fontSize = sp.sp, lineHeight = (sp * 1.6).sp, maxLines = 1) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                buildAnnotatedString { withStyle(SpanStyle(color = c(2))) { append("mrd@docker-01") }; append(":"); withStyle(SpanStyle(color = c(4))) { append("~") }; append("$ ") },
                color = fg, fontFamily = family, fontSize = sp.sp,
            )
            val cur = Color(t.cursor)
            when (cursor) {
                2 -> Box(Modifier.width(2.dp).height((sp + 4).dp).background(cur))
                1 -> Box(Modifier.padding(top = (sp).dp).width((sp * 0.65).dp).height(2.dp).background(cur))
                else -> Box(Modifier.width((sp * 0.65).dp).height((sp + 3).dp).background(cur))
            }
        }
    }
}

/** Command history (suggestions) and session logs, with a confirmation before erasing. */
@Composable
private fun HistorySection(onOpenLogs: () -> Unit) {
    val app = LocalContext.current.applicationContext as NautileApp
    val prefs = app.terminalPrefs
    val scope = rememberCoroutineScope()
    val history by prefs.history.collectAsStateWithLifecycle()
    val logs by prefs.logSessions.collectAsStateWithLifecycle()
    val confirmClose by prefs.confirmClose.collectAsStateWithLifecycle()
    val count by app.database.toolsDao().observeHistoryCount().collectAsStateWithLifecycle(initialValue = 0)
    var confirm by remember { mutableStateOf(false) }

    SectionLabel(tr("Fermeture"))
    NtCard {
        ToggleRow(
            tr("Confirmer la fermeture"), tr("Demande avant de fermer une session connectée (terminal ou bureau à distance)"),
            confirmClose, onChange = prefs::setConfirmClose,
        )
    }

    // OSC 52: off by default, and never applied while the app is locked (SessionManager).
    val remoteClipboard by prefs.remoteClipboard.collectAsStateWithLifecycle()
    SectionLabel(tr("Presse-papiers pour les programmes"), Modifier.padding(top = 8.dp))
    NtCard {
        ToggleRow(
            tr("Autoriser les programmes à écrire dans le presse-papiers"),
            tr("Copie demandée par un programme distant (tmux, vim…, séquence OSC 52), jamais quand l'appli est verrouillée"),
            remoteClipboard, onChange = prefs::setRemoteClipboard,
        )
    }

    SectionLabel(tr("Historique et journaux"), Modifier.padding(top = 8.dp))
    NtCard {
        ToggleRow(
            tr("Suggestions depuis l'historique"),
            tr("Commandes affichées en écho, par hôte (%1\$s enregistrées). Jamais les mots de passe.", count),
            history, onChange = prefs::setHistory,
        )
        HorizontalDivider(color = Nt.Border)
        ToggleRow(
            tr("Enregistrer les sessions"), tr("Copie de la sortie de chaque nouvelle session, stockée sur l'appareil"),
            logs, onChange = prefs::setLogSessions,
        )
        HorizontalDivider(color = Nt.Border)
        LinkRow(tr("Journaux de session"), tr("Lire, partager ou supprimer"), "", onOpenLogs)
    }
    if (count > 0) OutlinedButton(
        onClick = { confirm = true }, modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, Nt.Pink.copy(alpha = 0.5f)),
    ) { Text(tr("Effacer l'historique (%1\$s commandes)", count), color = Nt.Pink) }

    if (confirm) ConfirmDialog(
        tr("Effacer les %1\$s commandes enregistrées ?", count), tr("Les suggestions repartiront de zéro sur tous les hôtes."), tr("Effacer"),
        onDismiss = { confirm = false },
    ) { confirm = false; scope.launch { app.database.toolsDao().clearHistory() } }
}

@Composable
private fun Hint(text: String) = Text(text, color = Nt.Muted, fontSize = 12.sp)

/** The key bar above the system keyboard: which keys, in which order, and how they feel. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeyboardSettings() {
    val prefs = (LocalContext.current.applicationContext as NautileApp).terminalPrefs
    val ids by prefs.compactKeys.collectAsStateWithLifecycle()
    val haptic by prefs.haptic.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    fun label(id: String) = KeyCatalog.byId(id)?.label ?: id

    SectionLabel(tr("Barre de touches"))
    Hint(tr("Telle qu'elle s'affiche au-dessus du clavier. Appui long puis glissez une touche pour la déplacer ; touchez-la pour la retirer."))
    ReorderableKeyRow(
        ids, selected, onSelect = { selected = if (selected == it) null else it }, onReorder = prefs::setCompactKeys,
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { adding = true }) { Text(tr("+ Ajouter une touche"), color = Nt.Accent) }
        selected?.let { id ->
            OutlinedButton(onClick = { prefs.setCompactKeys(ids - id); selected = null }, border = BorderStroke(1.dp, Nt.Pink.copy(alpha = 0.5f))) {
                Text(tr("Retirer « %1\$s »", KeyCatalog.byId(id)?.description ?: label(id)), color = Nt.Pink, maxLines = 1)
            }
        }
    }
    TextButton(onClick = { prefs.setCompactKeys(TerminalPrefs.DEFAULT_COMPACT_KEYS); selected = null }) {
        Text(tr("Revenir à la barre par défaut"), color = Nt.TextSoft, fontSize = 13.sp)
    }

    NtCard {
        ToggleRow(
            tr("Bouton mot de passe"), tr("Tape le mot de passe sudo de l'hôte à une invite"), "password" in ids,
            onChange = { on -> prefs.setCompactKeys(if (on) listOf("password") + ids else ids - "password") },
        )
        HorizontalDivider(color = Nt.Border)
        ToggleRow(tr("Vibration des touches"), tr("Retour tactile en touchant la barre"), haptic, onChange = prefs::setHaptic)
    }

    if (adding) AlertDialog(
        onDismissRequest = { adding = false }, containerColor = Nt.Surface,
        title = { Text(tr("Ajouter une touche")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    KeyCatalog.all.filter { it.id !in ids }.forEach { def ->
                        Surface(
                            onClick = { prefs.setCompactKeys(ids + def.id) }, shape = RoundedCornerShape(10.dp), color = Nt.KeyBg,
                            border = BorderStroke(1.dp, Nt.Border),
                            modifier = Modifier.height(42.dp).width(com.nautile.app.ui.terminal.keyWidth(def)).semantics { contentDescription = def.description },
                        ) { KeyFace(def) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { adding = false }) { Text(tr("Terminé"), color = Nt.Accent) } },
    )
}
