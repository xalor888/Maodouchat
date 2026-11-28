// LocalAiChatCompletionsCodec fuzz (150th block). Oracle reimplements
// chatCompletionsBody from production text. Production returns an
// org.json JSONObject (hash-ordered keys), so oracle and probe both
// canonicalize: keys sorted by UTF-16 unit, org.json quote/number
// rendering. The parse side (parseChatCompletions) is already fuzzed
// by block 6 via LocalAiProtocolCodec -- this file covers the encoding
// side only, which no earlier block touches.
package com.maodouchat.domain.messaging

import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// CANON150-BEGIN
// Canonical rendering shared with Parse150.kt (probe/oracle harness):
// the text here must stay identical to that copy; gen_oracle150.py
// checks it and strips this span when assembling Oracle150.kt.
internal object JNull150

internal fun canon150(v: Any?): String = when (v) {
    null, JNull150, JSONObject.NULL -> "null"
    is JSONObject -> "{" + v.keys().asSequence().toList().sorted()
        .joinToString(",") { k -> JSONObject.quote(k) + ":" + canon150(v.opt(k)) } + "}"
    is Map<*, *> -> "{" + v.keys.map { it.toString() }.sorted()
        .joinToString(",") { k -> JSONObject.quote(k) + ":" + canon150(v[k]) } + "}"
    is JSONArray -> "[" + (0 until v.length()).joinToString(",") { canon150(v.opt(it)) } + "]"
    is Collection<*> -> "[" + v.joinToString(",") { canon150(it) } + "]"
    is String -> JSONObject.quote(v)
    is Number -> JSONObject.numberToString(v)
    is Boolean -> v.toString()
    else -> JSONObject.quote(v.toString())
}
// CANON150-END

internal fun toJsonOracle150(value: Any?): Any? = when (value) {
    null -> JNull150
    is JSONObject, is JSONArray -> value
    is Map<*, *> -> {
        val o = LinkedHashMap<String, Any?>()
        value.forEach { (k, v) -> o[k.toString()] = toJsonOracle150(v) }
        o
    }
    is List<*> -> value.map { toJsonOracle150(it) }
    else -> value
}

internal fun clampedMaxTokensOracle150(maxTokens: Int): Int = maxTokens.coerceIn(16, 128_000)

internal fun applySamplingOracle150(body: MutableMap<String, Any?>, provider: LocalAiProvider) {
    provider.temperature?.let { body["temperature"] = it.coerceIn(0.0, 2.0) }
    provider.topP?.let { body["top_p"] = it.coerceIn(0.0, 1.0) }
    body["max_tokens"] = clampedMaxTokensOracle150(provider.maxTokens)
}

internal fun encodeChatMessagesOracle150(messages: List<AgentChatMessage>): List<Any?> {
    val array = ArrayList<Any?>()
    messages.forEach { m ->
        val o = LinkedHashMap<String, Any?>()
        o["role"] = m.role
        when (m.role) {
            "tool" -> {
                o["content"] = m.content
                o["tool_call_id"] = m.toolCallId.orEmpty()
                if (!m.toolName.isNullOrBlank()) o["name"] = m.toolName
            }
            else -> {
                o["content"] = m.content
                if (m.toolCalls.isNotEmpty()) {
                    val calls = ArrayList<Any?>()
                    m.toolCalls.forEach { call ->
                        val fn = LinkedHashMap<String, Any?>()
                        fn["name"] = call.name
                        fn["arguments"] = call.argumentsJson
                        val co = LinkedHashMap<String, Any?>()
                        co["id"] = call.id
                        co["type"] = "function"
                        co["function"] = fn
                        calls.add(co)
                    }
                    o["tool_calls"] = calls
                }
            }
        }
        array.add(o)
    }
    return array
}

