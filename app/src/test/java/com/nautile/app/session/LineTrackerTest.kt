package com.nautile.app.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LineTrackerTest {
    private val committed = mutableListOf<String>()
    private var screen = ""
    private val t = LineTracker({ screen }, { committed += it })

    @Test fun commitsEchoedTyping() {
        t.onInput("ls -la"); assertEquals("ls -la", t.current)
        t.onInput("\u007f\u007fl")
        screen = "dorian@srv:~$ ls -l"
        t.onInput("\r")
        assertEquals(listOf("ls -l"), committed)
        assertEquals("", t.current)
    }

    @Test fun neverKeepsUnechoedPasswords() {
        screen = "[sudo] password for dorian: "
        t.onInput("hunter2\r")
        assertEquals(emptyList<String>(), committed)
    }

    @Test fun readsLineFromScreenAfterTabCompletion() {
        t.onInput("docker comp\t")
        assertNull(t.current)
        screen = "dorian@srv ~ ❯ docker compose ps"
        t.onInput("\r")
        assertEquals(listOf("docker compose ps"), committed)
    }

    @Test fun arrowKeysAndCtrlC() {
        t.onInput("\u001b[A")
        screen = "root@pi:/# apt update"
        t.onInput("\r")
        t.onInput("rm -rf /tmp/x\u0003")
        screen = "root@pi:/# "
        t.onInput("\r")
        assertEquals(listOf("apt update"), committed)
    }

    @Test fun promptExtraction() {
        assertEquals("git status", LineTracker.afterPrompt("~/proj main \uE0B0 git status"))
        assertNull(LineTracker.afterPrompt("no prompt here"))
    }
}
