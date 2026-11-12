package com.nautile.app

import android.app.Application
import com.nautile.app.keys.KeyRepository
import com.nautile.app.session.SessionManager
import com.nautile.app.session.TerminalPrefs
import com.nautile.data.db.NautileDatabase
import com.nautile.data.repo.HostRepository
import com.nautile.security.AppLock
import com.nautile.security.DatabaseKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

/** Manual dependency container; small enough not to need a DI framework yet. */
open class NautileApp : Application() {
    val appScope = MainScope()

    /** Open so that screenshot tests can use an in-memory database. */
    open val database: NautileDatabase by lazy {
        NautileDatabase.open(this, DatabaseKeyStore(this).passphrase())
    }
    val hostRepository: HostRepository by lazy { HostRepository(database) }
    val keyRepository: KeyRepository by lazy { KeyRepository(database.keyDao()) }
    val appLock: AppLock by lazy { AppLock(this) }
    val appearance by lazy { com.nautile.app.ui.theme.AppearancePrefs(this) }
    val backups by lazy { com.nautile.app.backup.BackupRepository(database) }
    /** API keys and passwords, encrypted with a key of the Android Keystore. */
    val secrets by lazy { com.nautile.security.SecretStore(this) }
    val backupPrefs by lazy { com.nautile.app.backup.BackupPrefs(this, secrets) }
    open val ai: com.nautile.app.ai.AiAssistant by lazy {
        com.nautile.app.ai.AiAssistant(
            object : com.nautile.app.ai.AiAssistant.Secrets {
                override fun get(name: String) = secrets.get(name)
                override fun put(name: String, value: String?) = secrets.put(name, value)
            },
            getSharedPreferences("nautile_ai", MODE_PRIVATE),
            java.io.File(filesDir, "ai-conversations"),
        )
    }
    /** Synchronisation between devices through the backup destination. */
    val sync by lazy { com.nautile.app.backup.SyncManager(this, appScope) }
    /** Host to connect to, set by a widget or launcher shortcut. */
    val pendingConnect = kotlinx.coroutines.flow.MutableStateFlow<Long?>(null)
    /** Opens the Keys tab on « Hôtes connus » (from Settings › Sécurité). */
    val showKnownHosts = kotlinx.coroutines.flow.MutableStateFlow(false)
    val terminalPrefs: TerminalPrefs by lazy { TerminalPrefs(this) }
    val sessions: SessionManager by lazy {
        SessionManager(this, hostRepository, keyRepository, database.toolsDao(), database.knownHostDao(), terminalPrefs, appScope)
    }

    override fun onCreate() {
        super.onCreate()
        // Before anything builds a label: English unless the app (or, by default, the phone) is in French.
        com.nautile.i18n.I18n.english = when (getSharedPreferences("nautile_appearance", MODE_PRIVATE).getString("language", "SYSTEM")) {
            "FR" -> false
            "EN" -> true
            else -> java.util.Locale.getDefault().language != "fr"
        }
        CrashReporter.install(this)
        // Android ships a trimmed "BC" provider; sshj needs the full one (Ed25519, ECDH, …).
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
        Security.insertProviderAt(BouncyCastleProvider(), 1)
        appScope.launch(Dispatchers.IO) { runCatching { keyRepository.migrateLegacyKeys() } }
        // Pull the other devices' changes at start, then follow local edits.
        appScope.launch(Dispatchers.IO) {
            runCatching { sync.watchLocalChanges() }
            if (backupPrefs.syncEnabled.value) sync.requestSoon(3_000)
        }
        // Keep launcher shortcuts and the widget in sync with recent connections.
        appScope.launch {
            hostRepository.hosts.collect { hosts ->
                com.nautile.app.widget.HostShortcuts.updateRecent(this@NautileApp, hosts)
                runCatching { com.nautile.app.widget.HostsWidget.refresh(this@NautileApp) }
            }
        }
    }
}
