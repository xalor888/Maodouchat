package com.maodouchat.core.model

import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PrefsJsonLists 编解码 fuzz（清单 931 行第十四块，簇 2）。
 *
 * 往返是有损的：decode 会 trim 并丢弃空串，所以 oracle 是归一化后恒等
 * （`items.map { it.trim() }.filter { it.isNotEmpty() }`），不是严格恒等。
 * decode 永不抛（runCatching → 空列表），fuzz 断言覆盖损坏输入与嵌套元素。
 */
class PrefsJsonListsFuzzTest {

    private companion object {
        private const val RANDOM_ITERATIONS = 200
        private const val STRUCTURED_ITERATIONS = 150
        private const val TEXT_ALPHABET =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 _-.,!?\"'\\/[]()：中文🎉\n\t\r"
    }

    private fun randomText(random: Random, maxLength: Int): String {
        val length = random.nextInt(maxLength + 1)
        // 按代码点取字符，避免拆散 emoji 的代理对（孤立代理对进不了合法 JSON 字符串）。
        val codePoints = TEXT_ALPHABET.codePoints().toArray()
        return buildString {
            repeat(length) {
                appendCodePoint(codePoints[random.nextInt(codePoints.size)])
            }
        }
    }

    private fun normalized(items: List<String>): List<String> =
        items.map { it.trim() }.filter { it.isNotEmpty() }

    @Test
    fun `randomListsRoundTripAfterNormalization`() {
        val random = Random(0x6F_2026)
        repeat(RANDOM_ITERATIONS) { i ->
            val items = List(random.nextInt(0, 12)) { randomText(random, 30) }
            assertEquals(
                normalized(items),
                PrefsJsonLists.decode(PrefsJsonLists.encode(items)),
                "fuzz 迭代 #$i：随机列表往返应等于 trim/去空后的归一化列表"
            )
        }
    }

    @Test
    fun `randomGarbageDecodesToCleanListWithoutThrowing`() {
        val random = Random(0x6F_2026)
        repeat(RANDOM_ITERATIONS) { i ->
            val raw = randomText(random, 60)
            val decoded = PrefsJsonLists.decode(raw)
            decoded.forEach { item ->
                assertEquals(item, item.trim(), "fuzz 迭代 #$i：解码结果不得含首尾空白")
                assertTrue(item.isNotEmpty(), "fuzz 迭代 #$i：解码结果不得含空串")
            }
            assertEquals(
                decoded, PrefsJsonLists.decode(raw),
                "fuzz 迭代 #$i：同一损坏输入两次解码必须相同"
            )
        }
    }

    @Test
    fun `randomScalarArraysDecodeToLiteralStrings`() {
        val random = Random(0x6F_2026)
        repeat(STRUCTURED_ITERATIONS) { i ->
            val elements = List(random.nextInt(0, 10)) {
                when (random.nextInt(5)) {
                    0 -> random.nextInt()
                    1 -> random.nextLong()
                    2 -> random.nextBoolean()
                    3 -> null
                    else -> randomText(random, 20)
                }
            }
            val json = JSONArray().apply {
                elements.forEach { element ->
                    when (element) {
                        null -> put(JSONObject.NULL)
                        is Int -> put(element)
                        is Long -> put(element)
                        is Boolean -> put(element)
                        is String -> put(element)
                    }
                }
            }.toString()
            // oracle：数字/布尔按字面读，null 按空串丢弃，字符串 trim 后丢空。
            val expected = elements.mapNotNull { element ->
                when (element) {
                    null -> null
                    is String -> element.trim().ifEmpty { null }
                    else -> element.toString()
                }
            }
            assertEquals(expected, PrefsJsonLists.decode(json), "fuzz 迭代 #$i：标量数组解码应与字面一致")
        }
    }

    @Test
    fun `nestedElementsNeverThrow`() {
        val random = Random(0x6F_2026)
        repeat(STRUCTURED_ITERATIONS) { i ->
            val json = JSONArray().apply {
                repeat(random.nextInt(0, 8)) {
                    when (random.nextInt(4)) {
                        0 -> put(randomText(random, 12))
                        1 -> put(JSONArray().apply { put(1); put("x") })
                        2 -> put(JSONObject().apply { put("k", 1) })
                        else -> put(random.nextInt())
                    }
                }
            }.toString()
            val decoded = PrefsJsonLists.decode(json)
            decoded.forEach { item ->
                assertEquals(item, item.trim(), "fuzz 迭代 #$i：嵌套元素解码结果不得含首尾空白")
                assertTrue(item.isNotEmpty(), "fuzz 迭代 #$i：嵌套元素解码结果不得含空串")
            }
        }
    }

    @Test
    fun `encodeDecodeAreDeterministicAndIdempotent`() {
        val random = Random(0x6F_2026)
        repeat(RANDOM_ITERATIONS) { i ->
            val items = List(random.nextInt(0, 12)) { randomText(random, 30) }
            val encoded = PrefsJsonLists.encode(items)
            assertEquals(encoded, PrefsJsonLists.encode(items), "fuzz 迭代 #$i：同输入必须同输出")
            val once = PrefsJsonLists.decode(encoded)
            assertEquals(
                once, PrefsJsonLists.decode(PrefsJsonLists.encode(once)),
                "fuzz 迭代 #$i：编解码必须幂等"
            )
        }
    }

    @Test
    fun `boundaryLengths`() {
        assertEquals("[]", PrefsJsonLists.encode(emptyList()))
        assertEquals(emptyList(), PrefsJsonLists.decode("[]"))
        assertEquals(emptyList(), PrefsJsonLists.decode(PrefsJsonLists.encode(listOf("", "   ", "\t"))))
        assertEquals(listOf("x"), PrefsJsonLists.decode(PrefsJsonLists.encode(listOf("  x  "))))
        val long = List(200) { "item-$it" }
        assertEquals(long, PrefsJsonLists.decode(PrefsJsonLists.encode(long)))
        // 控制字符（\r）经 JSON 转义往返不丢。
        assertEquals(listOf("a\rb"), PrefsJsonLists.decode(PrefsJsonLists.encode(listOf("a\rb"))))
    }
}