internal fun chatCompletionsBodyOracle150(
    provider: LocalAiProvider,
    messages: List<AgentChatMessage>,
    tools: List<Map<String, Any?>>?,
    stream: Boolean,
): String {
    val body = LinkedHashMap<String, Any?>()
    body["model"] = provider.model
    body["stream"] = stream && tools.isNullOrEmpty()
    body["messages"] = encodeChatMessagesOracle150(messages)
    applySamplingOracle150(body, provider)
    if (!tools.isNullOrEmpty()) {
        body["tools"] = tools.map { toJsonOracle150(it) }
        body["tool_choice"] = "auto"
        body["stream"] = false
    }
    return canon150(body)
}

// Wire JSON builder for GEN mode: converts a value tree to a
// JSONObject (null -> JSONObject.NULL, never dropped) and renders it.
// Number literals come from Kotlin Double/Int toString, so GEN wire
// lines carry the same literals the probe parses back.
internal fun wireValue150(value: Any?): Any? = when (value) {
    null -> JSONObject.NULL
    is Map<*, *> -> {
        val o = JSONObject()
        value.forEach { (k, v) -> o.put(k.toString(), wireValue150(v)) }
        o
    }
    is List<*> -> {
        val a = JSONArray()
        value.forEach { a.put(wireValue150(it)) }
        a
    }
    else -> value
}

internal fun wireJson150(
    provider: LocalAiProvider,
    messages: List<AgentChatMessage>,
    tools: List<Map<String, Any?>>?,
    stream: Boolean,
): String {
    val root = JSONObject()
    val prov = JSONObject()
    prov.put("model", provider.model)
    if (provider.temperature == null) prov.put("temperature", JSONObject.NULL)
    else prov.put("temperature", provider.temperature)
    if (provider.topP == null) prov.put("topP", JSONObject.NULL)
    else prov.put("topP", provider.topP)
    prov.put("maxTokens", provider.maxTokens)
    root.put("provider", prov)
    val msgs = JSONArray()
    messages.forEach { m ->
        val o = JSONObject()
        o.put("role", m.role)
        o.put("content", m.content)
        if (m.toolCallId == null) o.put("toolCallId", JSONObject.NULL) else o.put("toolCallId", m.toolCallId)
        if (m.toolName == null) o.put("toolName", JSONObject.NULL) else o.put("toolName", m.toolName)
        val calls = JSONArray()
        m.toolCalls.forEach { call ->
            val co = JSONObject()
            co.put("id", call.id)
            co.put("name", call.name)
            co.put("argumentsJson", call.argumentsJson)
            calls.put(co)
        }
        o.put("toolCalls", calls)
        msgs.put(o)
    }
    root.put("messages", msgs)
    if (tools == null) root.put("tools", JSONObject.NULL)
    else root.put("tools", wireValue150(tools))
    root.put("stream", stream)
    return root.toString()
}

// ---------- generators ----------

internal val MODEL_POOL150 = arrayOf(
    "", " ", "gpt-4o", "gpt-4o-mini", "claude-3-5-sonnet", "gemini-2.0-flash",
    "qwen-vl-max", "vision-model", "  padded  ", "MODEL", "a/b", "a\"b",
    "a\\b", "a\nb", "模型", "ﬀ", "ß", "x",
)

internal val TEMP_POOL150 = arrayOf<Double?>(
    null, 0.0, -0.0, 0.5, 1.0, 1.5, 2.0, -1.0, 3.0, 0.25, 0.75, 0.1,
    1.0 / 3.0, 1e-7, 1e-9, 2.5, 100.0, -100.0, 0.001, 1.999, 0.3333333333333333,
)

internal val TOPP_POOL150 = arrayOf<Double?>(
    null, 0.0, 0.5, 1.0, -0.5, 1.5, 0.1, 0.9, 1e-7, 0.3333333333333333,
)

internal val MAXTOKENS_POOL150 = intArrayOf(
    16, 128000, 4096, 0, 15, 17, -5, 200000, 1, Int.MAX_VALUE, Int.MIN_VALUE,
)

internal val ROLE_POOL150 = arrayOf(
    "system", "user", "assistant", "tool", "", "System", "USER", "developer",
    "tool_result", "a b", "  ", "u\u00A0ser", "u\u0085ser",
)

