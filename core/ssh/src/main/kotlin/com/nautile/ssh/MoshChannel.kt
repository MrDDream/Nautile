package com.nautile.ssh

import com.nautile.i18n.tr

import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.modes.OCBBlockCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Mosh client (State Synchronization Protocol over UDP), without local echo prediction.
 *
 * The server's screen updates are terminal byte strings that turn the client's previous screen
 * into the new one; they are exposed through [input] and fed to the terminal emulator, which acts
 * as the client framebuffer. Only diffs based on the latest applied state are applied; others
 * are dropped and the server resends from the state we acknowledged.
 */
class MoshChannel private constructor(
    private val address: InetAddress,
    private val port: Int,
    key: ByteArray,
    columns: Int,
    rows: Int,
) : TerminalChannel {
    private val crypto = Crypto(key)
    private val socket = DatagramSocket().apply { soTimeout = 100 }
    private val incoming = LinkedBlockingQueue<ByteArray>()
    @Volatile private var closed = false
    @Volatile private var remoteGone = false

    // Client → server state: a growing list of user events; state n = first eventsAt[n] events.
    private val lock = Object()
    private val events = mutableListOf<ByteArray>()
    private val eventsAt = mutableMapOf(0L to 0)
    private var currentNum = 0L
    private var ackedNum = 0L
    private var lastSentNum = -1L
    private var lastSendAt = 0L

    // Server → client state.
    private var remoteNum = 0L
    private var sendSeq = 0L
    private var fragmentId = 0L
    private val assembly = FragmentAssembly()

    init {
        pushEvent(Proto.resize(columns, rows))
        Thread(::receiveLoop, tr("mosh-recv")).apply { isDaemon = true }.start()
        Thread(::tickLoop, tr("mosh-tick")).apply { isDaemon = true }.start()
    }

    override val input: InputStream = object : InputStream() {
        private var current: ByteArray? = null
        private var pos = 0
        override fun read(): Int {
            val b = ByteArray(1)
            return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 0xFF
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            while (current == null || pos >= current!!.size) {
                if (closed || (remoteGone && incoming.isEmpty())) return -1
                current = incoming.poll(200, TimeUnit.MILLISECONDS); pos = 0
            }
            val c = minOf(len, current!!.size - pos)
            System.arraycopy(current!!, pos, b, off, c); pos += c
            return c
        }
    }

    override val output: OutputStream = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
        override fun write(b: ByteArray, off: Int, len: Int) {
            if (len > 0) pushEvent(Proto.keystroke(b.copyOfRange(off, off + len)))
        }
    }

    override fun resize(columns: Int, rows: Int) = pushEvent(Proto.resize(columns, rows))

    private fun pushEvent(event: ByteArray) {
        synchronized(lock) {
            events += event
            currentNum++
            eventsAt[currentNum] = events.size
        }
        send(force = true)
    }

    /** Sends our latest state (diff from what the server acknowledged) plus our ack of its state. */
    private fun send(force: Boolean = false, shutdown: Boolean = false) {
        val inst: ByteArray
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val pending = lastSentNum != currentNum || ackedNum != currentNum
            if (!force && !shutdown && !(pending && now - lastSendAt > RESEND_MS) && now - lastSendAt < HEARTBEAT_MS) return
            val from = eventsAt[ackedNum] ?: 0
            val diff = Proto.userMessage(events.subList(from, events.size))
            inst = Proto.instruction(
                oldNum = ackedNum,
                newNum = if (shutdown) -1L else currentNum,
                ackNum = remoteNum,
                throwawayNum = ackedNum,
                diff = if (currentNum == ackedNum && !shutdown) ByteArray(0) else diff,
            )
            lastSentNum = currentNum
            lastSendAt = now
        }
        val compressed = zlib(inst)
        val id = fragmentId++
        val chunks = compressed.toList().chunked(FRAGMENT_BYTES).map { it.toByteArray() }.ifEmpty { listOf(ByteArray(0)) }
        chunks.forEachIndexed { i, chunk ->
            val header = ByteBuffer.allocate(10).putLong(id).putShort(((if (i == chunks.lastIndex) 0x8000 else 0) or i).toShort()).array()
            val plain = ByteBuffer.allocate(4 + header.size + chunk.size)
                .putShort((System.currentTimeMillis() % 65536).toInt().toShort()).putShort(-1)
                .put(header).put(chunk).array()
            val packet = crypto.seal(sendSeq++, plain)
            runCatching { socket.send(DatagramPacket(packet, packet.size, address, port)) }
        }
    }

    private fun receiveLoop() {
        val buf = ByteArray(2048)
        while (!closed) {
            val packet = DatagramPacket(buf, buf.size)
            try { socket.receive(packet) } catch (_: SocketTimeoutException) { continue } catch (_: IOException) { break }
            val plain = crypto.open(buf.copyOf(packet.length)) ?: continue
            if (plain.size < 14) continue
            val bb = ByteBuffer.wrap(plain, 4, plain.size - 4)
            val id = bb.long
            val numFinal = bb.short.toInt() and 0xFFFF
            val payload = ByteArray(bb.remaining()).also(bb::get)
            val whole = assembly.add(id, numFinal and 0x7FFF, numFinal and 0x8000 != 0, payload) ?: continue
            val inst = runCatching { Proto.parseInstruction(unzlib(whole)) }.getOrNull() ?: continue
            if (inst.protocolVersion != 2) continue
            onInstruction(inst)
        }
    }

    private fun onInstruction(inst: Proto.Instruction) {
        synchronized(lock) {
            if (inst.ackNum in eventsAt && inst.ackNum > ackedNum) {
                eventsAt.keys.removeAll { it < inst.ackNum }
                ackedNum = inst.ackNum
            }
        }
        if (inst.newNum == -1L) { remoteGone = true; send(force = true); return }
        if (inst.newNum > remoteNum && inst.oldNum == remoteNum) {
            Proto.hostStrings(inst.diff).forEach { if (it.isNotEmpty()) incoming.put(it) }
            remoteNum = inst.newNum
            send(force = true)
        } else if (inst.newNum <= remoteNum) {
            send() // duplicate: our periodic ack covers it
        }
    }

    private fun tickLoop() {
        while (!closed) {
            Thread.sleep(TICK_MS)
            send()
        }
    }

    override fun close() {
        if (closed) return
        runCatching { send(shutdown = true) }
        closed = true
        socket.close()
    }

    private class Crypto(key: ByteArray) {
        private val keyParam = KeyParameter(key)

        fun seal(seq: Long, plain: ByteArray): ByteArray {
            val nonce = seq and SEQUENCE_MASK // direction bit 0: to server
            val c = OCBBlockCipher(AESEngine.newInstance(), AESEngine.newInstance())
            c.init(true, AEADParameters(keyParam, 128, nonce12(nonce)))
            val out = ByteArray(c.getOutputSize(plain.size))
            val n = c.processBytes(plain, 0, plain.size, out, 0)
            c.doFinal(out, n)
            return ByteBuffer.allocate(8 + out.size).putLong(nonce).put(out).array()
        }

        fun open(packet: ByteArray): ByteArray? = runCatching {
            val nonce = ByteBuffer.wrap(packet, 0, 8).long
            if (nonce and DIRECTION_BIT == 0L) return null // not from the server
            val c = OCBBlockCipher(AESEngine.newInstance(), AESEngine.newInstance())
            c.init(false, AEADParameters(keyParam, 128, nonce12(nonce)))
            val out = ByteArray(c.getOutputSize(packet.size - 8))
            val n = c.processBytes(packet, 8, packet.size - 8, out, 0)
            out.copyOf(n + c.doFinal(out, n))
        }.getOrNull()

        private fun nonce12(v: Long) = ByteBuffer.allocate(12).putInt(0).putLong(v).array()
    }

    private class FragmentAssembly {
        private var id = -1L
        private val parts = sortedMapOf<Int, ByteArray>()
        private var total = -1

        fun add(fid: Long, num: Int, last: Boolean, data: ByteArray): ByteArray? {
            if (fid != id) { id = fid; parts.clear(); total = -1 }
            parts[num] = data
            if (last) total = num + 1
            if (total < 0 || parts.size != total) return null
            val out = ByteArrayOutputStream()
            parts.values.forEach(out::write)
            id = -1; parts.clear(); total = -1
            return out.toByteArray()
        }
    }

    /** Hand-written protobuf for mosh's three tiny message types. */
    internal object Proto {
        class Instruction(val protocolVersion: Int, val oldNum: Long, val newNum: Long, val ackNum: Long, val diff: ByteArray)

        fun instruction(oldNum: Long, newNum: Long, ackNum: Long, throwawayNum: Long, diff: ByteArray) = ByteArrayOutputStream().apply {
            varintField(1, 2); varintField(2, oldNum); varintField(3, newNum); varintField(4, ackNum); varintField(5, throwawayNum)
            bytesField(6, diff)
        }.toByteArray()

        fun keystroke(keys: ByteArray) = ByteArrayOutputStream().apply {
            bytesField(2, ByteArrayOutputStream().apply { bytesField(4, keys) }.toByteArray())
        }.toByteArray()

        fun resize(width: Int, height: Int) = ByteArrayOutputStream().apply {
            bytesField(3, ByteArrayOutputStream().apply { varintField(5, width.toLong()); varintField(6, height.toLong()) }.toByteArray())
        }.toByteArray()

        /** UserMessage { repeated Instruction instruction = 1 } */
        fun userMessage(instructions: List<ByteArray>) = ByteArrayOutputStream().apply {
            instructions.forEach { bytesField(1, it) }
        }.toByteArray()

        fun parseInstruction(b: ByteArray): Instruction {
            var version = 0; var old = 0L; var new = 0L; var ack = 0L; var diff = ByteArray(0)
            fields(b) { field, value, bytes ->
                when (field) {
                    1 -> version = value.toInt(); 2 -> old = value; 3 -> new = value; 4 -> ack = value
                    6 -> diff = bytes!!
                }
            }
            return Instruction(version, old, new, ack, diff)
        }

        /** HostMessage → the hoststring of each HostBytes instruction. */
        fun hostStrings(diff: ByteArray): List<ByteArray> {
            val out = mutableListOf<ByteArray>()
            fields(diff) { f, _, inst ->
                if (f == 1 && inst != null) fields(inst) { ext, _, hostBytes ->
                    if (ext == 2 && hostBytes != null) fields(hostBytes) { hf, _, s -> if (hf == 4 && s != null) out += s }
                }
            }
            return out
        }

        private fun ByteArrayOutputStream.varint(v: Long) {
            var x = v
            while (x and 0x7FL.inv() != 0L) { write(((x and 0x7F) or 0x80).toInt()); x = x ushr 7 }
            write(x.toInt())
        }
        private fun ByteArrayOutputStream.varintField(f: Int, v: Long) { varint((f shl 3).toLong()); varint(v) }
        private fun ByteArrayOutputStream.bytesField(f: Int, v: ByteArray) { varint(((f shl 3) or 2).toLong()); varint(v.size.toLong()); write(v) }

        private fun fields(b: ByteArray, onField: (Int, Long, ByteArray?) -> Unit) {
            var i = 0
            fun readVarint(): Long {
                var r = 0L; var shift = 0
                while (true) { val x = b[i++].toInt() and 0xFF; r = r or ((x and 0x7F).toLong() shl shift); if (x < 0x80) return r; shift += 7 }
            }
            while (i < b.size) {
                val tag = readVarint()
                val field = (tag ushr 3).toInt()
                when ((tag and 7).toInt()) {
                    0 -> onField(field, readVarint(), null)
                    2 -> { val len = readVarint().toInt(); onField(field, 0, b.copyOfRange(i, i + len)); i += len }
                    1 -> i += 8
                    5 -> i += 4
                    else -> return
                }
            }
        }
    }

    companion object {
        private const val DIRECTION_BIT = Long.MIN_VALUE
        private const val SEQUENCE_MASK = Long.MAX_VALUE
        private const val FRAGMENT_BYTES = 400
        private const val TICK_MS = 50L
        private const val RESEND_MS = 300L
        private const val HEARTBEAT_MS = 3000L

        private fun zlib(b: ByteArray): ByteArray {
            val d = Deflater(); d.setInput(b); d.finish()
            val out = ByteArrayOutputStream(); val buf = ByteArray(4096)
            while (!d.finished()) out.write(buf, 0, d.deflate(buf))
            d.end(); return out.toByteArray()
        }

        private fun unzlib(b: ByteArray): ByteArray {
            val inf = Inflater(); inf.setInput(b)
            val out = ByteArrayOutputStream(); val buf = ByteArray(8192)
            while (!inf.finished()) { val n = inf.inflate(buf); if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break; out.write(buf, 0, n) }
            inf.end(); return out.toByteArray()
        }

        private val CONNECT = Regex("""MOSH CONNECT (\d+) ([A-Za-z0-9/+]{22})""")

        /**
         * Starts `mosh-server` through [connection] (SSH, then closed by the caller) and opens the
         * UDP session to [udpHost].
         */
        fun start(
            connection: SshConnection,
            udpHost: String,
            columns: Int,
            rows: Int,
            serverCommand: String = tr("mosh-server new -s -c 256 -l LANG=C.UTF-8"),
        ): MoshChannel {
            val out = connection.exec("$serverCommand 2>&1 < /dev/null")
            val m = CONNECT.find(out) ?: throw IOException(
                if (out.contains("not found")) tr("mosh-server n'est pas installé sur le serveur")
                else tr("Réponse inattendue de mosh-server : %1\$s", out.trim().take(200)),
            )
            val key = Base64.getDecoder().decode(m.groupValues[2] + "==")
            return MoshChannel(InetAddress.getByName(udpHost), m.groupValues[1].toInt(), key, columns, rows)
        }
    }
}
