package com.nautile.app.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoBackupTest {
    @Test fun folderUrlGetsTheFileName() {
        assertEquals("https://cloud.lan/dav/Nautile/nautile.backup", AutoBackup.webDavFileUrl("https://cloud.lan/dav/Nautile/"))
        assertEquals("https://cloud.lan/dav/mine.bin", AutoBackup.webDavFileUrl("https://cloud.lan/dav/mine.bin"))
    }

    @Test fun backupBecomesStaleAfterThirtyDays() {
        val now = 100L * 24 * 3600 * 1000
        assertTrue(BackupPrefs.isStale(null, now))
        assertFalse(BackupPrefs.isStale(now - 29L * 24 * 3600 * 1000, now))
        assertTrue(BackupPrefs.isStale(now - 31L * 24 * 3600 * 1000, now))
    }
}
