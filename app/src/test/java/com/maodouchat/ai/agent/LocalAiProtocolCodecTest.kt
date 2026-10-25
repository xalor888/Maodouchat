package com.maodouchat.ai.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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


    @Test
    fun `parse protocol covers remaining aliases and fallbacks`() {
        assertEquals(LocalAiProtocol.OPENAI_RESPONSES, LocalAiProtocolCodec.parseProtocol("RESPONSE"))
        assertEquals(LocalAiProtocol.ANTHROPIC_MESSAGES, LocalAiProtocolCodec.parseProtocol("CLAUDE"))
        assertEquals(LocalAiProtocol.OPENAI_RESPONSES, LocalAiProtocolCodec.parseProtocol("OPENAI_RESPONSES"))
        assertEquals(LocalAiProtocol.ANTHROPIC_MESSAGES, LocalAiProtocolCodec.parseProtocol("ANTHROPIC_MESSAGES"))
        assertEquals(LocalAiProtocol.OPENAI_CHAT_COMPLETIONS, LocalAiProtocolCodec.parseProtocol(null))
        assertEquals(LocalAiProtocol.OPENAI_CHAT_COMPLETIONS, LocalAiProtocolCodec.parseProtocol("   "))
        assertEquals(LocalAiProtocol.OPENAI_CHAT_COMPLETIONS, LocalAiProtocolCodec.parseProtocol("gemini"))
        assertEquals(LocalAiProtocol.OPENAI_CHAT_COMPLETIONS, LocalAiProtocolCodec.parseProtocol(" chat "))
    }

    @Test
    fun `extra headers trims drops blank and tolerates malformed`() {
        val headers = LocalAiProtocolCodec.extraHeaders(
            provider.copy(extraHeadersJson = """{"X-A":"  v  ","X-B":"   ","":"z"}""")
        )
        assertEquals(mapOf("X-A" to "v"), headers)
        assertTrue(LocalAiProtocolCodec.extraHeaders(provider.copy(extraHeadersJson = "")).isEmpty())
        assertTrue(LocalAiProtocolCodec.extraHeaders(provider.copy(extraHeadersJson = "{oops")).isEmpty())
    }

    @Test
    fun `toJson converts nested structures and passes scalars through`() {
        assertEquals(org.json.JSONObject.NULL, LocalAiProtocolCodec.toJson(null))
        val mapped = LocalAiProtocolCodec.toJson(mapOf("a" to 1, "b" to listOf("x", null))) as org.json.JSONObject
        assertEquals(1, mapped.getInt("a"))
        assertEquals("x", mapped.getJSONArray("b").getString(0))
        assertTrue(mapped.getJSONArray("b").isNull(1))
        val listed = LocalAiProtocolCodec.toJson(listOf(1, "s")) as org.json.JSONArray
        assertEquals(2, listed.length())
        assertEquals(1, listed.getInt(0))
        assertEquals("s", listed.getString(1))
        assertEquals(5, LocalAiProtocolCodec.toJson(5))
        val obj = org.json.JSONObject("""{"k":"v"}""")
        assertTrue(LocalAiProtocolCodec.toJson(obj) === obj)
    }

    @Test
    fun `parse chat completions text tools and errors`() {
        val text = LocalAiProtocolCodec.parseChatCompletions(
            """{"choices":[{"message":{"role":"assistant","content":"hello"}}]}"""
        )
        assertTrue(text is OpenAiCompatClient.Completion.Text)
        assertEquals("hello", (text as OpenAiCompatClient.Completion.Text).content)
        val tools = LocalAiProtocolCodec.parseChatCompletions(
            """{"choices":[{"message":{"role":"assistant","content":"","tool_calls":[{"id":"c1","function":{"name":"list_chats","arguments":"{\"query\":\"a\"}"}}]}}]}"""
        )
        assertTrue(tools is OpenAiCompatClient.Completion.Tools)
        val call = (tools as OpenAiCompatClient.Completion.Tools).calls.single()
        assertEquals("list_chats", call.name)
        assertEquals("c1", call.id)
        assertTrue(call.argumentsJson.contains("query"))
        val malformed = LocalAiProtocolCodec.parseChatCompletions("{oops")
        assertTrue(malformed is OpenAiCompatClient.Completion.Error)
        assertEquals("模型返回不是 JSON", (malformed as OpenAiCompatClient.Completion.Error).message)
        val noChoices = LocalAiProtocolCodec.parseChatCompletions("{}")
        assertTrue(noChoices is OpenAiCompatClient.Completion.Error)
        assertEquals("模型没有 choices", (noChoices as OpenAiCompatClient.Completion.Error).message)
        val emptyChoices = LocalAiProtocolCodec.parseChatCompletions("""{"choices":[]}""")
        assertTrue(emptyChoices is OpenAiCompatClient.Completion.Error)
        assertEquals("模型没有 choices", (emptyChoices as OpenAiCompatClient.Completion.Error).message)
    }

    @Test
    fun `sse visible text handles null and non string fields`() {
        assertEquals("", LocalAiProtocolCodec.sseVisibleText(null))
        val mixed = org.json.JSONObject("""{"content":5,"reasoning_content":"r"}""")
        assertEquals("r", LocalAiProtocolCodec.sseVisibleText(mixed))
        val blank = org.json.JSONObject("""{"content":"","reasoning_content":123}""")
        assertEquals("", LocalAiProtocolCodec.sseVisibleText(blank))
    }

    @Test
    fun `sampling and max tokens clamp at the bounds`() {
        val hot = provider.copy(temperature = 5.0, topP = 2.0, maxTokens = 5)
        val chat = LocalAiProtocolCodec.chatCompletionsBody(hot, emptyList(), null, stream = false)
        assertEquals(2.0, chat.getDouble("temperature"), 0.0)
        assertEquals(1.0, chat.getDouble("top_p"), 0.0)
        assertEquals(16, chat.getInt("max_tokens"))
        val huge = provider.copy(maxTokens = 999_999)
        assertEquals(
            128_000,
            LocalAiProtocolCodec.chatCompletionsBody(huge, emptyList(), null, stream = false).getInt("max_tokens")
        )
        val anthropic = LocalAiProtocolCodec.anthropicBody(
            provider.copy(protocol = LocalAiProtocol.ANTHROPIC_MESSAGES, temperature = 5.0),
            emptyList(), null, stream = false
        )
        assertEquals(1.0, anthropic.getDouble("temperature"), 0.0)
        val cold = provider.copy(temperature = -1.0)
        assertEquals(
            0.0,
            LocalAiProtocolCodec.chatCompletionsBody(cold, emptyList(), null, stream = false).getDouble("temperature"),
            0.0
        )
    }

    @Test
    fun `chat completions body encodes tool calls and results`() {
        val messages = listOf(
            AgentChatMessage(role = "user", content = "hi"),
            AgentChatMessage(
                role = "assistant",
                content = "",
                toolCalls = listOf(AgentToolCall("c1", "list_chats", "{}"))
            ),
            AgentChatMessage(role = "tool", content = "res", toolCallId = "c1", toolName = "list_chats")
        )
        val body = LocalAiProtocolCodec.chatCompletionsBody(provider, messages, null, stream = false)
        val encoded = body.getJSONArray("messages")
        assertEquals(3, encoded.length())
        assertEquals("hi", encoded.getJSONObject(0).getString("content"))
        val calls = encoded.getJSONObject(1).getJSONArray("tool_calls")
        assertEquals("c1", calls.getJSONObject(0).getString("id"))
        assertEquals("function", calls.getJSONObject(0).getString("type"))
        assertEquals("list_chats", calls.getJSONObject(0).getJSONObject("function").getString("name"))
        assertEquals("{}", calls.getJSONObject(0).getJSONObject("function").getString("arguments"))
        assertEquals("res", encoded.getJSONObject(2).getString("content"))
        assertEquals("c1", encoded.getJSONObject(2).getString("tool_call_id"))
        assertEquals("list_chats", encoded.getJSONObject(2).getString("name"))
        val unnamed = LocalAiProtocolCodec.chatCompletionsBody(
            provider,
            listOf(AgentChatMessage(role = "tool", content = "res", toolCallId = "c9")),
            null, stream = false
        )
        assertFalse(unnamed.getJSONArray("messages").getJSONObject(0).has("name"))
    }

    @Test
    fun `responses body flattens tools and encodes tool traffic`() {
        val tools = AgentToolPolicy.openaiToolsJson().take(1)
        val expectedName = (tools.single()["function"] as Map<*, *>)["name"] as String
        val body = LocalAiProtocolCodec.responsesBody(provider, emptyList(), tools, stream = true)
        assertEquals(false, body.getBoolean("stream"))
        assertEquals("auto", body.getString("tool_choice"))
        val encodedTools = body.getJSONArray("tools")
        assertEquals(1, encodedTools.length())
        assertEquals("function", encodedTools.getJSONObject(0).getString("type"))
        assertEquals(expectedName, encodedTools.getJSONObject(0).getString("name"))
        val traffic = LocalAiProtocolCodec.responsesBody(
            provider, listOf(
                AgentChatMessage(
                    role = "assistant",
                    content = "",
                    toolCalls = listOf(AgentToolCall("c1", "list_chats", "{}"))
                ),
                AgentChatMessage(role = "tool", content = "res", toolCallId = "c1")
            ), null, stream = false
        )
        val input = traffic.getJSONArray("input")
        assertEquals(2, input.length())
        assertEquals("function_call", input.getJSONObject(0).getString("type"))
        assertEquals("c1", input.getJSONObject(0).getString("call_id"))
        assertEquals("function_call_output", input.getJSONObject(1).getString("type"))
        assertEquals("c1", input.getJSONObject(1).getString("call_id"))
        assertEquals("res", input.getJSONObject(1).getString("output"))
    }

    @Test
    fun `anthropic body joins system prompts and flushes tool results`() {
        val joined = LocalAiProtocolCodec.anthropicBody(
            provider.copy(protocol = LocalAiProtocol.ANTHROPIC_MESSAGES),
            listOf(
                AgentChatMessage(role = "system", content = "a"),
                AgentChatMessage(role = "system", content = "b"),
                AgentChatMessage(role = "user", content = "hi")
            ),
            null, stream = false
        )
        assertEquals("a\nb", joined.getString("system"))
        assertEquals(1, joined.getJSONArray("messages").length())
        assertEquals("hi", joined.getJSONArray("messages").getJSONObject(0).getString("content"))
        val blankSystem = LocalAiProtocolCodec.anthropicBody(
            provider.copy(protocol = LocalAiProtocol.ANTHROPIC_MESSAGES),
            listOf(
                AgentChatMessage(role = "system", content = "  "),
                AgentChatMessage(role = "user", content = "hi")
            ),
            null, stream = false
        )
        assertFalse(blankSystem.has("system"))
        val flushed = LocalAiProtocolCodec.anthropicBody(
            provider.copy(protocol = LocalAiProtocol.ANTHROPIC_MESSAGES),
            listOf(
                AgentChatMessage(
                    role = "assistant",
                    content = "",
                    toolCalls = listOf(AgentToolCall("c1", "list_chats", "{}"))
                ),
                AgentChatMessage(role = "tool", content = "res", toolCallId = "c1"),
                AgentChatMessage(role = "user", content = "hi")
            ),
            null, stream = false
        )
        val messages = flushed.getJSONArray("messages")
        assertEquals(3, messages.length())
        assertEquals("assistant", messages.getJSONObject(0).getString("role"))
        val toolUse = messages.getJSONObject(0).getJSONArray("content").getJSONObject(0)
        assertEquals("tool_use", toolUse.getString("type"))
        assertEquals("c1", toolUse.getString("id"))
        assertEquals("user", messages.getJSONObject(1).getString("role"))
        val toolResult = messages.getJSONObject(1).getJSONArray("content").getJSONObject(0)
        assertEquals("tool_result", toolResult.getString("type"))
        assertEquals("c1", toolResult.getString("tool_use_id"))
        assertEquals("res", toolResult.getString("content"))
        assertEquals("hi", messages.getJSONObject(2).getString("content"))
    }

    @Test
    fun `response parsers and sse handle error and fallback edges`() {
        val malformed = LocalAiProtocolCodec.parseResponses("{oops")
        assertTrue(malformed is OpenAiCompatClient.Completion.Error)
        assertEquals("模型返回不是 JSON", (malformed as OpenAiCompatClient.Completion.Error).message)
        val textOnly = LocalAiProtocolCodec.parseResponses(
            """{"output":[{"type":"message","content":[{"type":"output_text","text":"he"},{"type":"text","text":"llo"}]}]}"""
        )
        assertTrue(textOnly is OpenAiCompatClient.Completion.Text)
        assertEquals("hello", (textOnly as OpenAiCompatClient.Completion.Text).content)
        val noInput = LocalAiProtocolCodec.parseAnthropic(
            """{"content":[{"type":"tool_use","id":"tu1","name":"list_chats"}]}"""
        )
        assertTrue(noInput is OpenAiCompatClient.Completion.Tools)
        val call = (noInput as OpenAiCompatClient.Completion.Tools).calls.single()
        assertEquals("tu1", call.id)
        assertEquals("{}", call.argumentsJson)
        val emptyStream = LocalAiProtocolCodec.parseSseChat("data: [DONE]\n") { error("no delta expected") }
        assertTrue(emptyStream is OpenAiCompatClient.Completion.Error)
        assertEquals("empty stream", (emptyStream as OpenAiCompatClient.Completion.Error).message)
    }


    @Test
    fun `parse sse chat ignores non-data lines`() {
        val payload = """
            : ping
            event: message
            data: {"choices":[{"delta":{"content":"hi"}}]}
            data: [DONE]
        """.trimIndent()
        val deltas = mutableListOf<String>()
        val result = LocalAiProtocolCodec.parseSseChat(payload) { deltas += it }
        assertTrue(result is OpenAiCompatClient.Completion.Text)
        assertEquals("hi", (result as OpenAiCompatClient.Completion.Text).content)
        assertEquals(listOf("hi"), deltas)
    }

    @Test
    fun `parse sse chat skips malformed data lines`() {
        val payload = """
            data: {oops
            data: {"choices":[{"delta":{"content":"ok"}}]}
            data: [DONE]
        """.trimIndent()
        val deltas = mutableListOf<String>()
        val result = LocalAiProtocolCodec.parseSseChat(payload) { deltas += it }
        assertTrue(result is OpenAiCompatClient.Completion.Text)
        assertEquals("ok", (result as OpenAiCompatClient.Completion.Text).content)
        assertEquals(listOf("ok"), deltas)
    }

    @Test
    fun `parse sse chat drops reasoning once content has started`() {
        val payload = """
            data: {"choices":[{"delta":{"content":"A"}}]}
            data: {"choices":[{"delta":{"reasoning_content":"R"}}]}
            data: [DONE]
        """.trimIndent()
        val deltas = mutableListOf<String>()
        val result = LocalAiProtocolCodec.parseSseChat(payload) { deltas += it }
        assertTrue(result is OpenAiCompatClient.Completion.Text)
        assertEquals("A", (result as OpenAiCompatClient.Completion.Text).content)
        assertEquals(listOf("A"), deltas)
    }

    @Test
    fun `parse sse chat concatenates split content chunks`() {
        val payload = """
            data: {"choices":[{"delta":{"content":"Hel"}}]}
            data: {"choices":[{"delta":{"content":"lo"}}]}
            data: [DONE]
        """.trimIndent()
        val deltas = mutableListOf<String>()
        val result = LocalAiProtocolCodec.parseSseChat(payload) { deltas += it }
        assertTrue(result is OpenAiCompatClient.Completion.Text)
        assertEquals("Hello", (result as OpenAiCompatClient.Completion.Text).content)
        assertEquals(listOf("Hel", "lo"), deltas)
    }

    @Test
    fun `parse sse chat reads delta object fallback shape`() {
        val payload = """
            data: {"delta":{"text":[{"text":"hi"}]}}
            data: [DONE]
        """.trimIndent()
        val deltas = mutableListOf<String>()
        val result = LocalAiProtocolCodec.parseSseChat(payload) { deltas += it }
        assertTrue(result is OpenAiCompatClient.Completion.Text)
        assertEquals("hi", (result as OpenAiCompatClient.Completion.Text).content)
        assertEquals(listOf("hi"), deltas)
    }

    @Test
    fun `parse protocol accepts mixed case and padded aliases`() {
        assertEquals(LocalAiProtocol.OPENAI_RESPONSES, LocalAiProtocolCodec.parseProtocol("Responses"))
        assertEquals(LocalAiProtocol.ANTHROPIC_MESSAGES, LocalAiProtocolCodec.parseProtocol("AnThRoPiC"))
        assertEquals(LocalAiProtocol.OPENAI_RESPONSES, LocalAiProtocolCodec.parseProtocol("  responses  "))
        assertEquals(LocalAiProtocol.ANTHROPIC_MESSAGES, LocalAiProtocolCodec.parseProtocol("  CLAUDE "))
    }

    @Test
    fun `chat completions body keeps requested stream only without tools`() {
        val streamed = LocalAiProtocolCodec.chatCompletionsBody(provider, emptyList(), null, stream = true)
        assertTrue(streamed.getBoolean("stream"))
        val unstreamed = LocalAiProtocolCodec.chatCompletionsBody(provider, emptyList(), null, stream = false)
        assertFalse(unstreamed.getBoolean("stream"))
    }

    @Test
    fun `chat completions body omits null sampling keys but keeps max tokens`() {
        val body = LocalAiProtocolCodec.chatCompletionsBody(
            provider.copy(temperature = null, topP = null), emptyList(), null, stream = false
        )
        assertFalse(body.has("temperature"))
        assertFalse(body.has("top_p"))
        assertEquals(256, body.getInt("max_tokens"))
    }

    @Test
    fun `parse responses reads tool call type and root output text fallback`() {
        val tools = LocalAiProtocolCodec.parseResponses(
            """{"output":[{"type":"tool_call","id":"x1","function":{"name":"list_chats","arguments":"{}"}}]}"""
        )
        assertTrue(tools is OpenAiCompatClient.Completion.Tools)
        val call = (tools as OpenAiCompatClient.Completion.Tools).calls.single()
        assertEquals("x1", call.id)
        assertEquals("list_chats", call.name)
        assertEquals("{}", call.argumentsJson)
        val fallback = LocalAiProtocolCodec.parseResponses("""{"output":[],"output_text":"hi"}""")
        assertTrue(fallback is OpenAiCompatClient.Completion.Text)
        assertEquals("hi", (fallback as OpenAiCompatClient.Completion.Text).content)
    }

    @Test
    fun `parse anthropic concatenates text blocks and keeps scalar tool input`() {
        val text = LocalAiProtocolCodec.parseAnthropic(
            """{"content":[{"type":"text","text":"Hel"},{"type":"text","text":"lo"}]}"""
        )
        assertTrue(text is OpenAiCompatClient.Completion.Text)
        assertEquals("Hello", (text as OpenAiCompatClient.Completion.Text).content)
        val tools = LocalAiProtocolCodec.parseAnthropic(
            """{"content":[{"type":"tool_use","id":"t1","name":"list_chats","input":"raw"}]}"""
        )
        assertTrue(tools is OpenAiCompatClient.Completion.Tools)
        val call = (tools as OpenAiCompatClient.Completion.Tools).calls.single()
        assertEquals("t1", call.id)
        assertEquals("raw", call.argumentsJson)
    }

}
