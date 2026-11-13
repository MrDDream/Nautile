package com.nautile.app.ui

import androidx.compose.foundation.background
import com.nautile.data.model.isDesktop
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import com.nautile.i18n.tr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.platform.LocalContext
import com.nautile.app.NautileApp
import com.nautile.app.ui.terminal.PromptDialog
import com.nautile.app.ui.terminal.TerminalScreen
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.nautile.app.ui.hosts.HostEditScreen
import com.nautile.app.ui.hosts.HostsScreen
import com.nautile.app.ui.settings.SettingsScreen
import com.nautile.app.ui.theme.Nt

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
    val sessions by app.sessions.sessions.collectAsState()
    val desktops by app.sessions.rdp.list.collectAsState()
    val prompt by app.sessions.prompt.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    /** Connects to a machine typed in the search bar, without saving it. */
    fun quickConnect(target: com.nautile.app.ui.hosts.QuickTarget) {
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

    val pending by app.pendingConnect.collectAsState()
    androidx.compose.runtime.LaunchedEffect(pending) {
        pending?.let { id -> app.pendingConnect.value = null; connect(id) }
    }

    prompt?.let { PromptDialog(it) }
    CrashReportDialog()

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
                            if (open > 0) androidx.compose.material3.BadgedBox(badge = {
                                androidx.compose.material3.Badge(containerColor = Nt.Green, contentColor = Nt.Bg) { Text("$open") }
                            }) { Icon(tab.icon, contentDescription = null) } else Icon(tab.icon, contentDescription = null)
                        },
                        label = {
                            // One line whatever the system font size: « Connections » used to break in two.
                            val d = androidx.compose.ui.platform.LocalDensity.current
                            androidx.compose.runtime.CompositionLocalProvider(
                                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(d.density, d.fontScale.coerceAtMost(1.1f)),
                            ) {
                                Text(tab.label, maxLines = 1, softWrap = false, letterSpacing = 0.1.sp,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
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
                com.nautile.app.ui.connections.ConnectionsScreen(
                    onOpenTerminal = { nav.navigate("terminal/${it}") { launchSingleTop = true } },
                    onOpenDesktop = { nav.navigate("rdp/$it") { launchSingleTop = true } },
                    onConnect = ::connect,
                    onQuickConnect = ::quickConnect,
                    onSave = { target -> nav.navigate("host/0?kind=${target.protocol.name}&target=" + android.net.Uri.encode(target.label)) },
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
                com.nautile.app.ui.sftp.SftpScreen(
                    onBack = { nav.popBackStack() },
                    onEdit = { h, p -> nav.navigate("edit/$h?path=" + android.net.Uri.encode(p)) },
                )
            }
            composable("monitor/{hostId}", arguments = listOf(navArgument("hostId") { type = NavType.LongType })) { entry ->
                com.nautile.app.ui.monitor.MonitorScreen(entry.arguments?.getLong("hostId") ?: 0L, onBack = { nav.popBackStack() })
            }
            composable(
                "edit/{hostId}?path={path}",
                arguments = listOf(navArgument("hostId") { type = NavType.LongType }, navArgument("path") { type = NavType.StringType }),
            ) { entry ->
                com.nautile.app.ui.sftp.EditorScreen(
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
                )
            }
            composable("rdp/{sid}", arguments = listOf(navArgument("sid") { type = NavType.LongType })) { entry ->
                com.nautile.app.rdp.RdpScreen(
                    sessionId = entry.arguments?.getLong("sid") ?: 0L,
                    onBack = { if (!nav.popBackStack(Tab.Hosts.route, inclusive = false)) nav.navigate(Tab.Hosts.route) },
                    onReplaced = { id -> nav.navigate("rdp/$id") { popUpTo("rdp/{sid}") { inclusive = true } } },
                )
            }
            composable(Pages.KEYS) { com.nautile.app.ui.keys.KeychainScreen(onBack = { nav.popBackStack() }) }
            composable(Pages.TUNNELS) { com.nautile.app.ui.tools.TunnelsScreen(onBack = { nav.popBackStack() }) }
            composable(Tab.Snippets.route) {
                com.nautile.app.ui.tools.SnippetsScreen(onOpenSession = { nav.navigate("terminal/${it}") { launchSingleTop = true } })
            }
            composable(Tab.Settings.route) { SettingsScreen(onNavigate = { nav.navigate(it) }) }
            composable(com.nautile.app.ui.settings.SettingsRoutes.APPEARANCE) {
                com.nautile.app.ui.settings.AppearanceSettingsScreen(onBack = { nav.popBackStack() }, onOpenAccent = { nav.navigate(com.nautile.app.ui.settings.SettingsRoutes.ACCENT) })
            }
            composable(com.nautile.app.ui.settings.SettingsRoutes.RDP) { com.nautile.app.ui.settings.RdpSettingsScreen(onBack = { nav.popBackStack() }) }
            composable(com.nautile.app.ui.settings.SettingsRoutes.AI) { com.nautile.app.ui.settings.AiSettingsScreen(onBack = { nav.popBackStack() }) }
            composable(com.nautile.app.ui.settings.SettingsRoutes.SECURITY) {
                com.nautile.app.ui.settings.SecuritySettingsScreen(
                    onBack = { nav.popBackStack() },
                    onOpenKnownHosts = {
                        app.showKnownHosts.value = true
                        nav.navigate(Pages.KEYS) { launchSingleTop = true }
                    },
                )
            }
            composable(com.nautile.app.ui.settings.SettingsRoutes.BACKUP) {
                com.nautile.app.ui.settings.BackupSettingsScreen(onBack = { nav.popBackStack() })
            }
            composable(com.nautile.app.ui.settings.SettingsRoutes.TERMINAL) {
                com.nautile.app.ui.appearance.TerminalAppearanceScreen(onBack = { nav.popBackStack() }, onOpenLogs = { nav.navigate(com.nautile.app.ui.settings.SettingsRoutes.LOGS) })
            }
            composable(com.nautile.app.ui.settings.SettingsRoutes.ACCENT) { com.nautile.app.ui.appearance.AccentScreen(onBack = { nav.popBackStack() }) }
            composable("logs") { com.nautile.app.ui.settings.LogsScreen(onBack = { nav.popBackStack() }) }
        }
    }
}

/** After a crash: the anonymised report, to read, copy or post on GitHub (nothing is sent otherwise). */
@Composable
private fun CrashReportDialog() {
    val context = LocalContext.current
    var report by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(com.nautile.app.CrashReporter.pending(context)) }
    val text = report ?: return
    fun dismiss() { com.nautile.app.CrashReporter.clear(context); report = null }
    androidx.compose.material3.AlertDialog(
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
                    modifier = Modifier.heightIn(max = 220.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                        .background(Nt.Bg).verticalScroll(rememberScrollState()).padding(10.dp),
                )
                androidx.compose.material3.TextButton(onClick = {
                    context.getSystemService(android.content.ClipboardManager::class.java)
                        ?.setPrimaryClip(android.content.ClipData.newPlainText(tr("Rapport de crash"), text))
                }) { Text(tr("Copier le rapport"), color = Nt.TextSoft) }
            }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = ::dismiss) { Text(tr("Ignorer"), color = Nt.TextSoft) } },
        confirmButton = {
            androidx.compose.material3.Button(
                onClick = {
                    runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, com.nautile.app.CrashReporter.issueUrl(text))) }
                    dismiss()
                },
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Nt.Accent, contentColor = Nt.OnAccent),
            ) { Text(tr("Signaler sur GitHub"), fontWeight = FontWeight.SemiBold) }
        },
    )
}
