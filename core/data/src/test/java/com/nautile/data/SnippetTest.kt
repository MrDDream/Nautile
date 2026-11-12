package com.nautile.data

import com.nautile.data.model.Snippet
import org.junit.Assert.assertEquals
import org.junit.Test

class SnippetTest {
    private val s = Snippet(name = "logs", command = "docker logs -f --tail \${LINES} \${SERVICE} # \${SERVICE} \$HOME \${1bad}")

    @Test fun listsVariablesOnce() = assertEquals(listOf("LINES", "SERVICE"), s.variables)

    @Test fun fillsKnownVariablesOnly() = assertEquals(
        "docker logs -f --tail 200 n8n # n8n \$HOME \${1bad}",
        Snippet(name = "x", command = s.command).fill(mapOf("LINES" to "200", "SERVICE" to "n8n")),
    )
}
