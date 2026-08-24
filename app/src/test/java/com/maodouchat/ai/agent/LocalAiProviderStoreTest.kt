package com.maodouchat.ai.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAiProviderStoreTest {

    @Test
    fun `provider round trip keeps key on device json`() {
        val provider = LocalAiProvider(
            id = "p1",
            name = "sf",
            baseUrl = "https://api.siliconflow.cn/v1",
            apiKey = "sk-test",
            model = "deepseek-ai/DeepSeek-V3"
        )
        val encoded = LocalAiProviderStore.encodeProviders(listOf(provider))
        val decoded = LocalAiProviderStore.parseProviders(encoded)
        assertEquals(1, decoded.size)
        assertEquals("sk-test", decoded.single().apiKey)
        assertEquals("https://api.siliconflow.cn/v1", decoded.single().baseUrl)
    }

    @Test
    fun `invalid provider rows are dropped`() {
        val raw = """[{"id":"","model":"x","baseUrl":"https://x"},{"id":"ok","model":"m","baseUrl":"https://x","apiKey":""}]"""
        val decoded = LocalAiProviderStore.parseProviders(raw)
        assertEquals(1, decoded.size)
        assertEquals("ok", decoded.single().id)
    }

    @Test
    fun `session encode skips empty tool rows`() {
        val session = AgentSession(
            id = "s1",
            title = "t",
            createdAt = 1L,
            messages = listOf(
                AgentChatMessage(role = "user", content = "hi"),
                AgentChatMessage(role = "tool", content = "", toolName = "list_chats")
            )
        )
        val encoded = LocalAiProviderStore.encodeSessions(listOf(session))
        val decoded = LocalAiProviderStore.parseSessions(encoded)
        assertEquals(1, decoded.size)
        assertTrue(decoded.single().messages.none { it.role == "tool" && it.content.isBlank() })
        assertEquals("hi", decoded.single().messages.single().content)
    }
}
