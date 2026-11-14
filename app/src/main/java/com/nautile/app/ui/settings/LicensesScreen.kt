package com.nautile.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import com.nautile.app.ui.common.NtCard
import com.nautile.app.ui.theme.Nt
import com.nautile.i18n.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Route of « Licences tierces » (Settings › À propos). */
const val LICENSES_ROUTE = "settings/licenses"

private const val DIR = "licenses"
private const val NOTICE = "NOTICE.md"
private const val DEPENDENCIES = "dependencies.txt"

/** The notices and licence texts of assets/licenses (scripts/gen-licenses.py android): a list, then one text. */
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val files = remember {
        val all = context.assets.list(DIR).orEmpty().toList()
        listOf(NOTICE, DEPENDENCIES).filter { it in all } + all.filter { it != NOTICE && it != DEPENDENCIES }.sortedBy { it.lowercase() }
    }
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    val current = open
    if (current == null) {
        SettingsPage(tr("Licences tierces"), onBack) {
            Hint(tr("Nautile est un logiciel libre (GPLv3) construit avec ces bibliothèques, polices et codes tiers, chacun sous sa propre licence."))
            NtCard {
                files.forEachIndexed { i, name ->
                    if (i > 0) HorizontalDivider(color = Nt.Border)
                    LinkRow(title(name), detail(name), "") { open = name }
                }
            }
        }
    } else {
        BackHandler { open = null }
        var text by remember(current) { mutableStateOf<String?>(null) }
        LaunchedEffect(current) {
            text = withContext(Dispatchers.IO) {
                runCatching { context.assets.open("$DIR/$current").bufferedReader().use { it.readText() } }.getOrElse { it.message.orEmpty() }
            }
        }
        SettingsPage(title(current), onBack = { open = null }) {
            SelectionContainer {
                Text(text ?: "", fontFamily = Nt.Mono, fontSize = 11.sp, lineHeight = 15.sp, color = Nt.TextSoft)
            }
        }
    }
}

private fun title(file: String): String = when (file) {
    NOTICE -> tr("Codes et polices tiers")
    DEPENDENCIES -> tr("Bibliothèques de l'application")
    else -> file.substringBeforeLast('.')
}

private fun detail(file: String): String = when (file) {
    NOTICE -> tr("Termux, FreeRDP, OpenSSL, cJSON, uriparser, Nerd Fonts, icônes")
    DEPENDENCIES -> tr("Chaque bibliothèque, sa version et sa licence")
    else -> tr("Texte de la licence")
}
