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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BotSendPhotoParseFuzzTest {

    private companion object {
        /** sendPhoto 解析涉及的全部已知字段名（含别名）：随机名必须避开它们。 */
        private val KNOWN_FIELD_NAMES = setOf(
            "chatId", "caption",
            "photoBase64", "photo", "fileBase64", "data",
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

    private fun fieldsOf(obj: JsonObject): BotSendPhotoFields {
        val result = parseBotSendPhotoFields(obj)
        assertTrue(result is BotSendPhotoFieldsResult.Ok, "合法请求必须解析成功，实际 $result")
        return result.fields
    }

    @Test
    fun `sendPhoto parse survives seeded unknown-field fuzz`() {
        val random = Random(0xB0C_2027)
        repeat(ITERATIONS) { i ->
            val chatId = "c-$i"
            val caption = "caption-$i"
            val payload = "photo-bytes-$i".toByteArray()
            val b64 = java.util.Base64.getEncoder().encodeToString(payload)
            val base = JsonObject(
                mapOf(
                    "chatId" to JsonPrimitive(chatId),
                    "caption" to JsonPrimitive(caption),
                    "photoBase64" to JsonPrimitive(b64),
                )
            )
            val obj = injectUnknownFields(base, random)
            val fields = fieldsOf(obj)
            assertEquals(
                BotSendPhotoFields(chatId, caption, b64),
                fields,
                "fuzz 迭代 #$i：已知字段必须与注入前完全一致，未知键必须被忽略",
            )
            val decoded = decodeBotPhotoBytes(fields.fileBase64)
            assertTrue(decoded != null && decoded.contentEquals(payload), "fuzz 迭代 #$i：解码字节必须与原文一致")
        }
    }

    @Test
    fun `sendPhoto required fields are enforced`() {
        // chatId 缺失 → MissingRequired（处理器映射 400「chatId/photoBase64 required」）
        assertTrue(
            parseBotSendPhotoFields(JsonObject(mapOf("photoBase64" to JsonPrimitive("aGk="))))
                is BotSendPhotoFieldsResult.MissingRequired,
            "chatId 缺失必须判缺",
        )
        // 媒体缺失 → MissingRequired（9.138 语义：与 sendVoice/sendDocument 一致拒绝空媒体）
        assertTrue(
            parseBotSendPhotoFields(JsonObject(mapOf("chatId" to JsonPrimitive("c"))))
                is BotSendPhotoFieldsResult.MissingRequired,
            "媒体缺失必须判缺",
        )
        // 两者皆空 → MissingRequired
        assertTrue(
            parseBotSendPhotoFields(JsonObject(mapOf("chatId" to JsonPrimitive("  "))))
                is BotSendPhotoFieldsResult.MissingRequired,
            "空白 chatId 必须判缺",
        )
    }

    @Test
    fun `sendPhoto defaults are pinned`() {
        // caption 缺省 → ""（不是 null）
        val noCaption = fieldsOf(
            JsonObject(mapOf("chatId" to JsonPrimitive("c"), "photo" to JsonPrimitive("aGk=")))
        )
        assertEquals("", noCaption.caption, "caption 缺省必须回空串")
        // 别名解析：photo → b64 链（photoBase64 缺省）
        assertEquals("aGk=", noCaption.fileBase64, "photo 别名必须被解析")
    }

    @Test
    fun `sendPhoto alias priority is pinned`() {
        // photoBase64 → photo → fileBase64 → data
        val media = fieldsOf(
            JsonObject(
                mapOf(
                    "chatId" to JsonPrimitive("c"),
                    "photoBase64" to JsonPrimitive("AAA="),
                    "photo" to JsonPrimitive("aGk="),
                    "fileBase64" to JsonPrimitive("BBB="),
                    "data" to JsonPrimitive("CCC="),
                )
            )
        )
        assertEquals("AAA=", media.fileBase64, "photoBase64 必须优先于其余别名")
        val media2 = fieldsOf(
            JsonObject(
                mapOf(
                    "chatId" to JsonPrimitive("c"),
                    "photo" to JsonPrimitive("aGk="),
                    "fileBase64" to JsonPrimitive("BBB="),
                    "data" to JsonPrimitive("CCC="),
                )
            )
        )
        assertEquals("aGk=", media2.fileBase64, "photo 必须优先于 fileBase64/data")
        val media3 = fieldsOf(
            JsonObject(
                mapOf(
                    "chatId" to JsonPrimitive("c"),
                    "fileBase64" to JsonPrimitive("BBB="),
                    "data" to JsonPrimitive("CCC="),
                )
            )
        )
        assertEquals("BBB=", media3.fileBase64, "fileBase64 必须优先于 data")
        // 近似字段名被忽略（不是别名）
        val approx = fieldsOf(
            JsonObject(
                mapOf(
                    "chatId" to JsonPrimitive("c"),
                    "PhotoBase64" to JsonPrimitive("XXX="),
                    "data" to JsonPrimitive("aGk="),
                )
            )
        )
        assertEquals("aGk=", approx.fileBase64, "近似字段名 PhotoBase64 必须被忽略")
    }

    @Test
    fun `sendPhoto wrong-typed known fields fail loudly`() {
        // 已知字段类型错 → IllegalArgumentException（路由层 StatusPages 映射 400，不是 500）
        assertFailsWith<IllegalArgumentException>(
            "chatId 收对象必须大声失败",
        ) {
            parseBotSendPhotoFields(
                JsonObject(
                    mapOf(
                        "chatId" to JsonObject(mapOf("id" to JsonPrimitive("c"))),
                        "photo" to JsonPrimitive("aGk="),
                    )
                )
            )
        }
        assertFailsWith<IllegalArgumentException>(
            "photo 收数组必须大声失败",
        ) {
            parseBotSendPhotoFields(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c"),
                        "photo" to JsonArray(listOf(JsonPrimitive("aGk="))),
                    )
                )
            )
        }
        assertFailsWith<IllegalArgumentException>(
            "caption 收对象必须大声失败（不是静默回空串）",
        ) {
            parseBotSendPhotoFields(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c"),
                        "photo" to JsonPrimitive("aGk="),
                        "caption" to JsonObject(mapOf("t" to JsonPrimitive("x"))),
                    )
                )
            )
        }
    }

    @Test
    fun `sendPhoto caption is truncated at 500`() {
        val long = "x".repeat(700)
        val fields = fieldsOf(
            JsonObject(
                mapOf(
                    "chatId" to JsonPrimitive("c"),
                    "photo" to JsonPrimitive("aGk="),
                    "caption" to JsonPrimitive(long),
                )
            )
        )
        assertEquals(500, fields.caption.length, "caption 必须截断到 500（sendVoice 是 200，不统一是故意的）")
        assertEquals("x".repeat(500), fields.caption)
    }

    @Test
    fun `sendPhoto invalid base64 is rejected not tolerated`() {
        // 严格语义（原处理器 getOrNull + isEmpty 兜底 → 400 invalid base64）：
        // 坏 base64 返回 null，处理器拒绝——与 sendVoice 的「坏 base64 只走 size=0」宽容语义故意不同
        assertNull(decodeBotPhotoBytes("!!!not-base64!!!"), "坏 base64 必须返回 null 而不是抛错或给 0")
        assertNull(decodeBotPhotoBytes(""), "空输入解码出空字节必须返回 null")
        // data-URI 前缀剥离与空白剔除仍生效
        val payload = "abc".toByteArray()
        val b64 = java.util.Base64.getEncoder().encodeToString(payload)
        assertTrue(
            decodeBotPhotoBytes("data:image/png;base64, $b64")?.contentEquals(payload) == true,
            "data-URI 前缀与空白必须被剔除",
        )
        assertTrue(
            decodeBotPhotoBytes(" $b64\n ")?.contentEquals(payload) == true,
            "首尾空白必须被剔除",
        )
        // 5MB 边界（与处理器 `bytes.size > BOT_PHOTO_MAX_BYTES` 的 413 阈值同一常量）
        assertTrue(BOT_PHOTO_MAX_BYTES == 5 * 1024 * 1024)
        assertTrue(decodeBotPhotoBytes(b64)!!.size <= BOT_PHOTO_MAX_BYTES, "普通 photo 远小于 5MB 上限")
    }

    @Test
    fun `sendPhoto content shape is pinned`() {
        // 有体积 + caption 的完整形状
        assertEquals(
            "🖼 photo 128B\nhello\n[botPhotoSize:128]",
            buildBotPhotoContent("hello", 128),
            "完整模板形状必须钉住",
        )
        // caption 空白 → 不拼 caption 行（体积段仍按原处理器拼出）
        assertEquals(
            "🖼 photo 10B\n[botPhotoSize:10]",
            buildBotPhotoContent("   ", 10),
            "空白 caption 必须不拼行",
        )
        // 4000 截断
        val long = buildBotPhotoContent("y".repeat(5000), 0)
        assertEquals(4000, long.length, "内容必须截断到 4000")
    }
}
