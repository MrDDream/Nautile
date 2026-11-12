package com.nautile.app.ui.terminal

import com.nautile.i18n.tr

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DataObject
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.remember
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.semantics.selected
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Close
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nautile.app.session.TerminalPrefs
import com.nautile.app.session.TerminalThemes
import com.nautile.app.ui.theme.Nt
import com.nautile.data.model.CommandHistory
import com.nautile.data.model.Snippet

private val KeyBg get() = Nt.KeyBg

/** What the keys act on. */
class KeyTarget(
    val client: NtTerminalViewClient,
    val modifiers: ModifierKeys,
    val onTool: (ToolKind) -> Unit,
) {
    fun press(def: KeyDef) = when (val a = def.action) {
        is KeyAction.Code -> client.sendKey(a.keyCode)
        is KeyAction.Chr -> client.sendChar(a.char)
        is KeyAction.Raw -> client.sendRaw(a.text)
        KeyAction.Ctrl -> modifiers.ctrl = !modifiers.ctrl
        KeyAction.Alt -> modifiers.alt = !modifiers.alt
        is KeyAction.Tool -> onTool(a.tool)
    }

    fun isActive(def: KeyDef) = (def.action == KeyAction.Ctrl && modifiers.ctrl) || (def.action == KeyAction.Alt && modifiers.alt)
}

private enum class PanelTab(val label: String) { KEYS(tr("Touches")), SNIPPETS(tr("Snippets")), HISTORY(tr("Historique")), THEMES(tr("Affichage")) }

