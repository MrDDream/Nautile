package com.nautile.app.ui.settings

import com.nautile.i18n.tr

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nautile.app.NautileApp
import com.nautile.app.backup.BackupPrefs
import com.nautile.app.rdp.RdpTouchMode
import com.nautile.app.session.SessionLogs
import com.nautile.app.session.TerminalFonts
import com.nautile.app.ui.common.NtCard
import com.nautile.app.ui.common.SectionLabel
import com.nautile.app.ui.hosts.relativeTime
import com.nautile.app.ui.theme.Nt
import com.nautile.rdp.RdpQuality
import java.text.Normalizer

/** One searchable entry of the settings hub. */
private class Entry(
    val icon: ImageVector,
    val tint: Color,
    val title: String,
    val detail: String,
    /** Extra words matched by the search, in both languages. */
    val keywords: String,
    val route: String,
    val dot: Color? = null,
) {
    fun matches(q: String) = q.isEmpty() || fold("$title $detail $keywords").contains(q)
}

private fun fold(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()

/** Settings tab: a search field, a protection summary and one row per topic, each showing its current value. */
@Composable
fun SettingsScreen(onNavigate: (String) -> Unit) {
    val app = LocalContext.current.applicationContext as NautileApp
    val light = Nt.palette.isLight
    val teal = if (light) Color(0xFF0F766E) else Color(0xFF3DD6C6)
    val lavender = if (light) Color(0xFF5B3FC4) else Color(0xFFB39DFF)

    val appTheme by app.appearance.theme.collectAsState()
    val accent by app.appearance.accent.collectAsState()
    val termTheme by app.terminalPrefs.theme.collectAsState()
    val font by app.terminalPrefs.font.collectAsState()
    val fontPx by app.terminalPrefs.fontSize.collectAsState()
    val rdp = app.sessions.rdp.prefs
    val touch by rdp.touchMode.collectAsState()
    val quality by rdp.quality.collectAsState()
    val lastBackup by app.backupPrefs.lastExport.collectAsState()
    val autoBackup by app.backupPrefs.auto.collectAsState()
    val syncOn by app.backupPrefs.syncEnabled.collectAsState()
    val lastSync by app.backupPrefs.lastSync.collectAsState()
    val density = LocalDensity.current
    val fontSp = (fontPx / density.fontScale / density.density).toInt()
    val lockOn = app.appLock.enabled
    val aiFirst = remember { app.ai.readyProviders().firstOrNull() }
    val aiReady = aiFirst != null
    val logCount = remember { SessionLogs.list(app).size }
    var query by rememberSaveable { mutableStateOf("") }
    val q = fold(query.trim())

    val fontName = if (font.id == TerminalFonts.SYSTEM_ID) tr("Monospace du système") else font.name
    val personal = listOf(
        Entry(Icons.Outlined.Palette, Nt.Accent, tr("Apparence"), "${appTheme.label} · ${accent.label} · ${app.appearance.language.label}",
            "theme thème sombre clair oled couleur bouton accent langue language dark light color", SettingsRoutes.APPEARANCE),
        Entry(Icons.Outlined.Terminal, Nt.Blue, tr("Terminal"), "$fontName $fontSp · ${termTheme.name}",
            "police font taille size couleurs colors curseur cursor historique history suggestions journaux logs clavier", SettingsRoutes.TERMINAL),
        Entry(Icons.Outlined.DesktopWindows, teal, tr("Bureau à distance"),
            (if (touch == RdpTouchMode.DIRECT) tr("Tactile direct") else tr("Pavé tactile")) + " · " + tr("qualité %1\$s", when (quality) {
                RdpQuality.AUTO -> tr("auto"); RdpQuality.FAST -> tr("rapide"); RdpQuality.HIGH -> tr("haute")
            }),
            "rdp windows remote desktop tactile touchpad souris mouse presse-papier clipboard plein écran", SettingsRoutes.RDP),
    )
    val services = listOf(
        Entry(Icons.Outlined.AutoAwesome, lavender, tr("Assistant IA"),
            if (aiReady) "${aiFirst?.label} · ${com.nautile.app.ai.prettyModel(app.ai.config(aiFirst ?: app.ai.provider).model)}" else tr("À configurer"),
            "ia ai intelligence claude anthropic openai gemini mistral deepseek grok ollama cle key api modele model", SettingsRoutes.AI,
            dot = if (aiReady) Nt.Green else Nt.Accent),
        Entry(Icons.Outlined.Lock, Nt.Green, tr("Sécurité, sauvegarde et synchro"),
            (if (lockOn) tr("Verrouillage activé") else tr("Verrouillage désactivé")) + " · " +
                if (syncOn) lastSync?.let { tr("synchro %1\$s", relativeTime(it)) } ?: tr("synchro activée") else
                (if (autoBackup) tr("sauvegarde automatique") else lastBackup?.let { tr("sauvegarde %1\$s", relativeTime(it)) } ?: tr("jamais sauvegardé")),
            "securite security biometrie empreinte fingerprint verrou lock sauvegarde backup export import drive onedrive cloud mot de passe synchronisation sync appareils devices",
            SettingsRoutes.SECURITY),
        Entry(Icons.AutoMirrored.Outlined.Article, Nt.TextSoft, tr("Journaux de session"),
            if (logCount == 0) tr("Aucun journal") else tr("%1\$s fichier(s)", logCount),
            "logs journaux sorties output enregistrement", SettingsRoutes.LOGS),
    )
    val visiblePersonal = personal.filter { it.matches(q) }
    val visibleServices = services.filter { it.matches(q) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        com.nautile.app.ui.common.PageHeader(tr("Réglages"), Modifier.padding(top = 0.dp))
        OutlinedTextField(
            value = query, onValueChange = { query = it },
            placeholder = { Text(tr("Rechercher"), color = Nt.Placeholder, fontSize = 14.sp, maxLines = 1) },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, tint = Nt.Muted) },
            trailingIcon = {
                if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, contentDescription = tr("Effacer la recherche"), tint = Nt.Muted) }
            },
            singleLine = true, shape = RoundedCornerShape(14.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Nt.Accent, unfocusedBorderColor = Nt.Border,
                focusedContainerColor = Nt.Surface, unfocusedContainerColor = Nt.Surface,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        if (q.isEmpty()) ProtectionCard(lockOn, if (syncOn) lastSync ?: lastBackup else lastBackup, syncOn) { onNavigate(SettingsRoutes.SECURITY) }

        if (visiblePersonal.isNotEmpty()) {
            SectionLabel(tr("Personnalisation"), Modifier.padding(top = 4.dp))
            EntryCard(visiblePersonal, onNavigate)
        }
        if (visibleServices.isNotEmpty()) {
            SectionLabel(tr("Services et données"), Modifier.padding(top = 4.dp))
            EntryCard(visibleServices, onNavigate)
        }
        if (visiblePersonal.isEmpty() && visibleServices.isEmpty()) {
            Text(tr("Aucun réglage pour « %1\$s »", query.trim()), color = Nt.Muted, fontSize = 14.sp,
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }

        Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            com.nautile.app.ui.common.NautileWordmark(fontSize = 20.sp)
            Text(tr("Version %1\$s · client SSH et RDP libre (GPLv3)", com.nautile.app.BuildConfig.VERSION_NAME), color = Nt.Muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun EntryCard(entries: List<Entry>, onNavigate: (String) -> Unit) {
    NtCard {
        entries.forEachIndexed { i, e ->
            if (i > 0) HorizontalDivider(color = Nt.Border)
            NavRow(e.icon, e.tint, e.title, e.detail, onClick = { onNavigate(e.route) }) {
                e.dot?.let { Box(Modifier.size(8.dp).clip(CircleShape).background(it)) }
            }
        }
    }
}

/** What protects the user's data, and the one thing to do if any. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProtectionCard(lockOn: Boolean, lastBackup: Long?, syncOn: Boolean, onOpen: () -> Unit) {
    val stale = BackupPrefs.isStale(lastBackup)
    val todo = listOf(!lockOn, stale).count { it }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Nt.Surface)
            .border(1.dp, if (todo > 0) Nt.Accent.copy(alpha = 0.45f) else Nt.Border, RoundedCornerShape(18.dp))
            .clickable(role = Role.Button, onClick = onOpen).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconTile(Icons.Outlined.VerifiedUser, if (todo > 0) Nt.Accent else Nt.Green)
            Column(Modifier.weight(1f)) {
                Text(tr("Vos données restent sur l'appareil"), style = MaterialTheme.typography.titleMedium)
                Text(
                    when (todo) {
                        0 -> tr("Tout est en ordre")
                        1 -> tr("Une action recommandée")
                        else -> tr("%1\$s actions recommandées", todo)
                    },
                    color = Nt.Muted, fontSize = 12.sp,
                )
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip(tr("✓ Base chiffrée"), ok = true)
            Chip(if (lockOn) tr("✓ Verrouillage") else tr("Verrouillage désactivé"), ok = lockOn)
            if (syncOn) Chip(tr("✓ Synchro"), ok = true)
            Chip(
                when {
                    lastBackup == null -> tr("Aucune sauvegarde")
                    stale -> tr("Sauvegarde %1\$s", relativeTime(lastBackup))
                    else -> tr("✓ Sauvegarde %1\$s", relativeTime(lastBackup))
                },
                ok = !stale,
            )
        }
    }
}

@Composable
private fun Chip(text: String, ok: Boolean) {
    val c = if (ok) Nt.Green else Nt.Accent
    Box(Modifier.height(26.dp).clip(RoundedCornerShape(13.dp)).background(c.copy(alpha = 0.13f)).padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
        Text(text, color = c, fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
    }
}
