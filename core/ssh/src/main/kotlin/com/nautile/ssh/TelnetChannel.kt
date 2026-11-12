package com.nautile.ssh

import org.apache.commons.net.telnet.EchoOptionHandler
import org.apache.commons.net.telnet.SuppressGAOptionHandler
import org.apache.commons.net.telnet.TelnetClient
import org.apache.commons.net.telnet.TerminalTypeOptionHandler
import org.apache.commons.net.telnet.WindowSizeOptionHandler
import java.io.InputStream
import java.io.OutputStream

/** Plain Telnet (RFC 854) with terminal type, echo, SGA and window size (NAWS) negotiation. */
class TelnetChannel private constructor(private val client: TelnetClient) : TerminalChannel {
    override val input: InputStream get() = client.inputStream
    override val output: OutputStream get() = client.outputStream

    override fun resize(columns: Int, rows: Int) {
        runCatching {
            client.deleteOptionHandler(WINDOW_SIZE)
            client.addOptionHandler(WindowSizeOptionHandler(columns, rows, true, false, true, false))
        }
    }

    override fun close() {
        runCatching { client.disconnect() }
    }

    companion object {
        private const val WINDOW_SIZE = 31

        fun connect(host: String, port: Int, columns: Int, rows: Int, term: String = "xterm-256color", timeoutMs: Int = 15_000): TelnetChannel {
            val client = TelnetClient(term)
            client.connectTimeout = timeoutMs
            client.addOptionHandler(TerminalTypeOptionHandler(term, false, false, true, false))
            client.addOptionHandler(EchoOptionHandler(false, false, true, false))
            client.addOptionHandler(SuppressGAOptionHandler(true, true, true, true))
            client.addOptionHandler(WindowSizeOptionHandler(columns, rows, true, false, true, false))
            client.connect(host, port)
            return TelnetChannel(client)
        }
    }
}
