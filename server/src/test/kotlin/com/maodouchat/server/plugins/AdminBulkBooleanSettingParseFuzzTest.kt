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
import kotlin.test.assertNull

class AdminBulkBooleanSettingParseFuzzTest {

    private companion object {
        private val KEYS = listOf("showStatus", "showOnline", "searchable")
        private const val ITERATIONS = 150
    }

    private fun randomString(random: Random, length: Int): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 _-.,!?~\t\n"
        return buildString { repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) } }
    }

    private fun randomName(random: Random): String {
        val name = randomString(random, random.nextInt(1, 20)).trim().ifEmpty { "f" }
        return if (name in KEYS) "unknown_" + name else name
    }

    private fun randomScalar(random: Random): JsonElement = when (random.nextInt(9)) {
        0 -> JsonPrimitive(listOf("true", "false", "1", "0", "yes", "no", "on", "off").random(random))
        1 -> JsonPrimitive(listOf("TRUE", "Off", "Yes", "NO").random(random))
        2 -> JsonPrimitive(random.nextBoolean())
        3 -> JsonPrimitive(random.nextInt(-5, 5))
        4 -> JsonPrimitive(randomString(random, random.nextInt(0, 12)))
        5 -> JsonNull
        6 -> JsonArray(List(random.nextInt(0, 3)) { JsonPrimitive(random.nextInt(5)) })
        7 -> JsonObject(mapOf(randomName(random) to JsonPrimitive(1)))
        else -> JsonPrimitive("2")
    }

    // 旧内联写法的逐字复刻（when 逻辑搬出，不含路由的 return@post），只供等价性比对。
    private fun oldBooleanWay(obj: JsonObject, key: String): Boolean? =
        when (obj[key]?.jsonPrimitive?.content?.lowercase()) {
            "false", "0", "no", "off" -> false
            "true", "1", "yes", "on" -> true
            else -> null
        }

    // 新旧都可能抛（对象/数组/JsonNull 在 jsonPrimitive 处大声失败）：比对成功态与结果。
    private fun assertSameBoolean(obj: JsonObject, key: String, msg: String) {
        val oldResult = runCatching { oldBooleanWay(obj, key) }
        val newResult = runCatching { parseAdminBulkBooleanSetting(obj, key) }
        assertEquals(oldResult.isSuccess, newResult.isSuccess, "$msg: success-ness must match")
        if (oldResult.isSuccess) assertEquals(oldResult.getOrNull(), newResult.getOrNull(), msg)
    }

    @Test
    fun `old and new agree on seeded random payloads`() {
        val random = Random(18002)
        repeat(ITERATIONS) { i ->
            val entries = mutableListOf<Pair<String, JsonElement>>()
            repeat(random.nextInt(0, 8)) { entries += randomName(random) to randomScalar(random) }
            KEYS.forEach { key -> entries += key to randomScalar(random) }
            val obj = JsonObject(entries.toMap())
            KEYS.forEach { key ->
                assertSameBoolean(obj, key, "iteration " + i + ", key " + key)
            }
        }
    }

    @Test
    fun `accepted literals are pinned`() {
        val trueWords = listOf("true", "1", "yes", "on", "TRUE", "Off", "Yes", "NO")
        val falseWords = listOf("false", "0", "no", "off")
        trueWords.forEach { word ->
            assertEquals(true, parseAdminBulkBooleanSetting(JsonObject(mapOf("k" to JsonPrimitive(word))), "k"),
                "word $word must parse true")
        }
        falseWords.forEach { word ->
            assertEquals(false, parseAdminBulkBooleanSetting(JsonObject(mapOf("k" to JsonPrimitive(word))), "k"),
                "word $word must parse false")
        }
    }

    @Test
    fun `missing or misspelled yields null instead of defaulting`() {
        assertNull(parseAdminBulkBooleanSetting(JsonObject(emptyMap()), "showStatus"))
        assertNull(parseAdminBulkBooleanSetting(JsonObject(mapOf("showStatus" to JsonPrimitive("2"))), "showStatus"))
        assertNull(parseAdminBulkBooleanSetting(JsonObject(mapOf("showStatus" to JsonPrimitive(" true"))), "showStatus"))
    }
}
