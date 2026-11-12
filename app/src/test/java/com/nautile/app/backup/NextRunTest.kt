package com.nautile.app.backup

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class NextRunTest {
    private val zone = ZoneId.systemDefault()
    private fun at(s: String) = LocalDateTime.parse(s).atZone(zone).toInstant().toEpochMilli()
    private fun next(f: BackupFrequency, last: String?, now: String, weekday: Int = 7) =
        AutoBackup.nextRun(f, 3 * 60, weekday, last?.let(::at), at(now))

    @Test fun dailyRunsTheNextNight() {
        assertEquals(at("2026-11-13T03:00"), next(BackupFrequency.DAY, "2026-11-12T10:00", "2026-11-12T19:00"))
        assertEquals(at("2026-11-13T03:00"), next(BackupFrequency.DAY, "2026-11-12T03:05", "2026-11-12T19:00"))
        assertEquals(at("2026-11-13T03:00"), next(BackupFrequency.DAY, null, "2026-11-12T19:00"))
    }

    @Test fun weeklyRunsTheComingWeekday() {
        // Thursday 12, last backup on Monday 9: Sunday 15.
        assertEquals(at("2026-11-15T03:00"), next(BackupFrequency.WEEK, "2026-11-09T03:00", "2026-11-12T19:00"))
        // Just backed up on Sunday: the Sunday after.
        assertEquals(at("2026-11-22T03:00"), next(BackupFrequency.WEEK, "2026-11-15T03:00", "2026-11-15T09:00"))
    }

    @Test fun monthlyWaitsAboutThirtyDays() {
        assertEquals(at("2026-12-10T03:00"), next(BackupFrequency.MONTH, "2026-11-12T03:00", "2026-11-12T19:00"))
    }
}
