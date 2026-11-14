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
    fun aMissingOrIncompleteChecksumListRefusesTheUpdate() {
        val sha = "a".repeat(64)
        assertEquals(sha, UpdateChecker.requireSha256("$sha  Nautile-v0.5.0.apk\n", "Nautile-v0.5.0.apk"))
        // No SHA256SUMS in the release, or it could not be fetched.
        assertTrue(runCatching { UpdateChecker.requireSha256(null, "Nautile-v0.5.0.apk") }.exceptionOrNull() is java.io.IOException)
        // The list does not name this APK.
        assertTrue(runCatching { UpdateChecker.requireSha256("$sha  Other.apk\n", "Nautile-v0.5.0.apk") }.isFailure)
        // Not a SHA-256 (an HTML error page, a truncated line…).
        assertTrue(runCatching { UpdateChecker.requireSha256("<html>  Nautile-v0.5.0.apk", "Nautile-v0.5.0.apk") }.isFailure)
        assertTrue(runCatching { UpdateChecker.requireSha256("abc123  Nautile-v0.5.0.apk", "Nautile-v0.5.0.apk") }.isFailure)
    }

    @Test
    fun onlyTheInstalledAppsKeyMayReplaceIt() {
        val mine = setOf("k1")
        assertTrue(UpdateChecker.signersMatch(mine, setOf("k1"), setOf("k1")))
        assertFalse(UpdateChecker.signersMatch(mine, setOf("evil"), emptySet()))
        // A rotated key whose history holds the installed one.
        assertTrue(UpdateChecker.signersMatch(mine, setOf("k2"), setOf("k1", "k2")))
        assertFalse(UpdateChecker.signersMatch(mine, emptySet(), emptySet()))
        assertFalse(UpdateChecker.signersMatch(emptySet(), setOf("k1"), emptySet()))
        // Several signers: the very same set.
        assertFalse(UpdateChecker.signersMatch(setOf("a", "b"), setOf("a"), emptySet()))
    }

    @Test
    fun notesLoseTheirMarkdownMarks() {
        assertEquals("Nouveautés\n• **x** y".replace("**", ""), plain("## Nouveautés\n- **x** y"))
    }
}
