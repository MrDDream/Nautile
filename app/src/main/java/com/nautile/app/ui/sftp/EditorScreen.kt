package com.nautile.app.ui.sftp

import com.nautile.i18n.tr

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nautile.app.NautileApp
import com.nautile.app.session.ChannelBackend
import com.nautile.app.ui.theme.Nt
import kotlinx.coroutines.launch

private const val MAX_EDIT_BYTES = 2L * 1024 * 1024

/** Plain-text editor for a remote file, saved back over SFTP. */
@Composable
fun EditorScreen(hostId: Long, path: String, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as NautileApp
    val pool = app.sessions.sftp
    var text by remember { mutableStateOf<String?>(null) }
    var original by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(path) {
        runCatching {
            pool.with(hostId) { s ->
                val size = s.stat(path).size
                require(size <= MAX_EDIT_BYTES) { tr("Fichier trop gros pour l'éditeur (%1\$s Ko, max 2 Mo)", size / 1024) }
                val bytes = s.openRead(path).use { it.readBytes() }
                require(bytes.take(8000).none { it == 0.toByte() }) { tr("Fichier binaire : ouverture impossible") }
                bytes.decodeToString()
            }
        }.onSuccess { text = it; original = it }.onFailure { error = it.message ?: ChannelBackend.describe(it) }
    }

    Column(Modifier.fillMaxSize().background(Nt.Bg).imePadding()) {
        Row(Modifier.padding(start = 4.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("Retour")) }
            Column(Modifier.weight(1f)) {
                Text(path.substringAfterLast('/') + if (text != null && text != original) " •" else "", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Text(path, fontFamily = Nt.Mono, fontSize = 11.sp, color = Nt.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TextButton(
                enabled = text != null && text != original && !saving,
                onClick = {
                    val t = text ?: return@TextButton
                    saving = true
                    scope.launch {
                        runCatching { pool.with(hostId) { s -> s.openWrite(path).use { it.write(t.toByteArray()) } } }
                            .onSuccess { original = t; snackbar.showSnackbar(tr("Enregistré")) }
                            .onFailure { snackbar.showSnackbar(tr("Échec : %1\$s", ChannelBackend.describe(it))) }
                        saving = false
                    }
                },
            ) { Text(tr("Enregistrer"), color = Nt.Accent, fontWeight = FontWeight.SemiBold) }
        }
        Box(Modifier.weight(1f)) {
            when {
                error != null -> Text(error!!, color = Nt.Pink, modifier = Modifier.padding(20.dp))
                text == null -> CircularProgressIndicator(color = Nt.Accent, modifier = Modifier.align(Alignment.Center))
                else -> BasicTextField(
                    value = text!!, onValueChange = { text = it },
                    textStyle = TextStyle(fontFamily = Nt.Mono, fontSize = 13.sp, color = Nt.Text, lineHeight = 19.sp),
                    cursorBrush = SolidColor(Nt.Accent),
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(14.dp),
                )
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }
    }
}
