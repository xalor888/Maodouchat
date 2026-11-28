// LocalAiAnthropicEncode fuzz (150th block). Oracle reimplements
// anthropicBody from production text. Shared oracle helpers
// (canon150, toJsonOracle150, pools, generators) live in
// LocalAiChatCompletionsCodecFuzzTest.kt. The parse side
// (parseAnthropic) is already fuzzed by block 6 via
// LocalAiProtocolCodec -- this file covers the encoding side only.
package com.maodouchat.domain.messaging

import org.json.JSONObject
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

internal fun encodeAnthropicMessagesOracle150(messages: List<AgentChatMessage>): List<Any?> {
    val array = ArrayList<Any?>()
    var pendingToolResults = ArrayList<Any?>()
    fun flushTools() {
        if (pendingToolResults.isEmpty()) return
        val o = LinkedHashMap<String, Any?>()
        o["role"] = "user"
        o["content"] = pendingToolResults
        array.add(o)
        pendingToolResults = ArrayList()
    }
    messages.filter { it.role != "system" }.forEach { m ->
        when (m.role) {
            "tool" -> {
                val o = LinkedHashMap<String, Any?>()
                o["type"] = "tool_result"
                o["tool_use_id"] = m.toolCallId.orEmpty()
                o["content"] = m.content
                pendingToolResults.add(o)
            }
            "assistant" -> {
                flushTools()
                val content = ArrayList<Any?>()
                if (m.content.isNotBlank()) {
                    val t = LinkedHashMap<String, Any?>()
                    t["type"] = "text"
                    t["text"] = m.content
                    content.add(t)
                }
                m.toolCalls.forEach { call ->
                    val input = runCatching { JSONObject(call.argumentsJson.ifBlank { "{}" }) }
                        .getOrElse { JSONObject() }
                    val tu = LinkedHashMap<String, Any?>()
                    tu["type"] = "tool_use"
                    tu["id"] = call.id
                    tu["name"] = call.name
                    tu["input"] = input
                    content.add(tu)
                }
                if (content.isEmpty()) {
                    val t = LinkedHashMap<String, Any?>()
                    t["type"] = "text"
                    t["text"] = ""
                    content.add(t)
                }
                val o = LinkedHashMap<String, Any?>()
                o["role"] = "assistant"
                o["content"] = content
                array.add(o)
            }
            else -> {
                flushTools()
                val o = LinkedHashMap<String, Any?>()
                o["role"] = "user"
                o["content"] = m.content
                array.add(o)
            }
        }
    }
    flushTools()
    return array
}

internal fun encodeAnthropicToolsOracle150(tools: List<Map<String, Any?>>): List<Any?> {
    val array = ArrayList<Any?>()
    tools.forEach { spec ->
        val fn = spec["function"] as? Map<*, *> ?: return@forEach
        val o = LinkedHashMap<String, Any?>()
        val name = fn["name"]
        if (name != null) o["name"] = name
        val description = fn["description"]
        if (description != null) o["description"] = description
        o["input_schema"] = toJsonOracle150(fn["parameters"])
        array.add(o)
    }
    return array
}

internal fun anthropicBodyOracle150(
    provider: LocalAiProvider,
    messages: List<AgentChatMessage>,
    tools: List<Map<String, Any?>>?,
    stream: Boolean,
): String {
    val system = messages.filter { it.role == "system" }.joinToString("\n") { it.content }.trim()
    val body = LinkedHashMap<String, Any?>()
    body["model"] = provider.model
    body["max_tokens"] = clampedMaxTokensOracle150(provider.maxTokens)
    body["stream"] = stream && tools.isNullOrEmpty()
    body["messages"] = encodeAnthropicMessagesOracle150(messages)
    if (system.isNotBlank()) body["system"] = system
    provider.temperature?.let { body["temperature"] = it.coerceIn(0.0, 1.0) }
    provider.topP?.let { body["top_p"] = it.coerceIn(0.0, 1.0) }
    if (!tools.isNullOrEmpty()) {
        body["tools"] = encodeAnthropicToolsOracle150(tools)
        body["stream"] = false
    }
    return canon150(body)
}

// ---------- generators ----------
// (providers, messages, tools and pools are shared from
// LocalAiChatCompletionsCodecFuzzTest.kt.)

class LocalAiAnthropicEncodeFuzzTest {

    private fun provider(
        model: String = "m",
        temperature: Double? = null,
        topP: Double? = null,
        maxTokens: Int = 4096,
    ) = LocalAiProvider(
        id = "p1", name = "Test", baseUrl = "https://x.test", apiKey = "k",
        model = model, temperature = temperature, topP = topP, maxTokens = maxTokens,
    )

