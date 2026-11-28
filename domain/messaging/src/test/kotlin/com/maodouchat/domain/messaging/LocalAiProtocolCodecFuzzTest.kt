package com.maodouchat.domain.messaging

import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalAiProtocolCodecFuzzTest {

    private companion object {
        private const val CHAT_ITERATIONS = 150
        private const val OTHER_ITERATIONS = 100
        private val KNOWN_KEYS = setOf(
            "choices", "message", "role", "content", "tool_calls", "id", "function",
            "name", "arguments", "reasoning_content", "type", "call_id", "output",
            "output_text", "text", "input", "delta", "data", "usage", "model"
        )
    }

    private val testProvider = LocalAiProvider(
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

    private fun randomFieldName(random: Random): String {
        val stems = listOf("future", "x", "v9", "extra", "unknown", "meta", "debug", "tmp", "ext")
        var name = "${stems.random(random)}_${random.nextInt(10000)}"
        while (name in KNOWN_KEYS) name = "z$name"
        return name
    }

    private fun randomJsonValue(random: Random, depth: Int): Any {
        return when (random.nextInt(if (depth <= 0) 5 else 7)) {
            0 -> random.nextBoolean()
            1 -> random.nextLong()
            2 -> random.nextDouble()
            3 -> "str_${random.nextInt(100000)}"
            4 -> JSONObject.NULL
            5 -> JSONArray().apply {
                repeat(random.nextInt(0, 3)) { put(randomJsonValue(random, depth - 1)) }
            }
            else -> JSONObject().apply {
                repeat(random.nextInt(0, 3)) { put(randomFieldName(random), randomJsonValue(random, depth - 1)) }
            }
        }
    }

    private fun injectUnknowns(obj: JSONObject, random: Random, max: Int = 4) {
        repeat(random.nextInt(0, max + 1)) { obj.put(randomFieldName(random), randomJsonValue(random, 2)) }
    }

    @Test
    fun `chat completions text survives seeded unknown-field fuzz`() {
        val random = Random(0x81_2026)
        repeat(CHAT_ITERATIONS) { i ->
            val message = JSONObject().put("role", "assistant").put("content", "hello")
            injectUnknowns(message, random, 3)
            val choice = JSONObject().put("message", message)
            injectUnknowns(choice, random, 3)
            val root = JSONObject().put("choices", JSONArray().put(choice))
            injectUnknowns(root, random, 4)
            val result = LocalAiProtocolCodec.parseChatCompletions(root.toString())
            assertTrue(result is LocalAiCompletion.Text, "fuzz 迭代 #$i：必须是 Text，实际 $result")
            assertEquals("hello", (result as LocalAiCompletion.Text).content, "fuzz 迭代 #$i：content 必须恒等")
        }
    }

    @Test
    fun `chat completions tool calls survive seeded unknown-field fuzz`() {
        val random = Random(0x82_2026)
        repeat(OTHER_ITERATIONS) { i ->
            val function = JSONObject().put("name", "list_chats").put("arguments", "{\"query\":\"a\"}")
            injectUnknowns(function, random, 2)
            val call = JSONObject().put("id", "c1").put("function", function)
            injectUnknowns(call, random, 2)
            val message = JSONObject().put("role", "assistant").put("content", "hello")
                .put("tool_calls", JSONArray().put(call))
            injectUnknowns(message, random, 3)
            val choice = JSONObject().put("message", message)
            injectUnknowns(choice, random, 2)
            val root = JSONObject().put("choices", JSONArray().put(choice))
            injectUnknowns(root, random, 3)
            val result = LocalAiProtocolCodec.parseChatCompletions(root.toString())
            assertTrue(result is LocalAiCompletion.Tools, "fuzz 迭代 #$i：必须是 Tools，实际 $result")
            val tools = result as LocalAiCompletion.Tools
            assertEquals(1, tools.calls.size, "fuzz 迭代 #$i：必须恰好一个调用")
            assertEquals("c1", tools.calls.single().id)
            assertEquals("list_chats", tools.calls.single().name)
            assertEquals("{\"query\":\"a\"}", tools.calls.single().argumentsJson)
            assertEquals("hello", tools.content)
        }
    }

    @Test
    fun `responses tool calls and text survive seeded unknown-field fuzz`() {
        val random = Random(0x83_2026)
        repeat(OTHER_ITERATIONS) { i ->
            val fnCall = JSONObject().put("type", "function_call").put("call_id", "c1")
                .put("name", "get_me").put("arguments", "{}")
            injectUnknowns(fnCall, random, 2)
            val part = JSONObject().put("type", "output_text").put("text", "hi")
            injectUnknowns(part, random, 2)
            val msg = JSONObject().put("type", "message").put("content", JSONArray().put(part))
            injectUnknowns(msg, random, 2)
            val root = JSONObject().put("output", JSONArray().put(fnCall).put(msg))
            injectUnknowns(root, random, 3)
            val result = LocalAiProtocolCodec.parseResponses(root.toString())
            assertTrue(result is LocalAiCompletion.Tools, "fuzz 迭代 #$i：必须是 Tools，实际 $result")
            val tools = result as LocalAiCompletion.Tools
            assertEquals(1, tools.calls.size)
            assertEquals("c1", tools.calls.single().id)
            assertEquals("get_me", tools.calls.single().name)
            assertEquals("{}", tools.calls.single().argumentsJson)
            assertEquals("hi", tools.content)
        }
    }

    @Test
    fun `anthropic text and tool use survive seeded unknown-field fuzz`() {
        val random = Random(0x84_2026)
        repeat(OTHER_ITERATIONS) { i ->
            val textBlock = JSONObject().put("type", "text").put("text", "ok")
            injectUnknowns(textBlock, random, 2)
            val input = JSONObject().put("query", "a")
            injectUnknowns(input, random, 2)
            val toolBlock = JSONObject().put("type", "tool_use").put("id", "tu1")
                .put("name", "list_chats").put("input", input)
            injectUnknowns(toolBlock, random, 2)
            val root = JSONObject().put("content", JSONArray().put(textBlock).put(toolBlock))
            injectUnknowns(root, random, 3)
            val result = LocalAiProtocolCodec.parseAnthropic(root.toString())
            assertTrue(result is LocalAiCompletion.Tools, "fuzz 迭代 #$i：必须是 Tools，实际 $result")
            val tools = result as LocalAiCompletion.Tools
            assertEquals(1, tools.calls.size)
            assertEquals("tu1", tools.calls.single().id)
            assertEquals("list_chats", tools.calls.single().name)
            assertTrue(tools.calls.single().argumentsJson.contains("\"query\":\"a\""), "fuzz 迭代 #$i：input 必须保留 query")
            assertEquals("ok", tools.content)
        }
    }

    @Test
    fun `sse chat survives seeded random-line fuzz`() {
        val random = Random(0x85_2026)
        repeat(OTHER_ITERATIONS) { i ->
            val lines = List(random.nextInt(0, 8)) {
                when (random.nextInt(6)) {
                    0 -> "data: {\"choices\":[{\"delta\":{\"content\":\"${random.nextInt(100)}\"}}]}"
                    1 -> "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"r${random.nextInt(100)}\"}}]}"
                    2 -> "data: {oops"
                    3 -> ": ping"
                    4 -> "data: [DONE]"
                    else -> "garbage ${random.nextInt(1000)}"
                }
            }
            val payload = lines.joinToString("\n")
            val deltas = mutableListOf<String>()
            val result = LocalAiProtocolCodec.parseSseChat(payload) { deltas += it }
            when (result) {
                is LocalAiCompletion.Text -> assertEquals(
                    deltas.joinToString(""), result.content,
                    "fuzz 迭代 #$i：Text 内容必须等于全部 delta 之和"
                )
                is LocalAiCompletion.Error -> {
                    assertEquals("empty stream", result.message, "fuzz 迭代 #$i：空流错误信息必须钉住")
                    assertTrue(deltas.isEmpty(), "fuzz 迭代 #$i：Error 时不得有 delta")
                }
                is LocalAiCompletion.Tools -> error("fuzz 迭代 #$i：SSE 解析不应返回 Tools")
            }
        }
    }

    @Test
    fun `extra headers survive seeded random-object fuzz`() {
        val random = Random(0x86_2026)
        repeat(OTHER_ITERATIONS) { i ->
            val obj = JSONObject()
            repeat(random.nextInt(0, 5)) {
                obj.put(randomFieldName(random), randomJsonValue(random, 1))
            }
            val headers = LocalAiProtocolCodec.extraHeaders(testProvider.copy(extraHeadersJson = obj.toString()))
            headers.forEach { (k, v) ->
                assertTrue(k.isNotBlank(), "fuzz 迭代 #$i：header 键必须非空")
                assertTrue(v.isNotBlank() && v == v.trim(), "fuzz 迭代 #$i：header 值必须已 trim 且非空：$k=$v")
            }
        }
    }
}
