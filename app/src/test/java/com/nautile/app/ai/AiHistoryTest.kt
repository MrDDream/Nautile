package com.nautile.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AiHistoryTest {
    private fun turn(q: String, kind: AiProviderKind, endpoint: String?) =
        AiTurn(q, q, emptyList(), false, kind, "m", "r", AiAssistant.parse("r"), endpoint = endpoint)

    @Test fun onlyTheSameProviderAtTheSameAddressGoesAlong() {
        val home = AiAssistant.endpointOf(AiProviderKind.LOCAL, "http://192.168.1.10:11434/v1")
        val other = AiAssistant.endpointOf(AiProviderKind.LOCAL, "http://203.0.113.5:11434/v1")
        val turns = listOf(
            turn("a", AiProviderKind.LOCAL, home),
            turn("b", AiProviderKind.LOCAL, other),
            turn("c", AiProviderKind.CUSTOM, home),
            // Saved before endpoints, or by an unknown provider: never sent again.
            turn("d", AiProviderKind.LOCAL, null),
            turn("e", AiProviderKind.LOCAL, home),
        )
        assertEquals(listOf("a", "e"), AiAssistant.contextTurns(turns, AiProviderKind.LOCAL, home).map { it.request })
        assertEquals(listOf("b"), AiAssistant.contextTurns(turns, AiProviderKind.LOCAL, other).map { it.request })
    }

    @Test fun theAddressIsNormalisedButNotLoosely() {
        val a = AiAssistant.endpointOf(AiProviderKind.CUSTOM, "https://OpenRouter.ai/api/v1/")
        assertEquals(a, AiAssistant.endpointOf(AiProviderKind.CUSTOM, " https://openrouter.ai/api/v1"))
        assertNotEquals(a, AiAssistant.endpointOf(AiProviderKind.CUSTOM, "https://openrouter.ai/API/v1"))
        assertNotEquals(a, AiAssistant.endpointOf(AiProviderKind.OPENAI, "https://openrouter.ai/api/v1"))
        assertNotEquals(a, AiAssistant.endpointOf(AiProviderKind.CUSTOM, "https://openrouter.ai.evil.example/api/v1"))
    }

    @Test fun atMostTheLastEight() {
        val e = AiAssistant.endpointOf(AiProviderKind.CLAUDE, null)
        val turns = (1..12).map { turn("$it", AiProviderKind.CLAUDE, e) }
        assertEquals((5..12).map { "$it" }, AiAssistant.contextTurns(turns, AiProviderKind.CLAUDE, e).map { it.request })
    }
}
