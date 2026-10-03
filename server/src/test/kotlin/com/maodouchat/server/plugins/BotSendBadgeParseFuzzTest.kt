package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BotSendBadgeParseFuzzTest {

    private companion object {
        /** sendBadge 解析涉及的全部已知字段名：随机名必须避开它们。 */
        private val KNOWN_FIELD_NAMES = setOf(
            "chatId", "label", "value",
        )
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

    private fun fieldsOf(obj: JsonObject): BotSendBadgeFields {
        val result = parseBotSendBadgeFields(obj)
        assertTrue(result is BotSendBadgeFieldsResult.Ok, "合法请求必须解析成功，实际 $result")
        return result.fields
    }

    private fun validBase(
        chatId: String = "c1",
        label: String = "在线徽章",
        value: String = "v42",
    ): JsonObject = JsonObject(
        mapOf(
            "chatId" to JsonPrimitive(chatId),
            "label" to JsonPrimitive(label),
            "value" to JsonPrimitive(value),
        )
    )

    @Test
    fun `sendBadge parse survives seeded unknown-field fuzz`() {
        val random = Random(0xBD_2026)
        repeat(ITERATIONS) { i ->
            val chatId = "c-$i"
            val label = "label-$i"
            val value = "value-$i"
            val base = JsonObject(
                mapOf(
                    "chatId" to JsonPrimitive(chatId),
                    "label" to JsonPrimitive(label),
                    "value" to JsonPrimitive(value),
                )
            )
            val obj = injectUnknownFields(base, random)
            val fields = fieldsOf(obj)
            assertEquals(
                BotSendBadgeFields(chatId, label, value),
                fields,
                "fuzz 迭代 #$i：已知字段必须与注入前完全一致，未知键必须被忽略",
            )
        }
    }

    @Test
    fun `sendBadge required fields are enforced`() {
        // chatId 缺失 → MissingRequired
        assertTrue(
            parseBotSendBadgeFields(
                JsonObject(
                    mapOf(
                        "label" to JsonPrimitive("l"),
                        "value" to JsonPrimitive("v"),
                    )
                )
            ) is BotSendBadgeFieldsResult.MissingRequired,
            "chatId 缺失必须判缺",
        )
        // chatId 空白 → MissingRequired
        assertTrue(
            parseBotSendBadgeFields(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("   "),
                        "label" to JsonPrimitive("l"),
                        "value" to JsonPrimitive("v"),
                    )
                )
            ) is BotSendBadgeFieldsResult.MissingRequired,
            "chatId 空白必须判缺",
        )
        // label 缺失 → 不判缺，回 "badge"
        assertEquals(
            BotSendBadgeFields("c1", "badge", "v"),
            fieldsOf(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c1"),
                        "value" to JsonPrimitive("v"),
                    )
                )
            ),
            "label 缺省必须不判缺、回默认值",
        )
        // label 空白 → 不判缺，回 "badge"
        assertEquals(
            BotSendBadgeFields("c1", "badge", "v"),
            fieldsOf(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c1"),
                        "label" to JsonPrimitive("  "),
                        "value" to JsonPrimitive("v"),
                    )
                )
            ),
            "label 空白必须不判缺、回默认值",
        )
        // value 缺失 → 不判缺，回 ""（无默认值，特意钉住）
        assertEquals(
            BotSendBadgeFields("c1", "badge", ""),
            fieldsOf(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c1"),
                    )
                )
            ),
            "value 缺省必须不判缺、回空字符串",
        )
    }

    @Test
    fun `sendBadge label legacy quirks are pinned`() {
        // 不 trim：前导空格原样保留并计入 40 上限
        // 注：Kotlin 2.4.0（K2）下模板内 ${\"…\"} 转义引号报 Syntax error，
        // 故把 repeat 提到模板外，避免模板内嵌套引号。
        val xs = "x".repeat(36)
        val leading = "  徽章 $xs"
        val expected = leading.take(40)
        assertEquals(
            BotSendBadgeFields("c1", expected, "v"),
            fieldsOf(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c1"),
                        "label" to JsonPrimitive(leading),
                        "value" to JsonPrimitive("v"),
                    )
                )
            ),
            "label 不 trim：前导空格原样保留并计入 take(40)",
        )
        // 超长截 40（默认值回退在截断之前，这里用非空白长串验证截断本身）
        val long = "y".repeat(100)
        assertEquals(
            BotSendBadgeFields("c1", long.take(40), "v42"),
            fieldsOf(validBase(label = long)),
            "label 超长必须截断到 40",
        )
        // 显式 JSON null → 字面 "null"（非空白，ifBlank 不触发）
        assertEquals(
            BotSendBadgeFields("c1", "null", "v"),
            fieldsOf(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c1"),
                        "label" to JsonNull,
                        "value" to JsonPrimitive("v"),
                    )
                )
            ),
            "label 显式 null 必须得字面 \"null\"，不回默认值",
        )
    }

    @Test
    fun `sendBadge value quirks are pinned`() {
        // value 不 trim：前导空格原样保留并计入 80 上限
        val leading = "  值"
        assertEquals(
            BotSendBadgeFields("c1", "在线徽章", leading),
            fieldsOf(validBase(value = leading)),
            "value 不 trim：前导空格原样保留",
        )
        // value 超长截 80
        val long = "z".repeat(200)
        assertEquals(
            BotSendBadgeFields("c1", "在线徽章", long.take(80)),
            fieldsOf(validBase(value = long)),
            "value 超长必须截断到 80",
        )
        // value 显式 JSON null → 字面 "null"
        assertEquals(
            BotSendBadgeFields("c1", "徽章", "null"),
            fieldsOf(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c1"),
                        "label" to JsonPrimitive("徽章"),
                        "value" to JsonNull,
                    )
                )
            ),
            "value 显式 null 必须得字面 \"null\"",
        )
    }

    @Test
    fun `sendBadge content template is pinned`() {
        // 与原处理器逐字一致："**$label**: `$value`"
        assertEquals(
            "**在线徽章**: `v42`",
            buildBotBadgeContent("在线徽章", "v42"),
            "内容模板必须与原处理器逐字一致",
        )
        assertEquals(
            "**badge**: ``",
            buildBotBadgeContent("badge", ""),
            "默认值组合的内容模板必须逐字一致",
        )
        // 怪语义组合：显式 null 字面 + 前导空格不 trim
        assertEquals(
            "**null**: `  x`",
            buildBotBadgeContent("null", "  x"),
            "怪语义组合必须逐字透传",
        )
    }

    @Test
    fun `sendBadge wrong-typed known fields fail loudly`() {
        // 对象型 chatId → ?.jsonPrimitive 抛 IllegalArgumentException
        assertFailsWith<IllegalArgumentException>(
            "对象型 chatId 必须大声失败",
        ) {
            parseBotSendBadgeFields(
                JsonObject(
                    mapOf(
                        "chatId" to JsonObject(mapOf("nested" to JsonPrimitive("x"))),
                    )
                )
            )
        }
        // 数组型 label → ?.jsonPrimitive 抛 IllegalArgumentException
        assertFailsWith<IllegalArgumentException>(
            "数组型 label 必须大声失败",
        ) {
            parseBotSendBadgeFields(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c1"),
                        "label" to JsonArray(listOf(JsonPrimitive("a"))),
                    )
                )
            )
        }
        // 对象型 value → ?.jsonPrimitive 抛 IllegalArgumentException
        assertFailsWith<IllegalArgumentException>(
            "对象型 value 必须大声失败",
        ) {
            parseBotSendBadgeFields(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c1"),
                        "value" to JsonObject(mapOf("nested" to JsonPrimitive("x"))),
                    )
                )
            )
        }
    }
}