/**
 * The accessory keyboard: a scrollable row of favourite keys and a tab strip, always shown above the
 * system keyboard; a tab opens the panel (keys, snippets, history, themes) in place of the keyboard.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun KeyboardArea(
    target: KeyTarget,
    prefs: TerminalPrefs,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    snippets: List<Snippet>,
    history: List<CommandHistory>,
    onSnippet: (Snippet) -> Unit,
    onHistory: (String) -> Unit,
) {
    val ids by prefs.compactKeys.collectAsState()
    var tab by rememberSaveable { mutableStateOf(PanelTab.KEYS) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val imeVisible = androidx.compose.foundation.layout.WindowInsets.isImeVisible

    fun toggle(id: String) {
        prefs.setCompactKeys(if (id in ids) ids - id else ids + id)
        if (selected == id) selected = null
    }

    Column(Modifier.fillMaxWidth().background(Nt.Bar)) {
        HorizontalDivider(color = Nt.Border)
        if (editing) EditBar(
            ids = ids, selected = selected, onSelect = { selected = if (selected == it) null else it },
            onReorder = prefs::setCompactKeys,
            onRemove = { selected?.let(::toggle) },
            onReset = { prefs.setCompactKeys(TerminalPrefs.DEFAULT_COMPACT_KEYS); selected = null },
            onDone = { editing = false; selected = null },
        ) else Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            ids.mapNotNull(KeyCatalog::byId).forEach { def -> Key(def, target, Modifier.height(42.dp).width(keyWidth(def))) }
            Surface(
                onClick = { editing = true; tab = PanelTab.KEYS; target.client.hideKeyboard(); onExpandedChange(true) },
                shape = RoundedCornerShape(10.dp), color = Color.Transparent, border = BorderStroke(1.dp, Nt.Border),
                modifier = Modifier.size(42.dp).semantics { contentDescription = tr("Modifier la barre") },
            ) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Edit, null, tint = Nt.Muted, modifier = Modifier.size(18.dp)) } }
        }
        if (expanded) Box(Modifier.fillMaxWidth().height(280.dp)) {
            when (tab) {
                PanelTab.KEYS -> KeysGrid(target, editing, ids, onToggle = ::toggle, onCustomize = { editing = true })
                PanelTab.SNIPPETS -> SimpleList(
                    snippets, empty = tr("Aucun snippet : créez-en dans l'onglet Snippets."),
                    title = { it.name }, subtitle = { it.command.lineSequence().first() }, onClick = onSnippet,
                )
                PanelTab.HISTORY -> SimpleList(
                    history, empty = tr("Les commandes tapées sur cet hôte apparaîtront ici."),
                    title = { it.command }, subtitle = { if (it.count > 1) "${it.count} fois" else null }, onClick = { onHistory(it.command) },
                )
                PanelTab.THEMES -> ThemesTab(prefs)
            }
        }
        TabStrip(
            selected = if (expanded) tab else null,
            keyboardActive = imeVisible && !expanded,
            onTab = { t ->
                if (expanded && t == tab) { onExpandedChange(false); editing = false }
                else { tab = t; target.client.hideKeyboard(); onExpandedChange(true) }
            },
            onKeyboard = {
                if (expanded || !imeVisible) { onExpandedChange(false); editing = false; target.client.showKeyboard() }
                else target.client.hideKeyboard()
            },
        )
    }
}

internal fun keyWidth(def: KeyDef) = when {
    def.icon != null || def.action is KeyAction.Tool -> 46.dp
    def.label.length <= 2 -> 42.dp
    def.label.length <= 4 -> 56.dp
    else -> 78.dp
}

@Composable
private fun TabStrip(selected: PanelTab?, keyboardActive: Boolean, onTab: (PanelTab) -> Unit, onKeyboard: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        PanelTab.entries.forEach { t ->
            val on = t == selected
            Surface(
                onClick = { onTab(t) }, shape = RoundedCornerShape(14.dp),
                color = if (on) Nt.Green.copy(alpha = 0.18f) else Color.Transparent,
                modifier = Modifier.size(width = 56.dp, height = 38.dp).semantics { contentDescription = t.label },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        when (t) { PanelTab.KEYS -> Icons.Outlined.GridView; PanelTab.SNIPPETS -> Icons.Outlined.DataObject; PanelTab.HISTORY -> Icons.Outlined.History; PanelTab.THEMES -> Icons.Outlined.Palette },
                        null, tint = if (on) Nt.Green else Nt.Muted,
                    )
                }
            }
        }
        Box(Modifier.width(1.dp).height(24.dp).background(Nt.Border))
        Surface(
            onClick = onKeyboard, shape = RoundedCornerShape(14.dp),
            color = if (keyboardActive) Nt.Green.copy(alpha = 0.18f) else Color.Transparent,
            modifier = Modifier.size(width = 56.dp, height = 38.dp).semantics { contentDescription = if (keyboardActive) tr("Masquer le clavier") else tr("Clavier du téléphone") },
        ) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Keyboard, null, tint = if (keyboardActive) Nt.Green else Nt.Muted) } }
    }
}

/** The bar being edited: tap a key to select it, then move or remove it. */
@Composable
private fun EditBar(
    ids: List<String>, selected: String?, onSelect: (String) -> Unit,
    onReorder: (List<String>) -> Unit, onRemove: () -> Unit, onReset: () -> Unit, onDone: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (ids.isEmpty()) Text(tr("Barre vide : touchez des touches ci-dessous pour les ajouter."), color = Nt.Muted, fontSize = 12.sp, modifier = Modifier.padding(8.dp))
        else ReorderableKeyRow(ids, selected, onSelect, onReorder)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (selected == null) tr("Appui long puis glissez pour déplacer ; touchez la grille pour ajouter ou retirer.")
                else tr("Retirez la touche sélectionnée, ou glissez-la pour la déplacer."),
                color = Nt.Muted, fontSize = 11.sp, modifier = Modifier.weight(1f),
            )
            if (selected != null) androidx.compose.material3.IconButton(onClick = onRemove) { Icon(Icons.Outlined.Delete, tr("Retirer de la barre"), tint = Nt.Pink) }
            else TextButton(onClick = onReset) { Text(tr("Par défaut"), color = Nt.TextSoft, fontSize = 12.sp) }
            TextButton(onClick = onDone) { Text(tr("Terminé"), color = Nt.Accent, fontWeight = FontWeight.SemiBold) }
        }
    }
}

