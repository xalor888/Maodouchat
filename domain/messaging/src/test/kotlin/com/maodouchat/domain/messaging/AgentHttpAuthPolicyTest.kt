package com.maodouchat.domain.messaging

import com.maodouchat.domain.messaging.LocalAiProtocol
import com.maodouchat.domain.messaging.LocalAiProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AgentHttpAuthPolicyTest {

    private fun provider(
        protocol: LocalAiProtocol = LocalAiProtocol.OPENAI_CHAT_COMPLETIONS,
        apiKey: String = "k1",
        anthropicVersion: String = "2023-06-01",
        organization: String = "",
        extraHeadersJson: String = "{}"
    ) = LocalAiProvider(
        id = "p1",
        name = "p1",
        baseUrl = "https://example.test",
        apiKey = apiKey,
        model = "m",
        protocol = protocol,
        anthropicVersion = anthropicVersion,
        organization = organization,
        extraHeadersJson = extraHeadersJson
    )

    @Test
    fun `openai adds bearer and organization only when set`() {
        assertEquals(
            listOf("Authorization" to "Bearer k1"),
            AgentHttpAuthPolicy.authHeaders(provider())
        )
        assertEquals(
            listOf(
                "Authorization" to "Bearer k1",
                "OpenAI-Organization" to "org-1"
            ),
            AgentHttpAuthPolicy.authHeaders(provider(organization = "org-1"))
        )
    }

    @Test
    fun `anthropic adds api key header version and bearer`() {
        assertEquals(
            listOf(
                "x-api-key" to "k1",
                "anthropic-version" to "2023-06-01",
                "Authorization" to "Bearer k1"
            ),
            AgentHttpAuthPolicy.authHeaders(provider(protocol = LocalAiProtocol.ANTHROPIC_MESSAGES))
        )
    }

    @Test
    fun `anthropic blank version falls back to default and blank key skips bearer`() {
        assertEquals(
            listOf(
                "x-api-key" to "",
                "anthropic-version" to "2023-06-01"
            ),
            AgentHttpAuthPolicy.authHeaders(
                provider(
                    protocol = LocalAiProtocol.ANTHROPIC_MESSAGES,
                    apiKey = "",
                    anthropicVersion = "  "
                )
            )
        )
    }

    @Test
    fun `extra headers are appended after auth headers`() {
        assertEquals(
            listOf(
                "Authorization" to "Bearer k1",
                "X-Custom" to "v"
            ),
            AgentHttpAuthPolicy.authHeaders(
                provider(extraHeadersJson = """{"X-Custom": "v"}""")
            )
        )
    }
}
