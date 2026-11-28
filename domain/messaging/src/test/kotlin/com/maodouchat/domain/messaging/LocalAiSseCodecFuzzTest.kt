package com.maodouchat.domain.messaging

import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalAiSseCodecFuzzTest {

    private companion object {
        private const val ITERATIONS = 150
        private val TEXT_ALPHABET = "abcXYZ019 .,;:!?-_()[]'\"/\\@#%&=+*~"
    }

    private fun randomText(random: Random, maxLen: Int = 16): String {
        val len = random.nextInt(0, maxLen + 1)
        return buildString { repeat(len) { append(TEXT_ALPHABET[random.nextInt(TEXT_ALPHABET.length)]) } }
    }

    private fun randomNonBlankText(random: Random, maxLen: Int = 16): String {
        var text = randomText(random, maxLen)
        while (text.isBlank()) text = randomText(random, maxLen)
        return text
    }

    private fun randomFieldName(random: Random): String {
        val stems = listOf("future", "x", "v9", "extra", "unknown", "meta", "debug", "tmp")
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

    private fun contentLine(chunk: String): String {
        val delta = JSONObject().put("content", chunk)
        val choice = JSONObject().put("delta", delta)
        return "data: ${JSONObject().put("choices", JSONArray().put(choice))}"
    }

    private fun reasoningLine(chunk: String): String {
        val delta = JSONObject().put("reasoning_content", chunk)
        val choice = JSONObject().put("delta", delta)
        return "data: ${JSONObject().put("choices", JSONArray().put(choice))}"
    }

    // 第一种回落形态：delta 是数组，首项的 text 对象里再取 text。
    private fun fallbackArrayLine(chunk: String): String {
        val inner = JSONObject().put("text", chunk)
        val first = JSONObject().put("text", inner)
        return "data: ${JSONObject().put("delta", JSONArray().put(first))}"
    }

    // 第二种回落形态：delta 是对象，text 是数组，首项里取 text。
    private fun fallbackObjectLine(chunk: String): String {
        val first = JSONObject().put("text", chunk)
        val delta = JSONObject().put("text", JSONArray().put(first))
        return "data: ${JSONObject().put("delta", delta)}"
    }

    @Test
    fun `random structured streams concatenate content deltas in order`() {
        val random = Random(0xB1_2026)
        repeat(ITERATIONS) { i ->
            val chunks = List(random.nextInt(1, 7)) { randomNonBlankText(random) }
            val lines = mutableListOf<String>()
            chunks.forEach { chunk ->
                repeat(random.nextInt(0, 3)) {
                    lines += listOf(": ping", "event: message", "", "data: [DONE]", "data: {oops", "data: [1,2]")
                        .random(random)
                }
                lines += contentLine(chunk)
            }
            repeat(random.nextInt(0, 3)) { lines += "data: [DONE]" }
            val deltas = mutableListOf<String>()
            val result = LocalAiSseCodec.parseSseChat(lines.joinToString("\n")) { deltas += it }
            assertTrue(result is LocalAiCompletion.Text, "fuzz 迭代 #$i：有内容块时必须是 Text，实际 $result")
            assertEquals(chunks.joinToString(""), (result as LocalAiCompletion.Text).content, "fuzz 迭代 #$i：内容必须按序拼接")
            assertEquals(chunks, deltas, "fuzz 迭代 #$i：delta 回调必须与内容块一一对应")
        }
        // 固定钉住：空白内容块仍触发回调，但整体算空流（ifBlank 回落到空推理）。
        val deltas = mutableListOf<String>()
        val blank = LocalAiSseCodec.parseSseChat(contentLine(" ")) { deltas += it }
        assertTrue(blank is LocalAiCompletion.Error)
        assertEquals("empty stream", (blank as LocalAiCompletion.Error).message)
        assertEquals(listOf(" "), deltas)
    }

    @Test
    fun `reasoning only streams fall back once to the full reasoning text`() {
        val random = Random(0xB2_2026)
        repeat(ITERATIONS) { i ->
            val chunks = List(random.nextInt(1, 6)) { randomNonBlankText(random) }
            val payload = (chunks.map { reasoningLine(it) } + "data: [DONE]").joinToString("\n")
            val deltas = mutableListOf<String>()
            val result = LocalAiSseCodec.parseSseChat(payload) { deltas += it }
            assertTrue(result is LocalAiCompletion.Text, "fuzz 迭代 #$i：只有推理时必须是 Text，实际 $result")
            assertEquals(chunks.joinToString(""), (result as LocalAiCompletion.Text).content, "fuzz 迭代 #$i：推理必须按序拼接")
            assertEquals(listOf(chunks.joinToString("")), deltas, "fuzz 迭代 #$i：推理回落只应回调一次完整文本")
        }
    }

    @Test
    fun `content after reasoning discards reasoning`() {
        val random = Random(0xB3_2026)
        repeat(ITERATIONS) { i ->
            val reasoning = List(random.nextInt(1, 4)) { randomNonBlankText(random) }
            val content = List(random.nextInt(1, 5)) { randomNonBlankText(random) }
            val payload = (reasoning.map { reasoningLine(it) } + content.map { contentLine(it) } + "data: [DONE]")
                .joinToString("\n")
            val deltas = mutableListOf<String>()
            val result = LocalAiSseCodec.parseSseChat(payload) { deltas += it }
            assertTrue(result is LocalAiCompletion.Text, "fuzz 迭代 #$i：有内容块时必须是 Text，实际 $result")
            assertEquals(content.joinToString(""), (result as LocalAiCompletion.Text).content, "fuzz 迭代 #$i：内容到达后推理必须丢弃")
            assertEquals(content, deltas, "fuzz 迭代 #$i：delta 回调只应含内容块")
        }
    }

    @Test
    fun `fallback delta shapes read text from nested delta objects`() {
        val random = Random(0xB4_2026)
        repeat(ITERATIONS) { i ->
            val chunks = List(random.nextInt(1, 6)) { randomNonBlankText(random) }
            val lines = chunks.map { if (random.nextBoolean()) fallbackArrayLine(it) else fallbackObjectLine(it) }
            val deltas = mutableListOf<String>()
            val result = LocalAiSseCodec.parseSseChat((lines + "data: [DONE]").joinToString("\n")) { deltas += it }
            assertTrue(result is LocalAiCompletion.Text, "fuzz 迭代 #$i：回落形态有文本时必须是 Text，实际 $result")
            assertEquals(chunks.joinToString(""), (result as LocalAiCompletion.Text).content, "fuzz 迭代 #$i：回落文本必须按序拼接")
            assertEquals(chunks, deltas, "fuzz 迭代 #$i：delta 回调必须与回落文本一一对应")
        }
    }

    @Test
    fun `sse delta parts keep only string fields`() {
        val random = Random(0xB5_2026)
        repeat(ITERATIONS) { i ->
            val obj = JSONObject()
            if (random.nextBoolean()) obj.put("content", randomJsonValue(random, 1))
            if (random.nextBoolean()) obj.put("reasoning_content", randomJsonValue(random, 1))
            repeat(random.nextInt(0, 3)) { obj.put(randomFieldName(random), randomJsonValue(random, 1)) }
            val parts = LocalAiSseCodec.sseDeltaParts(obj)
            val expectedContent = obj.opt("content").let { if (it is String) it else "" }
            val expectedReasoning = obj.opt("reasoning_content").let { if (it is String) it else "" }
            assertEquals(expectedContent, parts.content, "fuzz 迭代 #$i：content 只认字符串")
            assertEquals(expectedReasoning, parts.reasoning, "fuzz 迭代 #$i：reasoning 只认字符串")
            assertEquals(
                expectedContent.ifEmpty { expectedReasoning },
                LocalAiSseCodec.sseVisibleText(obj),
                "fuzz 迭代 #$i：可见文本优先 content 再 reasoning"
            )
        }
        assertEquals("", LocalAiSseCodec.sseVisibleText(null))
        assertEquals(SseDeltaParts(), LocalAiSseCodec.sseDeltaParts(null))
    }
}
