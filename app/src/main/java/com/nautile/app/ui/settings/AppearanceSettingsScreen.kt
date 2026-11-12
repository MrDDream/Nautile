package com.nautile.app.ui.settings

import com.nautile.i18n.tr

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nautile.app.NautileApp
import com.nautile.app.ui.common.NtCard
import com.nautile.app.ui.common.SectionLabel
import com.nautile.app.ui.common.Segmented
import com.nautile.app.ui.theme.AppLanguage
import com.nautile.app.ui.theme.AppTheme
import com.nautile.app.ui.theme.Nt

/** « Apparence »: app theme, language and button colour. */
@Composable
fun AppearanceSettingsScreen(onBack: () -> Unit, onOpenAccent: () -> Unit) {
    val appearance = (LocalContext.current.applicationContext as NautileApp).appearance
    val appTheme by appearance.theme.collectAsState()
    val accent by appearance.accent.collectAsState()
    var pendingLanguage by remember { mutableStateOf<AppLanguage?>(null) }

    SettingsPage(tr("Apparence"), onBack) {
        SectionLabel(tr("Thème de l'app"))
        Segmented(AppTheme.entries.map { it to it.label }, appTheme, appearance::setTheme)
        Hint(tr("Le terminal garde ses propres couleurs : réglez-les dans Terminal."))

        SectionLabel(tr("Couleur"), Modifier.padding(top = 8.dp))
        NtCard { LinkRow(tr("Couleur des boutons"), tr("Teinte et style des actions principales"), accent.label, onOpenAccent) }

        SectionLabel(tr("Langue"), Modifier.padding(top = 8.dp))
        Segmented(AppLanguage.entries.map { it to it.label }, appearance.language, onSelect = { l ->
            if (l != appearance.language) pendingLanguage = l
        })
    }

    pendingLanguage?.let { l ->
        AlertDialog(
            onDismissRequest = { pendingLanguage = null }, containerColor = Nt.Surface,
            title = { Text(l.label) },
            text = { Text(tr("L'app va redémarrer pour appliquer la langue. Les sessions ouvertes seront fermées."), color = Nt.TextSoft) },
            dismissButton = { TextButton(onClick = { pendingLanguage = null }) { Text(tr("Annuler"), color = Nt.TextSoft) } },
            confirmButton = {
                val ctx = LocalContext.current
                TextButton(onClick = {
                    appearance.setLanguage(l)
                    ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.let { intent ->
                        ctx.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK))
                    }
                    Runtime.getRuntime().exit(0)
                }) { Text(tr("Redémarrer"), color = Nt.Accent) }
            },
        )
    }
}
