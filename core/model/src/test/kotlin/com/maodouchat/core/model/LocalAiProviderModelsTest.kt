package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAiProviderModelsTest {

    private fun provider(model: String = "gpt-4o-mini", supportsVision: Boolean = false) =
        LocalAiProvider(
            id = "p", name = "t", baseUrl = "https://api.openai.com/v1/",
            apiKey = "k", model = model, supportsVision = supportsVision
        )

    @Test
    fun `vision capability matches known model names`() {
        assertTrue(provider().hasVisionCapability())
        assertTrue(provider("llama-3.2-vision").hasVisionCapability())
        assertFalse(provider("gpt-3.5-turbo").hasVisionCapability())
        assertTrue(provider("gpt-3.5-turbo", supportsVision = true).hasVisionCapability())
    }

    @Test
    fun `resolved urls do not double the suffix`() {
        val p = provider()
        assertEquals(
            "https://api.openai.com/v1/chat/completions", p.resolvedChatCompletionsUrl()
        )
        assertEquals("https://api.openai.com/v1/responses", p.resolvedResponsesUrl())
        assertEquals("https://api.openai.com/v1/v1/messages", p.resolvedAnthropicUrl())
        assertEquals(
            "https://api.anthropic.com/v1/messages",
            provider().copy(baseUrl = "https://api.anthropic.com/v1/messages")
                .resolvedAnthropicUrl()
        )
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            provider().copy(baseUrl = "https://api.openai.com/v1/chat/completions")
                .resolvedChatCompletionsUrl()
        )
    }

    @Test
    fun `clamps bound history tokens and timeout`() {
        val p = provider().copy(
            historyMessageLimit = 1, maxTokens = 1,
            contextWindowTokens = 10, timeoutSeconds = 1
        )
        assertEquals(4, p.clampedHistoryLimit())
        assertEquals(16, p.clampedMaxTokens())
        assertEquals(1_024, p.clampedContextWindow())
        assertEquals(15, p.clampedTimeoutSeconds())
    }
}