    private fun prod(
        provider: LocalAiProvider,
        messages: List<AgentChatMessage>,
        tools: List<Map<String, Any?>>?,
        stream: Boolean,
    ): String = canon150(LocalAiAnthropicEncode.anthropicBody(provider, messages, tools, stream))

    @Test
    fun pinnedBoundaries() {
        val empty = provider()
        assertEquals(
            "{\"max_tokens\":4096,\"messages\":[],\"model\":\"m\",\"stream\":true}",
            anthropicBodyOracle150(empty, emptyList(), null, true),
        )
        assertEquals(
            anthropicBodyOracle150(empty, emptyList(), null, true),
            prod(empty, emptyList(), null, true),
        )
        assertEquals(
            "{\"max_tokens\":4096,\"messages\":[],\"model\":\"m\",\"stream\":false}",
            prod(empty, emptyList(), null, false),
        )
        // Anthropic clamps temperature to [0,1] (not [0,2] like the
        // OpenAI codecs), topP to [0,1], maxTokens to [16,128000].
        val clamped = provider(temperature = 3.0, topP = 2.0, maxTokens = 1)
        assertEquals(
            "{\"max_tokens\":16,\"messages\":[],\"model\":\"m\",\"stream\":true,\"temperature\":1,\"top_p\":1}",
            prod(clamped, emptyList(), null, true),
        )
        val floored = provider(temperature = -1.0, topP = -0.5, maxTokens = 200000)
        assertEquals(
            "{\"max_tokens\":128000,\"messages\":[],\"model\":\"m\",\"stream\":true,\"temperature\":0,\"top_p\":0}",
            prod(floored, emptyList(), null, true),
        )
        // System messages join with newline, trim, and lift to "system".
        val sys = listOf(
            AgentChatMessage(role = "system", content = "  Be helpful.  "),
            AgentChatMessage(role = "user", content = "hi"),
            AgentChatMessage(role = "system", content = "Second line."),
        )
        assertEquals(
            "{\"max_tokens\":4096,\"messages\":[{\"content\":\"hi\",\"role\":\"user\"}],\"model\":\"m\",\"stream\":true,\"system\":\"Be helpful.  \\nSecond line.\"}",
            prod(empty, sys, null, true),
        )
        // Blank-only system text leaves no "system" key.
        assertEquals(
            "{\"max_tokens\":4096,\"messages\":[],\"model\":\"m\",\"stream\":true}",
            prod(empty, listOf(AgentChatMessage(role = "system", content = "   ")), null, true),
        )
        // Tool results batch into one user message, flushed before the
        // next non-tool message and at the end.
        val batch = listOf(
            AgentChatMessage(role = "tool", content = "r1", toolCallId = "c1"),
            AgentChatMessage(role = "tool", content = "r2", toolCallId = "c2"),
            AgentChatMessage(role = "user", content = "next"),
            AgentChatMessage(role = "tool", content = "r3", toolCallId = "c3"),
        )
        assertEquals(
            "{\"max_tokens\":4096,\"messages\":[{\"content\":[{\"content\":\"r1\",\"tool_use_id\":\"c1\",\"type\":\"tool_result\"},{\"content\":\"r2\",\"tool_use_id\":\"c2\",\"type\":\"tool_result\"}],\"role\":\"user\"},{\"content\":\"next\",\"role\":\"user\"},{\"content\":[{\"content\":\"r3\",\"tool_use_id\":\"c3\",\"type\":\"tool_result\"}],\"role\":\"user\"}],\"model\":\"m\",\"stream\":true}",
            prod(empty, batch, null, true),
        )
        // Assistant with text and tool_use blocks; blank argumentsJson
        // parses as an empty object, malformed arguments as {} too.
        val asst = AgentChatMessage(
            role = "assistant", content = "thinking",
            toolCalls = listOf(
                AgentToolCall(id = "call_1", name = "f", argumentsJson = "{\"a\":1}"),
                AgentToolCall(id = "call_2", name = "g", argumentsJson = ""),
                AgentToolCall(id = "call_3", name = "h", argumentsJson = "{bad"),
            ),
        )
        assertEquals(
            "{\"max_tokens\":4096,\"messages\":[{\"content\":[{\"text\":\"thinking\",\"type\":\"text\"},{\"id\":\"call_1\",\"input\":{\"a\":1},\"name\":\"f\",\"type\":\"tool_use\"},{\"id\":\"call_2\",\"input\":{},\"name\":\"g\",\"type\":\"tool_use\"},{\"id\":\"call_3\",\"input\":{},\"name\":\"h\",\"type\":\"tool_use\"}],\"role\":\"assistant\"}],\"model\":\"m\",\"stream\":true}",
            prod(empty, listOf(asst), null, true),
        )
        // Assistant with no text and no calls gets one empty text block.
        assertEquals(
            "{\"max_tokens\":4096,\"messages\":[{\"content\":[{\"text\":\"\",\"type\":\"text\"}],\"role\":\"assistant\"}],\"model\":\"m\",\"stream\":true}",
            prod(empty, listOf(AgentChatMessage(role = "assistant", content = "")), null, true),
        )
        // Assistant with blank content but calls gets only tool_use blocks.
        assertEquals(
            "{\"max_tokens\":4096,\"messages\":[{\"content\":[{\"id\":\"call_1\",\"input\":{\"a\":1},\"name\":\"f\",\"type\":\"tool_use\"}],\"role\":\"assistant\"}],\"model\":\"m\",\"stream\":true}",
            prod(empty, listOf(AgentChatMessage(role = "assistant", content = "  ", toolCalls = listOf(AgentToolCall(id = "call_1", name = "f", argumentsJson = "{\"a\":1}")))), null, true),
        )
        // org.json leniency, pinned by hand (never in the generators):
        // single-quoted and trailing-comma arguments still parse.
        assertEquals(
            "{\"max_tokens\":4096,\"messages\":[{\"content\":[{\"id\":\"call_1\",\"input\":{\"a\":1},\"name\":\"f\",\"type\":\"tool_use\"}],\"role\":\"assistant\"}],\"model\":\"m\",\"stream\":true}",
            prod(empty, listOf(AgentChatMessage(role = "assistant", content = "", toolCalls = listOf(AgentToolCall(id = "call_1", name = "f", argumentsJson = "{'a':1}")))), null, true),
        )
        assertEquals(
            "{\"max_tokens\":4096,\"messages\":[{\"content\":[{\"id\":\"call_1\",\"input\":{\"a\":1},\"name\":\"f\",\"type\":\"tool_use\"}],\"role\":\"assistant\"}],\"model\":\"m\",\"stream\":true}",
            prod(empty, listOf(AgentChatMessage(role = "assistant", content = "", toolCalls = listOf(AgentToolCall(id = "call_1", name = "f", argumentsJson = "{\"a\":1,}")))), null, true),
        )
        // Tools: only specs with a function map survive; name/description
        // are dropped when null, parameters become input_schema via toJson.
        val tools = listOf(
            mapOf<String, Any?>("function" to mapOf("name" to "f", "description" to "d", "parameters" to mapOf("a" to 1))),
            mapOf<String, Any?>("function" to mapOf("name" to "g")),
            mapOf<String, Any?>("type" to "x"),
            mapOf<String, Any?>("function" to "str"),
            mapOf<String, Any?>("function" to mapOf("parameters" to null)),
        )
        assertEquals(
            "{\"max_tokens\":4096,\"messages\":[],\"model\":\"m\",\"stream\":false,\"tools\":[{\"description\":\"d\",\"input_schema\":{\"a\":1},\"name\":\"f\"},{\"input_schema\":null,\"name\":\"g\"},{\"input_schema\":null}]}",
            prod(empty, emptyList(), tools, true),
        )
        // Oracle agrees with production on every pinned case above.
        assertEquals(anthropicBodyOracle150(empty, sys, null, true), prod(empty, sys, null, true))
        assertEquals(anthropicBodyOracle150(empty, batch, null, true), prod(empty, batch, null, true))
        assertEquals(anthropicBodyOracle150(empty, listOf(asst), null, true), prod(empty, listOf(asst), null, true))
        assertEquals(anthropicBodyOracle150(empty, emptyList(), tools, true), prod(empty, emptyList(), tools, true))
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(150003)
        repeat(1500) {
            val p = randomProvider150(rnd)
            val msgs = randomMessages150(rnd)
            val tools = randomTools150(rnd)
            val stream = rnd.nextBoolean()
            assertEquals(
                anthropicBodyOracle150(p, msgs, tools, stream),
                prod(p, msgs, tools, stream),
            )
        }
    }

