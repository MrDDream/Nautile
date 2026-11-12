package com.nautile.app.ui.settings

import com.nautile.i18n.tr

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.nautile.app.session.SessionLogs
import com.nautile.app.ui.sftp.humanSize
import com.nautile.app.ui.theme.Nt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Recorded sessions: list, readable view, share, delete. */
@Composable
fun LogsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val files = remember(refresh) { SessionLogs.list(context) }
    var open by remember { mutableStateOf<File?>(null) }
    var text by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(open) { text = open?.let { f -> withContext(Dispatchers.IO) { SessionLogs.readable(f) } } }

    fun share(f: File) {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", f)
        context.startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            tr("Partager le journal"),
        ))
    }

    Column(Modifier.fillMaxSize().background(Nt.Bg)) {
        Row(Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (open != null) open = null else onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("Retour")) }
            Text(open?.name ?: tr("Journaux de session"), fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.weight(1f), maxLines = 1)
            open?.let { f ->
                IconButton(onClick = { share(f) }) { Icon(Icons.Outlined.Share, contentDescription = tr("Partager"), tint = Nt.TextSoft) }
                IconButton(onClick = { f.delete(); open = null; refresh++ }) { Icon(Icons.Outlined.Delete, contentDescription = tr("Supprimer"), tint = Nt.Pink) }
            }
        }
        HorizontalDivider(color = Nt.Border)
        if (open == null) {
            if (files.isEmpty()) Text(tr("Aucun journal. Activez « Enregistrer les sessions » dans les réglages."), color = Nt.Muted, modifier = Modifier.padding(20.dp))
            LazyColumn {
                items(files, key = { it.name }) { f ->
                    Column(Modifier.fillMaxWidth().clickable { open = f }.padding(horizontal = 20.dp, vertical = 12.dp)) {
                        Text(f.name.removeSuffix(".log"), fontFamily = Nt.Mono, fontSize = 13.sp)
                        Text(humanSize(f.length()), color = Nt.Muted, fontSize = 12.sp)
                    }
                }
            }
        } else Box(Modifier.fillMaxSize()) {
            val t = text
            if (t == null) CircularProgressIndicator(color = Nt.Accent, modifier = Modifier.align(Alignment.Center))
            else SelectionContainer {
                Text(
                    t, fontFamily = Nt.Mono, fontSize = 12.sp, color = Nt.TextSoft,
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(14.dp),
                )
            }
        }
    }
}
