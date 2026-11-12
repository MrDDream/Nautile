package com.nautile.app.rdp

import android.graphics.Bitmap
import com.nautile.i18n.tr
import com.nautile.rdp.RdpConnection
import com.nautile.rdp.RdpInput
import com.nautile.rdp.RdpPointer
import com.nautile.vnc.Keysyms
import com.nautile.vnc.VncClient
import java.io.Closeable
import java.net.Socket

/**
 * What the remote desktop screen needs from a connection, whatever the protocol: the picture and
 * the input. Pointer flags and key codes are RDP's (TS_POINTER_EVENT, Windows virtual keys), which
 * the VNC side translates.
 */
interface DesktopBackend {
    val bitmap: Bitmap?
    fun pointer(x: Int, y: Int, flags: Int)
    fun key(vk: Int, down: Boolean)
    fun text(text: String)
    fun clipboard(text: String)
    /** Asks for a desktop of this size, when the protocol can. */
    fun resize(width: Int, height: Int)
    fun setForeground(foreground: Boolean)
    fun close()
}

/** FreeRDP. */
class RdpBackend(val connection: RdpConnection) : DesktopBackend {
    override val bitmap: Bitmap? get() = connection.bitmap
    override fun pointer(x: Int, y: Int, flags: Int) = connection.pointer(x, y, flags)
    override fun key(vk: Int, down: Boolean) = connection.key(vk, down)
    override fun text(text: String) = connection.text(text)
    override fun clipboard(text: String) = connection.clipboard(text)
    override fun resize(width: Int, height: Int) = connection.resize(width, height)
    override fun setForeground(foreground: Boolean) = connection.setForeground(foreground)
    override fun close() = connection.close()
}

/** VNC over a socket (direct or through an SSH tunnel), drawn into an Android bitmap. */
class VncBackend(private val socket: Socket, private val events: Events) : DesktopBackend {
    interface Events {
        fun resized(width: Int, height: Int)
        fun frame(x: Int, y: Int, width: Int, height: Int)
        fun pointer(pointer: RdpPointer?)
        fun remoteClipboard(text: String)
        /** The connection ended: [error] null when the server closed it normally. */
        fun closed(error: String?)
    }

    @Volatile override var bitmap: Bitmap? = null
        private set

    private val client: VncClient = VncClient(socket.getInputStream(), socket.getOutputStream(), object : VncClient.Listener {
        override fun resized(width: Int, height: Int) {
            bitmap = Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            events.resized(width, height)
        }

        override fun updated(x: Int, y: Int, width: Int, height: Int) {
            val b = bitmap ?: return
            val c: VncClient = client
            val w = c.width
            if (width <= 0 || height <= 0 || x + width > b.width || y + height > b.height) return
            b.setPixels(c.framebuffer, y * w + x, w, x, y, width, height)
            events.frame(x, y, width, height)
        }

        override fun cursor(pixels: IntArray?, width: Int, height: Int, hotX: Int, hotY: Int) =
            events.pointer(pixels?.let { RdpPointer(it, width, height, hotX, hotY) })

        override fun clipboard(text: String) = events.remoteClipboard(text)
    })

    /** Desktop name sent by the server. */
    val name: String get() = client.name

    private var buttons = 0
    private var lastX = 0
    private var lastY = 0
    @Volatile private var closing = false
    var tunnel: Closeable? = null

    /** Handshake ([password] asked if the server wants one), then reads updates on its own thread. */
    fun start(password: () -> String?) {
        socket.tcpNoDelay = true
        client.connect(password)
        Thread({
            val error = try { client.run(); null } catch (e: Exception) { if (closing) null else e.message ?: tr("Connexion perdue") }
            events.closed(error)
        }, "vnc-reader").start()
    }

    override fun pointer(x: Int, y: Int, flags: Int) = guard {
        if (flags and RdpInput.PTR_WHEEL != 0) {
            val bit = if (flags and RdpInput.PTR_WHEEL_NEGATIVE == 0) WHEEL_UP else WHEEL_DOWN
            client.pointer(buttons or bit, lastX, lastY); client.pointer(buttons, lastX, lastY)
            return@guard
        }
        lastX = x; lastY = y
        val button = when {
            flags and RdpInput.PTR_LEFT != 0 -> LEFT
            flags and RdpInput.PTR_MIDDLE != 0 -> MIDDLE
            flags and RdpInput.PTR_RIGHT != 0 -> RIGHT
            else -> 0
        }
        if (button != 0) buttons = if (flags and RdpInput.PTR_DOWN != 0) buttons or button else buttons and button.inv()
        client.pointer(buttons, x, y)
    }

    override fun key(vk: Int, down: Boolean) = guard { Keysyms.fromVk(vk)?.let { client.key(it, down) } }
    override fun text(text: String) = guard { client.type(text) }
    override fun clipboard(text: String) = guard { client.clipboard(text) }
    /** VNC servers set the desktop size themselves. */
    override fun resize(width: Int, height: Int) {}
    override fun setForeground(foreground: Boolean) {}

    override fun close() {
        closing = true
        io.shutdownNow()
        runCatching { client.close() }
        runCatching { socket.close() }
        runCatching { tunnel?.close() }
    }

    /** Input leaves on its own thread, in order (Android forbids the network on the UI thread). */
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "vnc-input") }

    /** Input on a dead socket is dropped: the screen shows the disconnection anyway. */
    private fun guard(block: () -> Unit) {
        if (closing) return
        runCatching { io.execute { runCatching(block) } }
    }

    private companion object {
        const val LEFT = 1
        const val MIDDLE = 2
        const val RIGHT = 4
        const val WHEEL_UP = 8
        const val WHEEL_DOWN = 16
    }
}