    @Test
    fun sweepExhaustive() {
        val p0 = provider()
        // Temperature x topP x maxTokens grid (anthropic [0,1] clamp).
        for (t in TEMP_POOL150) {
            for (tp in TOPP_POOL150) {
                for (mt in listOf(16, 4096, 128000, 0, -5)) {
                    val p = provider(temperature = t, topP = tp, maxTokens = mt)
                    assertEquals(anthropicBodyOracle150(p, emptyList(), null, true), prod(p, emptyList(), null, true))
                }
            }
        }
        // Role x content grid, single message.
        for (role in ROLE_POOL150) {
            for (content in CONTENT_POOL150) {
                val m = AgentChatMessage(role = role, content = content)
                assertEquals(anthropicBodyOracle150(p0, listOf(m), null, true), prod(p0, listOf(m), null, true))
            }
        }
        // System join: pairs of system contents (trim + newline join).
        for (a in listOf("", " ", "x", "  x  ", "a\nb", "\u00A0x\u00A0")) {
            for (b in listOf("", " ", "y", "  y  ")) {
                val msgs = listOf(
                    AgentChatMessage(role = "system", content = a),
                    AgentChatMessage(role = "user", content = "u"),
                    AgentChatMessage(role = "system", content = b),
                )
                assertEquals(anthropicBodyOracle150(p0, msgs, null, true), prod(p0, msgs, null, true))
            }
        }
        // Tool-result batching: tool/tool/user/tool sequences.
        val t1 = AgentChatMessage(role = "tool", content = "r1", toolCallId = "c1")
        val t2 = AgentChatMessage(role = "tool", content = "r2", toolCallId = "c2")
        val u = AgentChatMessage(role = "user", content = "u")
        val a0 = AgentChatMessage(role = "assistant", content = "a")
        for (seq in listOf(
            listOf(t1), listOf(t1, t2), listOf(t1, u), listOf(u, t1),
            listOf(t1, t2, u, t1), listOf(t1, a0), listOf(a0, t1), listOf(t1, t2, a0),
        )) {
            assertEquals(anthropicBodyOracle150(p0, seq, null, true), prod(p0, seq, null, true))
        }
        // Assistant shape grid: blank/non-blank content x 0/1/2 tool calls.
        val calls0 = emptyList<AgentToolCall>()
        val calls1 = listOf(AgentToolCall(id = "call_1", name = "f", argumentsJson = "{}"))
        val calls2 = calls1 + AgentToolCall(id = "call_2", name = "g", argumentsJson = "{\"a\":1}")
        for (content in listOf("", " ", "x", "  x  ")) {
            for (calls in listOf(calls0, calls1, calls2)) {
                val m = AgentChatMessage(role = "assistant", content = content, toolCalls = calls)
                assertEquals(anthropicBodyOracle150(p0, listOf(m), null, true), prod(p0, listOf(m), null, true))
            }
        }
        // argumentsJson pool x single tool call.
        for (args in ARGS_POOL150) {
            val m = AgentChatMessage(
                role = "assistant", content = "",
                toolCalls = listOf(AgentToolCall(id = "call_1", name = "f", argumentsJson = args)),
            )
            assertEquals(anthropicBodyOracle150(p0, listOf(m), null, true), prod(p0, listOf(m), null, true))
        }
        // Tool spec shapes.
        val specs = listOf<Map<String, Any?>>(
            emptyMap(),
            mapOf("function" to "str"),
            mapOf("function" to 5),
            mapOf("function" to null),
            mapOf("function" to mapOf("name" to "f")),
            mapOf("function" to mapOf("description" to "d")),
            mapOf("function" to mapOf("parameters" to mapOf("a" to 1))),
            mapOf("function" to mapOf("name" to "f", "description" to "d", "parameters" to null)),
            mapOf("function" to mapOf("name" to null, "description" to null)),
            mapOf("type" to "x"),
        )
        for (spec in specs) {
            assertEquals(anthropicBodyOracle150(p0, emptyList(), listOf(spec), true), prod(p0, emptyList(), listOf(spec), true))
        }
        // Stream flag x tools presence.
        for (stream in listOf(true, false)) {
            for (tools in listOf<List<Map<String, Any?>>?>(null, emptyList(), listOf(mapOf("function" to mapOf("name" to "f"))))) {
                assertEquals(anthropicBodyOracle150(p0, emptyList(), tools, stream), prod(p0, emptyList(), tools, stream))
            }
        }
        // Model pool.
        for (model in MODEL_POOL150) {
            val p = provider(model = model)
            assertEquals(anthropicBodyOracle150(p, emptyList(), null, true), prod(p, emptyList(), null, true))
        }
    }
}
