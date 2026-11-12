package com.nautile.app.rdp

import com.nautile.i18n.tr

import android.app.Activity
import android.content.ClipboardManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.nautile.app.NautileApp
import com.nautile.app.session.SessionState
import com.nautile.app.ui.theme.Nt
import com.nautile.rdp.RdpInput
import kotlinx.coroutines.launch

/** Full-screen remote desktop with a floating toolbar and a key bar above the keyboard. */
@Composable
fun RdpScreen(sessionId: Long, onBack: () -> Unit, onReplaced: (Long) -> Unit) {
    val app = LocalContext.current.applicationContext as NautileApp
    val manager = app.sessions.rdp
    val sessions by manager.list.collectAsState()
    val session = sessions.firstOrNull { it.id == sessionId }
    LaunchedEffect(session == null) { if (session == null) onBack() }
    if (session == null) return
    val state by session.state.collectAsState()
    val scope = rememberCoroutineScope()
    var view by remember { mutableStateOf<RdpView?>(null) }
    var keys by remember { mutableStateOf(false) }
    var confirmClose by remember { mutableStateOf(false) }
    var barHeight by remember { mutableStateOf(0) }
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    val prefs = manager.prefs
    val touchMode by prefs.touchMode.collectAsState()
    val showPointer by prefs.pointer.collectAsState()
    val immersive by prefs.immersive.collectAsState()
    val keyBarPref by prefs.keyBar.collectAsState()

    if (immersive) Immersive()
    BackHandler { if (state is SessionState.Closed) { manager.close(session); onBack() } else confirmClose = true }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx -> RdpView(ctx).also { view = it } },
            update = { v ->
                v.session = session
                v.touchpad = touchMode == RdpTouchMode.TOUCHPAD
                v.drawPointer = showPointer
                v.obscuredBottom = imeBottom + if (keys && keyBarPref) barHeight else 0
            },
            // Keep the desktop out of the camera notch, where it could not be seen nor touched.
            modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout),
        )

        if (state == SessionState.Connected) FloatingToolbar(
            alias = session.alias,
            keyboard = keys,
            onKeyboard = { keys = !keys; if (keys) view?.showKeyboard() else view?.hideKeyboard() },
            onPaste = {
                val text = app.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(app)?.toString()
                if (!text.isNullOrEmpty()) { if (prefs.clipboard.value) session.input?.clipboard(text); view?.typeText(text) }
            },
            onClose = { confirmClose = true },
            modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout),
        )

        if (keys && keyBarPref && state == SessionState.Connected) view?.let { v ->
            KeyBar(v, Modifier.align(Alignment.BottomCenter).imePadding().onSizeChanged { barHeight = it.height })
        }

        when (val s = state) {
            is SessionState.Connecting -> Overlay {
                CircularProgressIndicator(color = Nt.Accent, modifier = Modifier.size(32.dp))
                Text(session.alias, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Nt.Text)
                Text(s.step, fontSize = 13.sp, color = Nt.TextSoft)
                OutlinedButton(onClick = { manager.close(session); onBack() }) { Text(tr("Annuler"), color = Nt.Text) }
            }
            is SessionState.Closed -> Overlay {
                Text(session.alias, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Nt.Text)
                Text(s.reason, fontSize = 14.sp, color = if (s.failed) Nt.Pink else Nt.TextSoft)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { manager.close(session); onBack() }) { Text(tr("Fermer"), color = Nt.Text) }
                    Button(
                        onClick = { scope.launch { manager.reconnect(session).onSuccess { onReplaced(it.id) }.onFailure { onBack() } } },
                        colors = ButtonDefaults.buttonColors(containerColor = Nt.PrimaryContainer, contentColor = Nt.OnPrimaryContainer),
                    ) { Text(tr("Reconnecter")) }
                }
            }
            SessionState.Connected -> {}
        }
    }

    if (confirmClose) AlertDialog(
        onDismissRequest = { confirmClose = false },
        containerColor = Nt.Surface,
        title = { Text(tr("Se déconnecter de %1\$s ?", session.alias)) },
        text = { Text(tr("La session Windows reste ouverte sur le serveur : vous la retrouverez à la prochaine connexion."), color = Nt.TextSoft) },
        dismissButton = { TextButton(onClick = { confirmClose = false }) { Text(tr("Rester"), color = Nt.TextSoft) } },
        confirmButton = {
            TextButton(onClick = { confirmClose = false; manager.close(session); onBack() }) {
                Text(tr("Déconnecter"), color = Nt.Pink, fontWeight = FontWeight.SemiBold)
            }
        },
    )
}

/** Hides the status and navigation bars while the desktop is shown (swipe to reveal them). */
@Composable
private fun Immersive() {
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

@Composable
private fun Overlay(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 420.dp).padding(24.dp).background(Nt.Surface, RoundedCornerShape(16.dp))
                .border(1.dp, Nt.Border, RoundedCornerShape(16.dp)).padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) { content() }
    }
}

/**
 * The session's controls folded into a small handle at the top, so they never sit on the desktop for long:
 * tap it to open the toolbar, drag it sideways to free what is under it; the toolbar folds back by itself.
 */
