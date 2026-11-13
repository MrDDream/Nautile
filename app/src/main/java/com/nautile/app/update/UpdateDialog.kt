package com.nautile.app.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nautile.app.BuildConfig
import com.nautile.app.ui.theme.Nt
import com.nautile.i18n.tr
import kotlinx.coroutines.launch
import java.io.File

/** The pop-up of a new version: its notes, then « Télécharger et installer » with the download's progress. */
@Composable
fun UpdateDialog() {
    val context = LocalContext.current
    val release by UpdateChecker.available.collectAsState()
    val r = release ?: return
    val scope = rememberCoroutineScope()
    var progress by remember(r.tag) { mutableStateOf<Float?>(null) }
    var error by remember(r.tag) { mutableStateOf<String?>(null) }
    var downloaded by remember(r.tag) { mutableStateOf<File?>(null) }
    val apk = remember(r.tag) { UpdateChecker.apkFor(r) }
    val busy = progress != null && downloaded == null
    AlertDialog(
        onDismissRequest = { if (!busy) UpdateChecker.later() },
        containerColor = Nt.Surface,
        title = { Text(tr("Nautile %1\$s est disponible", r.tag)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(tr("Vous avez la version %1\$s", BuildConfig.VERSION_NAME), color = Nt.Muted, fontSize = 13.sp)
                // The release notes, Markdown marks taken out.
                Text(
                    plain(r.notes).ifBlank { tr("Pas de notes pour cette version.") },
                    color = Nt.TextSoft, fontSize = 14.sp, lineHeight = 20.sp,
                    modifier = Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()),
                )
                progress?.let {
                    LinearProgressIndicator(progress = { it }, color = Nt.Accent, trackColor = Nt.KeyBg, modifier = Modifier.fillMaxWidth())
                }
                error?.let { Text(it, color = Nt.Pink, fontSize = 13.sp) }
                if (downloaded != null) Text(tr("Autorisez Nautile à installer des applis, puis touchez « Installer »."), color = Nt.Muted, fontSize = 12.sp)
            }
        },
        dismissButton = {
            if (!busy) {
                TextButton(onClick = { UpdateChecker.skip(context) }) { Text(tr("Ignorer cette version"), color = Nt.Muted) }
                TextButton(onClick = { UpdateChecker.later() }) { Text(tr("Plus tard"), color = Nt.TextSoft) }
            }
        },
        confirmButton = {
            Button(
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(containerColor = Nt.Accent, contentColor = Nt.OnAccent),
                onClick = {
                    downloaded?.let { f -> if (UpdateChecker.install(context, f)) UpdateChecker.later(); return@Button }
                    if (apk == null) {
                        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(r.page)))
                        return@Button
                    }
                    error = null
                    progress = 0f
                    scope.launch {
                        UpdateChecker.download(context, r, apk) { done, total -> progress = if (total > 0) done.toFloat() / total else 0f }
                            .onSuccess { f -> downloaded = f; if (UpdateChecker.install(context, f)) UpdateChecker.later() }
                            .onFailure { e -> progress = null; error = e.message ?: e.javaClass.simpleName }
                    }
                },
            ) {
                Text(
                    when {
                        downloaded != null -> tr("Installer")
                        busy -> tr("Téléchargement…")
                        apk == null -> tr("Ouvrir la page")
                        else -> tr("Télécharger et installer")
                    },
                )
            }
        },
    )
}

/** Markdown as plain text: titles, bold and list marks removed. */
internal fun plain(md: String): String = md.lineSequence().joinToString("\n") { line ->
    line.trimEnd()
        .replace(Regex("^#{1,6}\\s*"), "")
        .replace(Regex("^\\s*[-*]\\s+"), "• ")
        .replace("**", "").replace("`", "")
}.trim()
