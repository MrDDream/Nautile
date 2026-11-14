package com.nautile.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.nautile.app.CrashReporter
import com.nautile.app.NautileApp
import com.nautile.app.rdp.RdpScreen
import com.nautile.app.ui.appearance.AccentScreen
import com.nautile.app.ui.appearance.TerminalAppearanceScreen
import com.nautile.app.ui.connections.ConnectionsScreen
import com.nautile.app.ui.hosts.HostEditScreen
import com.nautile.app.ui.hosts.HostsScreen
import com.nautile.app.ui.hosts.QuickTarget
import com.nautile.app.ui.keys.KeychainScreen
import com.nautile.app.ui.monitor.MonitorScreen
import com.nautile.app.ui.settings.AboutScreen
import com.nautile.app.ui.settings.AiSettingsScreen
import com.nautile.app.ui.settings.AppearanceSettingsScreen
import com.nautile.app.ui.settings.BackupSettingsScreen
import com.nautile.app.ui.settings.LICENSES_ROUTE
import com.nautile.app.ui.settings.LicensesScreen
import com.nautile.app.ui.settings.LogsScreen
import com.nautile.app.ui.settings.RdpSettingsScreen
import com.nautile.app.ui.settings.SecuritySettingsScreen
import com.nautile.app.ui.settings.SettingsRoutes
import com.nautile.app.ui.settings.SettingsScreen
import com.nautile.app.ui.sftp.EditorScreen
import com.nautile.app.ui.sftp.SftpScreen
import com.nautile.app.ui.terminal.PromptDialog
import com.nautile.app.ui.terminal.TerminalScreen
import com.nautile.app.ui.theme.Nt
import com.nautile.app.ui.tools.SnippetsScreen
import com.nautile.app.ui.tools.TunnelsScreen
import com.nautile.app.update.UpdateDialog
import com.nautile.data.model.isDesktop
import com.nautile.i18n.tr
import kotlinx.coroutines.launch

private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    Hosts("hosts", tr("Hôtes"), Icons.Outlined.Dns),
    Connections("connections", tr("Connexions"), Icons.Outlined.History),
    Snippets("snippets", tr("Snippets"), Icons.Outlined.Terminal),
    Settings("settings", tr("Réglages"), Icons.Outlined.Tune),
}

/** Pages opened from the Hosts header (no bottom bar). */
private object Pages {
    const val KEYS = "keys"
    const val TUNNELS = "tunnels"
}

