package com.nautile.app

import android.app.Application
import android.os.Handler
import android.os.Looper
import com.nautile.app.ai.AiAssistant
import com.nautile.app.backup.AutoBackup
import com.nautile.app.backup.BackupPrefs
import com.nautile.app.backup.BackupRepository
import com.nautile.app.backup.SyncManager
import com.nautile.app.backup.lan.LanSync
import com.nautile.app.keys.KeyRepository
import com.nautile.app.session.SessionManager
import com.nautile.app.session.TerminalPrefs
import com.nautile.app.ui.common.MdiIcons
import com.nautile.app.ui.theme.AppearancePrefs
import com.nautile.app.update.UpdateChecker
import com.nautile.app.widget.HostShortcuts
import com.nautile.app.widget.HostsWidget
import com.nautile.data.db.NautileDatabase
import com.nautile.data.repo.HostRepository
import com.nautile.i18n.I18n
import com.nautile.security.AppLock
import com.nautile.security.DatabaseKeyStore
import com.nautile.security.FileCipher
import com.nautile.security.KeyLostException
import com.nautile.security.KeystoreUnavailableException
import com.nautile.security.SecretStore
import com.termux.terminal.AndroidPty
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.File
import java.io.IOException
import java.security.Security
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** Manual dependency container; small enough not to need a DI framework yet. */
open class NautileApp : Application() {
    val appScope = MainScope()

    @Volatile private var opened: NautileDatabase? = null
    private val openLock = Any()

    /**
     * Open so that screenshot tests can use an in-memory database. Throws [KeystoreUnavailableException]
     * when the Keystore fails for a moment (the next access tries again), [KeyLostException] when its key
     * looks lost: opened this way (in the background: a widget, a worker, any lazy user), nothing is ever
     * set aside; only a launch on screen may ([openDatabaseOrError]). Slow: never on the main thread.
     */
    open val database: NautileDatabase
        get() = opened ?: openDatabase(foreground = false)

    /** Whether the database is open already: reading [database] then neither waits nor touches the Keystore. */
    val databaseOpen: Boolean get() = opened != null || databaseJobs.get()

    private fun openDatabase(foreground: Boolean): NautileDatabase = synchronized(openLock) {
        opened?.let { return it }
        val keys = DatabaseKeyStore(this)
        // A database without its passphrase was copied from another device: it can never be opened here.
        if (!keys.hasPassphrase() && getDatabasePath(DB_NAME).exists()) {
            if (!foreground) throw KeyLostException()
            setAsideDatabase(keys)
        }
        val passphrase = try {
            keys.passphrase()
        } catch (e: KeyLostException) {
            // Only when its decryption failed for good on every try (any other Keystore failure is thrown),
            // seen by launches on screen after two distinct boots of the phone: a Keystore failing in a way
            // that looks final may still answer after a restart, and a start in the background (widget,
            // worker) never decides anything the user has not seen.
            if (!keys.noteLoss(foreground)) throw e
            setAsideDatabase(keys)
            keys.passphrase()
        }
        keys.clearLosses()
        NautileDatabase.open(this, passphrase).also { opened = it }
    }

    private val databaseJobs = AtomicBoolean(false)

    /**
     * Opens the database, or answers why it cannot be now (the Keystore failing: nothing was reset).
     * [foreground]: asked by a launch on screen ([MainActivity]), the only one that may count a lost key and
     * set the data aside. The work that needs the database starts the first time it opens. Slow: off the main thread.
     */
    fun openDatabaseOrError(foreground: Boolean = false): Throwable? = try {
        if (foreground) openDatabase(foreground = true) else database
        if (databaseJobs.compareAndSet(false, true)) startDatabaseJobs()
        null
    } catch (e: Exception) {
        e
    }

    /**
     * Chosen by the user when the database keeps failing to open: its files and passphrase are set aside
     * (nothing deleted) and an empty database is started.
     */
    fun startAfresh(): Throwable? {
        runCatching { synchronized(openLock) { if (opened == null) setAsideDatabase(DatabaseKeyStore(this)) } }.exceptionOrNull()?.let { return it }
        return openDatabaseOrError(foreground = true)
    }

