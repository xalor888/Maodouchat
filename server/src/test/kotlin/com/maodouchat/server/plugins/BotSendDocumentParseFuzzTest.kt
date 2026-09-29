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
 * Bot `sendDocument` 请求体手写解析的**模糊兼容性测试**（清单 Q01「协议模型向前/向后兼容与
 * fuzz 测试」的 bot 侧专项评估，G355 `sendMessage`、G355-2 `editMessage` 之后第三块）。
 *
 * Bot 路由没有 typed DTO——请求体是 `receiveBoundedTextOrEmpty()` 拿到的原始字符串，
 * 经 `Json.parseToJsonElement(body).jsonObject` 再手写抽取。本轮把原来内联在
 * `/api/bot/sendDocument` 处理器里的抽取 / 校验 / 内容组装逻辑收敛为纯函数
 * ([parseBotSendDocumentFields] / [decodeBotDocumentBytes] / [buildBotDocumentContent])
 * （生产侧零行为改动：校验顺序仍为 必填 → 成员检查（处理器）→ base64 → 体积），
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
 * - 钉住别名优先级（`fileName`→`filename`、`fileBase64`→`document`→`data`）、
 *   缺省值（空文件名→`document.bin`、caption 缺省→`""`）、各处截断上限
 *   （fileName 120 / caption 500 / 内容 4000）、data-URI 前缀剥离与空白剔除、
 *   base64 非法 / 空结果 → `InvalidBase64`、8MB 边界。
 */
class BotSendDocumentParseFuzzTest {

