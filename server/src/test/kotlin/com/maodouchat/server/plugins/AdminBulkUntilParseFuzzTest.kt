package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class AdminBulkUntilParseFuzzTest {

    private companion object {
        private const val SEED = 18041
        private const val ITERATIONS = 150
        private val NOISE_NAMES = listOf("userIds", "days", "reasonCode", "foo", "until2", "x")
    }

    private fun randomString(random: Random, length: Int): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 _-.,!?~\t\n"
        return buildString { repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) } }
    }

    private fun randomTimestampValue(random: Random): JsonElement = when (random.nextInt(10)) {
        0 -> JsonPrimitive(random.nextLong(-100_000L, 9_000_000_000_000L))
        1 -> JsonPrimitive(random.nextLong(-100_000L, 9_000_000_000_000L).toString())
        2 -> JsonPrimitive(randomString(random, random.nextInt(0, 12)))
        3 -> JsonPrimitive(random.nextInt(-5, 5))
        4 -> JsonPrimitive(random.nextBoolean())
        5 -> JsonPrimitive("1.5")
        6 -> JsonNull
        7 -> JsonArray(List(random.nextInt(0, 3)) { JsonPrimitive(random.nextInt(5)) })
        8 -> JsonObject(mapOf("n" to JsonPrimitive(1)))
        else -> JsonPrimitive("99999999999999999999999")
    }

    private fun randomObj(random: Random, keys: List<String>): JsonObject {
        val map = mutableMapOf<String, JsonElement>()
        keys.forEach { if (random.nextBoolean()) map[it] = randomTimestampValue(random) }
        repeat(random.nextInt(0, 3)) { map[NOISE_NAMES.random(random)] = randomTimestampValue(random) }
        return JsonObject(map)
    }

    // 旧内联写法的逐字复刻，只供等价性比对。
    private fun oldMsgRestrictUntilWay(obj: JsonObject): Long =
        (obj["until"]?.jsonPrimitive?.content?.toLongOrNull()
            ?: obj["untilMs"]?.jsonPrimitive?.content?.toLongOrNull()
            ?: 0L).coerceAtLeast(0L)

    private fun oldSuspendUntilWay(obj: JsonObject): Long =
        obj["suspendedUntil"]?.jsonPrimitive?.content?.toLongOrNull()
            ?: obj["until"]?.jsonPrimitive?.content?.toLongOrNull()
            ?: 0L

    private fun assertSame(obj: JsonObject, old: () -> Long, new: () -> Long, msg: String) {
        val oldResult = runCatching { old() }
        val newResult = runCatching { new() }
        assertEquals(oldResult.isSuccess, newResult.isSuccess, "$msg: success-ness must match")
        if (oldResult.isSuccess) assertEquals(oldResult.getOrThrow(), newResult.getOrThrow(), msg)
    }

    @Test
    fun `msg-restrict-until alias chain matches old inline`() {
        val random = Random(SEED)
        repeat(ITERATIONS) { i ->
            val obj = randomObj(random, listOf("until", "untilMs"))
            assertSame(obj, { oldMsgRestrictUntilWay(obj) }, { parseAdminBulkTimestampMs(obj, "until", "untilMs").coerceAtLeast(0L) }, "iter $i")
        }
    }

    @Test
    fun `suspend-until alias chain matches old inline`() {
        val random = Random(SEED + 1)
        repeat(ITERATIONS) { i ->
            val obj = randomObj(random, listOf("suspendedUntil", "until"))
            assertSame(obj, { oldSuspendUntilWay(obj) }, { parseAdminBulkTimestampMs(obj, "suspendedUntil", "until") }, "iter $i")
        }
    }

    @Test
    fun `alias priority and clamp semantics are pinned`() {
        // 别名优先级：until 在前，即使它是坏字符串也先消费它的"解析失败"，落到 untilMs。
        assertEquals(42L, parseAdminBulkTimestampMs(JsonObject(mapOf("until" to JsonPrimitive("abc"), "untilMs" to JsonPrimitive(42))), "until", "untilMs"))
        // suspendedUntil 优先于 until。
        assertEquals(7L, parseAdminBulkTimestampMs(JsonObject(mapOf("suspendedUntil" to JsonPrimitive(7), "until" to JsonPrimitive(9))), "suspendedUntil", "until"))
        // 全缺键/全解析失败 → 0。
        assertEquals(0L, parseAdminBulkTimestampMs(JsonObject(mapOf("until" to JsonPrimitive("abc"))), "until", "untilMs"))
        assertEquals(0L, parseAdminBulkTimestampMs(JsonObject(emptyMap()), "suspendedUntil", "until"))
        // msg 侧的 coerceAtLeast(0L)：负时间戳钳为 0；suspend 侧保留负值走"invalid"判定。
        assertEquals(0L, parseAdminBulkTimestampMs(JsonObject(mapOf("until" to JsonPrimitive(-5))), "until", "untilMs").coerceAtLeast(0L))
        assertEquals(-5L, parseAdminBulkTimestampMs(JsonObject(mapOf("suspendedUntil" to JsonPrimitive(-5))), "suspendedUntil", "until"))
        // Long 范围外字符串解析失败 → 落到 0。
        assertEquals(0L, parseAdminBulkTimestampMs(JsonObject(mapOf("until" to JsonPrimitive("99999999999999999999999"))), "until", "untilMs"))
    }
}