@Composable
fun NautileRoot() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val showBar = Tab.entries.any { it.route == route }
    val app = LocalContext.current.applicationContext as NautileApp
    val sessions by app.sessions.sessions.collectAsStateWithLifecycle()
    val desktops by app.sessions.rdp.list.collectAsStateWithLifecycle()
    val prompt by app.sessions.prompt.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    /** Connects to a machine typed in the search bar, without saving it. */
    fun quickConnect(target: QuickTarget) {
        val host = target.toHost()
        scope.launch {
            if (host.protocol.isDesktop) {
                app.sessions.rdp.open(host)
                    .onSuccess { nav.navigate("rdp/${it.id}") { launchSingleTop = true } }
                    .onFailure { snackbar.showSnackbar(it.message ?: tr("Connexion impossible")) }
            } else app.sessions.open(host)
                .onSuccess { nav.navigate("terminal/${it.id}") { launchSingleTop = true } }
                .onFailure { snackbar.showSnackbar(it.message ?: tr("Connexion impossible")) }
        }
    }

    fun connect(hostId: Long) {
        scope.launch {
            if (app.hostRepository.get(hostId)?.protocol?.isDesktop == true) {
                app.sessions.rdp.open(hostId)
                    .onSuccess { nav.navigate("rdp/${it.id}") { launchSingleTop = true } }
                    .onFailure { snackbar.showSnackbar(it.message ?: tr("Connexion impossible")) }
                return@launch
            }
            app.sessions.open(hostId)
                .onSuccess { nav.navigate("terminal/${it.id}") { launchSingleTop = true } }
                .onFailure { snackbar.showSnackbar(it.message ?: tr("Connexion impossible")) }
        }
    }

    val pending by app.pendingConnect.collectAsStateWithLifecycle()
    LaunchedEffect(pending) {
        pending?.let { id -> app.pendingConnect.value = null; connect(id) }
    }

    // Kept here, above the lock: relocking (or the lock screen) never brings back a question already closed.
    val context = LocalContext.current
    val reset by app.dataReset.collectAsStateWithLifecycle()
    var crashReport by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(CrashReporter.pending(context)) }
    // Closed without an answer: asked again at the next start, not at each unlock or rotation (NautileApp).
    var askConsent by remember { mutableStateOf(!app.updateConsentAsked && com.nautile.app.update.UpdateChecker.needsConsent(context)) }
    val release by com.nautile.app.update.UpdateChecker.available.collectAsStateWithLifecycle()

    // Android 17+: without « Nearby devices », every machine on the local network times out. Asked once per
    // start, once unlocked; the connection errors say what to do if it is refused.
    val localNetwork = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { com.nautile.app.LocalNetwork.needsPermission(context) }
    val locked = LocalAppLocked.current
    LaunchedEffect(locked) {
        if (!locked && !app.localNetworkAsked && com.nautile.app.LocalNetwork.needsPermission(context)) {
            app.localNetworkAsked = true
            localNetwork.launch(com.nautile.app.LocalNetwork.PERMISSION)
        }
    }

    // While locked (the lock screen is above): no window of these appears, not even for a frame on the way back.
    // One at a time, the most urgent first; the next one shows once it is closed.
    if (!LocalAppLocked.current) {
        val report = crashReport
        val question = prompt
        when {
            question != null -> PromptDialog(question)
            reset -> DataResetDialog(app)
            report != null -> CrashReportDialog(report) { crashReport = null }
            askConsent -> UpdateConsentDialog(app) { askConsent = false }
            release != null -> UpdateDialog()
        }
    }

    Scaffold(
        containerColor = Nt.Bg,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (showBar) NavigationBar(containerColor = Nt.Bar) {
                Tab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = route == tab.route,
                        onClick = {
                            nav.navigate(tab.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            val open = if (tab == Tab.Connections) sessions.size + desktops.size else 0
                            if (open > 0) BadgedBox(badge = {
                                Badge(containerColor = Nt.Green, contentColor = Nt.Bg) { Text("$open") }
                            }) { Icon(tab.icon, contentDescription = null) } else Icon(tab.icon, contentDescription = null)
                        },
                        label = {
                            // One line whatever the system font size: « Connections » used to break in two.
                            val d = LocalDensity.current
                            CompositionLocalProvider(
                                LocalDensity provides Density(d.density, d.fontScale.coerceAtMost(1.1f)),
                            ) {
                                Text(tab.label, maxLines = 1, softWrap = false, letterSpacing = 0.1.sp,
                                    overflow = TextOverflow.Ellipsis)
                            }
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Nt.Accent,
                            selectedTextColor = Nt.Accent,
                            indicatorColor = Nt.Accent.copy(alpha = 0.14f),
                            unselectedIconColor = Nt.Muted,
                            unselectedTextColor = Nt.Muted,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = Tab.Hosts.route, modifier = Modifier.padding(padding).consumeWindowInsets(padding)) {
            composable(Tab.Hosts.route) {
                HostsScreen(
                    onConnect = ::connect,
                    onQuickConnect = ::quickConnect,
                    onEditHost = { nav.navigate("host/$it") },
                    onSftp = { nav.navigate("sftp/$it") },
                    onNewHost = { kind -> nav.navigate("host/0?kind=${kind.name}") },
                    onKeys = { nav.navigate(Pages.KEYS) },
                    onTunnels = { nav.navigate(Pages.TUNNELS) },
                )
            }
            composable(Tab.Connections.route) {
                ConnectionsScreen(
                    onOpenTerminal = { nav.navigate("terminal/${it}") { launchSingleTop = true } },
                    onOpenDesktop = { nav.navigate("rdp/$it") { launchSingleTop = true } },
                    onConnect = ::connect,
                    onQuickConnect = ::quickConnect,
                    onSave = { target -> nav.navigate("host/0?kind=${target.protocol.name}&target=" + Uri.encode(target.label)) },
                )
            }
            composable(
                "host/{id}?kind={kind}&target={target}",
                arguments = listOf(
                    navArgument("id") { type = NavType.LongType },
                    navArgument("kind") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("target") { type = NavType.StringType; nullable = true; defaultValue = null },
                ),
            ) { HostEditScreen(onDone = { nav.popBackStack() }, onConnect = { id -> nav.popBackStack(); connect(id) }) }
            composable(
                "sftp/{hostId}?path={path}",
                arguments = listOf(
                    navArgument("hostId") { type = NavType.LongType },
                    navArgument("path") { type = NavType.StringType; nullable = true; defaultValue = null },
                ),
            ) {
                SftpScreen(
                    onBack = { nav.popBackStack() },
                    onEdit = { h, p -> nav.navigate("edit/$h?path=" + Uri.encode(p)) },
                )
            }
            composable("monitor/{hostId}", arguments = listOf(navArgument("hostId") { type = NavType.LongType })) { entry ->
                MonitorScreen(entry.arguments?.getLong("hostId") ?: 0L, onBack = { nav.popBackStack() })
            }
            composable(
                "edit/{hostId}?path={path}",
                arguments = listOf(navArgument("hostId") { type = NavType.LongType }, navArgument("path") { type = NavType.StringType }),
            ) { entry ->
                EditorScreen(
                    hostId = entry.arguments?.getLong("hostId") ?: 0L,
                    path = entry.arguments?.getString("path").orEmpty(),
                    onBack = { nav.popBackStack() },
                )
            }
            composable(
                "terminal/{sid}",
                arguments = listOf(navArgument("sid") { type = NavType.LongType }),
            ) { entry ->
                TerminalScreen(
                    initialSessionId = entry.arguments?.getLong("sid") ?: 0L,
                    onBack = { if (!nav.popBackStack(Tab.Hosts.route, inclusive = false)) nav.navigate(Tab.Hosts.route) },
                    onNewSession = { nav.popBackStack(Tab.Hosts.route, inclusive = false) },
                    onSftp = { hostId -> nav.navigate("sftp/$hostId") },
                    onMonitor = { hostId -> nav.navigate("monitor/$hostId") },
                    onOpenTerminalSettings = { nav.navigate(SettingsRoutes.TERMINAL) },
                )
            }
            composable("rdp/{sid}", arguments = listOf(navArgument("sid") { type = NavType.LongType })) { entry ->
                RdpScreen(
                    sessionId = entry.arguments?.getLong("sid") ?: 0L,
                    onBack = { if (!nav.popBackStack(Tab.Hosts.route, inclusive = false)) nav.navigate(Tab.Hosts.route) },
                    onReplaced = { id -> nav.navigate("rdp/$id") { popUpTo("rdp/{sid}") { inclusive = true } } },
                )
            }
            composable(Pages.KEYS) { KeychainScreen(onBack = { nav.popBackStack() }) }
            composable(Pages.TUNNELS) { TunnelsScreen(onBack = { nav.popBackStack() }) }
            composable(Tab.Snippets.route) {
                SnippetsScreen(onOpenSession = { nav.navigate("terminal/${it}") { launchSingleTop = true } })
            }
            composable(Tab.Settings.route) { SettingsScreen(onNavigate = { nav.navigate(it) }) }
            composable(SettingsRoutes.APPEARANCE) {
                AppearanceSettingsScreen(onBack = { nav.popBackStack() }, onOpenAccent = { nav.navigate(SettingsRoutes.ACCENT) })
            }
            composable(SettingsRoutes.RDP) { RdpSettingsScreen(onBack = { nav.popBackStack() }) }
            composable(SettingsRoutes.AI) { AiSettingsScreen(onBack = { nav.popBackStack() }) }
            composable(SettingsRoutes.SECURITY) {
                SecuritySettingsScreen(
                    onBack = { nav.popBackStack() },
                    onOpenKnownHosts = {
                        app.showKnownHosts.value = true
                        nav.navigate(Pages.KEYS) { launchSingleTop = true }
                    },
                )
            }
            composable(SettingsRoutes.BACKUP) {
                BackupSettingsScreen(onBack = { nav.popBackStack() })
            }
            composable(SettingsRoutes.TERMINAL) {
                TerminalAppearanceScreen(onBack = { nav.popBackStack() }, onOpenLogs = { nav.navigate(SettingsRoutes.LOGS) })
            }
            composable(SettingsRoutes.ACCENT) { AccentScreen(onBack = { nav.popBackStack() }) }
            composable(SettingsRoutes.LOGS) { LogsScreen(onBack = { nav.popBackStack() }) }
            composable(SettingsRoutes.ABOUT) { AboutScreen(onBack = { nav.popBackStack() }, onOpenLicenses = { nav.navigate(LICENSES_ROUTE) }) }
            composable(LICENSES_ROUTE) { LicensesScreen(onBack = { nav.popBackStack() }) }
        }
    }
}

/** After a crash: the anonymised report, to read, copy or post on GitHub (nothing is sent otherwise). */
@Composable
private fun CrashReportDialog(text: String, onClosed: () -> Unit) {
    val context = LocalContext.current
    fun dismiss() { CrashReporter.clear(context); onClosed() }
    AlertDialog(
        onDismissRequest = ::dismiss,
        containerColor = Nt.Surface,
        title = { Text(tr("Nautile s'est arrêté")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(tr("Désolé ! Vous pouvez signaler l'erreur sur GitHub : le rapport ci-dessous est anonymisé (ni adresse, ni nom d'hôte, ni utilisateur, ni mot de passe) et vous le relirez avant de le publier."),
                    color = Nt.TextSoft, fontSize = 14.sp, lineHeight = 20.sp)
                Text(
                    text.take(4000),
                    fontFamily = Nt.Mono, fontSize = 10.sp, lineHeight = 14.sp, color = Nt.Muted,
                    modifier = Modifier.heightIn(max = 220.dp).clip(RoundedCornerShape(10.dp))
                        .background(Nt.Bg).verticalScroll(rememberScrollState()).padding(10.dp),
                )
                TextButton(onClick = {
                    context.getSystemService(ClipboardManager::class.java)
                        ?.setPrimaryClip(ClipData.newPlainText(tr("Rapport de crash"), text))
                }) { Text(tr("Copier le rapport"), color = Nt.TextSoft) }
            }
        },
        dismissButton = { TextButton(onClick = ::dismiss) { Text(tr("Ignorer"), color = Nt.TextSoft) } },
        confirmButton = {
            Button(
                onClick = {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, CrashReporter.issueUrl(text))) }
                    dismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = Nt.Accent, contentColor = Nt.OnAccent),
            ) { Text(tr("Signaler sur GitHub"), fontWeight = FontWeight.SemiBold) }
        },
    )
}