    private companion object {
        /** sendDocument 解析涉及的全部已知字段名（含别名）：随机名必须避开它们。 */
        private val KNOWN_FIELD_NAMES = setOf(
            "chatId", "fileName", "filename", "caption",
            "fileBase64", "document", "data",
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

    private fun fieldsOf(obj: JsonObject): BotSendDocumentFields {
        val result = parseBotSendDocumentFields(obj)
        assertTrue(result is BotSendDocumentFieldsResult.Ok, "合法请求必须解析成功，实际 $result")
        return result.fields
    }

    @Test
    fun `sendDocument parse survives seeded unknown-field fuzz`() {
        val random = Random(0xD0C_2026)
        repeat(ITERATIONS) { i ->
            val chatId = "c-$i"
            val fileName = "report-$i.pdf"
            val caption = "caption-$i"
            val payload = "payload-bytes-$i".toByteArray()
            val b64 = java.util.Base64.getEncoder().encodeToString(payload)
            val base = JsonObject(
                mapOf(
                    "chatId" to JsonPrimitive(chatId),
                    "fileName" to JsonPrimitive(fileName),
                    "caption" to JsonPrimitive(caption),
                    "fileBase64" to JsonPrimitive(b64),
                )
            )
            val obj = injectUnknownFields(base, random)
            val fields = fieldsOf(obj)
            assertEquals(
                BotSendDocumentFields(chatId, fileName, caption, b64),
                fields,
                "fuzz 迭代 #$i：已知字段必须与注入前完全一致，未知键必须被忽略",
            )
            val decoded = decodeBotDocumentBytes(fields.fileBase64)
            assertTrue(decoded is BotDocumentBytesResult.Ok, "合法 base64 必须解码成功")
            assertTrue(
                decoded.bytes.contentEquals(payload),
                "fuzz 迭代 #$i：解码字节必须与原文一致",
            )
        }
    }

    @Test
    fun `missing fields fall back to defaults and required check`() {
        assertTrue(
            parseBotSendDocumentFields(JsonObject(emptyMap())) is BotSendDocumentFieldsResult.MissingRequired,
            "空对象：chatId/fileBase64 缺失必须报 MissingRequired",
        )
        assertTrue(
            parseBotSendDocumentFields(JsonObject(mapOf("chatId" to JsonPrimitive("c"))))
                is BotSendDocumentFieldsResult.MissingRequired,
            "缺 fileBase64 必须报 MissingRequired",
        )
        assertTrue(
            parseBotSendDocumentFields(
                JsonObject(mapOf("fileBase64" to JsonPrimitive("QUJD"), "chatId" to JsonPrimitive("  ")))
            ) is BotSendDocumentFieldsResult.MissingRequired,
            "chatId 全空白必须报 MissingRequired",
        )
        // 空文件名回落 document.bin；caption 缺省回 ""
        val fields = fieldsOf(
            JsonObject(
                mapOf(
                    "chatId" to JsonPrimitive("c"),
                    "fileName" to JsonPrimitive("   "),
                    "fileBase64" to JsonPrimitive("QUJD"),
                )
            )
        )
        assertEquals("document.bin", fields.fileName, "全空白文件名必须回落 document.bin")
        assertEquals("", fields.caption, "缺省 caption 必须回空字符串")
    }

    @Test
    fun `aliases work with documented priority and near-miss names are ignored`() {
        val withLowercaseAlias = fieldsOf(
            JsonObject(
                mapOf(
                    "chatId" to JsonPrimitive("c"),
                    "filename" to JsonPrimitive("alias.pdf"),
                    "document" to JsonPrimitive("QUJD"),
                )
            )
        )
        assertEquals("alias.pdf", withLowercaseAlias.fileName, "filename 别名必须生效")
        // fileName 优先于 filename；fileBase64 优先于 document 优先于 data
        val withBoth = fieldsOf(
            JsonObject(
                mapOf(
                    "chatId" to JsonPrimitive("c"),
                    "fileName" to JsonPrimitive("main.pdf"),
                    "filename" to JsonPrimitive("alias.pdf"),
                    "fileBase64" to JsonPrimitive("QUJD"),
                    "document" to JsonPrimitive("R0hJ"),
                    "data" to JsonPrimitive("SktM"),
                )
            )
        )
        assertEquals("main.pdf", withBoth.fileName, "fileName 必须优先于 filename")
        assertEquals("QUJD", withBoth.fileBase64, "fileBase64 必须优先于 document/data")
        val dataOnly = fieldsOf(
            JsonObject(
                mapOf(
                    "ChatId" to JsonPrimitive("WRONG"),
                    "chatId" to JsonPrimitive("c"),
                    "fileName" to JsonPrimitive("x.bin"),
                    "data" to JsonPrimitive("QUJD"),
                )
            )
        )
        assertEquals("c", dataOnly.chatId, "近似字段名 ChatId 必须被忽略，只有精确命中的 chatId 生效")
        assertEquals("QUJD", dataOnly.fileBase64, "data 别名在前两者缺席时必须生效")
    }

    @Test
    fun `wrong-typed known fields still fail loudly`() {
        // chatId 收对象：?.jsonPrimitive 抛 IllegalArgumentException（路由层映射为 400，不是 500）
        assertFailsWith<IllegalArgumentException>("chatId 类型错必须大声失败") {
            parseBotSendDocumentFields(
                JsonObject(mapOf("chatId" to JsonObject(mapOf("v" to JsonPrimitive(1)))))
            )
        }
        // fileBase64 收数组：同上
        assertFailsWith<IllegalArgumentException>("fileBase64 类型错必须大声失败") {
            parseBotSendDocumentFields(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c"),
                        "fileBase64" to JsonArray(listOf(JsonPrimitive("x"))),
                    )
                )
            )
        }
        // caption 收对象：同上
        assertFailsWith<IllegalArgumentException>("caption 类型错必须大声失败") {
            parseBotSendDocumentFields(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c"),
                        "fileBase64" to JsonPrimitive("QUJD"),
                        "caption" to JsonObject(emptyMap()),
                    )
                )
            )
        }
        // chatId 显式 null：JsonNull 本身是 JsonPrimitive（content == "null"），
        // ?.jsonPrimitive 不抛——这是搬移前的原有行为，按"零行为改动"原则钉住原有语义，不改源码。
        assertEquals(
            "null",
            fieldsOf(
                JsonObject(
                    mapOf(
                        "chatId" to JsonNull,
                        "fileBase64" to JsonPrimitive("QUJD"),
                    )
                )
            ).chatId,
            "chatId 显式 null 沿用原有语义（JsonNull.content == \"null\"），不抛",
        )
    }

    @Test
    fun `truncation caps and filename normalization are enforced`() {
        val fields = fieldsOf(
            JsonObject(
                mapOf(
                    "chatId" to JsonPrimitive("c"),
                    "fileName" to JsonPrimitive("  " + "n".repeat(200) + ".pdf  "),
                    "caption" to JsonPrimitive("c".repeat(800)),
                    "fileBase64" to JsonPrimitive("QUJD"),
                )
            )
        )
        assertEquals(120, fields.fileName.length, "fileName 必须截断到 120（含 trim 在前）")
        assertEquals("n".repeat(120), fields.fileName, "fileName 先 trim 再 take(120)")
        assertEquals(500, fields.caption.length, "caption 必须截断到 500")
        // 内容 4000 截断：直接对组装函数用超长输入钉住
        val huge = buildBotDocumentContent("n".repeat(5000), "c".repeat(5000), 123)
        assertEquals(4000, huge.length, "组装内容必须截断到 4000")
        assertTrue(huge.startsWith("📎 "), "截断不改变内容前缀形状")
    }

    @Test
    fun `base64 handling is pinned`() {
        fun decodeOf(b64: String): BotDocumentBytesResult = decodeBotDocumentBytes(b64)
        // data-URI 前缀剥离
        val withPrefix = decodeOf("data:application/pdf;base64,QUJD")
        assertTrue(withPrefix is BotDocumentBytesResult.Ok, "data-URI 前缀必须被剥离")
        assertTrue(withPrefix.bytes.contentEquals("ABC".toByteArray()), "前缀剥离后解码必须正确")
        // 空白字符剔除
        val withWhitespace = decodeOf("QU\nJD\r\n ")
        assertTrue(withWhitespace is BotDocumentBytesResult.Ok, "base64 中的空白必须被剔除")
        assertTrue(withWhitespace.bytes.contentEquals("ABC".toByteArray()), "剔除空白后解码必须正确")
        // 逗号分隔只认第一个逗号之后（substringAfter 语义）：首个逗号之后仍含逗号 → 非法 base64
        assertTrue(
            decodeOf("a,b,QUJD") is BotDocumentBytesResult.InvalidBase64,
            "首个逗号之后仍含逗号必须报 InvalidBase64",
        )
        // 非法 base64
        assertTrue(
            decodeOf("!!!not-base64!!!") is BotDocumentBytesResult.InvalidBase64,
            "非法 base64 必须报 InvalidBase64",
        )
        // 解码结果为空 → 同非法处理（原处理器 bytes.isEmpty() 分支）
        assertTrue(
            decodeOf("") is BotDocumentBytesResult.InvalidBase64,
            "空解码结果必须报 InvalidBase64",
        )
        // 8MB 边界：恰好 8MB 通过，8MB+1 拒绝
        val exactlyMax = decodeOf(
            java.util.Base64.getEncoder().encodeToString(ByteArray(BOT_DOCUMENT_MAX_BYTES))
        )
        assertTrue(exactlyMax is BotDocumentBytesResult.Ok, "恰好 8MB 必须通过")
        assertEquals(BOT_DOCUMENT_MAX_BYTES, (exactlyMax as BotDocumentBytesResult.Ok).bytes.size)
        val overMax = decodeOf(
            java.util.Base64.getEncoder().encodeToString(ByteArray(BOT_DOCUMENT_MAX_BYTES + 1))
        )
        assertTrue(overMax is BotDocumentBytesResult.TooLarge, "8MB+1 必须报 TooLarge")
    }

    @Test
    fun `document content shape is pinned`() {
        assertEquals(
            "📎 a.pdf (3 bytes)\nhi\n[botFileName:a.pdf]\n[botFileSize:3]",
            buildBotDocumentContent("a.pdf", "hi", 3),
            "内容组装形状必须与原处理器逐字一致",
        )
        assertEquals(
            "📎 a.pdf (3 bytes)\n[botFileName:a.pdf]\n[botFileSize:3]",
            buildBotDocumentContent("a.pdf", "   ", 3),
            "全空白 caption 不占行",
        )
    }
}
