package com.nautile.ssh

import com.nautile.i18n.tr

import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.util.Base64
import javax.net.SocketFactory

enum class ProxyType { SOCKS5, HTTP }

data class ProxyConfig(
    val type: ProxyType,
    val host: String,
    val port: Int,
    val username: String? = null,
    val password: String? = null,
)

/** Sockets that tunnel through a SOCKS5 or HTTP CONNECT proxy; the target name is resolved by the proxy. */
class ProxySocketFactory(private val proxy: ProxyConfig) : SocketFactory() {
    override fun createSocket(): Socket = ProxiedSocket(proxy)
    override fun createSocket(host: String, port: Int): Socket = createSocket().apply { connect(InetSocketAddress.createUnresolved(host, port)) }
    override fun createSocket(host: String, port: Int, localHost: InetAddress?, localPort: Int) = createSocket(host, port)
    override fun createSocket(host: InetAddress, port: Int): Socket = createSocket(host.hostAddress, port)
    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress?, localPort: Int) = createSocket(address, port)
}

internal class ProxiedSocket(private val proxy: ProxyConfig) : Socket() {
    override fun connect(endpoint: SocketAddress, timeout: Int) {
        val target = endpoint as InetSocketAddress
        super.connect(InetSocketAddress(proxy.host, proxy.port), timeout)
        val previous = soTimeout
        soTimeout = if (timeout > 0) timeout else 15_000
        try {
            when (proxy.type) {
                ProxyType.SOCKS5 -> socks5(target.hostString, target.port)
                ProxyType.HTTP -> httpConnect(target.hostString, target.port)
            }
        } catch (e: IOException) {
            runCatching { close() }
            throw e
        } finally {
            if (!isClosed) soTimeout = previous
        }
    }

    override fun connect(endpoint: SocketAddress) = connect(endpoint, 0)

    private fun socks5(host: String, port: Int) {
        val out = getOutputStream()
        val inp = DataInputStream(getInputStream())
        val withAuth = !proxy.username.isNullOrEmpty()
        out.write(if (withAuth) byteArrayOf(5, 2, 0, 2) else byteArrayOf(5, 1, 0))
        out.flush()
        if (inp.readUnsignedByte() != 5) throw IOException(tr("Proxy SOCKS5 : réponse invalide"))
        when (inp.readUnsignedByte()) {
            0 -> {}
            2 -> {
                val u = proxy.username!!.toByteArray(); val p = proxy.password.orEmpty().toByteArray()
                out.write(byteArrayOf(1, u.size.toByte()) + u + byteArrayOf(p.size.toByte()) + p)
                out.flush()
                inp.readUnsignedByte()
                if (inp.readUnsignedByte() != 0) throw IOException(tr("Proxy SOCKS5 : identifiants refusés"))
            }
            else -> throw IOException(tr("Proxy SOCKS5 : aucune méthode d'authentification acceptée"))
        }
        val name = host.toByteArray()
        out.write(byteArrayOf(5, 1, 0, 3, name.size.toByte()) + name + byteArrayOf((port shr 8).toByte(), port.toByte()))
        out.flush()
        if (inp.readUnsignedByte() != 5) throw IOException(tr("Proxy SOCKS5 : réponse invalide"))
        val rep = inp.readUnsignedByte()
        if (rep != 0) throw IOException(tr("Proxy SOCKS5 : connexion refusée (code %1\$s)", rep))
        inp.readUnsignedByte()
        val skip = when (inp.readUnsignedByte()) {
            1 -> 4
            4 -> 16
            3 -> inp.readUnsignedByte()
            else -> throw IOException(tr("Proxy SOCKS5 : adresse invalide"))
        }
        inp.readFully(ByteArray(skip + 2))
    }

    private fun httpConnect(host: String, port: Int) {
        val authority = if (':' in host) "[$host]:$port" else "$host:$port"
        val req = buildString {
            append("CONNECT $authority HTTP/1.1\r\nHost: $authority\r\n")
            if (!proxy.username.isNullOrEmpty()) {
                val token = Base64.getEncoder().encodeToString("${proxy.username}:${proxy.password.orEmpty()}".toByteArray())
                append(tr("Proxy-Authorization: Basic %1\$s\r\n", token))
            }
            append("\r\n")
        }
        getOutputStream().apply { write(req.toByteArray()); flush() }
        val inp = getInputStream()
        // Read headers byte by byte so no SSH data is consumed past the blank line.
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) {
            val b = inp.read()
            if (b < 0) throw IOException(tr("Proxy HTTP : connexion fermée"))
            head.append(b.toChar())
            if (head.length > 16_384) throw IOException(tr("Proxy HTTP : réponse trop longue"))
        }
        val status = head.lineSequence().first().split(' ').getOrNull(1)?.toIntOrNull()
        if (status != 200) throw IOException(tr("Proxy HTTP : %1\$s", head.lineSequence().first().trim()))
    }
}
