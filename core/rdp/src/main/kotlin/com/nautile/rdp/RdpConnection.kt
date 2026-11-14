package com.nautile.rdp

import android.content.Context
import android.graphics.Bitmap
import com.freerdp.freerdpcore.services.LibFreeRDP
import java.util.concurrent.locks.ReentrantReadWriteLock

/** A server certificate shown to the user; [fingerprint] is `sha256:…` hex, or the PEM when [isPem]. */
data class RdpCertificate(
    val commonName: String,
    val subject: String,
    val issuer: String,
    val fingerprint: String,
    val isPem: Boolean,
    /** The name in the certificate does not match the address. */
    val mismatch: Boolean,
)

data class RdpCredentials(val username: String, val domain: String, val password: String)

/** Remote pointer image (ARGB), hot spot included. */
class RdpPointer(val pixels: IntArray, val width: Int, val height: Int, val hotX: Int, val hotY: Int)

/**
 * One FreeRDP instance and its desktop bitmap. Callbacks of [Events] run on FreeRDP's session thread
 * and may block (prompts).
 */
class RdpConnection(context: Context, private val events: Events) : RdpCallbacks {
    interface Events {
        fun connected()
        /** The session ended; [error] is FreeRDP's last error when it failed. */
        fun closed(error: String?, failed: Boolean)
        fun reconnecting()
        fun resized(width: Int, height: Int)
        fun frame(x: Int, y: Int, width: Int, height: Int)
        fun credentials(current: RdpCredentials, gateway: Boolean): RdpCredentials?
        fun certificate(cert: RdpCertificate): Boolean
        fun remoteClipboard(text: String)
        fun pointer(pointer: RdpPointer?)
    }

    private val instance: Long = LibFreeRDP.newInstance(context.applicationContext, this)
    @Volatile var bitmap: Bitmap? = null
        private set
    /** Input calls (UI thread) hold the read lock; [close] takes the write lock before freeing. */
    private val lock = ReentrantReadWriteLock()
    @Volatile private var freed = false

    /** Runs [block] on the live instance, or does nothing once it is closed. */
    private inline fun live(block: () -> Unit) {
        val r = lock.readLock()
        r.lock()
        try { if (!freed) block() } finally { r.unlock() }
    }

    init {
        check(instance != 0L) { "freerdp_new failed" }
    }

    /** Set when a callback failed on the app's side: reported instead of FreeRDP's error. */
    @Volatile private var failure: String? = null
    /** Standard RDP security: the password is typed in Windows' own logon screen, never sent by the app. */
    @Volatile private var windowsLogon = false

    /**
     * Starts the session thread. Under the write lock: a [close] racing with it either frees the
     * instance before (nothing starts) or after the thread is marked running (it waits for it).
     */
    fun start(config: RdpConfig): Boolean {
        val w = lock.writeLock()
        w.lock()
        try {
            if (freed) return false
            windowsLogon = !RdpArgs.sendsPassword(config.security)
            val args = RdpArgs.build(config, LibFreeRDP.hasH264Support())
            if (!LibFreeRDP.parseArguments(instance, args.toTypedArray())) return false
            return LibFreeRDP.connect(instance)
        } finally { w.unlock() }
    }

    /**
     * Runs a callback of the session thread: an exception must not go back into JNI (it would stay
     * pending in native code). It ends the connection instead, and [fallback] is returned.
     */
    private inline fun <T> guard(fallback: T, block: () -> T): T = try {
        block()
    } catch (t: Throwable) {
        abort(t.toString())
        fallback
    }

    /** Ends the connection from the session thread (non-blocking), reporting [reason]. */
    private fun abort(reason: String) {
        if (failure == null) failure = "$CLIENT_ERROR: $reason"
        live { LibFreeRDP.disconnect(instance) }
    }

    /**
     * Ends the session and frees the instance; blocks until the session thread is gone, so never
     * call it from a callback.
     */
    fun close() {
        val w = lock.writeLock()
        w.lock()
        try {
            if (freed) return
            freed = true
        } finally { w.unlock() }
        LibFreeRDP.freeInstance(instance)
    }

    fun setForeground(foreground: Boolean) = live { LibFreeRDP.setForeground(instance, foreground) }

    fun pointer(x: Int, y: Int, flags: Int) = live { LibFreeRDP.sendCursorEvent(instance, x, y, flags) }

    fun key(vk: Int, down: Boolean) = live { LibFreeRDP.sendKeyEvent(instance, vk, down) }

