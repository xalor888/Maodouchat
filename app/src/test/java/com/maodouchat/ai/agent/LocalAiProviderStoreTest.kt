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

    @Test
    fun `new provider draft matches protocol defaults`() {
        val chat = LocalAiProviderStore.newProviderDraft(LocalAiProtocol.OPENAI_CHAT_COMPLETIONS)
        assertEquals("OpenAI Chat Completions", chat.name)
        assertEquals("https://api.openai.com/v1", chat.baseUrl)
        assertEquals("gpt-4o-mini", chat.model)
        assertEquals("", chat.apiKey)
        assertTrue(chat.id.startsWith("p_"))
        val responses = LocalAiProviderStore.newProviderDraft(LocalAiProtocol.OPENAI_RESPONSES)
        assertEquals("OpenAI Responses", responses.name)
        assertEquals("gpt-4.1-mini", responses.model)
        assertEquals(LocalAiProtocol.OPENAI_RESPONSES, responses.protocol)
        val anthropic = LocalAiProviderStore.newProviderDraft(LocalAiProtocol.ANTHROPIC_MESSAGES)
        assertEquals("Anthropic", anthropic.name)
        assertEquals("https://api.anthropic.com", anthropic.baseUrl)
        assertEquals("claude-sonnet-4-5", anthropic.model)
    }

    @Test
    fun `provider protocol enum names survive round trip`() {
        val providers = listOf(
            LocalAiProvider(id = "a", name = "n", baseUrl = "https://x", apiKey = "", model = "m", protocol = LocalAiProtocol.ANTHROPIC_MESSAGES),
            LocalAiProvider(id = "b", name = "n", baseUrl = "https://x", apiKey = "", model = "m", protocol = LocalAiProtocol.OPENAI_RESPONSES)
        )
        val decoded = LocalAiProviderStore.parseProviders(LocalAiProviderStore.encodeProviders(providers))
        assertEquals(2, decoded.size)
        assertEquals(LocalAiProtocol.ANTHROPIC_MESSAGES, decoded[0].protocol)
        assertEquals(LocalAiProtocol.OPENAI_RESPONSES, decoded[1].protocol)
    }

    @Test
    fun `session createdAt and empty messages survive parse`() {
        val raw = """[{"id":"s9","title":"t","createdAt":12345}]"""
        val decoded = LocalAiProviderStore.parseSessions(raw)
        assertEquals(1, decoded.size)
        assertEquals(12345L, decoded.single().createdAt)
        assertTrue(decoded.single().messages.isEmpty())
    }

    @Test
    fun `blank toolCallId and toolName parse to null`() {
        val raw = """[{"id":"s1","title":"t","createdAt":1,"messages":[{"role":"tool","content":"out","toolCallId":"","toolName":""}]}]"""
        val decoded = LocalAiProviderStore.parseSessions(raw)
        assertEquals(1, decoded.size)
        val message = decoded.single().messages.single()
        assertEquals("out", message.content)
        assertNull(message.toolCallId)
        assertNull(message.toolName)
    }

    @Test
    fun `blank base rows and non-object rows are dropped`() {
        val raw = """[{"id":"a","model":"m","baseUrl":""}, "junk", 42, {"id":"ok","model":"m","baseUrl":"https://x"}]"""
        val decoded = LocalAiProviderStore.parseProviders(raw)
        assertEquals(1, decoded.size)
        assertEquals("ok", decoded.single().id)
    }

    @Test
    fun `provider baseUrl with multiple trailing slashes is fully trimmed on parse`() {
        val raw = """[{"id":"p1","model":"m","baseUrl":"https://x.example///"}]"""
        val decoded = LocalAiProviderStore.parseProviders(raw)
        assertEquals(1, decoded.size)
        assertEquals("https://x.example", decoded.single().baseUrl)
    }

    @Test
    fun `provider rows with whitespace-only id model or base are dropped`() {
        val raw = """[
          {"id":"   ","model":"m","baseUrl":"https://x"},
          {"id":"ok","model":"  ","baseUrl":"https://x"},
          {"id":"ok2","model":"m","baseUrl":"   "}
        ]"""
        assertTrue(LocalAiProviderStore.parseProviders(raw).isEmpty())
    }

    @Test
    fun `provider apiKey whitespace is preserved on parse`() {
        val raw = """[{"id":"p1","model":"m","baseUrl":"https://x","apiKey":"  sk-test  "}]"""
        val decoded = LocalAiProviderStore.parseProviders(raw)
        assertEquals(1, decoded.size)
        assertEquals("  sk-test  ", decoded.single().apiKey)
    }

    @Test
    fun `provider explicit boolean flags survive parse`() {
        val raw = """[{"id":"p1","model":"m","baseUrl":"https://x","stream":false,"supportsVision":true}]"""
        val decoded = LocalAiProviderStore.parseProviders(raw)
        assertEquals(1, decoded.size)
        assertEquals(false, decoded.single().stream)
        assertEquals(true, decoded.single().supportsVision)
    }

    @Test
    fun `provider explicit blank optionals fall back to defaults`() {
        val raw = """[{"id":"p1","model":"m","baseUrl":"https://x","anthropicVersion":"","extraHeadersJson":""}]"""
        val decoded = LocalAiProviderStore.parseProviders(raw)
        assertEquals(1, decoded.size)
        assertEquals("2023-06-01", decoded.single().anthropicVersion)
        assertEquals("{}", decoded.single().extraHeadersJson)
    }

    @Test
    fun `provider explicit numeric fields survive round trip`() {
        val provider = LocalAiProvider(
            id = "p1",
            name = "n",
            baseUrl = "https://x",
            apiKey = "",
            model = "m",
            maxTokens = 8000,
            contextWindowTokens = 50000,
            historyMessageLimit = 10,
            timeoutSeconds = 60
        )
        val decoded = LocalAiProviderStore.parseProviders(LocalAiProviderStore.encodeProviders(listOf(provider)))
        assertEquals(1, decoded.size)
        val roundTripped = decoded.single()
        assertEquals(8000, roundTripped.maxTokens)
        assertEquals(50000, roundTripped.contextWindowTokens)
        assertEquals(10, roundTripped.historyMessageLimit)
        assertEquals(60, roundTripped.timeoutSeconds)
    }

    @Test
    fun `provider organization and anthropic version survive round trip`() {
        val provider = LocalAiProvider(
            id = "p1",
            name = "n",
            baseUrl = "https://x",
            apiKey = "",
            model = "m",
            organization = "org-x",
            anthropicVersion = "2024-01-01"
        )
        val decoded = LocalAiProviderStore.parseProviders(LocalAiProviderStore.encodeProviders(listOf(provider)))
        assertEquals(1, decoded.size)
        assertEquals("org-x", decoded.single().organization)
        assertEquals("2024-01-01", decoded.single().anthropicVersion)
    }

    @Test
    fun `session encode keeps non-blank tool rows in order`() {
        val session = AgentSession(
            id = "s1",
            title = "t",
            createdAt = 3L,
            messages = listOf(
                AgentChatMessage(role = "user", content = "hi"),
                AgentChatMessage(role = "tool", content = "found", toolCallId = "c1", toolName = "list_chats"),
                AgentChatMessage(role = "assistant", content = "done")
            )
        )
        val decoded = LocalAiProviderStore.parseSessions(LocalAiProviderStore.encodeSessions(listOf(session)))
        assertEquals(1, decoded.size)
        assertEquals(listOf("user", "tool", "assistant"), decoded.single().messages.map { it.role })
        assertEquals(listOf("hi", "found", "done"), decoded.single().messages.map { it.content })
    }

    @Test
    fun `parse sessions skips non-object message rows`() {
        val raw = """[{"id":"s1","title":"t","createdAt":1,"messages":["junk",42,{"role":"user","content":"hi"}]}]"""
        val decoded = LocalAiProviderStore.parseSessions(raw)
        assertEquals(1, decoded.size)
        assertEquals("hi", decoded.single().messages.single().content)
    }

    @Test
    fun `parse sessions trims id and defaults missing createdAt to zero`() {
        val raw = """[{"id":"  s1  ","title":"t"}]"""
        val decoded = LocalAiProviderStore.parseSessions(raw)
        assertEquals(1, decoded.size)
        assertEquals("s1", decoded.single().id)
        assertEquals(0L, decoded.single().createdAt)
    }

    @Test
    fun `provider protocol aliases are case-insensitive and trimmed on parse`() {
        val raw = """[
          {"id":"a","model":"m","baseUrl":"https://x","protocol":"  Responses "},
          {"id":"b","model":"m","baseUrl":"https://x","protocol":"CLAUDE"},
          {"id":"c","model":"m","baseUrl":"https://x","protocol":"response"},
          {"id":"d","model":"m","baseUrl":"https://x","protocol":"openai_chat_completions"},
          {"id":"e","model":"m","baseUrl":"https://x","protocol":"OPENAI_RESPONSES"}
        ]"""
        val decoded = LocalAiProviderStore.parseProviders(raw)
        assertEquals(5, decoded.size)
        assertEquals(LocalAiProtocol.OPENAI_RESPONSES, decoded[0].protocol)
        assertEquals(LocalAiProtocol.ANTHROPIC_MESSAGES, decoded[1].protocol)
        assertEquals(LocalAiProtocol.OPENAI_RESPONSES, decoded[2].protocol)
        assertEquals(LocalAiProtocol.OPENAI_CHAT_COMPLETIONS, decoded[3].protocol)
        assertEquals(LocalAiProtocol.OPENAI_RESPONSES, decoded[4].protocol)
    }

    @Test
    fun `provider name with surrounding spaces survives parse verbatim`() {
        val raw = """[
          {"id":"a","model":"m","baseUrl":"https://x","name":"  n  "},
          {"id":"b","model":"m","baseUrl":"https://x","name":"   "}
        ]"""
        val decoded = LocalAiProviderStore.parseProviders(raw)
        assertEquals(2, decoded.size)
        assertEquals("  n  ", decoded[0].name)
        assertEquals("m", decoded[1].name)
    }

    @Test
    fun `provider slash-only baseUrl row is dropped`() {
        val raw = """[{"id":"a","model":"m","baseUrl":"/"},{"id":"ok","model":"m","baseUrl":"https://x"}]"""
        val decoded = LocalAiProviderStore.parseProviders(raw)
        assertEquals(1, decoded.size)
        assertEquals("ok", decoded.single().id)
    }

    @Test
    fun `provider duplicate ids are both kept on parse`() {
        val raw = """[
          {"id":"dup","model":"m","baseUrl":"https://x"},
          {"id":"dup","model":"n","baseUrl":"https://y"}
        ]"""
        val decoded = LocalAiProviderStore.parseProviders(raw)
        assertEquals(2, decoded.size)
        assertEquals(listOf("dup", "dup"), decoded.map { it.id })
    }

    @Test
    fun `session blank non-tool rows survive encode round trip`() {
        val session = AgentSession(
            id = "s1",
            title = "t",
            createdAt = 1L,
            messages = listOf(
                AgentChatMessage(role = "user", content = ""),
                AgentChatMessage(role = "assistant", content = ""),
                AgentChatMessage(role = "tool", content = "", toolName = "list_chats")
            )
        )
        val decoded = LocalAiProviderStore.parseSessions(LocalAiProviderStore.encodeSessions(listOf(session)))
        assertEquals(1, decoded.size)
        assertEquals(listOf("user", "assistant"), decoded.single().messages.map { it.role })
        assertEquals(listOf("", ""), decoded.single().messages.map { it.content })
    }

    @Test
    fun `chat completions url trims slash and keeps existing suffix`() {
        val plain = LocalAiProvider(id = "p1", name = "n", baseUrl = "https://x.example/v1/", apiKey = "", model = "m")
        assertEquals("https://x.example/v1/chat/completions", plain.resolvedChatCompletionsUrl())
        val suffixed = LocalAiProvider(id = "p1", name = "n", baseUrl = "https://x.example/v1/chat/completions/", apiKey = "", model = "m")
        assertEquals("https://x.example/v1/chat/completions", suffixed.resolvedChatCompletionsUrl())
    }

    @Test
    fun `responses and anthropic urls append their suffixes`() {
        val provider = LocalAiProvider(id = "p1", name = "n", baseUrl = "https://x.example/v1", apiKey = "", model = "m")
        assertEquals("https://x.example/v1/responses", provider.resolvedResponsesUrl())
        assertEquals("https://x.example/v1/v1/messages", provider.resolvedAnthropicUrl())
        val anthropic = LocalAiProvider(id = "p1", name = "n", baseUrl = "https://api.anthropic.com", apiKey = "", model = "m")
        assertEquals("https://api.anthropic.com/v1/messages", anthropic.resolvedAnthropicUrl())
    }

    @Test
    fun `history limit clamps to four and two hundred`() {
        val low = LocalAiProvider(id = "p1", name = "n", baseUrl = "https://x", apiKey = "", model = "m", historyMessageLimit = 1)
        assertEquals(4, low.clampedHistoryLimit())
        val high = LocalAiProvider(id = "p1", name = "n", baseUrl = "https://x", apiKey = "", model = "m", historyMessageLimit = 500)
        assertEquals(200, high.clampedHistoryLimit())
    }

    @Test
    fun `max tokens clamps to sixteen and one hundred twenty eight thousand`() {
        val low = LocalAiProvider(id = "p1", name = "n", baseUrl = "https://x", apiKey = "", model = "m", maxTokens = 0)
        assertEquals(16, low.clampedMaxTokens())
        val high = LocalAiProvider(id = "p1", name = "n", baseUrl = "https://x", apiKey = "", model = "m", maxTokens = 200_000)
        assertEquals(128_000, high.clampedMaxTokens())
    }

    @Test
    fun `timeout clamps to fifteen and three hundred`() {
        val low = LocalAiProvider(id = "p1", name = "n", baseUrl = "https://x", apiKey = "", model = "m", timeoutSeconds = 5)
        assertEquals(15, low.clampedTimeoutSeconds())
        val high = LocalAiProvider(id = "p1", name = "n", baseUrl = "https://x", apiKey = "", model = "m", timeoutSeconds = 1000)
        assertEquals(300, high.clampedTimeoutSeconds())
    }

    @Test
    fun `session title with surrounding spaces survives parse verbatim`() {
        val raw = """[{"id":"s1","title":"  t  ","createdAt":1}]"""
        val decoded = LocalAiProviderStore.parseSessions(raw)
        assertEquals(1, decoded.size)
        assertEquals("  t  ", decoded.single().title)
    }

    @Test
    fun `session message role and content spaces survive parse verbatim`() {
        val raw = """[{"id":"s1","title":"t","createdAt":1,"messages":[{"role":" user ","content":" hi "}]}]"""
        val decoded = LocalAiProviderStore.parseSessions(raw)
        assertEquals(1, decoded.size)
        val message = decoded.single().messages.single()
        assertEquals(" user ", message.role)
        assertEquals(" hi ", message.content)
    }

    @Test
    fun `provider out of range temperature parses verbatim and non-numeric topP parses to null`() {
        val raw = """[{"id":"p1","model":"m","baseUrl":"https://x","temperature":5.0,"topP":"hot"}]"""
        val decoded = LocalAiProviderStore.parseProviders(raw)
        assertEquals(1, decoded.size)
        assertEquals(5.0, decoded.single().temperature)
        assertNull(decoded.single().topP)
    }

    @Test
    fun `provider extra headers json survives round trip verbatim`() {
        val provider = LocalAiProvider(
            id = "p1",
            name = "n",
            baseUrl = "https://x",
            apiKey = "",
            model = "m",
            extraHeadersJson = """{"X-A":"1","X-B":"2"}"""
        )
        val decoded = LocalAiProviderStore.parseProviders(LocalAiProviderStore.encodeProviders(listOf(provider)))
        assertEquals(1, decoded.size)
        assertEquals("""{"X-A":"1","X-B":"2"}""", decoded.single().extraHeadersJson)
    }

    @Test
    fun `empty lists encode to empty array and parse back empty`() {
        assertEquals("[]", LocalAiProviderStore.encodeProviders(emptyList()))
        assertTrue(LocalAiProviderStore.parseProviders(LocalAiProviderStore.encodeProviders(emptyList())).isEmpty())
        assertEquals("[]", LocalAiProviderStore.encodeSessions(emptyList()))
        assertTrue(LocalAiProviderStore.parseSessions(LocalAiProviderStore.encodeSessions(emptyList())).isEmpty())
    }

}
