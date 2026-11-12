package com.nautile.ssh

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream

/** A byte stream to a remote terminal (SSH shell, Telnet, serial line, Mosh…). Closing it releases everything. */
interface TerminalChannel : Closeable {
    val input: InputStream
    val output: OutputStream
    fun resize(columns: Int, rows: Int)
}
