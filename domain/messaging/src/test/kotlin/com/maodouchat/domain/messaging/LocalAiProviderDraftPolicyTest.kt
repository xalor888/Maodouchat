package com.maodouchat.domain.messaging

import com.maodouchat.core.model.LocalAiProtocol
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LocalAiProviderDraftPolicyTest {

    @Test
    fun `chat completions draft defaults to OpenAI gpt-4o-mini`() {
        assertEquals(
            Triple("OpenAI Chat Completions", "https://api.openai.com/v1", "gpt-4o-mini"),
            LocalAiProviderDraftPolicy.draftDefaults(LocalAiProtocol.OPENAI_CHAT_COMPLETIONS)
        )
    }

    @Test
    fun `responses draft defaults to OpenAI gpt-4-1-mini`() {
        assertEquals(
            Triple("OpenAI Responses", "https://api.openai.com/v1", "gpt-4.1-mini"),
            LocalAiProviderDraftPolicy.draftDefaults(LocalAiProtocol.OPENAI_RESPONSES)
        )
    }

    @Test
    fun `anthropic draft defaults to claude-sonnet-4-5`() {
        assertEquals(
            Triple("Anthropic", "https://api.anthropic.com", "claude-sonnet-4-5"),
            LocalAiProviderDraftPolicy.draftDefaults(LocalAiProtocol.ANTHROPIC_MESSAGES)
        )
    }
}
