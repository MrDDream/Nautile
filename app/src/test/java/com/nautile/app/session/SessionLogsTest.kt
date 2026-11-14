package com.nautile.app.session

import com.nautile.security.FileCipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class SessionLogsTest {
    private val cipher = FileCipher { ByteArray(32) { it.toByte() } }

    private fun temp() = File.createTempFile("log", ".log").apply { deleteOnExit() }

    @Test fun sealedLogReadsBackAndIsNotInClear() {
        val f = temp()
        SessionLogs.SealedLog(f, cipher).use { out ->
            out.write("secret output\r\n".toByteArray())
            out.flush()
            out.write("ls -la\r\n".toByteArray())
        }
        assertFalse(String(f.readBytes(), Charsets.ISO_8859_1).contains("secret"))
        assertEquals("secret output\r\nls -la\r\n", SessionLogs.plainTail(f, 1 shl 20, cipher).decodeToString())
        assertEquals("secret output\nls -la\n", SessionLogs.readable(f, cipher = cipher))
    }

    @Test fun cutOffLastChunkIsIgnored() {
        val f = temp()
        SessionLogs.SealedLog(f, cipher).use { out -> out.write("one".toByteArray()); out.flush(); out.write("two".toByteArray()) }
        val bytes = f.readBytes()
        f.writeBytes(bytes.copyOf(bytes.size - 5))
        assertEquals("one", SessionLogs.plainTail(f, 1 shl 20, cipher).decodeToString())
    }

    @Test fun plainLogsFromBeforeStayReadable() {
        val f = temp()
        f.writeText("old \u001b[31mred\u001b[0m log\r\n")
        assertEquals("old red log\n", SessionLogs.readable(f, cipher = cipher))
        assertEquals("tail", SessionLogs.plainTail(f.apply { writeText("head tail") }, 4, cipher).decodeToString())
    }

    @Test fun idleOutputIsSealedWithoutClosing() {
        val f = temp()
        val out = SessionLogs.SealedLog(f, cipher, delayMs = 50)
        out.write("prompt$ ".toByteArray())
        // The process could die now: the output must reach the disk without more writes or a close.
        val deadline = System.currentTimeMillis() + 5_000
        while (SessionLogs.plainTail(f, 1 shl 20, cipher).isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals("prompt$ ", SessionLogs.plainTail(f, 1 shl 20, cipher).decodeToString())
        out.write("ls".toByteArray())
        out.close()
        assertEquals("prompt$ ls", SessionLogs.plainTail(f, 1 shl 20, cipher).decodeToString())
    }
}