    /** Types [text] as Unicode key events, independent of the server's keyboard layout. */
    fun text(text: String) = live {
        text.forEach { ch ->
            if (ch == '\n') {
                LibFreeRDP.sendKeyEvent(instance, RdpInput.VK_RETURN, true); LibFreeRDP.sendKeyEvent(instance, RdpInput.VK_RETURN, false)
            } else {
                LibFreeRDP.sendUnicodeKeyEvent(instance, ch.code, true); LibFreeRDP.sendUnicodeKeyEvent(instance, ch.code, false)
            }
        }
    }

    fun clipboard(text: String) = live { LibFreeRDP.sendClipboardData(instance, text) }

    /** Asks the server to resize the desktop (needs the Display Control channel, Windows 8.1+). */
    fun resize(width: Int, height: Int) = live {
        val (w, h) = RdpArgs.desktopSize(width, height)
        LibFreeRDP.sendMonitorLayout(instance, w, h)
    }

    override fun onConnected() = guard(Unit) { events.connected() }
    override fun onConnectionFailed(error: String?) = ended(error, failed = true)
    override fun onDisconnected(error: String?) = ended(error, failed = false)
    override fun onReconnecting() = guard(Unit) { events.reconnecting() }
    override fun onSettingsChanged(width: Int, height: Int, bpp: Int) {}

    private fun ended(error: String?, failed: Boolean) {
        val own = failure
        try {
            if (own != null) events.closed(own, failed = true) else events.closed(error, failed)
        } catch (_: Throwable) {
            // The session is over anyway; nothing may reach JNI.
        }
    }

    override fun onAuthenticate(username: StringBuilder, domain: StringBuilder, password: StringBuilder, gateway: Boolean): Boolean = guard(false) {
        // Standard RDP security: go on without a password, Windows asks for it on its own screen.
        if (!gateway && windowsLogon) return@guard true
        val c = events.credentials(RdpCredentials(username.toString(), domain.toString(), password.toString()), gateway) ?: return@guard false
        username.setLength(0); username.append(c.username)
        domain.setLength(0); domain.append(c.domain)
        password.setLength(0); password.append(c.password)
        true
    }

    override fun onVerifyCertificate(host: String, port: Int, commonName: String, subject: String, issuer: String, fingerprint: String, flags: Long): Int = guard(0) {
        val cert = RdpCertificate(
            commonName, subject, issuer, fingerprint,
            isPem = flags and LibFreeRDP.VERIFY_CERT_FLAG_FP_IS_PEM != 0L,
            mismatch = flags and LibFreeRDP.VERIFY_CERT_FLAG_MISMATCH != 0L,
        )
        // 2: trusted for this connection only; the app keeps its own store of trusted certificates.
        if (events.certificate(cert)) 2 else 0
    }

    override fun onGraphicsResize(width: Int, height: Int, bpp: Int) = guard(Unit) {
        if (!desktopSizeAllowed(width, height)) {
            bitmap = null
            abort("$DESKTOP_TOO_LARGE ${width}x$height")
            return@guard
        }
        bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        events.resized(width, height)
    }

    // Graphics callbacks run on the session thread, which keeps the instance alive until it ends.
    override fun onGraphicsUpdate(x: Int, y: Int, width: Int, height: Int) = guard(Unit) {
        val b = bitmap ?: return@guard
        if (LibFreeRDP.updateGraphics(instance, b, x, y, width, height)) events.frame(x, y, width, height)
    }

    override fun onRemoteClipboard(text: String) = guard(Unit) { events.remoteClipboard(text) }

    override fun onPointer(pixels: IntArray?, width: Int, height: Int, hotX: Int, hotY: Int) = guard(Unit) {
        val ok = pixels != null && width in 1..MAX_POINTER && height in 1..MAX_POINTER && pixels.size >= width * height
        events.pointer(if (ok) RdpPointer(pixels, width, height, hotX, hotY) else null)
    }

    override fun onPointerDefault() = guard(Unit) { events.pointer(null) }

    companion object {
        /** Prefix of [Events.closed]'s error when the app itself ended the connection (see [abort]). */
        const val CLIENT_ERROR = "NAUTILE_CLIENT_ERROR"
        /** The server asked for a desktop larger than [MAX_PIXELS]. */
        const val DESKTOP_TOO_LARGE = "DESKTOP_TOO_LARGE"
        /** Largest desktop bitmap allocated: 8192×4096 (128 MiB in ARGB). */
        const val MAX_PIXELS = 8192L * 4096
        private const val MAX_SIDE = 8192
        private const val MAX_POINTER = 384

        fun desktopSizeAllowed(width: Int, height: Int): Boolean =
            width in 1..MAX_SIDE && height in 1..MAX_SIDE && width.toLong() * height <= MAX_PIXELS

        /** Null when FreeRDP can be used on this device, else the reason. */
        fun unavailableReason(): String? = LibFreeRDP.loadError()?.let { it.message ?: it.toString() }
        fun version(): String = LibFreeRDP.getVersion()
    }
}
