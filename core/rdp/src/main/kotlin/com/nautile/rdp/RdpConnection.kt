package com.nautile.rdp

import android.content.Context
import android.graphics.Bitmap
import com.freerdp.freerdpcore.services.LibFreeRDP

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
    private val lock = java.util.concurrent.locks.ReentrantReadWriteLock()
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

    fun start(config: RdpConfig): Boolean {
        val args = RdpArgs.build(config, LibFreeRDP.hasH264Support())
        if (!LibFreeRDP.parseArguments(instance, args.toTypedArray())) return false
        return LibFreeRDP.connect(instance)
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

    override fun onConnected() = events.connected()
    override fun onConnectionFailed(error: String?) = events.closed(error, failed = true)
    override fun onDisconnected(error: String?) = events.closed(error, failed = false)
    override fun onReconnecting() = events.reconnecting()
    override fun onSettingsChanged(width: Int, height: Int, bpp: Int) {}

    override fun onAuthenticate(username: StringBuilder, domain: StringBuilder, password: StringBuilder, gateway: Boolean): Boolean {
        val c = events.credentials(RdpCredentials(username.toString(), domain.toString(), password.toString()), gateway) ?: return false
        username.setLength(0); username.append(c.username)
        domain.setLength(0); domain.append(c.domain)
        password.setLength(0); password.append(c.password)
        return true
    }

    override fun onVerifyCertificate(host: String, port: Int, commonName: String, subject: String, issuer: String, fingerprint: String, flags: Long): Int {
        val cert = RdpCertificate(
            commonName, subject, issuer, fingerprint,
            isPem = flags and LibFreeRDP.VERIFY_CERT_FLAG_FP_IS_PEM != 0L,
            mismatch = flags and LibFreeRDP.VERIFY_CERT_FLAG_MISMATCH != 0L,
        )
        // 2: trusted for this connection only; the app keeps its own store of trusted certificates.
        return if (events.certificate(cert)) 2 else 0
    }

    override fun onGraphicsResize(width: Int, height: Int, bpp: Int) {
        bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        events.resized(width, height)
    }

    // Graphics callbacks run on the session thread, which keeps the instance alive until it ends.
    override fun onGraphicsUpdate(x: Int, y: Int, width: Int, height: Int) {
        val b = bitmap ?: return
        if (LibFreeRDP.updateGraphics(instance, b, x, y, width, height)) events.frame(x, y, width, height)
    }

    override fun onRemoteClipboard(text: String) = events.remoteClipboard(text)

    override fun onPointer(pixels: IntArray?, width: Int, height: Int, hotX: Int, hotY: Int) =
        events.pointer(pixels?.let { RdpPointer(it, width, height, hotX, hotY) })

    override fun onPointerDefault() = events.pointer(null)

    companion object {
        /** Null when FreeRDP can be used on this device, else the reason. */
        fun unavailableReason(): String? = LibFreeRDP.loadError()?.let { it.message ?: it.toString() }
        fun version(): String = LibFreeRDP.getVersion()
    }
}