@Composable
private fun FloatingToolbar(alias: String, keyboard: Boolean, onKeyboard: () -> Unit, onPaste: () -> Unit, onClose: () -> Unit, modifier: Modifier) {
    var open by rememberSaveable { mutableStateOf(true) }
    var touched by remember { mutableIntStateOf(0) }
    /** Horizontal position of the handle, 0 = left edge, 1 = right edge. */
    var position by rememberSaveable { mutableFloatStateOf(0.5f) }
    LaunchedEffect(open, touched) { if (open) { delay(4_000); open = false } }

    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        if (open) {
            Row(
                Modifier.align(Alignment.TopCenter).padding(top = 8.dp)
                    .background(Color(0xCC000000), RoundedCornerShape(22.dp)).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(alias, color = Color.White, fontSize = 13.sp, maxLines = 1, modifier = Modifier.padding(start = 12.dp, end = 4.dp).widthIn(max = 140.dp))
                IconButton(onClick = { touched++; onPaste() }) { Icon(Icons.Outlined.ContentPaste, contentDescription = tr("Coller le presse-papier du téléphone"), tint = Color.White) }
                IconButton(onClick = { touched++; onKeyboard() }, modifier = Modifier.semantics { selected = keyboard }) {
                    Icon(Icons.Outlined.Keyboard, contentDescription = tr("Clavier"), tint = if (keyboard) Nt.Accent else Color.White)
                }
                IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, contentDescription = tr("Se déconnecter"), tint = Color.White) }
                IconButton(onClick = { open = false }) { Icon(Icons.Outlined.ExpandLess, contentDescription = tr("Replier la barre"), tint = Color.White) }
            }
        } else {
            val handleWidth = 56.dp
            val handlePx = with(density) { handleWidth.toPx() }
            Box(
                Modifier
                    .offset { IntOffset(((widthPx - handlePx) * position).roundToInt(), 0) }
                    .size(handleWidth, 48.dp)
                    .pointerInput(widthPx) {
                        detectHorizontalDragGestures { change, dx ->
                            change.consume()
                            position = (position + dx / (widthPx - handlePx).coerceAtLeast(1f)).coerceIn(0f, 1f)
                        }
                    }
                    .clickable(onClickLabel = tr("Ouvrir la barre d'outils"), role = Role.Button) { open = true }
                    .semantics { contentDescription = tr("Barre d'outils de %1\$s", alias) },
                contentAlignment = Alignment.TopCenter,
            ) {
                Box(
                    Modifier.size(handleWidth, 22.dp).background(Color(0x99000000), RoundedCornerShape(bottomStart = 11.dp, bottomEnd = 11.dp)),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Outlined.ExpandMore, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp)) }
            }
        }
    }
}

@Composable
private fun KeyBar(view: RdpView, modifier: Modifier) {
    val mods by view.modifiers.collectAsState()
    Row(
        modifier.fillMaxWidth().background(Nt.Bar).horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        @Composable fun key(label: String, description: String = label, on: Boolean = false, onClick: () -> Unit) = Surface(
            onClick = onClick, shape = RoundedCornerShape(8.dp),
            color = if (on) Nt.Accent else Nt.Surface, contentColor = if (on) Nt.Bg else Nt.Text,
            modifier = Modifier.height(38.dp).semantics { contentDescription = description; selected = on },
        ) { Box(Modifier.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) { Text(label, fontSize = 14.sp) } }

        key("Esc", tr("Échap")) { view.pressKey(RdpInput.VK_ESCAPE) }
        key("Tab") { view.pressKey(RdpInput.VK_TAB) }
        key("Ctrl", on = RdpInput.VK_LCONTROL in mods) { view.toggleModifier(RdpInput.VK_LCONTROL) }
        key("Alt", on = RdpInput.VK_LMENU in mods) { view.toggleModifier(RdpInput.VK_LMENU) }
        key(tr("Maj"), on = RdpInput.VK_LSHIFT in mods) { view.toggleModifier(RdpInput.VK_LSHIFT) }
        key("⊞", tr("Touche Windows"), on = RdpInput.VK_LWIN in mods) {
            // Alone it opens the Start menu; latched, it combines with the next key (⊞+E, ⊞+R…).
            if (RdpInput.VK_LWIN in mods) view.pressKey(RdpInput.VK_LWIN) else view.toggleModifier(RdpInput.VK_LWIN)
        }
        key("←", tr("Gauche")) { view.pressKey(RdpInput.VK_LEFT) }
        key("↑", tr("Haut")) { view.pressKey(RdpInput.VK_UP) }
        key("↓", tr("Bas")) { view.pressKey(RdpInput.VK_DOWN) }
        key("→", tr("Droite")) { view.pressKey(RdpInput.VK_RIGHT) }
        key(tr("Suppr")) { view.pressKey(RdpInput.VK_DELETE) }
        key(tr("Début")) { view.pressKey(RdpInput.VK_HOME) }
        key(tr("Fin")) { view.pressKey(RdpInput.VK_END) }
        key(tr("Pg↑"), tr("Page précédente")) { view.pressKey(RdpInput.VK_PRIOR) }
        key(tr("Pg↓"), tr("Page suivante")) { view.pressKey(RdpInput.VK_NEXT) }
        (1..12).forEach { n -> key("F$n") { view.pressKey(RdpInput.VK_F1 + n - 1) } }
        key("Ctrl+Alt+Suppr", tr("Ctrl+Alt+Suppr")) { view.ctrlAltDel() }
    }
}
