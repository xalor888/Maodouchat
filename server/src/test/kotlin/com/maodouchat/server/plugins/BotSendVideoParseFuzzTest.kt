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
 * Bot `sendVideo` 请求体手写解析的**模糊兼容性测试**（清单 Q01「协议模型向前/向后兼容与
 * fuzz 测试」的 bot 侧专项评估，G355 `sendMessage`、G355-2 `editMessage`、
 * `sendDocument`、`sendVoice`、`sendPhoto` 之后第六块）。
 *
 * Bot 路由没有 typed DTO——请求体是 `receiveBoundedTextOrEmpty()` 拿到的原始字符串，
 * 经 `Json.parseToJsonElement(body).jsonObject` 再手写抽取。本轮把原来内联在
 * `/api/bot/sendVideo` 处理器里的抽取 / 体积测量 / 内容组装逻辑收敛为纯函数
 * ([parseBotSendVideoFields] / [measureBotVideoSize] / [buildBotVideoContent])
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
 *   例外：显式 JSON null 不是类型错（`JsonNull` 本就是 `JsonPrimitive` 的子类型），
 *   duration 显式 null 走 `toIntOrNull() ?: 0` 回 0，与原处理器逐字一致，特意钉住。
 * - 钉住别名优先级（`videoBase64`→`fileBase64`→`data`、`duration`→`durationSec`）、
 *   缺省值（caption 缺省→`""`、duration 缺省/非数字→`0`）、各处截断上限
 *   （caption 500 / 内容 4000）、data-URI 前缀剥离与空白剔除、
 *   **base64 非法 → size 0（宽容语义，与 sendVoice 一致）**
 *   ——与 sendPhoto 的「坏 base64 判 400 invalid base64」故意不同，特意钉住、
 *   12MB 边界。
 */
class BotSendVideoParseFuzzTest {

