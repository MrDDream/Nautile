package com.nautile.app.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nautile.app.ui.theme.Nt
import com.nautile.i18n.tr

/** « Fermer docker-01 ? » before ending a live session; [onConfirm] tells whether to stop asking. */
@Composable
fun ConfirmCloseDialog(name: String, desktop: Boolean = false, onDismiss: () -> Unit, onConfirm: (dontAskAgain: Boolean) -> Unit) {
    var dontAsk by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Nt.Surface,
        title = { Text(tr("Fermer %1\$s ?", name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (desktop) tr("La connexion se termine ; la session Windows reste ouverte sur le serveur.")
                    else tr("La connexion se termine : les commandes en cours sur le serveur s'arrêtent, sauf dans tmux ou screen."),
                    color = Nt.TextSoft, fontSize = 14.sp,
                )
                Row(Modifier.clickable { dontAsk = !dontAsk }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(dontAsk, { dontAsk = it }, colors = CheckboxDefaults.colors(checkedColor = Nt.Accent))
                    Text(tr("Ne plus demander"), fontSize = 14.sp, color = Nt.TextSoft)
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Annuler"), color = Nt.TextSoft) } },
        confirmButton = { TextButton(onClick = { onConfirm(dontAsk) }) { Text(tr("Fermer"), color = Nt.Pink, fontWeight = FontWeight.SemiBold) } },
    )
}

/** « Supprimer X ? » with Annuler and a pink [action] button; [message] is the optional explanation. */
@Composable
fun ConfirmDialog(title: String, message: String?, action: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Nt.Surface,
        title = { Text(title) },
        text = message?.let { { Text(it, color = Nt.TextSoft) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Annuler"), color = Nt.TextSoft) } },
        confirmButton = { TextButton(onClick = onConfirm) { Text(action, color = Nt.Pink, fontWeight = FontWeight.SemiBold) } },
    )
}

/** Explanation as a bulleted list of [lines], closed with « Compris ». */
@Composable
fun InfoDialog(title: String, lines: List<String>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Nt.Surface,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                lines.forEach { line ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("•", color = Nt.Accent, fontSize = 14.sp)
                        Text(line, color = Nt.TextSoft, fontSize = 14.sp, lineHeight = 20.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("Compris"), color = Nt.Accent) } },
    )
}
