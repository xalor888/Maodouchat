package com.maodouchat.server.plugins

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 管理后台用户管理子域路由（`POST /users/{id}/sessions/revoke`、
 * `POST /broadcast`，`AdminManagementRouting.kt`）
 * 请求体 JSON 对象信封解析的**模糊兼容性测试**（清单 Q01「协议模型向前/向后兼容与
 * fuzz 测试」的 admin 侧专项评估**第二块**）。
 *
 * 本测试直接钉住纯函数 ([parseAdminManagementJsonEnvelopeOrNull]) 的生产语义：
 *
 * - **固定种子** [Random]：CI 上确定性可复跑，150 个随机对象 payload。
 * - 随机值覆盖布尔 / 整数 / 浮点 / 字符串 / null / 数组 / 嵌套对象（深度 ≤ 2），
 *   随机键避开已知字段（`tokenHashPrefix` / `all` / `text` / `title`）——
 *   测的是「未知键全部保留交由字段抽取取舍」的恒等性，不是已知字段语义。
 * - payload 由「JsonObject 程序化构造再 encode 成 body 字符串」得到（不拼字符串）——
 *   注入本身永不破坏 JSON 语法，红只可能来自解析侧。
 * - 钉住 **吞异常怪语义**：坏 JSON（截断 / 空串 / 纯空白 / 语法错误）→ `null`
 *   （处理器报 400 `"invalid json"`；两端点共用此判定；sessions/revoke 的
 *   「空 body 宽容」在处理器侧 `if (body.isBlank()) null`，不在纯函数里，
 *   本测试只钉纯函数本身）。
 * - 钉住 **顶层非对象一律 null**：数组 / 字符串 / 数字 / 布尔 /
 *   显式 JSON null（`JsonNull` 是 `JsonPrimitive`，`.jsonObject` 处抛
 *   `IllegalArgumentException`，**大声失败**被 `runCatching` 吞掉）→ `null`。
 * - 钉住 **对象恒等**：合法对象逐字原样返回（不深拷贝、不排序、键与值逐一相等）。
 *
 * 本轮测试代码延续规避字符串模板内嵌套引号写法（消息文案用 `+` 拼接）。
 */
class AdminManagementJsonEnvelopeParseFuzzTest {

    private companion object {
        /** 解析涉及的全部已知字段名：随机名必须避开它们。 */
        private val KNOWN_FIELD_NAMES = setOf(
            "tokenHashPrefix",
            "all",
            "text",
            "title",
        )
        private const val ITERATIONS = 150
    }

    private fun randomScalar(random: Random): JsonElement = when (random.nextInt(7)) {
        0 -> JsonPrimitive(random.nextBoolean())
        1 -> JsonPrimitive(random.nextInt(-1000, 1000))
        2 -> JsonPrimitive(random.nextDouble(-1000.0, 1000.0))
        3 -> JsonPrimitive(randomString(random, random.nextInt(0, 40)))
        4 -> JsonNull
        5 -> JsonArray(List(random.nextInt(0, 4)) { randomScalar(random) })
        else -> JsonObject(mapOf(randomName(random) to randomScalar(random)))
    }

    private fun randomString(random: Random, length: Int): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 _-.,!?~"
        return buildString { repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) } }
    }

    private fun randomName(random: Random): String {
        val name = randomString(random, random.nextInt(1, 20))
        return if (name in KNOWN_FIELD_NAMES) "unknown_" + name else name
    }

    @Test
    fun `random object bodies round-trip identically with unknown keys preserved`() {
        val random = Random(18032)
        repeat(ITERATIONS) { i ->
            val obj = JsonObject(
                List(random.nextInt(0, 12)) { randomName(random) to randomScalar(random) }.toMap()
            )
            val body = Json.encodeToString(JsonObject.serializer(), obj)
            val parsed = parseAdminManagementJsonEnvelopeOrNull(body)
            assertNotNull(parsed, "iteration " + i + ": valid object body must parse")
            assertEquals(obj, parsed, "iteration " + i + ": object must round-trip identically")
        }
    }

    @Test
    fun `malformed bodies map to null`() {
        val badBodies = listOf(
            "",
            "   ",
            "\n\t ",
            "{",
            "{\"a\":",
            "{\"a\": 1,",
            "[1, 2",
            "{\"a\": \"unterminated}",
            "not json at all",
            "{\"a\": 1}}",
        )
        badBodies.forEachIndexed { index, body ->
            assertNull(
                parseAdminManagementJsonEnvelopeOrNull(body),
                "bad body #" + index + " must map to null",
            )
        }
    }

    @Test
    fun `non-object top-level elements map to null`() {
        val bodies = listOf(
            "[1, 2, 3]",
            "\"just a string\"",
            "42",
            "-3.5",
            "true",
            "null",
            "{} ",
        )
        val expected = listOf(true, true, true, true, true, true, false)
        bodies.forEachIndexed { index, body ->
            val parsed = parseAdminManagementJsonEnvelopeOrNull(body)
            if (expected[index]) {
                assertNull(parsed, "top-level non-object body #" + index + " must map to null")
            } else {
                assertNotNull(parsed, "trailing-whitespace object body must still parse")
                assertTrue(parsed.isEmpty(), "empty object body must parse to empty object")
            }
        }
    }

    @Test
    fun `explicit nested json null inside object is preserved`() {
        val obj = JsonObject(mapOf("a" to JsonNull, "b" to JsonPrimitive("x")))
        val parsed = parseAdminManagementJsonEnvelopeOrNull(Json.encodeToString(JsonObject.serializer(), obj))
        assertNotNull(parsed)
        assertEquals(obj, parsed)
    }
}
