package com.nautile.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AiAssistantParseTest {
    @Test fun extractsCommandAndExplanation() {
        val s = AiAssistant.parse("```bash\ndocker compose logs -f --tail 100 api\n```\nAffiche les 100 dernières lignes puis suit le flux.")
        assertEquals("docker compose logs -f --tail 100 api", s.command)
        assertEquals("Affiche les 100 dernières lignes puis suit le flux.", s.explanation)
    }

    @Test fun noCommandBlock() {
        val s = AiAssistant.parse("Cette erreur vient d'un disque plein.")
        assertNull(s.command)
        assertEquals("Cette erreur vient d'un disque plein.", s.explanation)
    }
}
