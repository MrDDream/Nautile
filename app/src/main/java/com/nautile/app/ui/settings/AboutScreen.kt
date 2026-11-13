package com.nautile.app.ui.settings

import com.nautile.i18n.tr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nautile.app.BuildConfig
import com.nautile.app.ui.common.NtCard
import com.nautile.app.ui.common.SectionLabel
import com.nautile.app.ui.theme.Nt
import com.nautile.app.update.UpdateChecker
import kotlinx.coroutines.launch

/** « À propos »: the version, the update check at start and « Vérifier maintenant ». */
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var auto by remember { mutableStateOf(UpdateChecker.enabled(context)) }
    var checking by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    SettingsPage(tr("À propos"), onBack) {
        com.nautile.app.ui.common.NautileWordmark(fontSize = 26.sp)
        Text(tr("Version %1\$s · client SSH et RDP libre (GPLv3)", BuildConfig.VERSION_NAME), color = Nt.Muted, fontSize = 13.sp)
        SectionLabel(tr("Mises à jour"), Modifier.padding(top = 8.dp))
        NtCard {
            ToggleRow(
                tr("Vérifier au démarrage"),
                tr("Une fois par jour au plus, sur GitHub (MrDDream/Nautile) ; rien n'est installé sans votre accord."),
                auto,
            ) { auto = it; UpdateChecker.setEnabled(context, it) }
            HorizontalDivider(color = Nt.Border)
            Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(tr("Nautile %1\$s", BuildConfig.VERSION_NAME), fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    val (ok, text) = status ?: (true to (if (checking) tr("Recherche…") else tr("Version %1\$s", BuildConfig.VERSION_NAME)))
                    Text(text, color = if (ok) Nt.Muted else Nt.Pink, fontSize = 12.sp)
                }
                OutlinedButton(enabled = !checking, onClick = {
                    checking = true
                    status = null
                    scope.launch {
                        UpdateChecker.checkNow(context)
                            .onSuccess { r -> status = true to (r?.let { tr("%1\$s disponible", it.tag) } ?: tr("Nautile est à jour (v%1\$s)", BuildConfig.VERSION_NAME)) }
                            .onFailure { e -> status = false to (e.message ?: e.javaClass.simpleName) }
                        checking = false
                    }
                }) { Text(tr("Vérifier maintenant")) }
            }
        }
        Hint(tr("L'APK vient de la page des versions sur GitHub, vérifié par sa somme SHA-256, puis Android l'installe par-dessus en gardant vos données (même clé de signature)."))
        SectionLabel(tr("Code source"), Modifier.padding(top = 8.dp))
        NtCard {
            LinkRow(tr("Nautile sur GitHub"), "github.com/${UpdateChecker.REPO}", "") {
                context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/${UpdateChecker.REPO}")))
            }
        }
    }
}