/** How a key looks, in the bar and wherever it is edited: its icon (IA, password, paste) or its label. */
@Composable
internal fun KeyFace(def: KeyDef, highlighted: Boolean = false, selected: Boolean = false) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (def.icon != null) Icon(def.icon, contentDescription = null, tint = Nt.Accent, modifier = Modifier.size(20.dp))
        else Text(
            def.label, fontFamily = Nt.Mono, fontSize = if (def.label.length > 4) 12.sp else 15.sp, fontWeight = FontWeight.Medium,
            color = if (highlighted || selected) Nt.Accent else Nt.Text, maxLines = 1, overflow = TextOverflow.Clip,
        )
    }
}

/**
 * The key bar as it will appear, to rearrange: press and hold a key then drag it sideways; tap to select it.
 * The new order is reported when the finger is lifted.
 */
@Composable
internal fun ReorderableKeyRow(ids: List<String>, selected: String?, onSelect: (String) -> Unit, onReorder: (List<String>) -> Unit, modifier: Modifier = Modifier) {
    var order by remember(ids) { mutableStateOf(ids) }
    var dragging by remember { mutableStateOf<String?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val view = androidx.compose.ui.platform.LocalView.current
    val gap = 5.dp
    fun widthPx(id: String) = with(density) { ((KeyCatalog.byId(id)?.let(::keyWidth) ?: 42.dp) + gap).toPx() }

    Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(gap)) {
        order.forEach { id ->
            val def = KeyCatalog.byId(id) ?: return@forEach
            androidx.compose.runtime.key(id) {
                val isDragged = dragging == id
                val on = id == selected
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = when { isDragged -> Nt.Accent.copy(alpha = 0.22f); on -> Nt.Accent.copy(alpha = 0.16f); else -> KeyBg },
                    border = BorderStroke(if (on || isDragged) 2.dp else 1.dp, if (on || isDragged) Nt.Accent else Nt.Border),
                    shadowElevation = if (isDragged) 8.dp else 0.dp,
                    modifier = Modifier.height(42.dp).width(keyWidth(def))
                        .zIndex(if (isDragged) 1f else 0f)
                        .graphicsLayer {
                            translationX = if (isDragged) offset else 0f
                            val k = if (isDragged) 1.08f else 1f
                            scaleX = k; scaleY = k
                        }
                        .pointerInput(id) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    dragging = id; offset = 0f
                                    view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    offset += amount.x
                                    val i = order.indexOf(id)
                                    // Swap with the neighbour once past half of it, keeping the key under the finger.
                                    if (offset > 0 && i < order.lastIndex && offset > widthPx(order[i + 1]) / 2) {
                                        offset -= widthPx(order[i + 1])
                                        order = order.toMutableList().apply { add(i + 1, removeAt(i)) }
                                    } else if (offset < 0 && i > 0 && -offset > widthPx(order[i - 1]) / 2) {
                                        offset += widthPx(order[i - 1])
                                        order = order.toMutableList().apply { add(i - 1, removeAt(i)) }
                                    }
                                },
                                onDragEnd = { dragging = null; offset = 0f; if (order != ids) onReorder(order) },
                                onDragCancel = { dragging = null; offset = 0f; if (order != ids) onReorder(order) },
                            )
                        }
                        .clickable { onSelect(id) }
                        .semantics {
                            contentDescription = def.description + if (on) tr(", sélectionnée") else ""
                            this.selected = on
                        },
                ) { KeyFace(def, selected = on) }
            }
        }
    }
}

@Composable
private fun KeysGrid(target: KeyTarget, editing: Boolean, ids: List<String>, onToggle: (String) -> Unit, onCustomize: () -> Unit) {
    LazyVerticalGrid(
        GridCells.Fixed(8),
        Modifier.fillMaxSize().padding(horizontal = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
    ) {
        if (!editing) item(span = { GridItemSpan(4) }) {
            Surface(onClick = onCustomize, shape = RoundedCornerShape(10.dp), color = KeyBg, border = BorderStroke(1.dp, Nt.Border), modifier = Modifier.height(44.dp)) {
                Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Settings, null, tint = Nt.TextSoft, modifier = Modifier.size(18.dp))
                    Text("  Personnaliser", color = Nt.TextSoft, fontSize = 14.sp)
                }
            }
        }
        val keys = KeyCatalog.tools + KeyCatalog.grid
        items(keys, key = { it.id }, span = { GridItemSpan(it.span) }) { def ->
            if (editing) EditableKey(def, inBar = def.id in ids) { onToggle(def.id) }
            else Key(def, target, Modifier.height(44.dp))
        }
    }
}

