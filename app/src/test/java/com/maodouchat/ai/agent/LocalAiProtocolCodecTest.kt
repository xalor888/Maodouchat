package com.maodouchat.ai.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAiProtocolCodecTest {

    private val provider = LocalAiProvider(
        id = "p",
        name = "t",
        baseUrl = "https://api.openai.com/v1",
        apiKey = "k",
        model = "m",
        protocol = LocalAiProtocol.OPENAI_CHAT_COMPLETIONS,
        temperature = 0.2,
        maxTokens = 256,
        historyMessageLimit = 12
    )

    @Test
    fun `parse protocol aliases`() {
        assertEquals(LocalAiProtocol.OPENAI_RESPONSES, LocalAiProtocolCodec.parseProtocol("responses"))
        assertEquals(LocalAiProtocol.ANTHROPIC_MESSAGES, LocalAiProtocolCodec.parseProtocol("anthropic"))
        assertEquals(LocalAiProtocol.OPENAI_CHAT_COMPLETIONS, LocalAiProtocolCodec.parseProtocol("chat"))
    }

    @Test
    fun `chat completions body includes sampling and tools`() {
        val body = LocalAiProtocolCodec.chatCompletionsBody(
            provider,
            listOf(AgentChatMessage(role = "user", content = "hi")),
            AgentToolPolicy.openaiToolsJson().take(1),
            stream = true
        )
        assertEquals("m", body.getString("model"))
        assertEquals(false, body.getBoolean("stream"))
        assertEquals(256, body.getInt("max_tokens"))
        assertEquals(0.2, body.getDouble("temperature"), 0.0)
        assertTrue(body.has("tools"))
    }

    @Test
    fun `responses body uses input array`() {
        val body = LocalAiProtocolCodec.responsesBody(
            provider.copy(protocol = LocalAiProtocol.OPENAI_RESPONSES),
            listOf(AgentChatMessage(role = "user", content = "hi")),
            null,
            stream = false
        )
        assertTrue(body.has("input"))
        assertEquals(256, body.getInt("max_output_tokens"))
    }

    @Test
    fun `anthropic body lifts system message`() {
        val body = LocalAiProtocolCodec.anthropicBody(
            provider.copy(protocol = LocalAiProtocol.ANTHROPIC_MESSAGES),
            listOf(
                AgentChatMessage(role = "system", content = "sys"),
                AgentChatMessage(role = "user", content = "hi")
            ),
            null,
            stream = false
        )
        assertEquals("sys", body.getString("system"))
        assertEquals(1, body.getJSONArray("messages").length())
    }

    @Test
    fun `parse anthropic tool use`() {
        val payload = """
            {"content":[
              {"type":"text","text":"ok"},
              {"type":"tool_use","id":"tu1","name":"list_chats","input":{"query":"a"}}
            ]}
        """.trimIndent()
        val result = LocalAiProtocolCodec.parseAnthropic(payload)
        assertTrue(result is OpenAiCompatClient.Completion.Tools)
        val tools = result as OpenAiCompatClient.Completion.Tools
        assertEquals("list_chats", tools.calls.single().name)
        assertTrue(tools.calls.single().argumentsJson.contains("query"))
    }

    @Test
    fun `parse responses function call`() {
        val payload = """
            {"output":[
              {"type":"function_call","call_id":"c1","name":"get_me","arguments":"{}"},
              {"type":"message","content":[{"type":"output_text","text":"hi"}]}
            ]}
        """.trimIndent()
        val result = LocalAiProtocolCodec.parseResponses(payload)
        assertTrue(result is OpenAiCompatClient.Completion.Tools)
        assertEquals("get_me", (result as OpenAiCompatClient.Completion.Tools).calls.single().name)
    }

    @Test
    fun `sse visible text prefers content then reasoning`() {
        val mixed = org.json.JSONObject("""{"content":"PONG","reasoning_content":"think"}""")
        assertEquals("PONG", LocalAiProtocolCodec.sseVisibleText(mixed))
        val reasoningOnly = org.json.JSONObject("""{"role":"assistant","reasoning_content":"The user"}""")
        assertEquals("The user", LocalAiProtocolCodec.sseVisibleText(reasoningOnly))
        assertEquals("", LocalAiProtocolCodec.sseVisibleText(org.json.JSONObject("""{"role":"assistant"}""")))
    }

    @Test
    fun `parse sse chat ignores reasoning when content arrives`() {
        val payload = """
            data: {"choices":[{"delta":{"role":"assistant","reasoning_content":"The user"}}]}
            data: {"choices":[{"delta":{"content":"PONG"}}]}
            data: [DONE]
        """.trimIndent()
        val deltas = mutableListOf<String>()
        val result = LocalAiProtocolCodec.parseSseChat(payload) { deltas += it }
        assertTrue(result is OpenAiCompatClient.Completion.Text)
        assertEquals("PONG", (result as OpenAiCompatClient.Completion.Text).content)
        assertEquals(listOf("PONG"), deltas)
    }

    @Test
    fun `parse sse chat falls back to reasoning when content never arrives`() {
        val payload = """
            data: {"choices":[{"delta":{"reasoning_content":"PONG"}}]}
            data: [DONE]
        """.trimIndent()
        val deltas = mutableListOf<String>()
        val result = LocalAiProtocolCodec.parseSseChat(payload) { deltas += it }
        assertTrue(result is OpenAiCompatClient.Completion.Text)
        assertEquals("PONG", (result as OpenAiCompatClient.Completion.Text).content)
        assertEquals(listOf("PONG"), deltas)
    }

    @Test
    fun `provider round trip keeps protocol and context`() {
        val original = provider.copy(
            protocol = LocalAiProtocol.ANTHROPIC_MESSAGES,
            extraHeadersJson = """{"X-Test":"1"}""",
            contextWindowTokens = 200_000,
            historyMessageLimit = 40
        )
        val decoded = LocalAiProviderStore.parseProviders(LocalAiProviderStore.encodeProviders(listOf(original))).single()
        assertEquals(LocalAiProtocol.ANTHROPIC_MESSAGES, decoded.protocol)
        assertEquals(200_000, decoded.contextWindowTokens)
        assertEquals(40, decoded.historyMessageLimit)
        assertTrue(decoded.extraHeadersJson.contains("X-Test"))
    }
}
