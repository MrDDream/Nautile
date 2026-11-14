package com.nautile.ssh

import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.FileMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SftpWritePolicyTest {
    private fun file(perms: Int, type: FileMode.Type = FileMode.Type.REGULAR) =
        FileAttributes.Builder().withType(type).withPermissions(perms).build()

    @Test fun linkCountFromLongName() {
        assertEquals(1, SftpSession.parseLinkCount("-rw-r--r--    1 alice    alice        1234 Jan  1 00:00 notes.txt"))
        assertEquals(3, SftpSession.parseLinkCount("-rw-------    3 root     root           0 Mar 10 12:00 shadow"))
        assertNull(SftpSession.parseLinkCount("notes.txt"))
        assertNull(SftpSession.parseLinkCount(""))
    }

    @Test fun temporaryFileOnlyWhenItKeepsTheSameFile() {
        assertTrue("missing file", SftpSession.atomicAllowed(null, null))
        assertTrue(SftpSession.atomicAllowed(file(0b110_100_100), 1))
        assertTrue("unknown link count", SftpSession.atomicAllowed(file(0b110_000_000), null))
        assertFalse("hard links", SftpSession.atomicAllowed(file(0b110_100_100), 2))
        assertFalse("read-only for its owner (0444)", SftpSession.atomicAllowed(file(0b100_100_100), 1))
        assertFalse("not a regular file", SftpSession.atomicAllowed(file(0b111_101_101, FileMode.Type.DIRECTORY), 1))
    }
}