/**
 * Asked once, the first time the app is unlocked (after an update from 0.4.0 too): whether to look for a new
 * version on GitHub by itself. Nothing is checked automatically until answered; Settings › À propos changes it.
 */
@Composable
private fun UpdateConsentDialog(app: NautileApp, onClosed: () -> Unit) {
    val context = LocalContext.current
    fun answer(on: Boolean) {
        com.nautile.app.update.UpdateChecker.setEnabled(context, on)
        onClosed()
        if (on) com.nautile.app.update.UpdateChecker.checkAtStart(app)
    }
    AlertDialog(
        // Not an answer: asked again at the next start of the app, not before.
        onDismissRequest = { app.updateConsentAsked = true; onClosed() },
        containerColor = Nt.Surface,
        title = { Text(tr("Vérifier automatiquement les mises à jour ?")) },
        text = {
            Text(
                tr("Nautile contacte GitHub une fois par jour pour savoir si une nouvelle version existe ; rien n'est téléchargé ni installé sans votre accord. Modifiable ensuite dans Réglages › À propos."),
                color = Nt.TextSoft, fontSize = 14.sp, lineHeight = 20.sp,
            )
        },
        dismissButton = { TextButton(onClick = { answer(false) }) { Text(tr("Non"), color = Nt.TextSoft) } },
        confirmButton = { TextButton(onClick = { answer(true) }) { Text(tr("Oui"), color = Nt.Accent) } },
    )
}

