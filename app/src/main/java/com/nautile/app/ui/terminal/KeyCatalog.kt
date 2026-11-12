package com.nautile.app.ui.terminal

import com.nautile.i18n.tr

import android.view.KeyEvent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Key
import androidx.compose.ui.graphics.vector.ImageVector

enum class ToolKind { AI, PASSWORD, PASTE }

sealed interface KeyAction {
    /** A key event, so sticky Ctrl/Alt and cursor-key modes apply. */
    data class Code(val keyCode: Int) : KeyAction
    /** A typed character (sticky Ctrl/Alt apply). */
    data class Chr(val char: Char) : KeyAction
    /** Raw bytes: escape sequences and multi-key combos (tmux prefix…). */
    data class Raw(val text: String) : KeyAction
    data object Ctrl : KeyAction
    data object Alt : KeyAction
    data class Tool(val tool: ToolKind) : KeyAction
}

/** One key of the accessory keyboard. [span] is its width in the expanded grid (8 columns). */
data class KeyDef(
    val id: String,
    val label: String,
    val description: String,
    val action: KeyAction,
    val icon: ImageVector? = null,
    val span: Int = 1,
)

object KeyCatalog {
    private const val PREFIX = "\u0002" // tmux Ctrl+B

    private fun code(id: String, label: String, desc: String, code: Int, span: Int = 1) = KeyDef(id, label, tr(desc), KeyAction.Code(code), span = span)
    private fun chr(c: Char, desc: String) = KeyDef(c.toString(), c.toString(), tr(desc), KeyAction.Chr(c))
    private fun raw(id: String, label: String, desc: String, text: String, span: Int = 1) = KeyDef(id, label, tr(desc), KeyAction.Raw(text), span = span)
    private fun tmux(c: Char, desc: String) = raw("tmux_$c", "^B $c", tr("tmux : %1\$s", tr(desc)), PREFIX + c, span = 2)
    private fun ctrlKey(c: Char) = raw("ctrl_${c.lowercaseChar()}", "^$c", "Ctrl+$c", (c.uppercaseChar().code - 64).toChar().toString())

    val tools = listOf(
        KeyDef("ai", tr("IA"), tr("Assistant IA"), KeyAction.Tool(ToolKind.AI), Icons.Outlined.AutoAwesome),
        KeyDef("password", tr("Mot de passe"), tr("Taper le mot de passe (sudo)"), KeyAction.Tool(ToolKind.PASSWORD), Icons.Outlined.Key),
        KeyDef("paste", tr("Coller"), tr("Coller le presse-papiers"), KeyAction.Tool(ToolKind.PASTE), Icons.Outlined.ContentPaste),
    )

    /** The expanded panel, row by row (each row sums to 8 columns). */
    val grid: List<KeyDef> = listOf(
        KeyDef("ctrl", "ctrl", tr("Contrôle (une touche)"), KeyAction.Ctrl), code("esc", "esc", tr("Échap"), KeyEvent.KEYCODE_ESCAPE),
        chr('-', "Tiret"), chr('/', tr("Barre oblique")),
        code("left", "←", tr("Gauche"), KeyEvent.KEYCODE_DPAD_LEFT), code("up", "↑", tr("Haut"), KeyEvent.KEYCODE_DPAD_UP),
        code("down", "↓", tr("Bas"), KeyEvent.KEYCODE_DPAD_DOWN), code("right", "→", tr("Droite"), KeyEvent.KEYCODE_DPAD_RIGHT),

        tmux('%', "couper verticalement"), tmux('"', "couper horizontalement"), tmux('o', "panneau suivant"), tmux('x', tr("fermer le panneau")),

        KeyDef("alt", "alt", tr("Alt (une touche)"), KeyAction.Alt), code("tab", "tab", tr("Tabulation"), KeyEvent.KEYCODE_TAB),
        code("ins", "ins", tr("Insérer"), KeyEvent.KEYCODE_INSERT), code("del", "del", tr("Supprimer"), KeyEvent.KEYCODE_FORWARD_DEL),
        code("home", "home", tr("Début"), KeyEvent.KEYCODE_MOVE_HOME), code("pgup", "pgUp", tr("Page précédente"), KeyEvent.KEYCODE_PAGE_UP),
        code("pgdn", "pgDn", tr("Page suivante"), KeyEvent.KEYCODE_PAGE_DOWN), code("end", "end", tr("Fin"), KeyEvent.KEYCODE_MOVE_END),

        raw("shift_tab", "shift+tab", "Maj+Tab", "\u001b[Z", span = 2), ctrlKey('C'), ctrlKey('D'), ctrlKey('Z'), ctrlKey('L'), ctrlKey('R'),
        code("enter", "⏎", tr("Entrée"), KeyEvent.KEYCODE_ENTER),

        chr('|', tr("Barre verticale")), chr(':', "Deux-points"), chr(';', "Point-virgule"), chr('!', "Point d'exclamation"),
        chr('~', "Tilde"), chr('@', "Arobase"), chr('$', "Dollar"), chr('*', tr("Étoile")),

        chr('^', "Accent circonflexe"), chr('%', "Pourcent"), chr('=', tr("Égal")), chr('`', "Accent grave"),
        chr('<', tr("Inférieur")), chr('>', tr("Supérieur")), chr('(', tr("Parenthèse ouvrante")), chr(')', tr("Parenthèse fermante")),

        chr('{', "Accolade ouvrante"), chr('}', "Accolade fermante"), chr('[', "Crochet ouvrant"), chr(']', "Crochet fermant"),
        chr('\\', tr("Barre oblique inverse")), chr('?', "Point d'interrogation"), chr('\'', "Apostrophe"), chr('"', "Guillemet"),

        chr('&', "Esperluette"), chr('#', tr("Dièse")), tmux('c', tr("nouvelle fenêtre")), tmux('n', tr("fenêtre suivante")), tmux('d', tr("détacher")),

        code("f1", "F1", "F1", KeyEvent.KEYCODE_F1), code("f2", "F2", "F2", KeyEvent.KEYCODE_F2),
        code("f3", "F3", "F3", KeyEvent.KEYCODE_F3), code("f4", "F4", "F4", KeyEvent.KEYCODE_F4),
        code("f5", "F5", "F5", KeyEvent.KEYCODE_F5), code("f6", "F6", "F6", KeyEvent.KEYCODE_F6),
        code("f7", "F7", "F7", KeyEvent.KEYCODE_F7), code("f8", "F8", "F8", KeyEvent.KEYCODE_F8),
        code("f9", "F9", "F9", KeyEvent.KEYCODE_F9), code("f10", "F10", "F10", KeyEvent.KEYCODE_F10),
        code("f11", "F11", "F11", KeyEvent.KEYCODE_F11), code("f12", "F12", "F12", KeyEvent.KEYCODE_F12),
        tmux('p', tr("fenêtre précédente")), tmux('[', tr("mode copie")),
    )

    val all: List<KeyDef> = tools + grid
    private val byId = all.associateBy { it.id }
    fun byId(id: String): KeyDef? = byId[id]
}
