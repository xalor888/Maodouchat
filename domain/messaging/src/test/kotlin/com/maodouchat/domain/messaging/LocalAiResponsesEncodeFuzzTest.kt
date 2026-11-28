// LocalAiResponsesEncode fuzz (150th block). Oracle reimplements
// responsesBody from production text. Shared oracle helpers
// (canon150, toJsonOracle150, pools, generators) live in
// LocalAiChatCompletionsCodecFuzzTest.kt. The parse side
// (parseResponses) is already fuzzed by block 6 via
// LocalAiProtocolCodec -- this file covers the encoding side only.
package com.maodouchat.domain.messaging

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

internal fun encodeResponsesInputOracle150(messages: List<AgentChatMessage>): List<Any?> {
    val array = ArrayList<Any?>()
    messages.forEach { m ->
        when (m.role) {
            "tool" -> {
                val o = LinkedHashMap<String, Any?>()
                o["type"] = "function_call_output"
                o["call_id"] = m.toolCallId.orEmpty()
                o["output"] = m.content
                array.add(o)
            }
            "assistant" -> {
                if (m.toolCalls.isNotEmpty()) {
                    m.toolCalls.forEach { call ->
                        val o = LinkedHashMap<String, Any?>()
                        o["type"] = "function_call"
                        o["call_id"] = call.id
                        o["name"] = call.name
                        o["arguments"] = call.argumentsJson
                        array.add(o)
                    }
                }
                if (m.content.isNotBlank()) {
                    val o = LinkedHashMap<String, Any?>()
                    o["role"] = "assistant"
                    o["content"] = m.content
                    array.add(o)
                }
            }
            else -> {
                val o = LinkedHashMap<String, Any?>()
                o["role"] = m.role
                o["content"] = m.content
                array.add(o)
            }
        }
    }
    return array
}

internal fun encodeResponsesToolsOracle150(tools: List<Map<String, Any?>>): List<Any?> {
    val array = ArrayList<Any?>()
    tools.forEach { spec ->
        val fn = spec["function"] as? Map<*, *> ?: return@forEach
        val o = LinkedHashMap<String, Any?>()
        o["type"] = "function"
        val name = fn["name"]
        if (name != null) o["name"] = name
        val description = fn["description"]
        if (description != null) o["description"] = description
        o["parameters"] = toJsonOracle150(fn["parameters"])
        array.add(o)
    }
    return array
}

internal fun responsesBodyOracle150(
    provider: LocalAiProvider,
    messages: List<AgentChatMessage>,
    tools: List<Map<String, Any?>>?,
    stream: Boolean,
): String {
    val body = LinkedHashMap<String, Any?>()
    body["model"] = provider.model
    body["stream"] = stream && tools.isNullOrEmpty()
    body["input"] = encodeResponsesInputOracle150(messages)
    body["max_output_tokens"] = clampedMaxTokensOracle150(provider.maxTokens)
    provider.temperature?.let { body["temperature"] = it.coerceIn(0.0, 2.0) }
    provider.topP?.let { body["top_p"] = it.coerceIn(0.0, 1.0) }
    if (!tools.isNullOrEmpty()) {
        body["tools"] = encodeResponsesToolsOracle150(tools)
        body["tool_choice"] = "auto"
        body["stream"] = false
    }
    return canon150(body)
}

// ---------- generators ----------
// (providers, messages, tools and pools are shared from
// LocalAiChatCompletionsCodecFuzzTest.kt.)

