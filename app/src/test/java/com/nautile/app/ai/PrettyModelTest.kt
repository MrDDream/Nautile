package com.nautile.app.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class PrettyModelTest {
    @Test fun names() {
        mapOf(
            "claude-sonnet-5-5" to "Sonnet 5.5",
            "claude-opus-4-1-20250805" to "Opus 4.1",
            "claude-haiku-4-5" to "Haiku 4.5",
            "claude-3-5-sonnet-latest" to "Sonnet 3.5",
            "anthropic/claude-sonnet-4.5" to "Sonnet 4.5",
            "gpt-5-mini" to "GPT-5 mini",
            "gpt-4o" to "GPT-4o",
            "gpt-5" to "GPT-5",
            "gemini-2.5-flash" to "Gemini 2.5 Flash",
            "grok-4" to "Grok 4",
            "mistral-large-latest" to "Mistral Large",
            "qwen3" to "Qwen3",
            "deepseek-chat" to "DeepSeek Chat",
            "deepseek-reasoner" to "DeepSeek Reasoner",
        ).forEach { (id, name) -> assertEquals(id, name, prettyModel(id)) }
    }
}
