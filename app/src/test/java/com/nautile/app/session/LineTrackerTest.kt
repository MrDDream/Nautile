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

    @Test fun passwordMatchingPartOfThePromptIsNotKept() {
        // Echo off: the typed text happens to appear in the prompt.
        for (pw in listOf("dorian", "pass", "word", "for", "dorian:")) {
            screen = "[sudo] password for dorian:"
            t.onInput("$pw\r")
        }
        assertEquals(emptyList<String>(), committed)
    }

    @Test fun suppressedLineIsNeverKept() {
        screen = "dorian@srv:~$ hunter2"
        t.suppressNextLine()
        t.onInput("hunter2\r")
        assertEquals(emptyList<String>(), committed)
        // Tracking resumes after it.
        screen = "dorian@srv:~$ ls"
        t.onInput("ls\r")
        assertEquals(listOf("ls"), committed)
    }

    @Test fun echoDetection() {
        assertEquals(true, LineTracker.echoed("me@h:~$ echo password", "echo password"))
        assertEquals(false, LineTracker.echoed("Enter passphrase for key: abc", "abc"))
        assertEquals(true, LineTracker.echoed("ong-option --x", "some --very-long-option --x")) // wrapped
        assertEquals(false, LineTracker.echoed("me@h:~$ ls", "rm"))
    }

    @Test fun rightPromptAndSuggestionsDoNotHideTheCommand() {
        // zsh RPROMPT / powerlevel10k / starship: status and time at the right of the row.
        screen = "dorian@srv ~/proj ❯ git status                         ✔  took 2s  10:32:01"
        t.onInput("git status\r")
        // fish: the greyed autosuggestion follows what was typed.
        screen = "dorian@srv ~> ls -la /var/log/nginx"
        t.onInput("ls\r")
        // zsh-autosuggestions
        screen = "~/proj main % docker compose up -d --build"
        t.onInput("docker compose\r")
        assertEquals(listOf("git status", "ls", "docker compose"), committed)
    }

    @Test fun passwordWordInAFolderOrBranchDoesNotBlockHistory() {
        screen = "dorian@srv:~/.password-store$ pass ls"
        t.onInput("pass ls\r")
        screen = "~/src/app (feature/password-reset) ❯ git log      main ✔"
        t.onInput("git log\r")
        assertEquals(listOf("pass ls", "git log"), committed)
    }

    @Test fun secretsStayOutWithRightPromptsToo() {
        // Echo off: what was typed only matches text inside the password prompt.
        for ((row, typed) in listOf(
            "[sudo] password for dorian: " to "for",
            "Enter password for # 3: " to "3:",
            "Password > " to "word >",
            "Enter passphrase for key '/home/d/.ssh/id_ed25519': " to "key",
            "mot de passe de root : " to ":",
        )) {
            screen = row
            t.onInput("$typed\r")
        }
        assertEquals(emptyList<String>(), committed)
        assertEquals(false, LineTracker.echoed("Password for # admin  ok", "admin"))
    }
}