@Composable
private fun EditableKey(def: KeyDef, inBar: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick, shape = RoundedCornerShape(10.dp),
        color = if (inBar) Nt.Accent.copy(alpha = 0.14f) else KeyBg,
        border = BorderStroke(1.dp, if (inBar) Nt.Accent else Nt.Border),
        modifier = Modifier.height(44.dp).semantics { contentDescription = "${def.description} : ${if (inBar) "dans la barre, toucher pour retirer" else "toucher pour ajouter"}" },
    ) {
        Box {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (def.icon != null) Icon(def.icon, null, tint = Nt.Accent, modifier = Modifier.size(18.dp))
                else Text(def.label, fontFamily = Nt.Mono, fontSize = if (def.label.length > 4) 11.sp else 14.sp, color = Nt.Text, maxLines = 1)
            }
            Text(if (inBar) "✓" else "+", color = if (inBar) Nt.Accent else Nt.Muted, fontSize = 10.sp,
                modifier = Modifier.align(Alignment.TopEnd).padding(end = 4.dp, top = 1.dp))
        }
    }
}

@Composable
private fun Key(def: KeyDef, target: KeyTarget, modifier: Modifier) {
    val active = target.isActive(def)
    val shape = RoundedCornerShape(10.dp)
    val view = androidx.compose.ui.platform.LocalView.current
    val haptic by (view.context.applicationContext as com.nautile.app.NautileApp).terminalPrefs.haptic.collectAsState()
    Surface(
        onClick = {
            if (haptic) view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            target.press(def)
        },
        shape = shape,
        color = if (active) Nt.Accent.copy(alpha = 0.16f) else KeyBg,
        border = BorderStroke(1.dp, if (active) Nt.Accent else Nt.Border),
        modifier = modifier.semantics { contentDescription = def.description },
    ) { KeyFace(def, highlighted = active) }
}


