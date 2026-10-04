package com.maodouchat.server.plugins

import com.maodouchat.server.service.DispositionService
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class AdminBulkDaysParseFuzzTest {

    private companion object {
        private const val ITERATIONS = 150
        // 生产侧五个调用点的 (minDays, maxDays)，逐字抄自 AdminBulkRouting。
        private val BOUND_PAIRS = listOf(
            1 to DispositionService.MAX_BAN_DAYS,
            1 to 365,
            0 to DispositionService.MAX_MESSAGE_RESTRICT_DAYS,
            0 to DispositionService.MAX_POST_RESTRICT_DAYS,
            1 to DispositionService.MAX_MESSAGE_RESTRICT_DAYS,
        )
    }

    private fun randomString(random: Random, length: Int): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 _-.,!?~\t\n"
        return buildString { repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) } }
    }

    private fun randomName(random: Random): String {
        val name = randomString(random, random.nextInt(1, 20)).trim().ifEmpty { "f" }
        return if (name == "days") "unknown_" + name else name
    }

    private fun randomScalar(random: Random): JsonElement = when (random.nextInt(8)) {
        0 -> JsonPrimitive(random.nextInt(-50, 5000))
        1 -> JsonPrimitive(random.nextLong(-100_000L, 100_000L))
        2 -> JsonPrimitive(random.nextInt(-50, 5000).toString())
        3 -> JsonPrimitive(randomString(random, random.nextInt(0, 12)))
        4 -> JsonPrimitive(random.nextBoolean())
        5 -> JsonNull
        6 -> JsonArray(List(random.nextInt(0, 4)) { JsonPrimitive(random.nextInt(10)) })
        else -> JsonObject(mapOf(randomName(random) to JsonPrimitive(1)))
    }

    // 旧内联写法的逐字复刻，只供等价性比对。
    private fun oldDaysWay(obj: JsonObject, minDays: Int, maxDays: Int): Int =
        (obj["days"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1).coerceIn(minDays, maxDays)

    // 新旧都可能抛（对象/数组/JsonNull 在 jsonPrimitive 处大声失败）：比对成功态与结果。
    private fun assertSameDays(obj: JsonObject, minDays: Int, maxDays: Int, msg: String) {
        val oldResult = runCatching { oldDaysWay(obj, minDays, maxDays) }
        val newResult = runCatching { parseAdminBulkDays(obj, minDays, maxDays) }
        assertEquals(oldResult.isSuccess, newResult.isSuccess, "$msg: success-ness must match")
        if (oldResult.isSuccess) assertEquals(oldResult.getOrNull(), newResult.getOrNull(), msg)
    }

    @Test
    fun `old and new agree on seeded random payloads`() {
        val random = Random(18001)
        repeat(ITERATIONS) { i ->
            val entries = mutableListOf<Pair<String, JsonElement>>()
            repeat(random.nextInt(0, 8)) { entries += randomName(random) to randomScalar(random) }
            entries += "days" to randomScalar(random)
            val obj = JsonObject(entries.toMap())
            BOUND_PAIRS.forEachIndexed { bi, (minDays, maxDays) ->
                assertSameDays(obj, minDays, maxDays, "iteration " + i + ", bounds " + bi)
            }
        }
    }

    @Test
    fun `missing or garbage days defaults to one`() {
        assertEquals(1, parseAdminBulkDays(JsonObject(emptyMap()), 1, 365))
        assertEquals(1, parseAdminBulkDays(JsonObject(mapOf("days" to JsonPrimitive("abc"))), 1, 365))
        assertEquals(1, parseAdminBulkDays(JsonObject(emptyMap()), 0, 90))
    }

    @Test
    fun `bounds are pinned per call site`() {
        val huge = JsonObject(mapOf("days" to JsonPrimitive(99999)))
        val negative = JsonObject(mapOf("days" to JsonPrimitive(-5)))
        assertEquals(DispositionService.MAX_BAN_DAYS, parseAdminBulkDays(huge, 1, DispositionService.MAX_BAN_DAYS))
        assertEquals(365, parseAdminBulkDays(huge, 1, 365))
        assertEquals(1, parseAdminBulkDays(negative, 1, 365))
        assertEquals(0, parseAdminBulkDays(negative, 0, DispositionService.MAX_MESSAGE_RESTRICT_DAYS))
        assertEquals(7, parseAdminBulkDays(JsonObject(mapOf("days" to JsonPrimitive("7"))), 1, 365))
    }
}
