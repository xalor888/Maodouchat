package com.maodouchat.server.plugins

import com.maodouchat.server.model.AdminSessionRequest
import com.maodouchat.server.model.AssignUserTagsRequest
import com.maodouchat.server.model.CreateAnnouncementRequest
import com.maodouchat.server.model.CreateUserTagRequest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Admin 协议模型的**模糊兼容性测试**（清单 Q01「协议模型向前/向后兼容与 fuzz 测试」的
 * fuzz 部分，第二块；第一块 G344 只覆盖了 messaging-v2 的 send/ack，这里补上管理后台
 * 一侧——`adminJson` + 走 [receiveAdminJson] 解码的请求 DTO）。
 *
 * 设计要点（与 G344 同款）：
 * - **固定种子** [Random]：CI 上确定性可复跑；每个 DTO 100–150 个随机 payload。
 * - **随机名避开所有已知字段**（见 [KNOWN_FIELD_NAMES]）：否则测的是「重复键覆盖语义」，
 *   而不是「未知键忽略语义」。
 * - **随机值**覆盖布尔 / 整数 / 浮点 / 字符串 / null / 数组 / 嵌套对象（深度 ≤ 2）；
 *   注入位置为顶层（admin DTO 均无嵌套对象字段）。
 * - payload 由「合法请求先序列化成 JsonObject，再程序化注入未知字段」构造（不拼字符串）——
 *   注入本身永不破坏 JSON 语法，红只可能来自解码侧。
 * - 反证 `wrong-typed known fields still fail loudly`：`ignoreUnknownKeys = true`
 *   只放行**未知键**；已知字段类型错必须继续抛 [SerializationException]，不能悄悄吞掉坏数据。
 *   这对 [AdminSessionRequest] 尤其关键——它是管理会话提权入口，坏数据静默通过就是安全门洞。
 *
 * 注意：测试直接引用生产侧同一份 [adminJson] 配置——如果有人把
 * `ignoreUnknownKeys` 改回 `false`，这里的 fuzz 用例会立刻变红。
 */
class AdminModelsFuzzTest {

    private companion object {
        /** 被 fuzz 的五个 DTO 的全部已知字段名：随机名必须避开它们。 */
        private val KNOWN_FIELD_NAMES = setOf(
            "password", "totpCode",
            "title", "content", "level", "audience", "tagId", "startsAt", "expiresAt", "draft",
            "name", "color", "description", "riskLevel",
            "tagIds",
            "bannedUntil", "note", "reasonCode",
        )
        private const val SESSION_ITERATIONS = 150
        private const val ANNOUNCEMENT_ITERATIONS = 150
        private const val TAG_ITERATIONS = 100
        private const val STATUS_ITERATIONS = 100
    }

