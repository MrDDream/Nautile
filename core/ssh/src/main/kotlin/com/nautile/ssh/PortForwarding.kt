package com.nautile.ssh

import net.schmizz.sshj.connection.channel.direct.DirectConnection
import net.schmizz.sshj.connection.channel.direct.Parameters
import net.schmizz.sshj.connection.channel.forwarded.RemotePortForwarder
import net.schmizz.sshj.connection.channel.forwarded.SocketForwardingConnectListener
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections

enum class ForwardType { LOCAL, REMOTE, DYNAMIC }

/**
 * LOCAL (-L): listen on [bindHost]:[bindPort] here, connect to [destHost]:[destPort] from the server.
 * REMOTE (-R): listen on [bindHost]:[bindPort] on the server, connect to [destHost]:[destPort] from here.
 * DYNAMIC (-D): SOCKS5 proxy on [bindHost]:[bindPort] here, destinations chosen by the client.
 */
data class ForwardSpec(
    val type: ForwardType,
    val bindHost: String,
    val bindPort: Int,
    val destHost: String = "",
    val destPort: Int = 0,
)

/** A running forward; [boundPort] is the actual port (useful when 0 was requested). */
interface ActiveForward : Closeable {
    val spec: ForwardSpec
    val boundPort: Int
}

fun SshConnection.startForward(spec: ForwardSpec): ActiveForward = when (spec.type) {
    ForwardType.LOCAL -> {
        val server = bindLocal(spec)
        val forwarder = client.newLocalPortForwarder(Parameters(spec.bindHost, server.localPort, spec.destHost, spec.destPort), server)
        Thread({ runCatching { forwarder.listen() } }, "forward-L-${server.localPort}").apply { isDaemon = true }.start()
        object : ActiveForward {
            override val spec = spec
            override val boundPort = server.localPort
            override fun close() { runCatching { forwarder.close() }; runCatching { server.close() } }
        }
    }
    ForwardType.REMOTE -> {
        val rpf = client.remotePortForwarder
        val bound = rpf.bind(
            RemotePortForwarder.Forward(spec.bindHost, spec.bindPort),
            SocketForwardingConnectListener(InetSocketAddress(spec.destHost, spec.destPort)),
        )
        object : ActiveForward {
            override val spec = spec
            override val boundPort = bound.port
            override fun close() { runCatching { rpf.cancel(bound) } }
        }
    }
    ForwardType.DYNAMIC -> SocksForward(this, spec, bindLocal(spec)).also { it.start() }
}

private fun bindLocal(spec: ForwardSpec): ServerSocket = ServerSocket().apply {
    reuseAddress = true
    bind(InetSocketAddress(InetAddress.getByName(spec.bindHost.ifBlank { "127.0.0.1" }), spec.bindPort))
}

/** Minimal SOCKS5 server (no auth, CONNECT only) whose connections go through SSH direct-tcpip channels. */
private class SocksForward(
    private val connection: SshConnection,
    override val spec: ForwardSpec,
    private val server: ServerSocket,
) : ActiveForward {
    override val boundPort: Int = server.localPort
    private val open = Collections.synchronizedSet(mutableSetOf<Closeable>())

    fun start() {
        Thread({
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                Thread({ handle(socket) }, "socks-client").apply { isDaemon = true }.start()
            }
        }, "forward-D-$boundPort").apply { isDaemon = true }.start()
    }

    private fun handle(socket: Socket) {
        open += socket
        var channel: DirectConnection? = null
        try {
            val inp = DataInputStream(socket.getInputStream())
            val out = socket.getOutputStream()
            if (inp.readUnsignedByte() != 5) throw IOException("not SOCKS5")
            val methods = ByteArray(inp.readUnsignedByte()).also(inp::readFully)
            if (0.toByte() !in methods) { out.write(byteArrayOf(5, 0xFF.toByte())); throw IOException("no acceptable auth") }
            out.write(byteArrayOf(5, 0)); out.flush()
            inp.readUnsignedByte()
            val cmd = inp.readUnsignedByte()
            inp.readUnsignedByte()
            val host = when (inp.readUnsignedByte()) {
                1 -> InetAddress.getByAddress(ByteArray(4).also(inp::readFully)).hostAddress
                4 -> InetAddress.getByAddress(ByteArray(16).also(inp::readFully)).hostAddress
                3 -> String(ByteArray(inp.readUnsignedByte()).also(inp::readFully))
                else -> throw IOException("bad address type")
            }
            val port = inp.readUnsignedShort()
            if (cmd != 1) { reply(out, 7); throw IOException("unsupported command") }
            channel = try {
                connection.client.newDirectConnection(host, port)
            } catch (e: Exception) {
                reply(out, 5); throw e
            }
            open += channel
            reply(out, 0)
            val ch = channel
            val up = Thread({ pump(inp, ch.outputStream) }, "socks-up").apply { isDaemon = true; start() }
            pump(ch.inputStream, out)
            runCatching { socket.shutdownOutput() }
            up.join(2_000)
        } catch (_: Exception) {
        } finally {
            channel?.let { runCatching { it.close() }; open -= it }
            runCatching { socket.close() }; open -= socket
        }
    }

    private fun reply(out: OutputStream, code: Int) {
        out.write(byteArrayOf(5, code.toByte(), 0, 1, 0, 0, 0, 0, 0, 0)); out.flush()
    }

    override fun close() {
        runCatching { server.close() }
        synchronized(open) { open.toList() }.forEach { runCatching { it.close() } }
    }
}

internal fun pump(from: InputStream, to: OutputStream) {
    val buf = ByteArray(16 * 1024)
    try {
        while (true) {
            val n = from.read(buf)
            if (n < 0) break
            to.write(buf, 0, n)
            to.flush()
        }
    } catch (_: IOException) {
    }
}
