package com.nautile.app.screenshots

import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Battery5Bar
import androidx.compose.material.icons.filled.SignalCellular4Bar
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.nautile.app.session.ActiveSession
import com.nautile.app.session.aiKey
import com.nautile.app.session.SessionManager
import com.nautile.app.session.SessionState
import com.nautile.app.ui.NautileRoot
import com.nautile.app.ui.terminal.TerminalScreen
import com.nautile.app.ui.theme.AppTheme
import com.nautile.app.ui.theme.NautileTheme
import com.nautile.app.ui.theme.Nt
import com.nautile.data.model.AuthMethod
import com.nautile.data.model.ConnectionLog
import com.nautile.data.model.Host
import com.nautile.data.model.HostGroup
import com.nautile.data.model.Protocol
import com.nautile.data.model.Snippet
import com.nautile.i18n.I18n
import com.nautile.i18n.tr
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the real screens, framed as a phone, for the README and the website:
 * `scripts/screenshots.sh` (or `./gradlew :app:testDebugUnitTest --tests '*ScreenshotTest*' -PnautileScreenshots=fr`).
 * Skipped in normal test runs.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = ScreenshotApp::class, qualifiers = "w416dp-h892dp-xhdpi")
class ScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val lang: String = System.getProperty("nautile.screenshots").orEmpty()
    private val app get() = ApplicationProvider.getApplicationContext<ScreenshotApp>()
    private val out = File("../docs/assets/shots").apply { mkdirs() }

    @Before fun setUp() {
        assumeTrue("Screenshots only with -PnautileScreenshots=fr|en", lang == "fr" || lang == "en")
        System.setProperty("roborazzi.test.record", "true")
        I18n.english = lang == "en"
        java.util.Locale.setDefault(if (lang == "fr") java.util.Locale.FRANCE else java.util.Locale.US)
        app.appearance.setTheme(AppTheme.DARK)
        seed()
    }

    private fun seed() = runBlocking {
        val now = System.currentTimeMillis()
        val dao = app.database.hostDao()
        val homelab = dao.insertGroup(HostGroup(name = "Homelab", color = 0xFFFFB547, sortOrder = 0))
        val vps = dao.insertGroup(HostGroup(name = "VPS", color = 0xFF7AA2F7, sortOrder = 1))
        val office = dao.insertGroup(HostGroup(name = tr("Bureau"), color = 0xFF5BD69A, sortOrder = 2))
        fun h(alias: String, host: String, user: String, group: Long, os: String?, ago: Long?, port: Int = 22, proto: Protocol = Protocol.SSH, color: Long = 0xFFFFB547) =
            Host(alias = alias, hostname = host, port = port, username = user, protocol = proto, authMethod = AuthMethod.KEY, groupId = group, osId = os,
                lastConnectedAt = ago?.let { now - it * 60_000 }, color = color)
        listOf(
            h("docker-01", "10.0.0.20", "mrd", homelab, "debian", 4),
            h("nas", "nas.lan", "admin", homelab, "truenas", 95, port = 2222, color = 0xFF7AA2F7),
            h("pihole", "10.0.0.53", "pi", homelab, "raspbian", 60 * 26, color = 0xFFF38BA8),
            h("proxmox", "10.0.0.2", "root", homelab, "proxmox", 60 * 50, port = 8022),
            h("vps-paris", "vps.example.org", "deploy", vps, "ubuntu", 60 * 5, color = 0xFF7AA2F7),
            h("pc-bureau", "pc-bureau.lan", "Administrateur", office, "windows", 60 * 3, port = 3389, proto = Protocol.RDP, color = 0xFF5BD69A),
        ).forEach { dao.upsertHost(it) }

        val tools = app.database.toolsDao()
        listOf(
            Snippet(name = tr("Conteneurs Docker"), command = "docker ps -a --format 'table {{.Names}}\\t{{.Status}}'", tag = "Docker", runCount = 42),
            Snippet(name = tr("Journal d'un service"), command = "journalctl -u \${SERVICE} -n 100 --no-pager", tag = tr("Système"), runCount = 17),
            Snippet(name = tr("Mises à jour (Debian, Ubuntu)"), command = "sudo apt update && sudo apt upgrade -y", tag = tr("Système"), runCount = 9),
            Snippet(name = tr("Espace disque"), command = "df -h", tag = tr("Système"), runCount = 6),
        ).forEach { tools.upsertSnippet(it) }

        listOf(
            ConnectionLog(hostId = 1, alias = "docker-01", protocol = Protocol.SSH, username = "mrd", hostname = "10.0.0.20", port = 22, at = now - 4 * 60_000),
            ConnectionLog(alias = "root@192.168.1.2", protocol = Protocol.SSH, username = "root", hostname = "192.168.1.2", port = 22, at = now - 38 * 60_000),
            ConnectionLog(hostId = 6, alias = "pc-bureau", protocol = Protocol.RDP, username = "Administrateur", hostname = "pc-bureau.lan", port = 3389, at = now - 3 * 3600_000),
            ConnectionLog(hostId = 2, alias = "nas", protocol = Protocol.SSH, username = "admin", hostname = "nas.lan", port = 2222, at = now - 95 * 60_000),
        ).forEach { tools.insertConnection(it) }
    }

    /** A connected session fed with a recorded terminal screen (no network, no native code). */
    private fun fakeSession(): ActiveSession {
        val session = ActiveSession(1, 1, "docker-01", "mrd@10.0.0.20", "ssh")
        val client = object : TerminalSessionClient {
            override fun onTextChanged(changedSession: TerminalSession) {}
            override fun onTitleChanged(changedSession: TerminalSession) {}
            override fun onSessionFinished(finishedSession: TerminalSession) {}
            override fun onCopyTextToClipboard(session: TerminalSession, text: String) {}
            override fun onPasteTextFromClipboard(session: TerminalSession) {}
            override fun onBell(session: TerminalSession) {}
            override fun onColorsChanged(session: TerminalSession) {}
            override fun onTerminalCursorStateChange(state: Boolean) {}
            override fun getTerminalCursorStyle(): Int = 0
            override fun logError(tag: String, message: String) {}
            override fun logWarn(tag: String, message: String) {}
            override fun logInfo(tag: String, message: String) {}
            override fun logDebug(tag: String, message: String) {}
            override fun logVerbose(tag: String, message: String) {}
            override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {}
            override fun logStackTrace(tag: String, e: Exception) {}
        }
        val backend = object : TerminalSession.Backend {
            override fun start(columns: Int, rows: Int) {}
            override fun write(data: ByteArray, offset: Int, count: Int) {}
            override fun resize(columns: Int, rows: Int) {}
            override fun close() {}
        }
        session.terminal = TerminalSession(backend, 2000, client)
        session._state.value = SessionState.Connected
        @Suppress("UNCHECKED_CAST")
        val field = SessionManager::class.java.getDeclaredField("_sessions").apply { isAccessible = true }
        (field.get(app.sessions) as MutableStateFlow<List<ActiveSession>>).value = listOf(session)
        return session
    }

    private fun idle() { shadowOf(Looper.getMainLooper()).idle(); compose.waitForIdle() }

    /** Captures the screen in dark then light theme, transparent around the phone. */
    private fun shoot(name: String) {
        fun capture(theme: String) {
            idle()
            // The theme paints the window; the area around the phone must stay transparent.
            compose.activity.window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            compose.activity.window.decorView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            idle()
            compose.onRoot().captureRoboImage(File(out, "$lang-$theme-$name.png").path)
        }
        capture("dark")
        app.appearance.setTheme(AppTheme.LIGHT)
        capture("light")
    }

    private fun root(before: () -> Unit = {}) {
        before()
        compose.setContent { NautileTheme { PhoneFrame { NautileRoot() } } }
        idle()
    }

    @Test fun hosts() { fakeSession(); root(); shoot("hosts") }

    @Test fun quickConnect() {
        root()
        compose.onNodeWithText(tr("Hôte ou user@ip")).performTextInput("root@192.168.1.2")
        shoot("quick")
    }

    @Test fun connections() {
        val session = fakeSession()
        // No terminal view on this screen: size the emulator by hand so the preview has something to show.
        session.terminal.updateSize(46, 30)
        val bytes = SCREEN.replace("\n", "\r\n").toByteArray()
        session.terminal.feed(bytes, 0, bytes.size)
        root(); compose.onNodeWithText(tr("Connexions")).performClick(); shoot("connections")
    }

    @Test fun snippets() { root(); compose.onNodeWithText("Snippets").performClick(); shoot("snippets") }

    @Test fun settings() { root(); compose.onNodeWithText(tr("Réglages")).performClick(); shoot("settings") }

    @Test fun hostForm() {
        root()
        compose.onAllNodesWithText("docker-01").onFirst().performTouchInput { longClick() }
        idle()
        compose.onNodeWithText(tr("Modifier")).performClick()
        shoot("form")
    }

    @Test fun terminal() {
        val session = fakeSession()
        compose.setContent { NautileTheme { PhoneFrame { TerminalScreen(1, onBack = {}, onNewSession = {}, onSftp = {}, onMonitor = {}) } } }
        idle()
        // The emulator exists once the view has a size; then play the recorded output.
        repeat(20) { if (session.terminal.emulator == null) idle() }
        val bytes = SCREEN.replace("\n", "\r\n").toByteArray()
        session.terminal.feed(bytes, 0, bytes.size)
        idle()
        shoot("terminal")
    }

    @Test fun keyboardSettings() {
        root(); compose.onNodeWithText(tr("Réglages")).performClick(); idle()
        compose.onNodeWithText(tr("Terminal")).performClick(); idle()
        compose.onNodeWithText(tr("Clavier")).performClick()
        shoot("settings-keys")
    }

    @Test fun aiSettings() {
        root(); compose.onNodeWithText(tr("Réglages")).performClick(); idle()
        compose.onNodeWithText(tr("Assistant IA")).performClick(); idle()
        shoot("settings-ai")
    }

    /** A sheet as Android draws it, inside the phone frame (a real one is a separate window). */
    @Composable
    private fun SheetOver(content: @Composable () -> Unit, sheet: @Composable () -> Unit) {
        Box(Modifier.fillMaxSize()) {
            content()
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f)))
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(Nt.Surface).padding(top = 22.dp),
            ) { sheet() }
        }
    }

    @Test fun backupSchedule() {
        app.backupPrefs.setDestination(com.nautile.app.backup.BackupDestination.Document("content://com.google.android.apps.docs.storage/document/x", "Google Drive › nautile.backup"))
        app.backupPrefs.setAuto(true)
        app.backupPrefs.setFrequency(com.nautile.app.backup.BackupFrequency.WEEK)
        app.backupPrefs.markExported(System.currentTimeMillis() - 3 * 24 * 3_600_000L)
        compose.setContent { NautileTheme { PhoneFrame { com.nautile.app.ui.settings.BackupSettingsScreen(onBack = {}) } } }
        idle()
        compose.onNodeWithText(tr("Seulement en charge")).performScrollTo()
        shoot("settings-backup")
    }

    @Test fun snippetEditor() {
        val sn = Snippet(id = 9, name = tr("Journal d'un service"), command = "journalctl -u \${SERVICE} -n 100 --no-pager", tag = tr("Système"))
        compose.setContent {
            NautileTheme {
                PhoneFrame {
                    SheetOver({ com.nautile.app.ui.tools.SnippetsScreen(onOpenSession = {}) }) {
                        com.nautile.app.ui.tools.SnippetEditorContent(sn, listOf("Docker", tr("Système")), onDismiss = {}, onSave = {}, onDelete = {})
                    }
                }
            }
        }
        shoot("snippet-editor")
    }

    @Test fun assistantConversation() {
        val session = fakeSession()
        val now = System.currentTimeMillis()
        fun turn(q: String, raw: String, at: Long, files: List<String> = emptyList(), screen: Boolean = false) = com.nautile.app.ai.AiTurn(
            q, q, files, screen, com.nautile.app.ai.AiProviderKind.OPENAI, "gpt-5-mini", raw, com.nautile.app.ai.AiAssistant.parse(raw), at,
        )
        app.ai.clearConversation(session.aiKey)
        app.ai.record(session.aiKey, turn(
            tr("Pourquoi papote-api redémarre en boucle ?"),
            "```bash\ndocker logs --tail 50 papote-api\n```\n" + tr("L'API n'arrive pas à joindre **PostgreSQL** sur `10.0.0.31:5432` (ECONNREFUSED) : la base est arrêtée ou n'écoute pas sur cette adresse. Vérifiez le conteneur de la base, puis relancez l'API."),
            now - 120_000, files = listOf("compose.yml"), screen = true,
        ))
        app.ai.record(session.aiKey, turn(
            tr("Et comment relancer la base ?"),
            "```bash\ndocker compose -f /srv/stacks/papote/compose.yml up -d db\n```\n" + tr("Relance seulement le service **db** de la stack, sans toucher à l'API.") +
                "\n\n### " + tr("Ensuite") + "\n- " + tr("Vérifiez qu'elle écoute : `docker compose ps db`") + "\n- " + tr("L'API se reconnecte seule dans les **5 s**"),
            now - 30_000,
        ))
        compose.setContent { NautileTheme { PhoneFrame { com.nautile.app.ui.terminal.ConversationContent(session, onClose = {}, onInsert = { _, _ -> }) } } }
        shoot("assistant-conversation")
    }

    private val demoGroups get() = listOf(
        HostGroup(id = 1, name = "Homelab", color = 0xFFFFB547), HostGroup(id = 2, name = "VPS", color = 0xFF7AA2F7),
        HostGroup(id = 3, name = tr("Bureau"), color = 0xFF5FD38D),
    )
    private val demoCounts = mapOf(1L to 4, 2L to 1, 3L to 1)

    @Test fun groups() {
        compose.setContent {
            NautileTheme { PhoneFrame { SheetOver({ NautileRoot() }) { com.nautile.app.ui.hosts.GroupsSheetContent(demoGroups, demoCounts, {}, {}, {}, { _, _ -> }) } } }
        }
        shoot("groups")
    }

    @Test fun groupEdit() {
        compose.setContent {
            NautileTheme { PhoneFrame { SheetOver({ NautileRoot() }) { com.nautile.app.ui.hosts.GroupsSheetContent(demoGroups, demoCounts, {}, {}, {}, { _, _ -> }, initialEditing = demoGroups[0]) } } }
        }
        shoot("group-edit")
    }

    @Test fun terminalAiTab() {
        val session = fakeSession()
        val raw = "```bash\ndocker compose -f /srv/stacks/papote/compose.yml up -d db\n```\n" + tr("Relance seulement le service **db** de la stack, sans toucher à l'API.") +
            "\n\n### " + tr("Ensuite") + "\n- " + tr("Vérifiez qu'elle écoute : `docker compose ps db`") + "\n- " + tr("L'API se reconnecte seule dans les **5 s**")
        app.ai.record(session.aiKey, com.nautile.app.ai.AiTurn(
            tr("Et comment relancer la base ?"), "", emptyList(), false, com.nautile.app.ai.AiProviderKind.OPENAI, "gpt-5-mini",
            raw, com.nautile.app.ai.AiAssistant.parse(raw),
        ))
        compose.setContent { NautileTheme { PhoneFrame { TerminalScreen(1, onBack = {}, onNewSession = {}, onSftp = {}, onMonitor = {}) } } }
        idle()
        compose.onNodeWithContentDescription(tr("Conversation avec l'assistant sur %1\$s", "docker-01")).performClick()
        shoot("terminal-ai")
    }

    @Test fun aiProvider() {
        compose.setContent {
            NautileTheme {
                PhoneFrame {
                    SheetOver({ com.nautile.app.ui.settings.AiSettingsScreen(onBack = {}) }) {
                        com.nautile.app.ui.settings.ProviderSheetContent(com.nautile.app.ai.AiProviderKind.CLAUDE, onSaved = {}, onDismiss = {})
                    }
                }
            }
        }
        shoot("settings-ai-provider")
    }

    @Test fun aiModels() {
        val models = listOf(
            "gpt-5", "gpt-5-mini", "gpt-5-nano", "gpt-5-pro", "gpt-4.1", "gpt-4.1-mini", "gpt-4o", "gpt-4o-mini", "o3", "o4-mini",
            "text-embedding-3-large", "tts-1", "whisper-1", "gpt-image-1", "gpt-4o-realtime-preview",
        )
        compose.setContent {
            NautileTheme {
                PhoneFrame {
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f)), contentAlignment = Alignment.Center) {
                        Box(Modifier.fillMaxWidth(0.94f).fillMaxSize(0.9f).clip(RoundedCornerShape(24.dp)).background(Nt.Surface)) {
                            com.nautile.app.ui.settings.ModelPickerContent(models, null, "gpt-5-mini", onRetry = {}, onPick = {}, onClose = {})
                        }
                    }
                }
            }
        }
        shoot("settings-ai-models")
    }

    @Test fun autocomplete() {
        val session = fakeSession()
        runBlocking {
            val now = System.currentTimeMillis()
            listOf("sudo su", "sudo systemctl restart nginx", "sudo apt update && sudo apt upgrade -y", "sudo docker compose up -d")
                .forEachIndexed { i, cmd -> app.database.toolsDao().insertHistory(1, cmd, now - i * 60_000) }
        }
        compose.setContent { NautileTheme { PhoneFrame { TerminalScreen(1, onBack = {}, onNewSession = {}, onSftp = {}, onMonitor = {}) } } }
        idle()
        repeat(20) { if (session.terminal.emulator == null) idle() }
        val bytes = (SCREEN.replace("\n", "\r\n") + "sudo").toByteArray()
        session.terminal.feed(bytes, 0, bytes.size)
        session._typed.value = "sudo"
        shoot("autocomplete")
    }

    @Test fun terminalDisplay() {
        val session = fakeSession()
        compose.setContent { NautileTheme { PhoneFrame { TerminalScreen(1, onBack = {}, onNewSession = {}, onSftp = {}, onMonitor = {}) } } }
        idle()
        repeat(20) { if (session.terminal.emulator == null) idle() }
        val bytes = SCREEN.replace("\n", "\r\n").toByteArray()
        session.terminal.feed(bytes, 0, bytes.size)
        compose.onNodeWithContentDescription(tr("Affichage")).performClick()
        shoot("terminal-display")
    }

    @Test fun assistant() {
        val session = fakeSession()
        compose.setContent {
            NautileTheme {
                PhoneFrame {
                    Box(Modifier.fillMaxSize()) {
                        TerminalScreen(1, onBack = {}, onNewSession = {}, onSftp = {}, onMonitor = {})
                        // The sheet as Android draws it, inside the phone frame (a real sheet is a separate window).
                        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f)))
                        Column(
                            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(Nt.Surface).padding(top = 22.dp),
                        ) {
                            com.nautile.app.ui.terminal.AssistantPanel(
                                session, screen = { null }, onDismiss = {}, onInsert = { _, _ -> },
                                initialRequest = tr("Pourquoi papote-api redémarre en boucle ?"),
                                initialFiles = listOf(
                                    com.nautile.app.ai.AiFile("/srv/stacks/papote/compose.yml", "", 1_850, cut = false, tail = false),
                                    com.nautile.app.ai.AiFile("/var/log/syslog", "", 3_400_000, cut = true, tail = true),
                                ),
                                initialResult = com.nautile.app.ai.AiAssistant.Suggestion(
                                    "docker logs --tail 50 papote-api && docker inspect -f '{{.State.ExitCode}}' papote-api",
                                    tr("L'API n'arrive pas à joindre **PostgreSQL** sur `10.0.0.31:5432` (ECONNREFUSED) : la base est arrêtée ou n'écoute pas sur cette adresse. Vérifiez le conteneur de la base, puis relancez l'API."),
                                ),
                            )
                        }
                    }
                }
            }
        }
        idle()
        repeat(20) { if (session.terminal.emulator == null) idle() }
        val bytes = SCREEN.replace("\n", "\r\n").toByteArray()
        session.terminal.feed(bytes, 0, bytes.size)
        shoot("assistant")
    }

    companion object {
        private const val E = "\u001b["
        private val PROMPT = "${E}38;5;16;48;5;214m  docker-01 ${E}38;5;214;48;5;67m${E}38;5;255;48;5;67m  ~/stacks ${E}0;38;5;67m${E}0m "
        val SCREEN = buildString {
            append(PROMPT).append("docker compose ps\n")
            append("${E}1mNAME           STATUS               PORTS${E}0m\n")
            append("jellyfin       ${E}32mUp 3 days${E}0m            8096\n")
            append("immich         ${E}32mUp 3 days${E}0m            2283\n")
            append("nextcloud      ${E}32mUp 3 days (healthy)${E}0m  443\n")
            append("vaultwarden    ${E}32mUp 3 days${E}0m            8222\n")
            append("papote-api     ${E}33mRestarting (1) 4s${E}0m\n\n")
            append(PROMPT).append("df -h /srv\n")
            append("Filesystem      Size  Used Avail Use% Mounted on\n")
            append("/dev/sda1       1.8T  1.1T  700G  61% /srv\n\n")
            append(PROMPT).append("docker logs --tail 3 papote-api\n")
            append("${E}36m[api]${E}0m listening on :3000\n")
            append("${E}31mError: connect ECONNREFUSED 10.0.0.31:5432${E}0m\n")
            append("${E}36m[api]${E}0m retrying in 5s…\n\n")
            append(PROMPT)
        }
    }
}

/** A phone around the app: bezel, camera hole, status bar and gesture bar. */
@Composable
fun PhoneFrame(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(6.dp).clip(RoundedCornerShape(54.dp)).background(Color(0xFF15171B)).padding(9.dp)) {
        Column(Modifier.fillMaxSize().clip(RoundedCornerShape(46.dp)).background(Nt.Bg)) {
            Row(Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 26.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("9:41", color = Nt.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(Icons.Filled.SignalCellular4Bar, Icons.Filled.Wifi, Icons.Filled.Battery5Bar).forEach {
                        Icon(it, contentDescription = null, tint = Nt.Text, modifier = Modifier.size(16.dp))
                    }
                }
            }
            // What the app's Scaffold provides around its screens.
            Box(Modifier.weight(1f).fillMaxWidth()) { CompositionLocalProvider(LocalContentColor provides Nt.Text) { content() } }
            Box(Modifier.fillMaxWidth().height(22.dp).background(Nt.Bar), contentAlignment = Alignment.Center) {
                Box(Modifier.width(110.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Nt.Muted))
            }
        }
        Box(Modifier.align(Alignment.TopCenter).padding(top = 10.dp).size(12.dp).clip(CircleShape).background(Color.Black))
    }
}