internal val CONTENT_POOL150 = arrayOf(
    "", " ", "hello", "Hello, world!", "multi\nline\ntext", "tab\there",
    "quote\"x\"", "back\\slash", "emoji \uD83D\uDE00", "CJK 你好世界",
    "  padded  ", "\u00A0x\u00A0", "\u0085x\u0085", "\u2000x", "tool result",
    "{\"json\":1}", "  ", "\t", "ß", "ﬀ", "İ", "ı", "line1\r\nline2", "a/b",
    "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
)

internal val TOOLCALL_ID_POOL150 = arrayOf(
    "call_1", "call_abc", "", "  ", "id with space", "id\"q\"", "call_\uD83D\uDE00",
)

internal val TOOL_NAME_POOL150 = arrayOf(
    "get_weather", "search", "", "  ", "fn\"x\"", "a b", "wea\u00A0ther",
)

// Arguments JSON: strict JSON objects plus strings both org.json and
// the Python model reject the same way. org.json-lenient-only inputs
// (single quotes, trailing commas) stay out of the generators; they
// are pinned by hand in pinnedBoundaries instead.
internal val ARGS_POOL150 = arrayOf(
    "{}", "{\"a\":1}", "{\"city\":\"Paris\"}", "", "   ", "not json", "{bad",
    "[1,2]", "\"str\"", "null", "5",
    "{\"nested\":{\"x\":[1,2,{\"y\":\"z\"}]}}", "{\"a\":}",
    "{\"a\":\"x\ny\"}", "{\"a\":1e400}", "{\"a\":-0.0}",
    "{\"a\":9007199254740993}", "{\"a\":1.0}", "{\"a\":null}",
    "{\"a\":true}", "{\"a\":[true,false,null]}",
)

internal val TOOL_KEY_POOL150 = arrayOf(
    "function", "name", "description", "parameters", "type", "a", "A", "z",
    "0", "", " ", "x\ny", "ß", "ﬀ", "a\"b", "a\\b", "é", "\uD83D\uDE00",
    "\uD800", "a\uD800b", "function_call", "properties",
)

internal val TOOL_STR_POOL150 = arrayOf(
    "", " ", "x", "hello world", "a\"b", "a\\b", "a\nb", "a\tb", "ß", "ﬀ",
    "é", "\uD83D\uDE00", "\uD800", "a\uD800b", "\u00A0", "\u0085", "\u2028",
    "0", "-0", "1.5", "true", "null", "  padded  ",
)

internal fun randomProvider150(rnd: Random): LocalAiProvider = LocalAiProvider(
    id = "p1",
    name = "Test",
    baseUrl = "https://x.test",
    apiKey = "k",
    model = MODEL_POOL150[rnd.nextInt(MODEL_POOL150.size)],
    temperature = TEMP_POOL150[rnd.nextInt(TEMP_POOL150.size)],
    topP = TOPP_POOL150[rnd.nextInt(TOPP_POOL150.size)],
    maxTokens = MAXTOKENS_POOL150[rnd.nextInt(MAXTOKENS_POOL150.size)],
)

internal fun randomToolCall150(rnd: Random): AgentToolCall = AgentToolCall(
    id = TOOLCALL_ID_POOL150[rnd.nextInt(TOOLCALL_ID_POOL150.size)],
    name = TOOL_NAME_POOL150[rnd.nextInt(TOOL_NAME_POOL150.size)],
    argumentsJson = ARGS_POOL150[rnd.nextInt(ARGS_POOL150.size)],
)

internal fun randomMessage150(rnd: Random): AgentChatMessage {
    val role = ROLE_POOL150[rnd.nextInt(ROLE_POOL150.size)]
    val toolCalls = (0 until rnd.nextInt(0, 3)).map { randomToolCall150(rnd) }
    return AgentChatMessage(
        role = role,
        content = CONTENT_POOL150[rnd.nextInt(CONTENT_POOL150.size)],
        toolCallId = when (rnd.nextInt(4)) {
            0 -> null
            1 -> ""
            2 -> " "
            else -> TOOLCALL_ID_POOL150[rnd.nextInt(TOOLCALL_ID_POOL150.size)]
        },
        toolName = when (rnd.nextInt(4)) {
            0 -> null
            1 -> ""
            2 -> " "
            else -> TOOL_NAME_POOL150[rnd.nextInt(TOOL_NAME_POOL150.size)]
        },
        toolCalls = toolCalls,
    )
}

