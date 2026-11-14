package com.nautile.app.ui.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** An inserted command must never contain what the shell takes as Enter. */
class CommandInsertTest {
    private fun assertNoEnter(s: String) = assertFalse(s, s.contains('\n') || s.contains('\r'))

    @Test fun singleLineKeepsItsText() {
        assertEquals("ls -la /tmp", CommandInsert.flatten("ls -la /tmp\n"))
        assertEquals("df -h", CommandInsert.flatten("df -h\r\n\r\n"))
    }

    @Test fun separateCommandsAreJoinedWithSemicolons() {
        val s = CommandInsert.flatten("cd /var/log\nls\n")
        assertEquals("cd /var/log; ls", s)
        assertNoEnter(s)
    }

    @Test fun continuationsAreJoinedWithSpaces() {
        assertEquals("docker run --rm -it alpine sh", CommandInsert.flatten("docker run \\\n  --rm -it \\\n  alpine sh"))
        assertEquals("ps aux | grep ssh && echo ok", CommandInsert.flatten("ps aux |\n grep ssh &&\necho ok"))
    }

    @Test fun controlCharactersAreDropped() {
        // An injected escape sequence or a lone CR must not act on the terminal.
        val s = CommandInsert.flatten("echo hi\u001b[201~\rrm -rf ~\u0003")
        assertNoEnter(s)
        assertFalse(s.contains('\u001b'))
        assertFalse(s.contains('\u0003'))
        assertEquals("echo hi[201~; rm -rf ~", s)
    }

    @Test fun multiLineScriptsBecomeOneLine() {
        // Bracketed paste is not trusted: the line must hold no Enter whatever the terminal mode.
        val s = CommandInsert.flatten("for f in *.log; do\n  gzip \"\$f\"\ndone\n")
        assertNoEnter(s)
        assertEquals("for f in *.log; do gzip \"\$f\"; done", s)
        assertEquals("if true; then echo a; else echo b; fi", CommandInsert.flatten("if true; then\n echo a\nelse\n echo b\nfi"))
        assertEquals("sleep 5 & wait", CommandInsert.flatten("sleep 5 &\nwait"))
        assertEquals("echo undo; ls", CommandInsert.flatten("echo undo\nls"))
        assertEquals("echo log in; ls", CommandInsert.flatten("echo log in\nls"))
        assertEquals("case \$1 in a) echo a;; esac", CommandInsert.flatten("case \$1 in\n a) echo a;;\nesac"))
    }

    @Test fun commentLinesAreLeftOut() {
        // Joined with « ; », the comment would swallow the commands after it.
        assertEquals("apt update; apt upgrade -y", CommandInsert.flatten("# refresh the index\napt update\n  # then upgrade\napt upgrade -y"))
        assertEquals("ls", CommandInsert.flatten("#!/bin/sh\nls"))
        assertEquals("echo a#b", CommandInsert.flatten("echo a#b"))
    }
}
