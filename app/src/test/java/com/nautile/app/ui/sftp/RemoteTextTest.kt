package com.nautile.app.ui.sftp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteTextTest {
    @Test fun utf8RoundTrips() {
        val bytes = "café ☕\n".toByteArray()
        val t = RemoteText.decode(bytes)
        assertEquals(Charsets.UTF_8, t.charset)
        assertEquals("café ☕\n", t.text)
        assertArrayEquals(bytes, RemoteText.encode(t.text, t.charset))
    }

    @Test fun latin1FileIsNotDamaged() {
        // « é » in ISO-8859-1 is the single byte E9, invalid in UTF-8.
        val bytes = byteArrayOf('c'.code.toByte(), 'a'.code.toByte(), 'f'.code.toByte(), 0xE9.toByte(), '\n'.code.toByte())
        val t = RemoteText.decode(bytes)
        assertEquals(Charsets.ISO_8859_1, t.charset)
        assertEquals("café\n", t.text)
        assertArrayEquals(bytes, RemoteText.encode(t.text, t.charset))
        // Unchanged bytes, also after an edit in the same encoding.
        assertArrayEquals(byteArrayOf(0xE9.toByte(), 'x'.code.toByte()), RemoteText.encode("éx", t.charset))
    }

    @Test fun characterOutsideTheEncodingIsRefused() {
        assertNull(RemoteText.encode("€", Charsets.ISO_8859_1))
    }

    @Test fun permissionsKeepSpecialBits() {
        assertEquals("755", permissionsText(0b111_101_101))
        assertEquals("4755", permissionsText(0b100_111_101_101))
        assertEquals("1777", permissionsText(0b001_111_111_111))
        assertEquals("644", permissionsText(0b1000_000_110_100_100)) // file type bits ignored
    }
}