internal fun randomMessages150(rnd: Random): List<AgentChatMessage> =
    (0 until rnd.nextInt(0, 5)).map { randomMessage150(rnd) }

internal fun randomJsonValue150(rnd: Random, depth: Int): Any? {
    if (depth <= 0) return TOOL_STR_POOL150[rnd.nextInt(TOOL_STR_POOL150.size)]
    return when (rnd.nextInt(7)) {
        0 -> null
        1 -> rnd.nextBoolean()
        2 -> when (rnd.nextInt(4)) {
            0 -> 0
            1 -> rnd.nextInt(-1000, 1000)
            2 -> rnd.nextInt()
            else -> rnd.nextLong()
        }
        3 -> when (rnd.nextInt(5)) {
            0 -> 0.0
            1 -> -0.0
            2 -> rnd.nextDouble()
            3 -> rnd.nextDouble() * 2000.0 - 1000.0
            else -> (rnd.nextInt(-1000, 1000)) / 10.0
        }
        4 -> TOOL_STR_POOL150[rnd.nextInt(TOOL_STR_POOL150.size)]
        5 -> (0 until rnd.nextInt(0, 3)).map { randomJsonValue150(rnd, depth - 1) }
        else -> {
            val m = LinkedHashMap<String, Any?>()
            repeat(rnd.nextInt(0, 3)) {
                m[TOOL_KEY_POOL150[rnd.nextInt(TOOL_KEY_POOL150.size)]] = randomJsonValue150(rnd, depth - 1)
            }
            m
        }
    }
}

internal fun randomToolSpec150(rnd: Random): Map<String, Any?> {
    val spec = LinkedHashMap<String, Any?>()
    when (rnd.nextInt(5)) {
        0 -> {
            // No function key at all.
        }
        1 -> spec["function"] = TOOL_STR_POOL150[rnd.nextInt(TOOL_STR_POOL150.size)]
        2 -> spec["function"] = randomJsonValue150(rnd, 1)
        else -> {
            val fn = LinkedHashMap<String, Any?>()
            if (rnd.nextInt(4) != 0) fn["name"] = TOOL_NAME_POOL150[rnd.nextInt(TOOL_NAME_POOL150.size)]
            if (rnd.nextInt(3) == 0) fn["description"] = TOOL_STR_POOL150[rnd.nextInt(TOOL_STR_POOL150.size)]
            if (rnd.nextInt(3) != 0) fn["parameters"] = randomJsonValue150(rnd, 2)
            if (rnd.nextInt(4) == 0) fn["extra"] = randomJsonValue150(rnd, 1)
            spec["function"] = fn
        }
    }
    repeat(rnd.nextInt(0, 2)) {
        spec[TOOL_KEY_POOL150[rnd.nextInt(TOOL_KEY_POOL150.size)]] = randomJsonValue150(rnd, 1)
    }
    return spec
}

internal fun randomTools150(rnd: Random): List<Map<String, Any?>>? =
    when (rnd.nextInt(4)) {
        0 -> null
        1 -> emptyList()
        else -> (0 until rnd.nextInt(1, 3)).map { randomToolSpec150(rnd) }
    }