    private fun randomFieldName(random: Random): String {
        val stems = listOf(
            "future", "x", "v9", "extra", "unknown", "meta", "debug", "tmp",
            "adminExt", "srvExt", "exp", "flag",
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

    /** 顶层注入 1–5 个未知字段，返回 fuzz 后的 JSON 字符串。 */
    private fun injectUnknownFields(base: JsonObject, random: Random): String {
        val fields = base.toMutableMap()
        repeat(random.nextInt(1, 6)) {
            fields[randomFieldName(random)] = randomJsonValue(random, 2)
        }
        return adminJson.encodeToString(JsonElement.serializer(), JsonObject(fields))
    }

    private inline fun <reified T> fuzzedPayload(expected: T, random: Random): Pair<String, T> {
        val base = adminJson.parseToJsonElement(adminJson.encodeToString(expected)).jsonObject
        return injectUnknownFields(base, random) to expected
    }

    @Test
    fun `admin session request survives seeded unknown-field fuzz`() {
        val random = Random(0xAD1_2026)
        repeat(SESSION_ITERATIONS) { i ->
            val expected = AdminSessionRequest(password = "s3cr3t-$i", totpCode = "123456")
            val (payload, _) = fuzzedPayload(expected, random)
            val decoded = adminJson.decodeFromString<AdminSessionRequest>(payload)
            assertEquals(expected, decoded, "fuzz 迭代 #$i：已知字段必须与注入前完全一致")
        }
        // totpCode 缺省（null）时也必须扛住未知字段
        val random2 = Random(0xAD2_2026)
        repeat(50) { i ->
            val expected = AdminSessionRequest(password = "pw-$i")
            val (payload, _) = fuzzedPayload(expected, random2)
            val decoded = adminJson.decodeFromString<AdminSessionRequest>(payload)
            assertEquals(expected, decoded, "fuzz 迭代 #$i（无 totp）：已知字段必须与注入前完全一致")
        }
    }

    @Test
    fun `create announcement request survives seeded unknown-field fuzz`() {
        val random = Random(0xAD3_2026)
        repeat(ANNOUNCEMENT_ITERATIONS) { i ->
            val expected = CreateAnnouncementRequest(
                title = "标题 $i",
                content = "内容 $i",
                level = if (i % 2 == 0) "URGENT" else "INFO",
                audience = "ALL",
                tagId = if (i % 3 == 0) "t-$i" else null,
                startsAt = 1727000000000L + i,
                expiresAt = null,
                draft = i % 2 == 1,
            )
            val (payload, _) = fuzzedPayload(expected, random)
            val decoded = adminJson.decodeFromString<CreateAnnouncementRequest>(payload)
            assertEquals(expected, decoded, "fuzz 迭代 #$i：已知字段必须与注入前完全一致")
        }
    }

    @Test
    fun `user tag requests survive seeded unknown-field fuzz`() {
        val random = Random(0xAD4_2026)
        repeat(TAG_ITERATIONS) { i ->
            val expected = CreateUserTagRequest(
                name = "标签 $i",
                color = "#ff0000",
                description = if (i % 2 == 0) "描述 $i" else null,
                riskLevel = "HIGH",
            )
            val (payload, _) = fuzzedPayload(expected, random)
            val decoded = adminJson.decodeFromString<CreateUserTagRequest>(payload)
            assertEquals(expected, decoded, "fuzz 迭代 #$i：已知字段必须与注入前完全一致")
        }
        val random2 = Random(0xAD5_2026)
        repeat(TAG_ITERATIONS) { i ->
            val expected = AssignUserTagsRequest(tagIds = listOf("t1", "t2-$i"))
            val (payload, _) = fuzzedPayload(expected, random2)
            val decoded = adminJson.decodeFromString<AssignUserTagsRequest>(payload)
            assertEquals(expected, decoded, "fuzz 迭代 #$i：tagIds 列表必须与注入前完全一致")
        }
    }

    @Test
    fun `update user status request survives seeded unknown-field fuzz`() {
        val random = Random(0xAD6_2026)
        repeat(STATUS_ITERATIONS) { i ->
            val expected = UpdateUserStatusRequest(
                bannedUntil = if (i % 2 == 0) 1727000000000L + i else null,
                note = "note-$i",
                reasonCode = if (i % 3 == 0) "spam" else null,
            )
            val (payload, _) = fuzzedPayload(expected, random)
            val decoded = adminJson.decodeFromString<UpdateUserStatusRequest>(payload)
            assertEquals(expected, decoded, "fuzz 迭代 #$i：已知字段必须与注入前完全一致")
        }
    }

    @Test
    fun `wrong-typed known fields still fail loudly`() {
        // password 给数字：String 字段收数字必须抛，不能静默
        assertFailsWith<SerializationException>("password 类型错必须大声失败") {
            adminJson.decodeFromString<AdminSessionRequest>("""{"password":12345}""")
        }
        // totpCode 给对象：可空 String 字段收对象必须抛
        assertFailsWith<SerializationException>("totpCode 类型错必须大声失败") {
            adminJson.decodeFromString<AdminSessionRequest>(
                """{"password":"pw","totpCode":{"code":"123456"}}"""
            )
        }
        // tagIds 给字符串：List<String> 字段收字符串必须抛
        assertFailsWith<SerializationException>("tagIds 类型错必须大声失败") {
            adminJson.decodeFromString<AssignUserTagsRequest>("""{"tagIds":"t1,t2"}""")
        }
        // bannedUntil 给字符串：可空 Long 字段收字符串必须抛
        assertFailsWith<SerializationException>("bannedUntil 类型错必须大声失败") {
            adminJson.decodeFromString<UpdateUserStatusRequest>(
                """{"bannedUntil":"tomorrow"}"""
            )
        }
    }

    @Test
    fun `near-miss field names are treated as unknown keys`() {
        // 大小写/前后缀只差一点的字段名必须按未知键忽略，不能误读进已知字段
        val decoded = adminJson.decodeFromString<AdminSessionRequest>(
            """{"Password":"WRONG","password":"right","password2":"x","totpcode":"000000"}"""
        )
        assertEquals(
            AdminSessionRequest(password = "right", totpCode = null),
            decoded,
            "近似字段名必须被忽略，只有精确命中的 password 生效",
        )
    }
}
