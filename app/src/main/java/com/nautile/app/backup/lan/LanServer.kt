package com.nautile.app.backup.lan

import com.nautile.i18n.tr
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/** Who a device says it is, sent once the handshake is done (its `platform`: shown as its icon on the other side). */
data class PeerHello(val id: String, val name: String, val platform: String?) {
    fun encode(): String = buildJsonObject { put("id", id); put("name", name); platform?.let { put("platform", it) } }.toString()

    companion object {
        /** The other side's hello, which must name the device the handshake was made with. */
        fun parse(text: String, id: String): PeerHello {
            val o = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrElse { throw IOException("invalid hello") }
            fun field(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (field("id") != id) throw IOException("hello from another device")
            return PeerHello(id, field("name") ?: throw IOException("hello without a name"), field("platform")?.takeIf { it.isNotBlank() })
        }
    }
}

/**
 * The data exchange of a synchronisation, once the handshake is done (the responder already has the initiator's
 * hello): the initiator sends its hello and its data; the responder merges them first, then answers with its hello and
 * its data taken after that merge, which the initiator merges in turn. Both then hold the same in one exchange, even
 * where two versions decide a tie differently (only the responder moves one past: SyncMerge.merge moveTies). The
 * messages and their order are those of every protocol v2 version; the desktop's lan_sync::exchange does the same.
 */
internal object LanExchange {
    /** The initiator's side: sends [hello] and [mine]; the responder's hello and data. */
    fun initiate(ch: LanProtocol.Channel, hello: PeerHello, mine: String, peer: String): Pair<PeerHello, String> {
        ch.sendText(hello.encode()); ch.sendText(mine)
        val them = PeerHello.parse(ch.receiveShortText(), peer)
        return them to ch.receiveText()
    }

    /**
     * The responder's side: [merge]s the initiator's data (false: stopped or forgotten meanwhile, no answer), then
     * answers with [hello] and this device's data merged ([snapshot]).
     */
    inline fun respond(ch: LanProtocol.Channel, hello: PeerHello, merge: (String) -> Boolean, snapshot: () -> String) {
        val theirs = ch.receiveText()
        if (!merge(theirs)) return
        val mine = snapshot()
        ch.sendText(hello.encode()); ch.sendText(mine)
    }
}

/**
 * Closes a socket once the time given to [arm] is over: a whole handshake or transfer cannot last longer, however
 * slowly the other side sends (the socket's own timeout only bounds each read).
 */
internal class Deadline(private val socket: Socket) : Closeable {
    private var task: ScheduledFuture<*>? = null

    @Synchronized fun arm(ms: Long) {
        task?.cancel(false)
        task = timer.schedule({ runCatching { socket.close() } }, ms, TimeUnit.MILLISECONDS)
    }

    @Synchronized override fun close() { task?.cancel(false); task = null }