class LocalAiChatCompletionsCodecFuzzTest {

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
    ): String = canon150(LocalAiChatCompletionsCodec.chatCompletionsBody(provider, messages, tools, stream))

    @Test
    fun pinnedBoundaries() {
        // Empty everything: sorted keys, stream stays true without tools.
        val empty = provider()
        assertEquals(
            "{\"max_tokens\":4096,\"messages\":[],\"model\":\"m\",\"stream\":true}",
            chatCompletionsBodyOracle150(empty, emptyList(), null, true),
        )
        assertEquals(
            chatCompletionsBodyOracle150(empty, emptyList(), null, true),
            prod(empty, emptyList(), null, true),
        )
        assertEquals(
            "{\"max_tokens\":4096,\"messages\":[],\"model\":\"m\",\"stream\":false}",
            prod(empty, emptyList(), null, false),
        )
        // Sampling clamps: temperature [0,2], topP [0,1], maxTokens [16,128000].
        val clamped = provider(temperature = 3.0, topP = 2.0, maxTokens = 1)
        assertEquals(
            "{\"max_tokens\":16,\"messages\":[],\"model\":\"m\",\"stream\":true,\"temperature\":2,\"top_p\":1}",
            prod(clamped, emptyList(), null, true),
        )
        val floored = provider(temperature = -1.0, topP = -0.5, maxTokens = 200000)
        assertEquals(
            "{\"max_tokens\":128000,\"messages\":[],\"model\":\"m\",\"stream\":true,\"temperature\":0,\"top_p\":0}",
            prod(floored, emptyList(), null, true),
        )
        // -0.0 temperature survives the clamp as -0.
        val negZero = provider(temperature = -0.0)
        assertEquals("{\"max_tokens\":4096,\"messages\":[],\"model\":\"m\",\"stream\":true,\"temperature\":-0}", prod(negZero, emptyList(), null, true))
        // Tools force stream false and add tool_choice auto.
        val tools = listOf(mapOf<String, Any?>("function" to mapOf("name" to "f", "parameters" to mapOf("a" to 1))))
        assertEquals(
            "{\"max_tokens\":4096,\"messages\":[],\"model\":\"m\",\"stream\":false,\"tool_choice\":\"auto\",\"tools\":[{\"function\":{\"name\":\"f\",\"parameters\":{\"a\":1}}}]}",
            prod(empty, emptyList(), tools, true),
        )
        // Empty tools list is the same as no tools.
        assertEquals(prod(empty, emptyList(), null, true), prod(empty, emptyList(), emptyList(), true))
        // Tool message carries tool_call_id and name only when non-blank.
        val toolMsg = AgentChatMessage(role = "tool", content = "r", toolCallId = "c1", toolName = "fn")
        assertEquals(
            "{\"max_tokens\":4096,\"messages\":[{\"content\":\"r\",\"role\":\"tool\",\"tool_call_id\":\"c1\"}],\"model\":\"m\",\"stream\":true}",
            prod(empty, listOf(toolMsg.copy(toolName = null)), null, true),
        )
        assertEquals(
            "{\"max_tokens\":4096,\"messages\":[{\"content\":\"r\",\"name\":\"fn\",\"role\":\"tool\",\"tool_call_id\":\"c1\"}],\"model\":\"m\",\"stream\":true}",
            prod(empty, listOf(toolMsg), null, true),
        )
        // Assistant tool_calls encode id/type/function.
        val asst = AgentChatMessage(
            role = "assistant", content = "hi",
            toolCalls = listOf(AgentToolCall(id = "call_1", name = "f", argumentsJson = "{\"a\":1}")),
        )
        assertEquals(
            "{\"max_tokens\":4096,\"messages\":[{\"content\":\"hi\",\"role\":\"assistant\",\"tool_calls\":[{\"function\":{\"arguments\":\"{\\\"a\\\":1}\",\"name\":\"f\"},\"id\":\"call_1\",\"type\":\"function\"}]}],\"model\":\"m\",\"stream\":true}",
            prod(empty, listOf(asst), null, true),
        )
        // Unknown roles fall into the else branch (content only, plus tool_calls).
        val other = AgentChatMessage(role = "developer", content = "d")
        assertEquals(
            "{\"max_tokens\":4096,\"messages\":[{\"content\":\"d\",\"role\":\"developer\"}],\"model\":\"m\",\"stream\":true}",
            prod(empty, listOf(other), null, true),
        )
        // toJson: nulls become JSON null, nested maps/lists convert.
        val nested = listOf(
            mapOf<String, Any?>(
                "function" to mapOf(
                    "name" to "f",
                    "parameters" to mapOf("a" to null, "b" to listOf(1, "x", null), "c" to mapOf("d" to true)),
                ),
            ),
        )
        assertEquals(
            "{\"max_tokens\":4096,\"messages\":[],\"model\":\"m\",\"stream\":false,\"tool_choice\":\"auto\",\"tools\":[{\"function\":{\"name\":\"f\",\"parameters\":{\"a\":null,\"b\":[1,\"x\",null],\"c\":{\"d\":true}}}}]}",
            prod(empty, emptyList(), nested, true),
        )
        // A tool spec without a function map passes through toJson untouched
        // (chatCompletions never reads spec["function"]).
        val plain = listOf(mapOf<String, Any?>("type" to "x", "n" to 5))
        assertEquals(
            "{\"max_tokens\":4096,\"messages\":[],\"model\":\"m\",\"stream\":false,\"tool_choice\":\"auto\",\"tools\":[{\"n\":5,\"type\":\"x\"}]}",
            prod(empty, emptyList(), plain, true),
        )
        // Oracle agrees with production on every pinned case above (re-checked).
        assertEquals(chatCompletionsBodyOracle150(empty, emptyList(), tools, true), prod(empty, emptyList(), tools, true))
        assertEquals(chatCompletionsBodyOracle150(empty, listOf(toolMsg), null, true), prod(empty, listOf(toolMsg), null, true))
        assertEquals(chatCompletionsBodyOracle150(empty, listOf(asst), null, true), prod(empty, listOf(asst), null, true))
        assertEquals(chatCompletionsBodyOracle150(empty, emptyList(), nested, true), prod(empty, emptyList(), nested, true))
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(150001)
        repeat(1500) {
            val p = randomProvider150(rnd)
            val msgs = randomMessages150(rnd)
            val tools = randomTools150(rnd)
            val stream = rnd.nextBoolean()
            assertEquals(
                chatCompletionsBodyOracle150(p, msgs, tools, stream),
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
                    assertEquals(chatCompletionsBodyOracle150(p, emptyList(), null, true), prod(p, emptyList(), null, true))
                }
            }
        }
        // Role x content grid, single message.
        for (role in ROLE_POOL150) {
            for (content in CONTENT_POOL150) {
                val m = AgentChatMessage(role = role, content = content)
                assertEquals(chatCompletionsBodyOracle150(p0, listOf(m), null, true), prod(p0, listOf(m), null, true))
            }
        }
        // Tool message: toolCallId/toolName presence grid.
        for (tcid in listOf<String?>(null, "", " ", "c1")) {
            for (tname in listOf<String?>(null, "", " ", "fn")) {
                val m = AgentChatMessage(role = "tool", content = "r", toolCallId = tcid, toolName = tname)
                assertEquals(chatCompletionsBodyOracle150(p0, listOf(m), null, true), prod(p0, listOf(m), null, true))
            }
        }
        // argumentsJson pool x single tool call.
        for (args in ARGS_POOL150) {
            val m = AgentChatMessage(
                role = "assistant", content = "",
                toolCalls = listOf(AgentToolCall(id = "call_1", name = "f", argumentsJson = args)),
            )
            assertEquals(chatCompletionsBodyOracle150(p0, listOf(m), null, true), prod(p0, listOf(m), null, true))
        }
        // Tool spec shapes: function missing / scalar / map, parameters pool.
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
            assertEquals(chatCompletionsBodyOracle150(p0, emptyList(), listOf(spec), true), prod(p0, emptyList(), listOf(spec), true))
        }
        // Stream flag x tools presence.
        for (stream in listOf(true, false)) {
            for (tools in listOf<List<Map<String, Any?>>?>(null, emptyList(), listOf(mapOf("function" to mapOf("name" to "f"))))) {
                assertEquals(chatCompletionsBodyOracle150(p0, emptyList(), tools, stream), prod(p0, emptyList(), tools, stream))
            }
        }
        // Model pool.
        for (model in MODEL_POOL150) {
            val p = provider(model = model)
            assertEquals(chatCompletionsBodyOracle150(p, emptyList(), null, true), prod(p, emptyList(), null, true))
        }
    }
}