    private companion object {
        /** sendVideo 解析涉及的全部已知字段名（含别名）：随机名必须避开它们。 */
        private val KNOWN_FIELD_NAMES = setOf(
            "chatId", "caption", "duration", "durationSec",
            "videoBase64", "fileBase64", "data",
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

    private fun fieldsOf(obj: JsonObject): BotSendVideoFields {
        val result = parseBotSendVideoFields(obj)
        assertTrue(result is BotSendVideoFieldsResult.Ok, "合法请求必须解析成功，实际 $result")
        return result.fields
    }

    @Test
    fun `sendVideo parse survives seeded unknown-field fuzz`() {
        val random = Random(0xB0C_2029)
        repeat(ITERATIONS) { i ->
            val chatId = "c-$i"
            val caption = "caption-$i"
            val duration = i % 3600
            val payload = "video-bytes-$i".toByteArray()
            val b64 = java.util.Base64.getEncoder().encodeToString(payload)
            val base = JsonObject(
                mapOf(
                    "chatId" to JsonPrimitive(chatId),
                    "caption" to JsonPrimitive(caption),
                    "duration" to JsonPrimitive(duration),
                    "videoBase64" to JsonPrimitive(b64),
                )
            )
            val obj = injectUnknownFields(base, random)
            val fields = fieldsOf(obj)
            assertEquals(
                BotSendVideoFields(chatId, duration, caption, b64),
                fields,
                "fuzz 迭代 #$i：已知字段必须与注入前完全一致，未知键必须被忽略",
            )
            assertEquals(payload.size, measureBotVideoSize(fields.fileBase64), "fuzz 迭代 #$i：体积测量必须与原文一致")
        }
    }

    @Test
    fun `sendVideo required fields are enforced`() {
        // chatId 缺失 → MissingRequired（处理器映射 400「chatId/videoBase64 required」）
        assertTrue(
            parseBotSendVideoFields(JsonObject(mapOf("videoBase64" to JsonPrimitive("aGk="))))
                is BotSendVideoFieldsResult.MissingRequired,
            "chatId 缺失必须判缺",
        )
        // 媒体缺失 → MissingRequired（9.138 语义：与 sendPhoto/sendDocument 一致拒绝空媒体）
        assertTrue(
            parseBotSendVideoFields(JsonObject(mapOf("chatId" to JsonPrimitive("c"))))
                is BotSendVideoFieldsResult.MissingRequired,
            "媒体缺失必须判缺",
        )
        // 两者皆空 → MissingRequired
        assertTrue(
            parseBotSendVideoFields(JsonObject(mapOf("chatId" to JsonPrimitive("  "))))
                is BotSendVideoFieldsResult.MissingRequired,
            "空白 chatId 必须判缺",
        )
    }

    @Test
    fun `sendVideo defaults are pinned`() {
        // caption 缺省 → ""（不是 null），duration 缺省 → 0
        val noOptionals = fieldsOf(
            JsonObject(mapOf("chatId" to JsonPrimitive("c"), "videoBase64" to JsonPrimitive("aGk=")))
        )
        assertEquals("", noOptionals.caption, "caption 缺省必须回空串")
        assertEquals(0, noOptionals.durationSec, "duration 缺省必须回 0")
        // duration 非数字字符串 → 0（原处理器 toIntOrNull ?: 0）
        val badDuration = fieldsOf(
            JsonObject(
                mapOf(
                    "chatId" to JsonPrimitive("c"),
                    "videoBase64" to JsonPrimitive("aGk="),
                    "duration" to JsonPrimitive("not-a-number"),
                )
            )
        )
        assertEquals(0, badDuration.durationSec, "duration 非数字必须回 0")
    }

    @Test
    fun `sendVideo alias priority is pinned`() {
        // videoBase64 → fileBase64 → data
        val media = fieldsOf(
            JsonObject(
                mapOf(
                    "chatId" to JsonPrimitive("c"),
                    "videoBase64" to JsonPrimitive("AAA="),
                    "fileBase64" to JsonPrimitive("BBB="),
                    "data" to JsonPrimitive("CCC="),
                )
            )
        )
        assertEquals("AAA=", media.fileBase64, "videoBase64 必须优先于 fileBase64/data")
        val media2 = fieldsOf(
            JsonObject(
                mapOf(
                    "chatId" to JsonPrimitive("c"),
                    "fileBase64" to JsonPrimitive("BBB="),
                    "data" to JsonPrimitive("CCC="),
                )
            )
        )
        assertEquals("BBB=", media2.fileBase64, "fileBase64 必须优先于 data")
        // duration → durationSec
        val withBoth = fieldsOf(
            JsonObject(
                mapOf(
                    "chatId" to JsonPrimitive("c"),
                    "videoBase64" to JsonPrimitive("aGk="),
                    "duration" to JsonPrimitive(90),
                    "durationSec" to JsonPrimitive(10),
                )
            )
        )
        assertEquals(90, withBoth.durationSec, "duration 必须优先于 durationSec")
        val secOnly = fieldsOf(
            JsonObject(
                mapOf(
                    "chatId" to JsonPrimitive("c"),
                    "videoBase64" to JsonPrimitive("aGk="),
                    "durationSec" to JsonPrimitive(10),
                )
            )
        )
        assertEquals(10, secOnly.durationSec, "durationSec 别名必须被解析")
        // 近似字段名被忽略（不是别名）
        val approx = fieldsOf(
            JsonObject(
                mapOf(
                    "chatId" to JsonPrimitive("c"),
                    "VideoBase64" to JsonPrimitive("XXX="),
                    "data" to JsonPrimitive("aGk="),
                )
            )
        )
        assertEquals("aGk=", approx.fileBase64, "近似字段名 VideoBase64 必须被忽略")
    }

    @Test
    fun `sendVideo wrong-typed known fields fail loudly`() {
        // 已知字段类型错 → IllegalArgumentException（路由层 StatusPages 映射 400，不是 500）
        assertFailsWith<IllegalArgumentException>(
            "chatId 收对象必须大声失败",
        ) {
            parseBotSendVideoFields(
                JsonObject(
                    mapOf(
                        "chatId" to JsonObject(mapOf("id" to JsonPrimitive("c"))),
                        "videoBase64" to JsonPrimitive("aGk="),
                    )
                )
            )
        }
        assertFailsWith<IllegalArgumentException>(
            "videoBase64 收数组必须大声失败",
        ) {
            parseBotSendVideoFields(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c"),
                        "videoBase64" to JsonArray(listOf(JsonPrimitive("aGk="))),
                    )
                )
            )
        }
        assertFailsWith<IllegalArgumentException>(
            "caption 收对象必须大声失败（不是静默回空串）",
        ) {
            parseBotSendVideoFields(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c"),
                        "videoBase64" to JsonPrimitive("aGk="),
                        "caption" to JsonObject(mapOf("t" to JsonPrimitive("x"))),
                    )
                )
            )
        }
        // duration 显式 null → 回 0（不是大声失败）：JsonNull 本就是 JsonPrimitive 的子类型，
        // `obj["duration"]` 取到 JsonNull 时非空（Elvis 不触发），`.content` 为字符串 "null"，
        // `toIntOrNull() ?: 0` 回 0——与原内联处理器逐字一致，特意钉住
        assertEquals(
            0,
            fieldsOf(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c"),
                        "videoBase64" to JsonPrimitive("aGk="),
                        "duration" to JsonNull,
                    )
                )
            ).durationSec,
            "duration 显式 null 必须回 0（与原处理器一致）",
        )
    }

    @Test
    fun `sendVideo caption is truncated at 500`() {
        val long = "x".repeat(700)
        val fields = fieldsOf(
            JsonObject(
                mapOf(
                    "chatId" to JsonPrimitive("c"),
                    "videoBase64" to JsonPrimitive("aGk="),
                    "caption" to JsonPrimitive(long),
                )
            )
        )
        assertEquals(500, fields.caption.length, "caption 必须截断到 500")
        assertEquals("x".repeat(500), fields.caption)
    }

    @Test
    fun `sendVideo invalid base64 is tolerated at size 0 not rejected`() {
        // 宽容语义（原处理器 getOrDefault(0)）：坏 base64 只走 size = 0 分支，
        // 内容里不拼体积段，**不会**触发 400——与 sendPhoto 的「坏 base64 判 400
        // invalid base64」故意不同、与 sendVoice 一致，特意钉住
        assertEquals(0, measureBotVideoSize("!!!not-base64!!!"), "坏 base64 必须量出 0 而不是抛错")
        assertEquals(0, measureBotVideoSize(""), "空输入必须量出 0")
        // data-URI 前缀剥离与空白剔除仍生效
        val payload = "abc".toByteArray()
        val b64 = java.util.Base64.getEncoder().encodeToString(payload)
        assertEquals(
            payload.size,
            measureBotVideoSize("data:video/mp4;base64, $b64"),
            "data-URI 前缀与空白必须被剔除",
        )
        assertEquals(
            payload.size,
            measureBotVideoSize(" $b64\n "),
            "首尾空白必须被剔除",
        )
        // 12MB 边界（与处理器 `size > BOT_VIDEO_MAX_BYTES` 的 413 阈值同一常量）
        assertTrue(BOT_VIDEO_MAX_BYTES == 12 * 1024 * 1024)
        assertTrue(measureBotVideoSize(b64) <= BOT_VIDEO_MAX_BYTES, "普通 video 远小于 12MB 上限")
    }

    @Test
    fun `sendVideo content shape is pinned`() {
        // 有时长 + 体积 + caption 的完整形状
        assertEquals(
            "🎬 video 90s (128B)\nhello\n[botVideoSize:128]",
            buildBotVideoContent(90, "hello", 128),
            "完整模板形状必须钉住",
        )
        // 时长 0 → 不拼时长段；caption 空白 → 不拼 caption 行
        assertEquals(
            "🎬 video (10B)\n[botVideoSize:10]",
            buildBotVideoContent(0, "   ", 10),
            "时长 0 / 空白 caption 必须不拼段",
        )
        // 4000 截断
        val long = buildBotVideoContent(0, "y".repeat(5000), 0)
        assertEquals(4000, long.length, "内容必须截断到 4000")
    }
}
