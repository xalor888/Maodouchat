package com.maodouchat.ai.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun `non-array provider json parses to empty list`() {
        assertTrue(LocalAiProviderStore.parseProviders("not json").isEmpty())
        assertTrue(LocalAiProviderStore.parseProviders("{}").isEmpty())
    }

    @Test
    fun `provider baseUrl trailing slash is trimmed on parse`() {
        val raw = """[{"id":"p1","model":"m","baseUrl":"https://x.example/"}]"""
        val decoded = LocalAiProviderStore.parseProviders(raw)
        assertEquals(1, decoded.size)
        assertEquals("https://x.example", decoded.single().baseUrl)
    }

    @Test
    fun `provider protocol aliases resolve on parse`() {
        val raw = """[
          {"id":"a","model":"m","baseUrl":"https://x","protocol":"anthropic"},
          {"id":"b","model":"m","baseUrl":"https://x","protocol":"responses"},
          {"id":"c","model":"m","baseUrl":"https://x","protocol":"weird"}
        ]"""
        val decoded = LocalAiProviderStore.parseProviders(raw)
        assertEquals(LocalAiProtocol.ANTHROPIC_MESSAGES, decoded[0].protocol)
        assertEquals(LocalAiProtocol.OPENAI_RESPONSES, decoded[1].protocol)
        assertEquals(LocalAiProtocol.OPENAI_CHAT_COMPLETIONS, decoded[2].protocol)
    }

    @Test
    fun `null temperature survives provider round trip`() {
        val provider = LocalAiProvider(
            id = "p1",
            name = "n",
            baseUrl = "https://x",
            apiKey = "sk-test",
            model = "m",
            temperature = null
        )
        val decoded = LocalAiProviderStore.parseProviders(LocalAiProviderStore.encodeProviders(listOf(provider)))
        assertEquals(1, decoded.size)
        assertNull(decoded.single().temperature)
    }

    @Test
    fun `session with blank title defaults and blank id rows are dropped`() {
        val raw = """[{"id":"","title":"x"},{"id":"s1","title":"  "}]"""
        val decoded = LocalAiProviderStore.parseSessions(raw)
        assertEquals(1, decoded.size)
        assertEquals("新对话", decoded.single().title)
    }

    @Test
    fun `provider name defaults to model when blank`() {
        val raw = """[{"id":"p1","model":"deepseek-ai/DeepSeek-V3","baseUrl":"https://x.example"}]"""
        val decoded = LocalAiProviderStore.parseProviders(raw)
        assertEquals(1, decoded.size)
        assertEquals("deepseek-ai/DeepSeek-V3", decoded.single().name)
    }

    @Test
    fun `provider numeric and boolean defaults freeze on parse`() {
        val raw = """[{"id":"p1","model":"m","baseUrl":"https://x.example"}]"""
        val decoded = LocalAiProviderStore.parseProviders(raw)
        val provider = decoded.single()
        assertEquals(4_096, provider.maxTokens)
        assertEquals(128_000, provider.contextWindowTokens)
        assertEquals(24, provider.historyMessageLimit)
        assertEquals(120, provider.timeoutSeconds)
        assertEquals(true, provider.stream)
        assertEquals(false, provider.supportsVision)
        assertEquals("2023-06-01", provider.anthropicVersion)
        assertEquals("{}", provider.extraHeadersJson)
        assertEquals("", provider.organization)
    }

    @Test
    fun `provider id and model are trimmed on parse`() {
        val raw = """[{"id":"  p1  ","model":"  m  ","baseUrl":"https://x.example"}]"""
        val decoded = LocalAiProviderStore.parseProviders(raw)
        assertEquals(1, decoded.size)
        assertEquals("p1", decoded.single().id)
        assertEquals("m", decoded.single().model)
    }

    @Test
    fun `tool message with content keeps call id and name on round trip`() {
        val session = AgentSession(
            id = "s1",
            title = "t",
            createdAt = 7L,
            messages = listOf(
                AgentChatMessage(role = "tool", content = "found 3 chats", toolCallId = "call-1", toolName = "list_chats")
            )
        )
        val decoded = LocalAiProviderStore.parseSessions(LocalAiProviderStore.encodeSessions(listOf(session)))
        assertEquals(1, decoded.size)
        val message = decoded.single().messages.single()
        assertEquals("tool", message.role)
        assertEquals("found 3 chats", message.content)
        assertEquals("call-1", message.toolCallId)
        assertEquals("list_chats", message.toolName)
    }

    @Test
    fun `non-null temperature and topP survive provider round trip`() {
        val provider = LocalAiProvider(
            id = "p1",
            name = "n",
            baseUrl = "https://x",
            apiKey = "sk-test",
            model = "m",
            temperature = 0.7,
            topP = 0.9
        )
        val decoded = LocalAiProviderStore.parseProviders(LocalAiProviderStore.encodeProviders(listOf(provider)))
        assertEquals(1, decoded.size)
        assertEquals(0.7, decoded.single().temperature)
        assertEquals(0.9, decoded.single().topP)
    }
}
