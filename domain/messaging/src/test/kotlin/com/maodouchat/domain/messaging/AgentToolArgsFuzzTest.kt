package com.maodouchat.domain.messaging

import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentToolArgsFuzzTest {

    private companion object {
        private const val ITERATIONS = 200
        private val TEXT_ALPHABET = "abcXYZ019 .,;:!?-_()[]'\"/\\@#%&=+*~"
        private val GARBAGE_ALPHABET = "abcxyz012{}[]\",: truefalsn|#@"
        private val TRUE_WORDS = setOf("true", "1", "yes")
        private val FALSE_WORDS = setOf("false", "0", "no")
    }

    private fun randomText(random: Random, maxLen: Int = 20): String {
        val len = random.nextInt(0, maxLen + 1)
        return buildString { repeat(len) { append(TEXT_ALPHABET[random.nextInt(TEXT_ALPHABET.length)]) } }
    }

    private fun randomFieldName(random: Random): String {
        val stems = listOf("future", "x", "v9", "extra", "unknown", "meta", "debug", "tmp", "query", "count")
        return "${stems.random(random)}_${random.nextInt(10000)}"
    }

    private fun randomJsonValue(random: Random, depth: Int): Any {
        return when (random.nextInt(if (depth <= 0) 5 else 7)) {
            0 -> random.nextBoolean()
            1 -> random.nextLong(-1_000_000L, 1_000_000L)
            2 -> random.nextDouble()
            3 -> randomText(random)
            4 -> JSONObject.NULL
            5 -> JSONArray().apply { repeat(random.nextInt(0, 3)) { put(randomJsonValue(random, depth - 1)) } }
            else -> JSONObject().apply {
                repeat(random.nextInt(0, 3)) { put(randomFieldName(random), randomJsonValue(random, depth - 1)) }
            }
        }
    }

    @Test
    fun `parseArgs never throws on random garbage`() {
        val random = Random(0xA1_2026)
        repeat(ITERATIONS) { i ->
            val raw = buildString {
                repeat(random.nextInt(0, 81)) { append(GARBAGE_ALPHABET[random.nextInt(GARBAGE_ALPHABET.length)]) }
            }
            val parsed = AgentToolArgs.parseArgs(raw)
            // 空白输入按 "{}" 处理；其余合法性以 JSONObject 本身为准，只钉住映射关系。
            val expected = runCatching { JSONObject(raw.ifBlank { "{}" }) }.getOrNull()
            if (expected == null) {
                assertTrue(parsed.isEmpty(), "fuzz 迭代 #$i：非法 JSON 必须回空映射：$raw")
            } else {
                assertEquals(expected.length(), parsed.size, "fuzz 迭代 #$i：键数必须与输入一致")
                expected.keys().forEach { key ->
                    assertEquals(
                        expected.opt(key).toString(), parsed[key],
                        "fuzz 迭代 #$i：键 $key 的值必须原样转字符串"
                    )
                }
            }
        }
    }

    @Test
    fun `parseArgs flattens random json objects to strings`() {
        val random = Random(0xA2_2026)
        repeat(ITERATIONS) { i ->
            val obj = JSONObject()
            repeat(random.nextInt(0, 6)) { obj.put(randomFieldName(random), randomJsonValue(random, 2)) }
            val parsed = AgentToolArgs.parseArgs(obj.toString())
            assertEquals(obj.length(), parsed.size, "fuzz 迭代 #$i：键数必须与输入一致")
            obj.keys().forEach { key ->
                assertEquals(obj.opt(key).toString(), parsed[key], "fuzz 迭代 #$i：键 $key 的值必须原样转字符串")
            }
        }
    }

    @Test
    fun `parseBool maps random strings to the documented alias table`() {
        val random = Random(0xA3_2026)
        val words = TRUE_WORDS + FALSE_WORDS
        repeat(ITERATIONS) { i ->
            val raw = when (random.nextInt(4)) {
                0 -> words.random(random).let { if (random.nextBoolean()) it.uppercase() else it }
                1 -> "  ${words.random(random)}  "
                2 -> randomText(random)
                else -> random.nextInt(100).toString()
            }
            val expected = when (raw.trim().lowercase()) {
                in TRUE_WORDS -> true
                in FALSE_WORDS -> false
                else -> null
            }
            assertEquals(expected, AgentToolArgs.parseBool(raw), "fuzz 迭代 #$i：$raw 必须映射为 $expected")
        }
        assertNull(AgentToolArgs.parseBool(null))
        assertNull(AgentToolArgs.parseBool("   "))
    }

    @Test
    fun `fail always prefixes Error and keeps the message`() {
        val random = Random(0xA4_2026)
        repeat(100) { i ->
            val message = randomText(random, 40)
            val error = RuntimeException(message)
            assertEquals("Error: $message", AgentToolArgs.fail(error), "fuzz 迭代 #$i：必须保留原始消息")
            val nameless = RuntimeException(null as String?)
            assertEquals("Error: RuntimeException", AgentToolArgs.fail(nameless), "fuzz 迭代 #$i：无消息时回退类名")
        }
    }

    @Test
    fun `parseArgs keeps blank keys and nested values as strings`() {
        val random = Random(0xA5_2026)
        repeat(ITERATIONS) { i ->
            val innerText = randomText(random)
            val nested = JSONObject().put("inner", innerText)
            val firstNumber = random.nextInt(100)
            val secondText = randomText(random)
            val arr = JSONArray().put(firstNumber).put(secondText)
            val obj = JSONObject()
                .put("", randomText(random))
                .put("   ", random.nextInt(1000))
                .put("nested", nested)
                .put("arr", arr)
                .put("null", JSONObject.NULL)
            val parsed = AgentToolArgs.parseArgs(obj.toString())
            // 与 extraHeaders 不同：parseArgs 不过滤键、不过滤值，只做展平。
            assertEquals(5, parsed.size, "fuzz 迭代 #$i：空白键不得过滤")
            assertTrue(parsed.containsKey(""), "fuzz 迭代 #$i：空串键必须保留")
            assertTrue(parsed.containsKey("   "), "fuzz 迭代 #$i：空白键必须保留")
            assertEquals(innerText, JSONObject(parsed["nested"]).getString("inner"), "fuzz 迭代 #$i：嵌套对象必须原样保留")
            assertEquals(firstNumber, JSONArray(parsed["arr"]).getInt(0), "fuzz 迭代 #$i：数组首项必须原样保留")
            assertEquals(secondText, JSONArray(parsed["arr"]).getString(1), "fuzz 迭代 #$i：数组次项必须原样保留")
            assertEquals("null", parsed["null"], "fuzz 迭代 #$i：JSON null 必须转成 null 字符串")
        }
    }
}
