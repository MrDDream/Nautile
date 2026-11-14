package com.nautile.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nautile.app.ui.theme.Nt
import com.nautile.i18n.tr

/**
 * The database cannot be opened now: the Keystore that keeps its key failed (busy, crashed…).
 * Nothing was reset or deleted; [onRetry] tries again. [onStartAfresh], after a confirmation, sets the
 * data aside (kept on the phone) and starts an empty database, for when retrying never works.
 * [busy]: a try is under way (off the main thread), the buttons wait for it.
 */
@Composable
fun StartupErrorScreen(error: Throwable, onRetry: () -> Unit, onStartAfresh: () -> Unit, busy: Boolean = false) {
    var confirm by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(Nt.Bg).systemBarsPadding(), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 380.dp).padding(horizontal = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(64.dp).background(Nt.Pink.copy(alpha = 0.14f), RoundedCornerShape(20.dp)), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = Nt.Pink, modifier = Modifier.size(30.dp))
            }
            Spacer(Modifier.height(18.dp))
            Text(tr("Impossible d'ouvrir vos données"), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Spacer(Modifier.height(10.dp))
            Text(
                if (error is com.nautile.security.KeyLostException) {
                    // Set aside by itself only once lost at launches after two distinct boots (see NautileApp.openDatabase).
                    tr("La clé de la base de données semble perdue. Rien n'a été effacé. Redémarrez le téléphone : si elle reste illisible en rouvrant Nautile après le redémarrage, vos données seront mises de côté sur le téléphone et une base vide sera créée.")
                } else {
                    tr("L'Android Keystore, qui garde la clé de la base de données, n'a pas répondu. Rien n'a été effacé. Réessayez dans un instant ; si cela persiste, redémarrez le téléphone.")
                },
                color = Nt.TextSoft, fontSize = 14.sp, lineHeight = 20.sp, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                tr("Détail : %1\$s", generateSequence(error) { it.cause }.last().let { it.message ?: it.javaClass.simpleName }),
                color = Nt.Muted, fontSize = 12.sp, lineHeight = 16.sp, textAlign = TextAlign.Center, maxLines = 3,
            )
            Spacer(Modifier.height(22.dp))
            Button(
                onClick = onRetry,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Nt.Accent, contentColor = Nt.OnAccent),
            ) { Text(tr("Réessayer"), fontWeight = FontWeight.SemiBold) }
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = { confirm = true }, enabled = !busy) { Text(tr("Repartir de zéro…"), color = Nt.TextSoft) }
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        containerColor = Nt.Surface,
        title = { Text(tr("Repartir de zéro ?")) },
        text = {
            Text(
                tr("Vos données actuelles seront mises de côté sur le téléphone (rien n'est effacé), mais Nautile ne pourra probablement plus les ouvrir. Une base vide sera créée : restaurez ensuite une sauvegarde ou synchronisez. À faire seulement si un redémarrage n'a pas suffi."),
                color = Nt.TextSoft, fontSize = 14.sp, lineHeight = 20.sp,
            )
        },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text(tr("Annuler"), color = Nt.TextSoft) } },
        confirmButton = {
            TextButton(onClick = { confirm = false; onStartAfresh() }) { Text(tr("Repartir de zéro"), color = Nt.Pink) }
        },
    )
}
