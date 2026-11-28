package com.maodouchat.domain.groups

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GroupPlayJsonFuzzTest {

    private companion object {
        private const val ARRAY_ITERATIONS = 150
        private const val RAW_SWEEP_ITERATIONS = 200
        private val WRAPPER_KEYS = listOf("items", "data", "polls", "pks", "chains", "ranking", "list")
        private val ITEM_KEYS = listOf("id", "userId", "question")
        private val OTHER_KEYS = listOf("zzz", "foo", "bar", "a", "b", "note", "extra")
        private const val GARBAGE_ALPHABET = "abcXYZ019{}[]\",: truefalsn"
    }

    private fun randomFieldName(random: Random, avoid: Set<String>): String {
        val stems = listOf("future", "x", "v9", "extra", "unknown", "meta", "debug", "tmp")
        var name = "${stems.random(random)}_${random.nextInt(10000)}"
        while (name in avoid) name = "z$name"
        return name
    }

    private fun randomJsonValue(random: Random, depth: Int): JsonElement {
        val leafKinds = 5
        return when (random.nextInt(if (depth <= 0) leafKinds else leafKinds + 2)) {
            0 -> JsonPrimitive(random.nextBoolean())
            1 -> JsonPrimitive(random.nextLong())
            2 -> JsonPrimitive(random.nextDouble())
            3 -> JsonPrimitive("str_${random.nextInt(100000)}_${random.nextLong()}")
            4 -> JsonNull
            5 -> JsonArray(List(random.nextInt(0, 4)) { randomJsonValue(random, depth - 1) })
            else -> JsonObject(
                (0 until random.nextInt(0, 4))
                    .associate { randomFieldName(random, emptySet()) to randomJsonValue(random, depth - 1) }
            )
        }
    }

    private fun randomGarbageText(random: Random, maxLength: Int = 80): String =
        buildString {
            repeat(random.nextInt(0, maxLength + 1)) {
                append(GARBAGE_ALPHABET[random.nextInt(GARBAGE_ALPHABET.length)])
            }
        }

    @Test
    fun `array returns bare json arrays unchanged`() {
        val random = Random(0x61_2026)
        repeat(ARRAY_ITERATIONS) { i ->
            val arr = JsonArray(List(random.nextInt(0, 5)) { randomJsonValue(random, 2) })
            assertEquals(arr, GroupPlayJson.array(arr.toString()), "fuzz 迭代 #$i：裸数组必须原样返回")
        }
    }

    @Test
    fun `array unwraps wrapper keys`() {
        val random = Random(0x62_2026)
        repeat(ARRAY_ITERATIONS) { i ->
            val key = WRAPPER_KEYS.random(random)
            val child = JsonArray(List(random.nextInt(0, 4)) { randomJsonValue(random, 2) })
            val obj = JsonObject(mapOf(key to child))
            assertEquals(child, GroupPlayJson.array(obj.toString()), "fuzz 迭代 #$i：包装键 $key 必须解包")
        }
    }

    @Test
    fun `array wraps item-like objects in a single element array`() {
        val random = Random(0x63_2026)
        repeat(ARRAY_ITERATIONS) { i ->
            val key = ITEM_KEYS.random(random)
            val obj = JsonObject(
                mapOf(
                    key to JsonPrimitive("v_$i"),
                    randomFieldName(random, WRAPPER_KEYS.toSet() + ITEM_KEYS.toSet()) to randomJsonValue(random, 1)
                )
            )
            assertEquals(JsonArray(listOf(obj)), GroupPlayJson.array(obj.toString()), "fuzz 迭代 #$i：类条目对象必须包成单元素数组")
        }
    }

    @Test
    fun `array rejects objects without wrapper or item keys`() {
        val random = Random(0x64_2026)
        repeat(ARRAY_ITERATIONS) { i ->
            val fields = (0 until random.nextInt(1, 4)).associate {
                OTHER_KEYS.random(random) to randomJsonValue(random, 1)
            }
            val obj = JsonObject(fields)
            assertFailsWith<IllegalStateException>("fuzz 迭代 #$i：无包装键、无条目键的对象必须大声失败：$obj") {
                GroupPlayJson.array(obj.toString())
            }
        }
    }

    @Test
    fun `array rejects json primitives and null`() {
        val random = Random(0x65_2026)
        repeat(ARRAY_ITERATIONS) { i ->
            val element: JsonElement = when (random.nextInt(4)) {
                0 -> JsonPrimitive(random.nextBoolean())
                1 -> JsonPrimitive(random.nextLong())
                2 -> JsonPrimitive("str_${random.nextInt(1000)}")
                else -> JsonNull
            }
            // jsonObject on a non-object throws IllegalArgumentException.
            assertFailsWith<IllegalArgumentException>("fuzz 迭代 #$i：原始值必须大声失败：$element") {
                GroupPlayJson.array(element.toString())
            }
        }
    }

    @Test
    fun `malformed json fails loudly instead of degrading to empty`() {
        listOf("{oops", "{", "[1,2", "{\"a\":}", "[}", "{\"items\":}").forEach { text ->
            assertFailsWith<SerializationException>("text=$text 必须抛 SerializationException") {
                GroupPlayJson.array(text)
            }
        }
    }

    @Test
    fun `random raw strings only ever return an array or throw the two known failures`() {
        val random = Random(0x66_2026)
        repeat(RAW_SWEEP_ITERATIONS) { i ->
            val text = randomGarbageText(random)
            val result = runCatching { GroupPlayJson.array(text) }
            result.onFailure { failure ->
                assertTrue(
                    failure is IllegalStateException || failure is IllegalArgumentException,
                    "fuzz 迭代 #$i：只允许 IllegalStateException/IllegalArgumentException，实际 ${failure::class.simpleName}：$text"
                )
            }
        }
    }

    @Test
    fun `errorMessage never throws on random strings`() {
        val random = Random(0x67_2026)
        repeat(RAW_SWEEP_ITERATIONS) { i ->
            val text = randomGarbageText(random)
            val result = GroupPlayJson.errorMessage(text)
            assertTrue(result == null || result.isNotBlank(), "fuzz 迭代 #$i：errorMessage 只能返回 null 或非空串：$text")
        }
        assertEquals("oops", GroupPlayJson.errorMessage("""{"error":"oops","message":"m"}"""))
        assertEquals("m", GroupPlayJson.errorMessage("""{"message":"m"}"""))
    }

    @Test
    fun `parseObjectList maps every object element and skips the rest`() {
        val random = Random(0x68_2026)
        repeat(ARRAY_ITERATIONS) { i ->
            val elements = List(random.nextInt(0, 5)) {
                when (random.nextInt(3)) {
                    0 -> JsonObject(mapOf("id" to JsonPrimitive("id_${random.nextInt(1000)}")))
                    1 -> JsonObject(mapOf(OTHER_KEYS.random(random) to randomJsonValue(random, 1)))
                    else -> randomJsonValue(random, 1)
                }
            }
            val expected = elements.filterIsInstance<JsonObject>().map { obj ->
                (obj["id"] as? JsonPrimitive)?.contentOrNull ?: ""
            }
            val result = GroupPlayJsonPolicy.parseObjectList(JsonArray(elements).toString()) { it.optString("id") }
            assertEquals(expected, result, "fuzz 迭代 #$i：对象元素逐一映射、非对象跳过")
        }
    }

    @Test
    fun `parsePolls maps poll objects and ignores non-objects`() {
        val random = Random(0x69_2026)
        repeat(100) { i ->
            val count = random.nextInt(0, 4)
            val polls = List(count) {
                JsonObject(
                    mapOf(
                        "id" to JsonPrimitive("p_${random.nextInt(1000)}"),
                        "question" to JsonPrimitive("q_${random.nextInt(1000)}"),
                        "options" to JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b"))),
                        "counts" to JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(2)))
                    )
                )
            }
            val elements = polls + List(random.nextInt(0, 3)) { randomJsonValue(random, 1) }
            val result = GroupPlayJsonPolicy.parsePolls(JsonArray(elements).toString())
            assertEquals(elements.count { it is JsonObject }, result.size, "fuzz 迭代 #$i：对象元素都成条目、非对象跳过")
            result.take(polls.size).forEachIndexed { idx, item ->
                assertEquals((polls[idx]["id"] as JsonPrimitive).content, item.id)
                assertEquals((polls[idx]["question"] as JsonPrimitive).content, item.question)
                assertEquals(listOf("a", "b"), item.options)
                assertEquals(listOf(1, 2), item.counts)
            }
        }
    }

    @Test
    fun `parseObjectList lets malformed json escape instead of degrading to empty`() {
        // 现行行为钉住：parseObjectList(text) 对畸形 JSON 没有 runCatching 兜底，
        // JsonDecodingException 逃逸到调用方；四处生产调用方（GroupChain/Checkin/Pk/Poll
        // 四屏）目前各自包了 runCatching 兜底，故无现网崩溃路径，本轮只钉行为、不改生产。
        assertFailsWith<SerializationException> {
            GroupPlayJsonPolicy.parseObjectList("{oops") { it.optString("id") }
        }
        assertFailsWith<SerializationException> {
            GroupPlayJsonPolicy.parsePolls("[1,2")
        }
    }
}
