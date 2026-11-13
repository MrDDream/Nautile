package com.nautile.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {
    @Test
    fun versionsCompareByNumbers() {
        assertTrue(UpdateChecker.isNewer("v0.4.1", "0.4.0"))
        assertTrue(UpdateChecker.isNewer("0.10.0", "0.9.9"))
        assertFalse(UpdateChecker.isNewer("v0.4.0", "0.4.0"))
        assertFalse(UpdateChecker.isNewer("0.3.0", "0.4.0"))
        assertFalse(UpdateChecker.isNewer("0.4.0-beta", "0.4.0"))
    }

    @Test
    fun theApkOfTheProcessorIsChosenElseTheUniversalOne() {
        val names = listOf("Nautile-v0.5.0.apk", "Nautile-v0.5.0-arm64-v8a.apk", "Nautile-v0.5.0-x86_64.apk", "SHA256SUMS.txt")
        val r = UpdateChecker.Release("v0.5.0", "", false, "", names.map { UpdateChecker.Asset(it, "https://x/$it", 1) })
        assertEquals("Nautile-v0.5.0-arm64-v8a.apk", UpdateChecker.apkFor(r, listOf("arm64-v8a", "armeabi-v7a"))?.name)
        assertEquals("Nautile-v0.5.0.apk", UpdateChecker.apkFor(r, listOf("armeabi-v7a"))?.name)
        assertNull(UpdateChecker.apkFor(r.copy(assets = emptyList()), listOf("x86_64")))
    }

    @Test
    fun checksumsAreReadLikeSha256sumWritesThem() {
        val sums = "abc123  Nautile-v0.5.0.apk\nDEF456 *Nautile-v0.5.0-x86_64.apk\n"
        assertEquals("abc123", UpdateChecker.expectedSha256(sums, "Nautile-v0.5.0.apk"))
        assertEquals("def456", UpdateChecker.expectedSha256(sums, "Nautile-v0.5.0-x86_64.apk"))
        assertNull(UpdateChecker.expectedSha256(sums, "missing"))
    }

    @Test
    fun notesLoseTheirMarkdownMarks() {
        assertEquals("Nouveautés\n• **x** y".replace("**", ""), plain("## Nouveautés\n- **x** y"))
    }
}
