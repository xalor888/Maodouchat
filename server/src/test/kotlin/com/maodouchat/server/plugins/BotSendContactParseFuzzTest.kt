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

/**
 * Bot `sendContact` 请求体手写解析的**模糊兼容性测试**（清单 Q01「协议模型向前/向后兼容与
 * fuzz 测试」的 bot 侧专项评估，G355 `sendMessage`、G355-2 `editMessage`、
 * `sendDocument`、`sendVoice`、`sendPhoto`、`sendVideo`、`sendLocation`、
 * `sendSticker` 之后**第九块**）。
 *
 * Bot 路由没有 typed DTO——请求体是 `receiveBoundedTextOrEmpty()` 拿到的原始字符串，
 * 经 `Json.parseToJsonElement(body).jsonObject` 再手写抽取。本轮把原来内联在
 * `/api/bot/sendContact` 处理器里的抽取 / 校验 / 内容组装逻辑收敛为纯函数
 * ([parseBotSendContactFields] / [buildBotContactContent])
 * （生产侧零行为改动：功能门 `contact_card_enabled` 与成员检查仍在处理器里），
 * 本测试直接钉住这些函数的生产语义：
 *
 * - **固定种子** [Random]：CI 上确定性可复跑，150 个随机 payload。
 * - **随机名避开所有已知字段**（见 [KNOWN_FIELD_NAMES]）：否则测的是「重复键覆盖语义」，
 *   而不是「未知键忽略语义」。
 * - **随机值**覆盖布尔 / 整数 / 浮点 / 字符串 / null / 数组 / 嵌套对象（深度 ≤ 2），
 *   注入位置为顶层。
 * - payload 由「合法请求先构造成 JsonObject，再程序化注入未知字段」得到（不拼字符串）——
 *   注入本身永不破坏 JSON 语法，红只可能来自解析侧。
 * - 反证 `wrong-typed known fields still fail loudly`：手写解析里 `?.jsonPrimitive`
 *   在类型错时抛 [IllegalArgumentException]，路由层 `StatusPages` 把它映射为 400
 *   「参数无效」（不是 500）——坏数据必须大声失败，不能悄悄吞掉。
 *   例外：显式 JSON null 不是类型错（`JsonNull` 本就是 `JsonPrimitive` 的子类型，
 *   `.content` 为 `"null"` 字符串）——对别名链而言 `?:` 接在 `jsonPrimitive`
 *   **之前**，主字段存在（哪怕显式 null）即不穿透别名，与原处理器逐字一致，特意钉住。
 * - 钉住别名优先级（`name`→`firstName`、`phone`→`phoneNumber`）、缺省值
 *   （name/phone/userId 缺省→`""`）、三处截断（name 80 / phone 40 / userId 64，
 *   且 userId **没有** `trim()`——与 name/phone 不对称，原处理器逐字如此）、
 *   必填语义（chatId 非空 且 三者至少其一非空）、内容模板形状。
 */
class BotSendContactParseFuzzTest {

