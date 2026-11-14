package com.nautile.app.ai

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.nautile.security.FileCipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class AiConversationStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private val key = ByteArray(32) { it.toByte() }
    /** The Keystore answering or not. */
    private var keyReadable = true
    private val cipher = FileCipher { if (keyReadable) key else null }

    private fun assistant(dir: File, cipher: FileCipher = this.cipher) = AiAssistant(
        object : AiAssistant.Secrets {
            val m = mutableMapOf<String, String>()
            override fun get(name: String) = m[name]
            override fun put(name: String, value: String?) { if (value == null) m.remove(name) else m[name] = value }
        },
        ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("ai_test", Context.MODE_PRIVATE),
        dir,
        cipher,
    )

    private fun turn(q: String) = AiTurn(q, q, emptyList(), false, AiProviderKind.CLAUDE, "m", "r", AiAssistant.parse("r"))

    @Test fun aConversationUnreadableForNowIsNeitherEmptiedNorOverwritten() {
        val dir = tmp.newFolder()
        assistant(dir).record("host:1", turn("first"))
        val saved = dir.listFiles()!!.single().readBytes()

        // Next start, the Keystore not answering: nothing shown yet, and a new question does not replace the file.
        keyReadable = false
        val ai = assistant(dir)
        assertTrue(ai.conversation("host:1").value.isEmpty())
        ai.record("host:1", turn("second"))
        assertTrue(saved.contentEquals(dir.listFiles()!!.single { it.name.endsWith(".json") }.readBytes()))

        // The Keystore answers again: a later start reads the saved conversation.
        keyReadable = true
        assertEquals(listOf("first"), assistant(dir).conversation("host:1").value.map { it.request })
    }

    @Test fun theConversationOfADeletedHostGoes() {
        val dir = tmp.newFolder()
        val ai = assistant(dir)
        ai.record("host:1", turn("a"))
        ai.record("host:2", turn("b"))
        ai.record("quick:10.0.0.1", turn("c"))
        assertEquals(3, dir.listFiles()!!.size)
        ai.forgetDeletedHosts(setOf(1L))
        assertEquals(2, dir.listFiles()!!.size)
        assertTrue(ai.conversation("host:2").value.isEmpty())
        assertEquals(listOf("a"), ai.conversation("host:1").value.map { it.request })
        // Even the last host deleted (no host left).
        ai.forgetDeletedHosts(emptySet())
        assertTrue(ai.conversation("host:1").value.isEmpty())
        assertFalse(assistant(dir).conversation("quick:10.0.0.1").value.isEmpty())
    }

    private fun hashName(key: String) =
        java.security.MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)

    @Test fun aHostFarPastTheOthersIsForgottenAndNoOtherIsTouched() {
        val dir = tmp.newFolder()
        val ai = assistant(dir)
        ai.record("host:2", turn("a"))
        ai.record("host:500", turn("b"))
        ai.forgetDeletedHosts(setOf(2L))
        assertEquals(setOf("host-2.json"), dir.list()!!.toSet())
        // Only the files of hosts that existed are looked at: nothing else is ever removed.
        ai.forgetDeletedHosts(setOf(2L, 3L))
        assertEquals(listOf("a"), assistant(dir).conversation("host:2").value.map { it.request })
    }

    @Test fun filesOfEarlierVersionsAreRenamedAfterTheirHost() {
        val dir = tmp.newFolder()
        // Saved before: plain JSON named by a hash of the key.
        val json = """[{"request":"old","raw":"r","provider":"CLAUDE","model":"m","at":1}]"""
        File(dir, "${hashName("host:3")}.json").writeText(json)
        File(dir, "${hashName("host:70000")}.json").writeText(json)
        File(dir, "${hashName("quick:10.0.0.1")}.json").writeText(json)
        val ai = assistant(dir)
        assertEquals(listOf("old"), ai.conversation("host:3").value.map { it.request })
        assertTrue(File(dir, "host-3.json").exists())
        ai.forgetDeletedHosts(setOf(3L))
        assertEquals(setOf("host-3.json", "${hashName("quick:10.0.0.1")}.json"), dir.list()!!.toSet())
    }

    @Test fun anUnreadableFileIsKeptAsideUnderANameOfItsOwn() {
        val dir = tmp.newFolder()
        assistant(dir).record("host:1", turn("a"))
        val other = FileCipher { ByteArray(32) { 7 } }
        assertTrue(assistant(dir, other).conversation("host:1").value.isEmpty())
        assistant(dir).record("host:1", turn("b"))
        assertTrue(assistant(dir, other).conversation("host:1").value.isEmpty())
        assertEquals(setOf("host-1.json.unreadable", "host-1.json.unreadable-1"), dir.list()!!.toSet())
    }
}
