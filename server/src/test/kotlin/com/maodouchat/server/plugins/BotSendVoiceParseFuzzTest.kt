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
 * Bot `sendVoice` 请求体手写解析的**模糊兼容性测试**（清单 Q01「协议模型向前/向后兼容与
 * fuzz 测试」的 bot 侧专项评估，G355 `sendMessage`、G355-2 `editMessage`、
 * `sendDocument` 之后第四块）。
 *
 * Bot 路由没有 typed DTO——请求体是 `receiveBoundedTextOrEmpty()` 拿到的原始字符串，
 * 经 `Json.parseToJsonElement(body).jsonObject` 再手写抽取。本轮把原来内联在
 * `/api/bot/sendVoice` 处理器里的抽取 / 体积测量 / 内容组装逻辑收敛为纯函数
 * ([parseBotSendVoiceFields] / [measureBotVoiceSize] / [buildBotVoiceContent])
 * （生产侧零行为改动：校验顺序仍为 必填 → 成员检查（处理器）→ 体积），
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
 * - 钉住别名优先级（`duration`→`durationSec`、`fileBase64`→`voice`→`data`）、
 *   缺省值（duration 缺省→`0`、caption 缺省→`""`）、各处截断上限
 *   （caption 200 / 内容 4000）、data-URI 前缀剥离与空白剔除、
 *   **base64 非法 / 空输入 → 体积 0 而不是报错**（原处理器 `getOrDefault(0)` 的宽容语义，
 *   与 sendDocument 的 `InvalidBase64` 故意不同，特意钉住）、4MB 边界。
 */
class BotSendVoiceParseFuzzTest {