    private companion object {
        /** sendContact 解析涉及的全部已知字段名（含别名）：随机名必须避开它们。 */
        private val KNOWN_FIELD_NAMES = setOf(
            "chatId",
            "name", "firstName",
            "phone", "phoneNumber",
            "userId",
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

    private fun fieldsOf(obj: JsonObject): BotSendContactFields {
        val result = parseBotSendContactFields(obj)
        assertTrue(result is BotSendContactFieldsResult.Ok, "合法请求必须解析成功，实际 $result")
        return result.fields
    }

    @Test
    fun `sendContact parse survives seeded unknown-field fuzz`() {
        val random = Random(0xC0A7_0AC9)
        repeat(ITERATIONS) { i ->
            val chatId = "c-$i"
            val name = "name-$i"
            val phone = "+86-1380000$i"
            val userId = "u-$i"
            val base = JsonObject(
                mapOf(
                    "chatId" to JsonPrimitive(chatId),
                    "name" to JsonPrimitive(name),
                    "phone" to JsonPrimitive(phone),
                    "userId" to JsonPrimitive(userId),
                )
            )
            val obj = injectUnknownFields(base, random)
            val fields = fieldsOf(obj)
            assertEquals(
                BotSendContactFields(chatId, name, phone, userId),
                fields,
                "fuzz 迭代 #$i：已知字段必须与注入前完全一致，未知键必须被忽略",
            )
        }
    }

    @Test
    fun `sendContact required fields are enforced`() {
        // chatId 缺失 → MissingRequired
        assertTrue(
            parseBotSendContactFields(
                JsonObject(mapOf("name" to JsonPrimitive("Ada")))
            ) is BotSendContactFieldsResult.MissingRequired,
            "chatId 缺失必须判缺",
        )
        // 空白 chatId → MissingRequired
        assertTrue(
            parseBotSendContactFields(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("  "),
                        "name" to JsonPrimitive("Ada"),
                    )
                )
            ) is BotSendContactFieldsResult.MissingRequired,
            "空白 chatId 必须判缺",
        )
        // 三个联系人字段全空 → MissingRequired（chatId and contact fields required）
        assertTrue(
            parseBotSendContactFields(
                JsonObject(mapOf("chatId" to JsonPrimitive("c")))
            ) is BotSendContactFieldsResult.MissingRequired,
            "联系人字段全空必须判缺",
        )
        assertTrue(
            parseBotSendContactFields(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c"),
                        "name" to JsonPrimitive("   "),
                        "phone" to JsonPrimitive(""),
                        "userId" to JsonPrimitive(""),
                    )
                )
            ) is BotSendContactFieldsResult.MissingRequired,
            "联系人字段全空白必须判缺",
        )
        // 三者任意其一非空即合法（各补一个最简合法）
        fieldsOf(JsonObject(mapOf("chatId" to JsonPrimitive("c"), "name" to JsonPrimitive("Ada"))))
        fieldsOf(JsonObject(mapOf("chatId" to JsonPrimitive("c"), "phone" to JsonPrimitive("+1"))))
        fieldsOf(JsonObject(mapOf("chatId" to JsonPrimitive("c"), "userId" to JsonPrimitive("u"))))
    }

    @Test
    fun `sendContact alias priority is pinned`() {
        // name → firstName：主字段优先
        val nameAlias = fieldsOf(
            JsonObject(
                mapOf(
                    "chatId" to JsonPrimitive("c"),
                    "name" to JsonPrimitive("Ada"),
                    "firstName" to JsonPrimitive("Grace"),
                )
            )
        )
        assertEquals("Ada", nameAlias.contactName, "name 必须优先于 firstName")
        // 只有别名
        val firstNameOnly = fieldsOf(
            JsonObject(
                mapOf(
                    "chatId" to JsonPrimitive("c"),
                    "firstName" to JsonPrimitive("Grace"),
                )
            )
        )
        assertEquals("Grace", firstNameOnly.contactName, "firstName 别名必须被解析")
        // phone → phoneNumber：主字段优先
        val phoneAlias = fieldsOf(
            JsonObject(
                mapOf(
                    "chatId" to JsonPrimitive("c"),
                    "phone" to JsonPrimitive("+1"),
                    "phoneNumber" to JsonPrimitive("+2"),
                )
            )
        )
        assertEquals("+1", phoneAlias.phone, "phone 必须优先于 phoneNumber")
        val phoneNumberOnly = fieldsOf(
            JsonObject(
                mapOf(
                    "chatId" to JsonPrimitive("c"),
                    "phoneNumber" to JsonPrimitive("+2"),
                )
            )
        )
        assertEquals("+2", phoneNumberOnly.phone, "phoneNumber 别名必须被解析")
        // 近似字段名被忽略（不是别名）
        val approx = fieldsOf(
            JsonObject(
                mapOf(
                    "chatId" to JsonPrimitive("c"),
                    "Name" to JsonPrimitive("Ada"),
                    "firstName" to JsonPrimitive("Grace"),
                )
            )
        )
        assertEquals("Grace", approx.contactName, "近似字段名 Name 必须被忽略")
    }

    @Test
    fun `sendContact explicit null does not fall through to alias`() {
        // 「怪」语义钉住：?: 接在 jsonPrimitive 之前，主字段显式 null 即不穿透别名——
        // JsonNull.content 为 "null" 字符串，trim 不判住，contactName 非空，请求判合法。
        val nullPrimary = fieldsOf(
            JsonObject(
                mapOf(
                    "chatId" to JsonPrimitive("c"),
                    "name" to JsonNull,
                    "firstName" to JsonPrimitive("Grace"),
                )
            )
        )
        assertEquals("null", nullPrimary.contactName, "显式 null 的 name 必须得到字面 \"null\"，不穿透到 firstName")
        // phone 同理
        val nullPhone = fieldsOf(
            JsonObject(
                mapOf(
                    "chatId" to JsonPrimitive("c"),
                    "name" to JsonPrimitive("Ada"),
                    "phone" to JsonNull,
                    "phoneNumber" to JsonPrimitive("+2"),
                )
            )
        )
        assertEquals("null", nullPhone.phone, "显式 null 的 phone 必须得到字面 \"null\"，不穿透到 phoneNumber")
    }

    @Test
    fun `sendContact wrong-typed known fields fail loudly`() {
        // 已知字段类型错 → IllegalArgumentException（路由层 StatusPages 映射 400，不是 500）
        assertFailsWith<IllegalArgumentException>(
            "chatId 收对象必须大声失败",
        ) {
            parseBotSendContactFields(
                JsonObject(
                    mapOf(
                        "chatId" to JsonObject(mapOf("id" to JsonPrimitive("c"))),
                        "name" to JsonPrimitive("Ada"),
                    )
                )
            )
        }
        assertFailsWith<IllegalArgumentException>(
            "phone 收数组必须大声失败",
        ) {
            parseBotSendContactFields(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c"),
                        "phone" to JsonArray(listOf(JsonPrimitive("+1"))),
                    )
                )
            )
        }
        assertFailsWith<IllegalArgumentException>(
            "name 收对象必须大声失败（不是静默回空串）",
        ) {
            parseBotSendContactFields(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c"),
                        "name" to JsonObject(mapOf("n" to JsonPrimitive("Ada"))),
                    )
                )
            )
        }
        assertFailsWith<IllegalArgumentException>(
            "userId 收数组必须大声失败",
        ) {
            parseBotSendContactFields(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c"),
                        "userId" to JsonArray(listOf(JsonPrimitive("u"))),
                    )
                )
            )
        }
    }

    @Test
    fun `sendContact truncation and defaults are pinned`() {
        // 缺省 → ""
        val defaults = fieldsOf(JsonObject(mapOf("chatId" to JsonPrimitive("c"), "name" to JsonPrimitive("Ada"))))
        assertEquals("", defaults.phone, "phone 缺省必须回空串")
        assertEquals("", defaults.userId, "userId 缺省必须回空串")
        // name trim + 80 截断
        val longName = "  " + "n".repeat(100) + "  "
        val truncatedName = fieldsOf(
            JsonObject(
                mapOf(
                    "chatId" to JsonPrimitive("c"),
                    "name" to JsonPrimitive(longName),
                )
            )
        )
        assertEquals(80, truncatedName.contactName.length, "name 必须先 trim 再截断到 80")
        assertEquals("n".repeat(80), truncatedName.contactName)
        // phone trim + 40 截断
        val truncatedPhone = fieldsOf(
            JsonObject(
                mapOf(
                    "chatId" to JsonPrimitive("c"),
                    "phone" to JsonPrimitive("p".repeat(50)),
                )
            )
        )
        assertEquals("p".repeat(40), truncatedPhone.phone, "phone 必须截断到 40")
        // userId：take(64) 且没有 trim()——首尾空格原样保留（与 name/phone 不对称，零行为改动）
        val spacedUserId = fieldsOf(
            JsonObject(
                mapOf(
                    "chatId" to JsonPrimitive("c"),
                    "userId" to JsonPrimitive("  u  " + "x".repeat(70)),
                )
            )
        )
        assertEquals(64, spacedUserId.userId.length, "userId 必须截断到 64")
        assertTrue(spacedUserId.userId.startsWith("  u  "), "userId 的首部空格必须原样保留")
    }

    @Test
    fun `sendContact content shape is pinned`() {
        // 全量：name + phone + userId
        assertEquals(
            "👤 Ada +1\n[contactUser:u]\n[contactPhone:+1]",
            buildBotContactContent("Ada", "+1", "u"),
            "全量模板形状必须钉住",
        )
        // 只有 name
        assertEquals(
            "👤 Ada",
            buildBotContactContent("Ada", "", ""),
            "仅 name 的模板形状必须钉住",
        )
        // name 为空而 phone 非空：起手 \"👤 \" 自带尾空格，不补第二个空格
        assertEquals(
            "👤 +1\n[contactPhone:+1]",
            buildBotContactContent("", "+1", ""),
            "name 为空时 phone 不得前导双空格",
        )
        // name + phone：中间补一个空格
        assertEquals(
            "👤 Ada +1\n[contactPhone:+1]",
            buildBotContactContent("Ada", "+1", ""),
            "name 与 phone 之间恰一个空格",
        )
        // 只有 userId
        assertEquals(
            "👤 \n[contactUser:u]",
            buildBotContactContent("", "", "u"),
            "仅 userId 的模板形状必须钉住",
        )
    }
}