/**
 * The data copied from another phone (or whose key was lost) could not be decrypted: it was set aside and an
 * empty database was started. The automatic backups wait until the user resumes them here (shown at each start
 * until then), so that the empty database never replaces the good backups.
 */
@Composable
private fun DataResetDialog(app: NautileApp) {
    val reset by app.dataReset.collectAsStateWithLifecycle()
    if (!reset) return
    val context = LocalContext.current
    AlertDialog(
        // Later: asked again at the next start, the backups stay paused.
        onDismissRequest = { app.dataReset.value = false },
        containerColor = Nt.Surface,
        title = { Text(tr("Données illisibles sur cet appareil")) },
        text = {
            Text(
                tr("Les données de Nautile n'ont pas pu être déchiffrées ici (clé perdue, ou données copiées depuis un autre appareil). Elles ont été mises de côté sur le téléphone, rien n'a été effacé, et une base vide a été créée : restaurez une sauvegarde ou synchronisez pour retrouver vos hôtes.\n\nLes sauvegardes automatiques sont suspendues pour que cette base vide ne remplace pas vos sauvegardes. Reprenez-les une fois vos données retrouvées."),
                color = Nt.TextSoft, fontSize = 14.sp, lineHeight = 20.sp,
            )
        },
        dismissButton = {
            TextButton(onClick = { app.dataReset.value = false }) { Text(tr("Plus tard"), color = Nt.TextSoft) }
        },
        confirmButton = {
            TextButton(onClick = {
                com.nautile.app.backup.AutoBackup.setAutoPaused(context, false)
                app.dataReset.value = false
            }) { Text(tr("Reprendre les sauvegardes"), color = Nt.Accent) }
        },
    )
}
