package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BotLogEventParseFuzzTest {

    private companion object {
        /** logEvent 解析涉及的全部已知字段名：随机名必须避开它们。 */
        private val KNOWN_FIELD_NAMES = setOf("event", "name", "chatId", "userId")
        private const val ITERATIONS = 150
    }

    private fun randomFieldName(random: Random): String {
        val stems = listOf(
            "future", "x", "v9", "extra", "unknown", "meta", "debug", "tmp",
            "clientExt", "appExt", "exp", "flag",
        )
        var name = "${stems.random(random)}_${random.nextInt(10000)}"
        while (name in KNOWN_FIELD_NAMES) name = "z$name"
        return name
    }

    private fun randomJsonValue(random: Random, depth: Int): JsonElement {
        val leafKinds = 5 // bool / long / double / string / null
        return when (random.nextInt(if (depth <= 0) leafKinds else leafKinds + 2)) {
            0 -> JsonPrimitive(random.nextBoolean())
            1 -> JsonPrimitive(random.nextLong())
            2 -> JsonPrimitive(random.nextDouble())
            3 -> JsonPrimitive("str_${random.nextInt(100000)}_${random.nextLong()}")
            4 -> JsonNull
            5 -> JsonArray(List(random.nextInt(1, 4)) { randomJsonValue(random, depth - 1) })
            else -> JsonObject(
                (0 until random.nextInt(1, 4))
                    .associate { randomFieldName(random) to randomJsonValue(random, depth - 1) }
            )
        }
    }

    /** 顶层注入 1–5 个未知字段。 */
    private fun injectUnknownFields(base: JsonObject, random: Random): JsonObject {
        val fields = base.toMutableMap()
        repeat(random.nextInt(1, 6)) {
            fields[randomFieldName(random)] = randomJsonValue(random, 2)
        }
        return JsonObject(fields)
    }

    private fun fieldsOf(obj: JsonObject): BotLogEventFields {
        val result = parseBotLogEventFields(obj)
        assertTrue(result is BotLogEventFieldsResult.Ok, "合法请求必须解析成功，实际 $result")
        return result.fields
    }

    @Test
    fun `logEvent parse survives seeded unknown-field fuzz`() {
        val random = Random(0x2E55_0E55)
        repeat(ITERATIONS) { i ->
            val event = "event-$i"
            val chatId = "chat-$i"
            val userId = "user-$i"
            val base = JsonObject(
                mapOf(
                    "event" to JsonPrimitive(event),
                    "chatId" to JsonPrimitive(chatId),
                    "userId" to JsonPrimitive(userId),
                )
            )
            val obj = injectUnknownFields(base, random)
            val fields = fieldsOf(obj)
            assertEquals(
                BotLogEventFields(event, chatId, userId),
                fields,
                "fuzz 迭代 #$i：已知字段必须与注入前完全一致，未知键必须被忽略",
            )
        }
    }

    @Test
    fun `logEvent wrong-typed known fields fail loudly`() {
        // 四个已知字段显式给出但不是 JSON 字符串 → InvalidType（路由层映射 400，不是 500）
        for (name in KNOWN_FIELD_NAMES) {
            for (bad in listOf<JsonElement>(JsonPrimitive(42), JsonPrimitive(true), JsonObject(emptyMap()), JsonArray(emptyList()), JsonNull)) {
                val result = parseBotLogEventFields(JsonObject(mapOf(name to bad)))
                assertTrue(
                    result is BotLogEventFieldsResult.InvalidType,
                    "$name 收 ${bad::class.simpleName} 必须判 InvalidType，实际 $result",
                )
            }
        }
    }

    @Test
    fun `logEvent name alias and event defaults are pinned`() {
        // name 别名：event 缺席时用 name
        val aliased = fieldsOf(JsonObject(mapOf("name" to JsonPrimitive("signup"))))
        assertEquals("signup", aliased.event, "event 缺席时必须回退到 name")
        // event 优先于 name
        val both = fieldsOf(
            JsonObject(mapOf("event" to JsonPrimitive("click"), "name" to JsonPrimitive("signup")))
        )
        assertEquals("click", both.event, "event 与 name 并存时 event 优先")
        // 双缺席/空白回 "custom"
        assertEquals("custom", fieldsOf(JsonObject(emptyMap())).event, "空对象 event 必须回 custom")
        val blankEvent = fieldsOf(JsonObject(mapOf("event" to JsonPrimitive("   "))))
        assertEquals("custom", blankEvent.event, "空白 event 必须回 custom")
        // 近似字段名被忽略
        val approx = fieldsOf(
            JsonObject(mapOf("Event" to JsonPrimitive("wrong"), "event2" to JsonPrimitive("wrong")))
        )
        assertEquals("custom", approx.event, "近似字段名 Event/event2 必须被忽略")
    }

    @Test
    fun `logEvent event truncation is pinned`() {
        val long = "x".repeat(100)
        val truncated = fieldsOf(JsonObject(mapOf("event" to JsonPrimitive(long))))
        assertEquals("x".repeat(40), truncated.event, "event 超长必须截断到 40 字符")
    }

    @Test
    fun `logEvent chatId and userId trim and drop blanks`() {
        // trim 后置空：空白字符串按缺省处理
        val blankIds = fieldsOf(
            JsonObject(
                mapOf(
                    "event" to JsonPrimitive("e"),
                    "chatId" to JsonPrimitive("   "),
                    "userId" to JsonPrimitive(""),
                )
            )
        )
        assertNull(blankIds.chatId, "空白 chatId 必须被丢弃")
        assertNull(blankIds.userId, "空串 userId 必须被丢弃")
        // 缺席同样回 null
        val missing = fieldsOf(JsonObject(mapOf("event" to JsonPrimitive("e"))))
        assertNull(missing.chatId, "缺席 chatId 必须回 null")
        assertNull(missing.userId, "缺席 userId 必须回 null")
        // trim 保留真实值
        val padded = fieldsOf(JsonObject(mapOf("chatId" to JsonPrimitive("  c1  "))))
        assertEquals("c1", padded.chatId, "chatId 必须 trim")
    }
}