    /**
     * The data could not be decrypted on this device and an empty database was started: Root says so,
     * and the automatic backups wait for the user ([AutoBackup.autoPaused]) so that the empty database
     * never replaces the good backups.
     */
    /** « Nearby devices » (local network, Android 17+) was asked in this process. */
    @Volatile var localNetworkAsked = false

    val dataReset by lazy { MutableStateFlow(AutoBackup.autoPaused(this)) }

    /** Host asked by a widget or shortcut ([LaunchActivity]), connected only once the app is unlocked and on screen. */
    val connectRequest = ConnectRequest()

    /**
     * The question about update checks was shown in this process and closed without an answer: not asked
     * again before the next start (each unlock and rotation would bring it back).
     */
    @Volatile var updateConsentAsked = false

    /**
     * Keeps the unreadable files aside rather than crashing at every start; nothing is deleted:
     * the database files and the wrapped passphrase get the same « lost-<date> » name.
     */
    private fun setAsideDatabase(keys: DatabaseKeyStore) {
        // First: even a crash halfway leaves the backups paused, so an empty database never replaces them.
        AutoBackup.setAutoPaused(this, true)
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss-SSS", Locale.ROOT).format(Date())
        val db = getDatabasePath(DB_NAME)
        listOf("", "-wal", "-shm", "-journal").forEach { suffix ->
            val f = File(db.path + suffix)
            // A database left in place would then be opened with a new passphrase: stop instead.
            if (f.exists() && !f.renameTo(File(db.parentFile, "$DB_NAME.lost-$stamp$suffix")) && suffix == "") {
                throw IOException("cannot set the database aside")
            }
        }
        keys.setAside(stamp)
        dataReset.value = true
    }
    val hostRepository: HostRepository by lazy { HostRepository(database) }
    val keyRepository: KeyRepository by lazy { KeyRepository(database.keyDao()) }
    val appLock: AppLock by lazy { AppLock(this, secrets) }
    val appearance by lazy { AppearancePrefs(this) }
    val backups by lazy { BackupRepository(database) }
    /** API keys and passwords, encrypted with a key of the Android Keystore. */
    val secrets by lazy { SecretStore(this) }
    /** Encrypts the app's own files (AI conversations) with a key kept in [secrets]. */
    val fileCipher by lazy { FileCipher.of(secrets) }
    val backupPrefs by lazy { BackupPrefs(this, secrets) }
    open val ai: AiAssistant by lazy {
        AiAssistant(
            object : AiAssistant.Secrets {
                override fun get(name: String) = secrets.get(name)
                override fun put(name: String, value: String?) = secrets.put(name, value)
            },
            getSharedPreferences("nautile_ai", MODE_PRIVATE),
            File(filesDir, "ai-conversations"),
            fileCipher,
        )
    }
    /** Synchronisation between devices through the backup destination. */
    val sync by lazy { SyncManager(this, appScope) }
    /** Synchronisation straight between paired devices on the local network. */
    val lanSync by lazy { LanSync(this, appScope) }
    /** Host to connect to, set once the app is unlocked (from [connectRequest]). */
    val pendingConnect = MutableStateFlow<Long?>(null)
    /** Opens the Keys tab on « Hôtes connus » (from Settings › Sécurité). */
    val showKnownHosts = MutableStateFlow(false)
    val terminalPrefs: TerminalPrefs by lazy { TerminalPrefs(this) }
    val sessions: SessionManager by lazy {
        SessionManager(this, hostRepository, keyRepository, database.toolsDao(), database.knownHostDao(), terminalPrefs, appScope)
    }