/** A list for the panel, with a search field once there is something to search. */
@Composable
private fun <T> SimpleList(items: List<T>, empty: String, title: (T) -> String, subtitle: (T) -> String?, onClick: (T) -> Unit) {
    if (items.isEmpty()) { Text(empty, color = Nt.Muted, modifier = Modifier.padding(20.dp)); return }
    var query by rememberSaveable { mutableStateOf("") }
    val q = query.trim()
    val shown = if (q.isEmpty()) items else items.filter { title(it).contains(q, true) || subtitle(it)?.contains(q, true) == true }
    Column(Modifier.fillMaxSize()) {
        if (items.size > 3) androidx.compose.material3.OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            leadingIcon = { Icon(androidx.compose.material.icons.Icons.Outlined.Search, contentDescription = null, tint = Nt.Muted) },
            trailingIcon = {
                if (query.isNotEmpty()) androidx.compose.material3.IconButton(onClick = { query = "" }) {
                    Icon(androidx.compose.material.icons.Icons.Outlined.Close, contentDescription = tr("Effacer la recherche"), tint = Nt.Muted)
                }
            },
            placeholder = { Text(tr("Rechercher"), color = Nt.Placeholder, fontSize = 13.sp) },
            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = Nt.Mono, fontSize = 13.sp, color = Nt.Text),
            shape = RoundedCornerShape(12.dp),
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor = Nt.Surface, focusedContainerColor = Nt.Surface,
                unfocusedBorderColor = Nt.Border, focusedBorderColor = Nt.Accent, cursorColor = Nt.Accent,
            ),
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 4.dp),
        )
        if (shown.isEmpty()) Text(tr("Aucun résultat pour « %1\$s »", q), color = Nt.Muted, modifier = Modifier.padding(20.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            items(shown) { item ->
                Column(Modifier.fillMaxWidth().clickable { onClick(item) }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(title(item), fontFamily = Nt.Mono, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    subtitle(item)?.let { Text(it, fontSize = 11.sp, color = Nt.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
    }
}

/** Display settings at hand while typing: size and cursor, colours, font. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ThemesTab(prefs: TerminalPrefs) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val theme by prefs.theme.collectAsState()
    val custom by prefs.customThemes.collectAsState()
    val size by prefs.fontSize.collectAsState()
    val cursor by prefs.cursorStyle.collectAsState()
    val font by prefs.font.collectAsState()
    val imported by prefs.importedFonts.collectAsState()
    val sp = (size / density.fontScale / density.density).toInt()
    val step = with(density) { 1.sp.toPx() }.toInt().coerceAtLeast(1)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StepButton("A−", tr("Réduire"), enabled = size - step >= TerminalPrefs.MIN_FONT) { prefs.setFontSize(size - step) }
            Text("$sp", fontFamily = Nt.Mono, fontSize = 15.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.width(32.dp)
                .semantics { contentDescription = tr("Taille du texte : %1\$s", sp) })
            StepButton("A+", tr("Agrandir"), enabled = size + step <= TerminalPrefs.MAX_FONT) { prefs.setFontSize(size + step) }
            Box(Modifier.weight(1f))
            listOf(0 to "█", 2 to "▏", 1 to "▁").forEach { (style, glyph) ->
                val on = cursor == style
                Surface(
                    onClick = { prefs.setCursorStyle(style) }, shape = RoundedCornerShape(10.dp),
                    color = if (on) Nt.Accent.copy(alpha = 0.16f) else KeyBg, border = BorderStroke(1.dp, if (on) Nt.Accent else Nt.Border),
                    modifier = Modifier.size(40.dp).semantics {
                        contentDescription = when (style) { 0 -> tr("Curseur bloc"); 2 -> tr("Curseur barre"); else -> tr("Curseur souligné") }
                        selected = on
                    },
                ) { Box(contentAlignment = Alignment.Center) { Text(glyph, fontFamily = Nt.Mono, fontSize = 16.sp, color = if (on) Nt.Accent else Nt.TextSoft) } }
            }
        }

        Text(tr("COULEURS"), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, color = Nt.Muted)
        (TerminalThemes.builtIn + custom).chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { t ->
                    val on = t.id == theme.id
                    Surface(
                        onClick = { prefs.setTheme(t) }, shape = RoundedCornerShape(12.dp), color = Color(t.background),
                        border = BorderStroke(if (on) 2.dp else 1.dp, if (on) Nt.Accent else Nt.Border),
                        modifier = Modifier.weight(1f).semantics { selected = on },
                    ) {
                        Column(Modifier.padding(horizontal = 10.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(t.name, color = Color(t.foreground), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                t.ansi.subList(1, 7).forEach { c -> Box(Modifier.size(9.dp).background(Color(c), RoundedCornerShape(2.dp))) }
                            }
                        }
                    }
                }
                repeat(3 - row.size) { Box(Modifier.weight(1f)) }
            }
        }

        Text(tr("POLICE"), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, color = Nt.Muted)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            (com.nautile.app.session.TerminalFonts.builtIn + imported).forEach { f ->
                val on = f.id == font.id
                val family = androidx.compose.runtime.remember(f.id) {
                    androidx.compose.ui.text.font.FontFamily(com.nautile.app.session.TerminalFonts.typeface(context, f))
                }
                Surface(
                    onClick = { prefs.setFont(f) }, shape = RoundedCornerShape(10.dp),
                    color = if (on) Nt.Accent.copy(alpha = 0.16f) else KeyBg, border = BorderStroke(1.dp, if (on) Nt.Accent else Nt.Border),
                    modifier = Modifier.height(38.dp).semantics { selected = on },
                ) {
                    Box(Modifier.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                        Text(
                            if (f.id == com.nautile.app.session.TerminalFonts.SYSTEM_ID) tr("Système") else f.name,
                            fontFamily = family, fontSize = 13.sp, color = if (on) Nt.Accent else Nt.Text, maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StepButton(label: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick, enabled = enabled, shape = RoundedCornerShape(10.dp), color = KeyBg, border = BorderStroke(1.dp, Nt.Border),
        modifier = Modifier.size(40.dp).semantics { contentDescription = description },
    ) { Box(contentAlignment = Alignment.Center) { Text(label, fontSize = 14.sp, color = if (enabled) Nt.Text else Nt.Muted) } }
}