class LocalAiResponsesEncodeFuzzTest {

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
    ): String = canon150(LocalAiResponsesEncode.responsesBody(provider, messages, tools, stream))

    @Test
    fun pinnedBoundaries() {
        val empty = provider()
        assertEquals(
            "{\"input\":[],\"max_output_tokens\":4096,\"model\":\"m\",\"stream\":true}",
            responsesBodyOracle150(empty, emptyList(), null, true),
        )
        assertEquals(
            responsesBodyOracle150(empty, emptyList(), null, true),
            prod(empty, emptyList(), null, true),
        )
        assertEquals(
            "{\"input\":[],\"max_output_tokens\":4096,\"model\":\"m\",\"stream\":false}",
            prod(empty, emptyList(), null, false),
        )
        // Sampling clamps: temperature [0,2], topP [0,1], maxTokens [16,128000].
        val clamped = provider(temperature = 3.0, topP = 2.0, maxTokens = 1)
        assertEquals(
            "{\"input\":[],\"max_output_tokens\":16,\"model\":\"m\",\"stream\":true,\"temperature\":2,\"top_p\":1}",
            prod(clamped, emptyList(), null, true),
        )
        // Tool message flattens to a function_call_output entry.
        val toolMsg = AgentChatMessage(role = "tool", content = "out", toolCallId = "c9")
        assertEquals(
            "{\"input\":[{\"call_id\":\"c9\",\"output\":\"out\",\"type\":\"function_call_output\"}],\"max_output_tokens\":4096,\"model\":\"m\",\"stream\":true}",
            prod(empty, listOf(toolMsg), null, true),
        )
        // Missing toolCallId becomes an empty call_id.
        assertEquals(
            "{\"input\":[{\"call_id\":\"\",\"output\":\"out\",\"type\":\"function_call_output\"}],\"max_output_tokens\":4096,\"model\":\"m\",\"stream\":true}",
            prod(empty, listOf(toolMsg.copy(toolCallId = null)), null, true),
        )
        // Assistant with tool calls emits one function_call per call, then
        // the content message only when the content is not blank.
        val asst = AgentChatMessage(
            role = "assistant", content = "done",
            toolCalls = listOf(
                AgentToolCall(id = "call_1", name = "f", argumentsJson = "{\"a\":1}"),
                AgentToolCall(id = "call_2", name = "g", argumentsJson = "{}"),
            ),
        )
        assertEquals(
            "{\"input\":[{\"arguments\":\"{\\\"a\\\":1}\",\"call_id\":\"call_1\",\"name\":\"f\",\"type\":\"function_call\"},{\"arguments\":\"{}\",\"call_id\":\"call_2\",\"name\":\"g\",\"type\":\"function_call\"},{\"content\":\"done\",\"role\":\"assistant\"}],\"max_output_tokens\":4096,\"model\":\"m\",\"stream\":true}",
            prod(empty, listOf(asst), null, true),
        )
        // Blank assistant content after tool calls emits no content message.
        assertEquals(
            "{\"input\":[{\"arguments\":\"{\\\"a\\\":1}\",\"call_id\":\"call_1\",\"name\":\"f\",\"type\":\"function_call\"},{\"arguments\":\"{}\",\"call_id\":\"call_2\",\"name\":\"g\",\"type\":\"function_call\"}],\"max_output_tokens\":4096,\"model\":\"m\",\"stream\":true}",
            prod(empty, listOf(asst.copy(content = "   ")), null, true),
        )
        // Assistant without tool calls and with content is a plain message.
        assertEquals(
            "{\"input\":[{\"content\":\"hi\",\"role\":\"assistant\"}],\"max_output_tokens\":4096,\"model\":\"m\",\"stream\":true}",
            prod(empty, listOf(AgentChatMessage(role = "assistant", content = "hi")), null, true),
        )
        // Assistant with neither calls nor content emits nothing.
        assertEquals(
            "{\"input\":[],\"max_output_tokens\":4096,\"model\":\"m\",\"stream\":true}",
            prod(empty, listOf(AgentChatMessage(role = "assistant", content = "")), null, true),
        )
        // Other roles pass through with their own role string.
        assertEquals(
            "{\"input\":[{\"content\":\"x\",\"role\":\"system\"}],\"max_output_tokens\":4096,\"model\":\"m\",\"stream\":true}",
            prod(empty, listOf(AgentChatMessage(role = "system", content = "x")), null, true),
        )
        // Tools: only specs with a function map survive; name/description
        // are dropped when null, parameters go through toJson.
        val tools = listOf(
            mapOf<String, Any?>("function" to mapOf("name" to "f", "description" to "d", "parameters" to mapOf("a" to 1))),
            mapOf<String, Any?>("function" to mapOf("name" to "g")),
            mapOf<String, Any?>("type" to "x"),
            mapOf<String, Any?>("function" to "str"),
            mapOf<String, Any?>("function" to mapOf("parameters" to null)),
        )
        assertEquals(
            "{\"input\":[],\"max_output_tokens\":4096,\"model\":\"m\",\"stream\":false,\"tool_choice\":\"auto\",\"tools\":[{\"description\":\"d\",\"name\":\"f\",\"parameters\":{\"a\":1},\"type\":\"function\"},{\"name\":\"g\",\"parameters\":null,\"type\":\"function\"},{\"parameters\":null,\"type\":\"function\"}]}",
            prod(empty, emptyList(), tools, true),
        )
        // Oracle agrees with production on every pinned case above.
        assertEquals(responsesBodyOracle150(empty, listOf(toolMsg), null, true), prod(empty, listOf(toolMsg), null, true))
        assertEquals(responsesBodyOracle150(empty, listOf(asst), null, true), prod(empty, listOf(asst), null, true))
        assertEquals(responsesBodyOracle150(empty, emptyList(), tools, true), prod(empty, emptyList(), tools, true))
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(150002)
        repeat(1500) {
            val p = randomProvider150(rnd)
            val msgs = randomMessages150(rnd)
            val tools = randomTools150(rnd)
            val stream = rnd.nextBoolean()
            assertEquals(
                responsesBodyOracle150(p, msgs, tools, stream),
                prod(p, msgs, tools, stream),
            )
        }
    }

    @Test
    fun sweepExhaustive() {
        val p0 = provider()
        // Temperature x topP x maxTokens grid.
        for (t in TEMP_POOL150) {
            for (tp in TOPP_POOL150) {
                for (mt in listOf(16, 4096, 128000, 0, -5)) {
                    val p = provider(temperature = t, topP = tp, maxTokens = mt)
                    assertEquals(responsesBodyOracle150(p, emptyList(), null, true), prod(p, emptyList(), null, true))
                }
            }
        }
        // Role x content grid, single message.
        for (role in ROLE_POOL150) {
            for (content in CONTENT_POOL150) {
                val m = AgentChatMessage(role = role, content = content)
                assertEquals(responsesBodyOracle150(p0, listOf(m), null, true), prod(p0, listOf(m), null, true))
            }
        }
        // Assistant shape grid: blank/non-blank content x 0/1/2 tool calls.
        val calls0 = emptyList<AgentToolCall>()
        val calls1 = listOf(AgentToolCall(id = "call_1", name = "f", argumentsJson = "{}"))
        val calls2 = calls1 + AgentToolCall(id = "call_2", name = "g", argumentsJson = "{\"a\":1}")
        for (content in listOf("", " ", "x", "  x  ")) {
            for (calls in listOf(calls0, calls1, calls2)) {
                val m = AgentChatMessage(role = "assistant", content = content, toolCalls = calls)
                assertEquals(responsesBodyOracle150(p0, listOf(m), null, true), prod(p0, listOf(m), null, true))
            }
        }
        // Tool message call_id grid.
        for (tcid in listOf<String?>(null, "", " ", "c1")) {
            val m = AgentChatMessage(role = "tool", content = "r", toolCallId = tcid)
            assertEquals(responsesBodyOracle150(p0, listOf(m), null, true), prod(p0, listOf(m), null, true))
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
            assertEquals(responsesBodyOracle150(p0, emptyList(), listOf(spec), true), prod(p0, emptyList(), listOf(spec), true))
        }
        // Stream flag x tools presence.
        for (stream in listOf(true, false)) {
            for (tools in listOf<List<Map<String, Any?>>?>(null, emptyList(), listOf(mapOf("function" to mapOf("name" to "f"))))) {
                assertEquals(responsesBodyOracle150(p0, emptyList(), tools, stream), prod(p0, emptyList(), tools, stream))
            }
        }
        // Model pool.
        for (model in MODEL_POOL150) {
            val p = provider(model = model)
            assertEquals(responsesBodyOracle150(p, emptyList(), null, true), prod(p, emptyList(), null, true))
        }
    }
}
