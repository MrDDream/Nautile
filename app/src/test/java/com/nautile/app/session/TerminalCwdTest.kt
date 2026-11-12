package com.nautile.app.session

import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalCwdTest {
    @Test fun expandsHome() {
        assertEquals("/home/dorian", TerminalCwd.expand("~", "/home/dorian"))
        assertEquals("/home/dorian/stacks", TerminalCwd.expand("~/stacks", "/home/dorian/"))
        assertEquals("/etc", TerminalCwd.expand("/etc", "/home/dorian"))
    }
}