    private companion object {
        val timer = ScheduledThreadPoolExecutor(1) { r -> Thread(r, "lan-sync-deadline").apply { isDaemon = true } }
            .apply { removeOnCancelPolicy = true }
    }
}

/**
 * The listening side of the Wi-Fi synchronisation ([LanProtocol]): pairings with the code shown ([MAX_TRIES] tries
 * per code), synchronisations with the paired device's secret, [MAX_CONNECTIONS] at a time, the next ones told
 * « busy ». Nothing of Android in here: [LanSync] plugs it in, the unit tests drive it.
 */
internal class LanServer(
    private val deviceId: () -> String,
    private val host: Host,
    private val handshakeMs: Long = HANDSHAKE_DEADLINE,
    private val transferMs: Long = TRANSFER_DEADLINE,
) {
    /** What this device knows of the secret of a device asking to synchronise. */
    sealed interface Secret {
        class Known(val bytes: ByteArray) : Secret
        /** Never paired here, or forgotten: told « unknown », to pair again. */
        data object Unknown : Secret
        /** Paired, but its secret cannot be read for now (the Keystore failing for a moment): told « busy », to try again. */
        data object NotNow : Secret
    }

    interface Host {
        /** This device's hello. */
        fun hello(): PeerHello
        /** The secret of the device [id]. */
        fun secret(id: String): Secret
        /** Paired with [them]: [secret] is to keep for it. Called under the pairing lock, before this side answers. */
        fun paired(them: PeerHello, secret: ByteArray)
        /** A synchronisation from the paired device [them] on [channel]; [live] turns false once stopped. */
        fun exchange(channel: LanProtocol.Channel, them: PeerHello, live: () -> Boolean)
        fun status(message: String)
    }

    private val _code = MutableStateFlow<String?>(null)
    /** The code this device shows while waiting to be paired; null again once used, cancelled or after too many tries. */
    val code: StateFlow<String?> = _code.asStateFlow()

    /** Guards [_code], [attempts] and [socket] together. */
    private val lock = Any()
    private var attempts = 0
    @Volatile private var socket: ServerSocket? = null
    private val slots = Semaphore(MAX_CONNECTIONS)
    private val refusals = Semaphore(MAX_REFUSALS)

    val running: Boolean get() = socket != null

    /** Listens on a free port (unless already); that port. */
    fun start(): Int = synchronized(lock) {
        socket?.let { return it.localPort }
        val s = ServerSocket(0)
        socket = s
        Thread({ acceptLoop(s) }, "lan-sync-server").apply { isDaemon = true }.start()
        s.localPort
    }

    /** No more connection accepted; a pairing under way is not kept. */
    fun stop() = synchronized(lock) {
        runCatching { socket?.close() }
        socket = null
        _code.value = null
    }

    /** Shows a new code: another device types it to pair. */
    fun showCode(): String = LanProtocol.newCode().also { synchronized(lock) { attempts = 0; _code.value = it } }

    /** Drops the code shown; a pairing under way with it is not kept. */
    fun cancelCode() = synchronized(lock) { _code.value = null }

    private fun acceptLoop(server: ServerSocket) {
        while (!server.isClosed) {
            val client = runCatching { server.accept() }.getOrNull() ?: break
            // A few connections at a time; the next ones are told « busy », and past those closed straight away.
            val busy = !slots.tryAcquire()
            val held = when {
                !busy -> slots
                refusals.tryAcquire() -> refusals
                else -> { runCatching { client.close() }; continue }
            }
            val started = runCatching {
                Thread({
                    try { client.use { if (busy) refuse(it, LanProtocol.Status.BUSY) else handle(it, server) } } finally { held.release() }
                }, "lan-sync-peer").apply { isDaemon = true }.start()
            }
            if (started.isFailure) { held.release(); runCatching { client.close() } }
        }
    }

    /** Whether [server] is still the running one (not stopped or replaced meanwhile). */
    private fun serving(server: ServerSocket) = socket === server && !server.isClosed

    /** Answers with a refusal, then waits a little for the other side to hang up (its bytes unread would reset the connection). */
    private fun refuse(socket: Socket, status: Int?) {
        runCatching {
            if (status != null) LanProtocol.refuse(socket.getOutputStream(), status)
            socket.shutdownOutput()
            val end = System.currentTimeMillis() + REFUSE_LINGER
            val sink = ByteArray(512)
            while (true) {
                val left = end - System.currentTimeMillis()
                if (left <= 0) return
                socket.soTimeout = left.toInt()
                if (socket.getInputStream().read(sink) <= 0) return
            }
        }
    }

    /** An incoming connection: pairing (with the code shown) or synchronisation (with the paired device's secret). */
    private fun handle(socket: Socket, server: ServerSocket): Unit = Deadline(socket).use { deadline ->
        deadline.arm(handshakeMs)
        socket.soTimeout = HANDSHAKE_IDLE
        try {
            val input = socket.getInputStream(); val output = socket.getOutputStream()
            val hello = try {
                LanProtocol.readHello(input, output)
            } catch (e: LanProtocol.PeerTooOld) {
                refuse(socket, null)
                host.status(tr("Un appareil avec une ancienne version de Nautile a essayé de se connecter : mettez-le à jour"))
                return
            } catch (e: LanProtocol.PeerTooNew) {
                refuse(socket, null)
                host.status(tr("Un appareil avec une version plus récente de Nautile a essayé de se connecter : mettez à jour celui-ci"))
                return
            }
            if (!serving(server)) return
            when (hello.mode) {
                LanProtocol.MODE_PAIR -> {
                    // The try is counted before the handshake, under the lock: parallel connections get no more
                    // than MAX_TRIES between them.
                    val code = synchronized(lock) { _code.value?.takeIf { attempts < MAX_TRIES }?.also { attempts++ } }
                        ?: return refuse(socket, LanProtocol.Status.NO_CODE)
                    val paired = try {
                        LanProtocol.pairAccept(input, output, hello, deviceId(), code)
                    } catch (e: IOException) {
                        synchronized(lock) {
                            if (attempts >= MAX_TRIES && _code.value == code) {
                                _code.value = null
                                host.status(tr("Trop d'essais : nouveau code nécessaire"))
                            }
                        }
                        throw e
                    }
                    val them = PeerHello.parse(paired.channel.receiveShortText(), hello.id)
                    // Kept only if the code is still the one shown and the server still runs (not cancelled or
                    // stopped during the handshake); otherwise no answer, and the other side keeps nothing either.
                    synchronized(lock) {
                        if (!serving(server) || _code.value != code) return
                        _code.value = null
                        host.paired(them, paired.secret)
                    }
                    paired.channel.sendText(host.hello().encode())
                }
                LanProtocol.MODE_SYNC -> {
                    val secret = when (val s = host.secret(hello.id)) {
                        is Secret.Known -> s.bytes
                        Secret.Unknown -> return refuse(socket, LanProtocol.Status.UNKNOWN)
                        Secret.NotNow -> return refuse(socket, LanProtocol.Status.BUSY)
                    }
                    val ch = LanProtocol.syncAccept(input, output, hello, deviceId(), secret)
                    deadline.arm(transferMs)
                    socket.soTimeout = DATA_IDLE
                    val them = PeerHello.parse(ch.receiveShortText(), hello.id)
                    host.exchange(ch, them) { serving(server) }
                }
            }
        } catch (_: Exception) {
            // A failed or foreign connection: nothing to keep.
        }
    }

    companion object {
        /** Wrong codes allowed before the shown one is dropped. */
        const val MAX_TRIES = 3
        /** Connections handled at once; the next ones are told « busy ». */
        const val MAX_CONNECTIONS = 4
        /** « Busy » answers under way at once; past them, connections are closed straight away. */
        const val MAX_REFUSALS = 4
        /** The whole handshake, hellos included. */
        const val HANDSHAKE_DEADLINE = 15_000L
        /** The whole data exchange (a database each way). */
        const val TRANSFER_DEADLINE = 300_000L
        /** The longest wait for one read, during the handshake and then the data exchange. */
        const val HANDSHAKE_IDLE = 10_000
        const val DATA_IDLE = 60_000
        private const val REFUSE_LINGER = 2_000L
        const val CONNECT_TIMEOUT = 8_000

        /**
         * Connects to [host]:[port] and runs [block], the whole within [handshakeMs] until [block] arms the
         * [Deadline] again for the transfer.
         */
        inline fun <T> connect(host: InetAddress, port: Int, handshakeMs: Long = HANDSHAKE_DEADLINE, block: (Socket, Deadline) -> T): T =
            Socket().use { s ->
                Deadline(s).use { d ->
                    d.arm(handshakeMs)
                    s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT)
                    s.soTimeout = HANDSHAKE_IDLE
                    block(s, d)
                }
            }
    }
}