    /** Started once, when the database first opens. */
    private fun startDatabaseJobs() {
        appScope.launch(Dispatchers.IO) { runCatching { keyRepository.migrateLegacyKeys { hostRepository.save(it) } } }
        // Pull the other devices' changes at start, then follow local edits.
        appScope.launch(Dispatchers.IO) {
            runCatching { sync.watchLocalChanges() }
            if (backupPrefs.syncEnabled.value) sync.requestSoon(3_000)
        }
        // An API key set or removed here goes to the other devices, when they share them.
        ai.onKeysChanged = {
            if (backupPrefs.syncAiKeys.value) {
                backupPrefs.syncedChange = 0L
                if (backupPrefs.syncEnabled.value) sync.requestSoon(2_000)
                lanSync.requestSoon(2_000)
            }
        }
        // Keep launcher shortcuts and the widget in sync with recent connections (off the main thread).
        appScope.launch(Dispatchers.IO) {
            runCatching {
                var known: Set<Long>? = null
                hostRepository.hosts.collect { hosts ->
                    HostShortcuts.updateRecent(this@NautileApp, hosts)
                    runCatching { HostsWidget.refresh(this@NautileApp) }
                    // A host deleted (here or by a synchronisation) takes its assistant conversation along; not
                    // while the data was just set aside (an empty database until a backup is restored).
                    val ids = hosts.mapTo(HashSet()) { it.id }
                    if (dataReset.value || AutoBackup.autoPaused(this@NautileApp)) return@collect
                    if (known?.let { !ids.containsAll(it) } != false) runCatching { ai.forgetDeletedHosts(ids) }
                    known = ids
                }
            }
        }
    }

    /** Pinned shortcuts not refreshed yet (Android limits how often it is done in the background): again now. */
    fun refreshPinnedShortcuts() {
        if (!HostShortcuts.pinnedPending || !databaseJobs.get()) return
        appScope.launch(Dispatchers.IO) {
            runCatching { HostShortcuts.refreshPinned(this@NautileApp, hostRepository.hosts.first()) }
        }
    }

    companion object {
        private const val DB_NAME = "nautile.db"
        /** In the cache: files handed to other apps for a moment (decrypted logs), emptied at each start. */
        const val SHARED_DIR = "shared"
    }

    /** The terminal emulator is plain JVM code: give it Android's main thread and local PTY. */
    protected fun setUpTerminal() {
        val main = Handler(Looper.getMainLooper())
        TerminalSession.setMainThread { main.post(it) }
        TerminalSession.setPtyFactory(AndroidPty.FACTORY)
    }

    override fun onCreate() {
        super.onCreate()
        setUpTerminal()
        // Before anything builds a label: English unless the app (or, by default, the phone) is in French.
        I18n.english = AppearancePrefs.english(this)
        CrashReporter.install(this)
        // Android ships a trimmed "BC" provider; sshj needs the full one (Ed25519, ECDH, …).
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
        Security.insertProviderAt(BouncyCastleProvider(), 1)
        // The database opens off the main thread; what needs it starts once it is open (see openDatabaseOrError).
        // Started in the background (maybe by a widget or a worker): a lost key is only reported here.
        appScope.launch(Dispatchers.IO) { openDatabaseOrError() }
        // The MDI name table is read once here rather than on the main thread by the first group mark.
        appScope.launch(Dispatchers.IO) { runCatching { MdiIcons.all(this@NautileApp) } }
        appScope.launch(Dispatchers.IO) { runCatching { sync.watchVpn() } }
        // Logs decrypted for sharing are not kept (LogsScreen).
        appScope.launch(Dispatchers.IO) { runCatching { File(cacheDir, SHARED_DIR).deleteRecursively() } }
        // Its first look reads secrets from the Keystore: off the main thread.
        appScope.launch(Dispatchers.IO) { runCatching { lanSync.start() } }
        UpdateChecker.checkAtStart(this)
        // The widget hides the addresses while the lock is on.
        appLock.addListener {
            appScope.launch(Dispatchers.IO) { runCatching { HostsWidget.refresh(this@NautileApp) } }
        }
    }
}