    private companion object {
        /** sendVoice 解析涉及的全部已知字段名（含别名）：随机名必须避开它们。 */
        private val KNOWN_FIELD_NAMES = setOf(
            "chatId", "duration", "durationSec", "caption",
            "fileBase64", "voice", "data",
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

    private fun fieldsOf(obj: JsonObject): BotSendVoiceFields {
        val result = parseBotSendVoiceFields(obj)
        assertTrue(result is BotSendVoiceFieldsResult.Ok, "合法请求必须解析成功，实际 $result")
        return result.fields
    }

    @Test
    fun `sendVoice parse survives seeded unknown-field fuzz`() {
        val random = Random(0xB0C_2026)
        repeat(ITERATIONS) { i ->
            val chatId = "c-$i"
            val duration = i % 600
            val caption = "caption-$i"
            val payload = "voice-bytes-$i".toByteArray()
            val b64 = java.util.Base64.getEncoder().encodeToString(payload)
            val base = JsonObject(
                mapOf(
                    "chatId" to JsonPrimitive(chatId),
                    "duration" to JsonPrimitive(duration),
                    "caption" to JsonPrimitive(caption),
                    "fileBase64" to JsonPrimitive(b64),
                )
            )
            val obj = injectUnknownFields(base, random)
            val fields = fieldsOf(obj)
            assertEquals(
                BotSendVoiceFields(chatId, duration, caption, b64),
                fields,
                "fuzz 迭代 #$i：已知字段必须与注入前完全一致，未知键必须被忽略",
            )
            val size = measureBotVoiceSize(fields.fileBase64)
            assertEquals(payload.size, size, "fuzz 迭代 #$i：解码字节数必须与原文一致")
        }
    }

    @Test
    fun `sendVoice required fields are enforced`() {
        // chatId 缺失 → MissingRequired（处理器映射 400「chatId/voice required」）
        assertTrue(
            parseBotSendVoiceFields(JsonObject(mapOf("fileBase64" to JsonPrimitive("aGk="))))
                is BotSendVoiceFieldsResult.MissingRequired,
            "chatId 缺失必须判缺",
        )
        // 媒体缺失 → MissingRequired（9.138 语义：与 sendPhoto/sendDocument 一致拒绝空媒体）
        assertTrue(
            parseBotSendVoiceFields(JsonObject(mapOf("chatId" to JsonPrimitive("c"))))
                is BotSendVoiceFieldsResult.MissingRequired,
            "媒体缺失必须判缺",
        )
        // 两者皆空 → MissingRequired
        assertTrue(
            parseBotSendVoiceFields(JsonObject(mapOf("chatId" to JsonPrimitive("  "))))
                is BotSendVoiceFieldsResult.MissingRequired,
        )
    }

    @Test
    fun `sendVoice defaults are pinned`() {
        // duration 缺省 → 0（不是 null/负数）
        val noDuration = fieldsOf(
            JsonObject(mapOf("chatId" to JsonPrimitive("c"), "voice" to JsonPrimitive("aGk=")))
        )
        assertEquals(0, noDuration.durationSec, "duration 缺省必须回 0")
        // duration 坏数字（"abc"）→ toIntOrNull 回 0，不抛异常（原处理器语义）
        val badDuration = fieldsOf(
            JsonObject(
                mapOf(
                    "chatId" to JsonPrimitive("c"),
                    "duration" to JsonPrimitive("abc"),
                    "voice" to JsonPrimitive("aGk="),
                )
            )
        )
        assertEquals(0, badDuration.durationSec, "duration 非数字必须回 0")
        // caption 缺省 → ""
        assertEquals("", noDuration.caption, "caption 缺省必须回空串")
        // 别名解析：voice → data 的 b64 链（fileBase64 缺省）
        assertEquals("aGk=", noDuration.fileBase64, "voice 别名必须被解析")
    }

    @Test
    fun `sendVoice alias priority is pinned`() {
        // duration 优先于 durationSec
        val byDuration = fieldsOf(
            JsonObject(
                mapOf(
                    "chatId" to JsonPrimitive("c"),
                    "duration" to JsonPrimitive(30),
                    "durationSec" to JsonPrimitive(45),
                    "voice" to JsonPrimitive("aGk="),
                )
            )
        )
        assertEquals(30, byDuration.durationSec, "duration 必须优先于 durationSec")
        // 只有 durationSec 时用它
        val byDurationSec = fieldsOf(
            JsonObject(
                mapOf(
                    "chatId" to JsonPrimitive("c"),
                    "durationSec" to JsonPrimitive(45),
                    "voice" to JsonPrimitive("aGk="),
                )
            )
        )
        assertEquals(45, byDurationSec.durationSec)
        // fileBase64 → voice → data
        val media = fieldsOf(
            JsonObject(
                mapOf(
                    "chatId" to JsonPrimitive("c"),
                    "fileBase64" to JsonPrimitive("AAA="),
                    "voice" to JsonPrimitive("aGk="),
                    "data" to JsonPrimitive("BBB="),
                )
            )
        )
        assertEquals("AAA=", media.fileBase64, "fileBase64 必须优先于 voice/data")
        val media2 = fieldsOf(
            JsonObject(
                mapOf(
                    "chatId" to JsonPrimitive("c"),
                    "voice" to JsonPrimitive("aGk="),
                    "data" to JsonPrimitive("BBB="),
                )
            )
        )
        assertEquals("aGk=", media2.fileBase64, "voice 必须优先于 data")
        // 近似字段名被忽略（不是别名）
        val approx = fieldsOf(
            JsonObject(
                mapOf(
                    "chatId" to JsonPrimitive("c"),
                    "Duration" to JsonPrimitive(99),
                    "filebase64" to JsonPrimitive("XXX="),
                    "voice" to JsonPrimitive("aGk="),
                )
            )
        )
        assertEquals(0, approx.durationSec, "近似字段名 Duration 必须被忽略")
        assertEquals("aGk=", approx.fileBase64, "近似字段名 filebase64 必须被忽略")
    }

    @Test
    fun `sendVoice wrong-typed known fields fail loudly`() {
        // 已知字段类型错 → IllegalArgumentException（路由层 StatusPages 映射 400，不是 500）
        assertFailsWith<IllegalArgumentException>(
            "chatId 收对象必须大声失败",
        ) {
            parseBotSendVoiceFields(
                JsonObject(
                    mapOf(
                        "chatId" to JsonObject(mapOf("id" to JsonPrimitive("c"))),
                        "voice" to JsonPrimitive("aGk="),
                    )
                )
            )
        }
        assertFailsWith<IllegalArgumentException>(
            "duration 收对象必须大声失败（不是静默回 0）",
        ) {
            parseBotSendVoiceFields(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c"),
                        "duration" to JsonObject(mapOf("v" to JsonPrimitive(1))),
                        "voice" to JsonPrimitive("aGk="),
                    )
                )
            )
        }
        assertFailsWith<IllegalArgumentException>(
            "voice 收数组必须大声失败",
        ) {
            parseBotSendVoiceFields(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c"),
                        "voice" to JsonArray(listOf(JsonPrimitive("aGk="))),
                    )
                )
            )
        }
    }

    @Test
    fun `sendVoice caption is truncated at 200`() {
        val long = "x".repeat(300)
        val fields = fieldsOf(
            JsonObject(
                mapOf(
                    "chatId" to JsonPrimitive("c"),
                    "voice" to JsonPrimitive("aGk="),
                    "caption" to JsonPrimitive(long),
                )
            )
        )
        assertEquals(200, fields.caption.length, "caption 必须截断到 200")
        assertEquals("x".repeat(200), fields.caption)
    }

    @Test
    fun `sendVoice bad base64 degrades to size zero not an error`() {
        // 宽容语义（原处理器 getOrDefault(0)）：坏 base64 的 voice 只走「size = 0」分支，
        // 内容模板里不拼体积段，不触发 400——与 sendDocument 的 InvalidBase64 故意不同
        assertEquals(0, measureBotVoiceSize("!!!not-base64!!!"), "坏 base64 必须返回 0 而不是抛错")
        assertEquals(0, measureBotVoiceSize(""), "空输入必须返回 0")
        // data-URI 前缀剥离与空白剔除仍生效
        val payload = "abc".toByteArray()
        val b64 = java.util.Base64.getEncoder().encodeToString(payload)
        assertEquals(3, measureBotVoiceSize("data:audio/ogg;base64, $b64"), "data-URI 前缀与空白必须被剔除")
        assertEquals(3, measureBotVoiceSize(" $b64\n "), "首尾空白必须被剔除")
        // 4MB 边界（与处理器 `size > BOT_VOICE_MAX_BYTES` 的 413 阈值同一常量）
        assertTrue(BOT_VOICE_MAX_BYTES == 4 * 1024 * 1024)
        assertTrue(measureBotVoiceSize(b64) <= BOT_VOICE_MAX_BYTES, "普通 voice 远小于 4MB 上限")
    }

    @Test
    fun `sendVoice content shape is pinned`() {
        // 有时长 + 体积 + caption 的完整形状
        assertEquals(
            "🎤 voice 60s (128B)\nhello\n[botVoiceSize:128]",
            buildBotVoiceContent(60, "hello", 128),
            "完整模板形状必须钉住",
        )
        // duration 0 → 不拼时长段；size 0 → 不拼体积段（坏 base64 的形状）
        assertEquals(
            "🎤 voice\n[botVoiceSize:0]",
            buildBotVoiceContent(0, "", 0),
            "时长/体积 0 时段落必须省略",
        )
        // caption 空白 → 不拼 caption 行
        assertEquals(
            "🎤 voice 5s\n[botVoiceSize:10]",
            buildBotVoiceContent(5, "   ", 10),
            "空白 caption 必须不拼行",
        )
        // 4000 截断
        val long = buildBotVoiceContent(0, "y".repeat(5000), 0)
        assertEquals(4000, long.length, "内容必须截断到 4000")
    }
}
