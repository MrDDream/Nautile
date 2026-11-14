package com.nautile.app.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferNamesTest {
    @Test fun providerNamesStayInTheTargetFolder() {
        assertEquals("authorized_keys", TransferManager.safeName("../.ssh/authorized_keys"))
        assertEquals("evil.sh", TransferManager.safeName("..\\..\\evil.sh"))
        assertEquals("photo.jpg", TransferManager.safeName("photo.jpg"))
        assertTrue(TransferManager.safeName("..") !in listOf("", ".", ".."))
        assertTrue(TransferManager.safeName("/").isNotEmpty())
        assertEquals("ab", TransferManager.safeName("a\u0000b"))
    }

    @Test fun partNameIsHiddenBesideTheFile() {
        val p = com.nautile.ssh.SftpSession.partName("/srv/www/index.html")
        assertTrue(p, p.startsWith("/srv/www/.index.html.nautile-") && p.endsWith(".part"))
    }
}
