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

class BotExportChatInviteLinkParseFuzzTest {

    private companion object {
        /** 解析涉及的全部已知字段名：随机名必须避开它们。 */
        private val KNOWN_FIELD_NAMES =
            setOf(
                "chatId", "rotate", "expiresInSeconds", "maxUses",
            )
        private const val ITERATIONS = 150
    }

    private fun parseOf(obj: JsonObject): BotExportChatInviteLinkFieldsResult =
        parseBotExportChatInviteLinkFields(obj)

    private fun okOf(obj: JsonObject): BotExportChatInviteLinkFields =
        (parseOf(obj) as BotExportChatInviteLinkFieldsResult.Ok).fields

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
        var name: String
        do {
            name = "fuzz_" + randomString(random, random.nextInt(3, 12)).replace(" ", "_")
        } while (name in KNOWN_FIELD_NAMES)
        return name
    }

    /** 与生产侧逐字一致的期望计算（fuzz 基 payload 专用：已知字段恒定类型合法）。 */
    private fun expectedOf(
        rawRotate: JsonElement?,
        rawExpires: JsonElement?,
        rawMaxUses: JsonElement?,
    ): Triple<Boolean, Long, Int> {
        val rotate = (rawRotate as? JsonPrimitive)?.let {
            it.content.toBooleanStrictOrNull()
        } == true
        val expires = (rawExpires as? JsonPrimitive)?.content?.toLongOrNull()
            ?: BOT_EXPORT_CHAT_INVITE_DEFAULT_EXPIRES_IN_SECONDS
        val clampedExpires = expires.coerceIn(
            BOT_EXPORT_CHAT_INVITE_MIN_EXPIRES_IN_SECONDS,
            BOT_EXPORT_CHAT_INVITE_MAX_EXPIRES_IN_SECONDS,
        )
        val maxUses = (rawMaxUses as? JsonPrimitive)?.content?.toIntOrNull()
            ?: BOT_EXPORT_CHAT_INVITE_DEFAULT_MAX_USES
        val clampedMaxUses = maxUses.coerceIn(
            BOT_EXPORT_CHAT_INVITE_MIN_MAX_USES,
            BOT_EXPORT_CHAT_INVITE_MAX_MAX_USES,
        )
        return Triple(rotate, clampedExpires, clampedMaxUses)
    }

    private fun basePayload(random: Random, i: Int): Pair<MutableMap<String, JsonElement>, Triple<Boolean, Long, Int>> {
        val base = mutableMapOf<String, JsonElement>()
        // 必填字段确定性合法（第四十一块 CI 教训）：chatId 恒为 "c<i>"，
        // 未知键注入永不触达必填。
        base["chatId"] = JsonPrimitive("c" + i)
        // rotate 轮换：JSON true / JSON false / 缺席（→false）。
        val rawRotate: JsonElement? = when (i % 3) {
            0 -> JsonPrimitive(true)
            1 -> JsonPrimitive(false)
            else -> null
        }
        if (rawRotate != null) base["rotate"] = rawRotate
        // expiresInSeconds 轮换：数字型 / 字符串数字 / 低于下限 / 高于上限 / 缺席（→604800）。
        val rawExpires: JsonElement? = when (i % 5) {
            0 -> JsonPrimitive(3600)
            1 -> JsonPrimitive("7200")
            2 -> JsonPrimitive(-10)
            3 -> JsonPrimitive(99_999_999)
            else -> null
        }
        if (rawExpires != null) base["expiresInSeconds"] = rawExpires
        // maxUses 轮换：数字型 / 字符串数字 / 低于下限 / 高于上限 / 缺席（→100）。
        val rawMaxUses: JsonElement? = when (i % 5) {
            0 -> JsonPrimitive(50)
            1 -> JsonPrimitive("10")
            2 -> JsonPrimitive(0)
            3 -> JsonPrimitive(5000)
            else -> null
        }
        if (rawMaxUses != null) base["maxUses"] = rawMaxUses
        // 程序化注入未知字段：永不破坏 JSON 语法。
        repeat(random.nextInt(0, 6)) {
            base[randomName(random)] = randomScalar(random)
        }
        return base to expectedOf(rawRotate, rawExpires, rawMaxUses)
    }

    @Test
    fun fuzzUnknownKeysIgnoredAndKnownSemanticsHold() {
        val random = Random(20261002)
        repeat(ITERATIONS) { i ->
            val (base, expected) = basePayload(random, i)
            val fields = okOf(JsonObject(base))
            assertEquals("c" + i, fields.chatId, "未知键不得污染 chatId，迭代 " + i)
            assertEquals(expected.first, fields.rotate, "未知键不得污染 rotate，迭代 " + i)
            assertEquals(expected.second, fields.expiresInSeconds, "未知键不得污染 expiresInSeconds，迭代 " + i)
            assertEquals(expected.third, fields.maxUses, "未知键不得污染 maxUses，迭代 " + i)
        }
    }

    @Test
    fun missingRequiredSemantics() {
        val validExtras = mapOf(
            "rotate" to JsonPrimitive(true),
            "expiresInSeconds" to JsonPrimitive(3600),
            "maxUses" to JsonPrimitive(50),
        )
        // chatId 缺 / 空 / 纯空白 → MissingRequired（其余字段合法时）。
        assertTrue(parseOf(JsonObject(validExtras)) is BotExportChatInviteLinkFieldsResult.MissingRequired)
        assertTrue(
            parseOf(JsonObject(validExtras + ("chatId" to JsonPrimitive("")))) is BotExportChatInviteLinkFieldsResult.MissingRequired
        )
        assertTrue(
            parseOf(JsonObject(validExtras + ("chatId" to JsonPrimitive("   ")))) is BotExportChatInviteLinkFieldsResult.MissingRequired
        )
        // 只有 chatId 参与必填：其余字段缺席也照样 Ok（恒有默认值）。
        val withoutExtras = okOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1"))))
        assertEquals("c1", withoutExtras.chatId)
        assertEquals(false, withoutExtras.rotate)
        assertEquals(BOT_EXPORT_CHAT_INVITE_DEFAULT_EXPIRES_IN_SECONDS, withoutExtras.expiresInSeconds)
        assertEquals(BOT_EXPORT_CHAT_INVITE_DEFAULT_MAX_USES, withoutExtras.maxUses)
        // 显式 null 得字面量 "null"→非空→Ok 的逐字怪语义。
        val nullChatId = okOf(JsonObject(mapOf("chatId" to JsonNull)))
        assertEquals("null", nullChatId.chatId)
    }

    @Test
    fun rotateStrictlyTrueOnly() {
        fun rotateOf(rotate: JsonElement?): Boolean {
            val base = mutableMapOf<String, JsonElement>("chatId" to JsonPrimitive("c1"))
            if (rotate != null) base["rotate"] = rotate
            return okOf(JsonObject(base)).rotate
        }
        // JSON true / 内容为 "true" 的字符串得 true——注意大小写不敏感：
        // booleanOrNull 即 content.toBooleanStrictOrNull()，"严格"指非法输入得 null（不抛），
        // 而非大小写敏感——"TRUE"/"True" 同样得 true（CI 曾据此红过，见本轮 fix）。
        assertEquals(true, rotateOf(JsonPrimitive(true)))
        assertEquals(true, rotateOf(JsonPrimitive("true")))
        assertEquals(true, rotateOf(JsonPrimitive("TRUE")))
        // 其余一律 false。
        assertEquals(false, rotateOf(null))
        assertEquals(false, rotateOf(JsonPrimitive(false)))
        assertEquals(false, rotateOf(JsonNull))
        assertEquals(false, rotateOf(JsonPrimitive("false")))
        assertEquals(false, rotateOf(JsonPrimitive("yes")))
        assertEquals(false, rotateOf(JsonPrimitive(1)))
        assertEquals(false, rotateOf(JsonPrimitive(0)))
    }

    @Test
    fun expiresInSecondsDefaultThenClamp() {
        fun expiresOf(expires: JsonElement?): Long {
            val base = mutableMapOf<String, JsonElement>("chatId" to JsonPrimitive("c1"))
            if (expires != null) base["expiresInSeconds"] = expires
            return okOf(JsonObject(base)).expiresInSeconds
        }
        // 缺席 / 非数字串 / 显式 null → 默认 604800（7 天）。
        assertEquals(604800L, expiresOf(null))
        assertEquals(604800L, expiresOf(JsonPrimitive("tomorrow")))
        assertEquals(604800L, expiresOf(JsonNull))
        assertEquals(604800L, expiresOf(JsonPrimitive("")))
        // 合法值原样通过（含边界恰好等于界限）。
        assertEquals(3600L, expiresOf(JsonPrimitive(3600)))
        assertEquals(7200L, expiresOf(JsonPrimitive("7200")))
        assertEquals(300L, expiresOf(JsonPrimitive(300)))
        assertEquals(2592000L, expiresOf(JsonPrimitive(2592000)))
        // 小于下限夹到 300（含 0 与负数），大于上限夹到 2592000。
        assertEquals(300L, expiresOf(JsonPrimitive(0)))
        assertEquals(300L, expiresOf(JsonPrimitive(-100)))
        assertEquals(300L, expiresOf(JsonPrimitive("10")))
        assertEquals(2592000L, expiresOf(JsonPrimitive(99_999_999)))
        assertEquals(2592000L, expiresOf(JsonPrimitive("2592001")))
    }

    @Test
    fun maxUsesDefaultThenClamp() {
        fun maxUsesOf(maxUses: JsonElement?): Int {
            val base = mutableMapOf<String, JsonElement>("chatId" to JsonPrimitive("c1"))
            if (maxUses != null) base["maxUses"] = maxUses
            return okOf(JsonObject(base)).maxUses
        }
        // 缺席 / 非数字串 / 显式 null → 默认 100。
        assertEquals(100, maxUsesOf(null))
        assertEquals(100, maxUsesOf(JsonPrimitive("unlimited")))
        assertEquals(100, maxUsesOf(JsonNull))
        assertEquals(100, maxUsesOf(JsonPrimitive("")))
        // 合法值原样通过（含边界恰好等于界限）。
        assertEquals(50, maxUsesOf(JsonPrimitive(50)))
        assertEquals(10, maxUsesOf(JsonPrimitive("10")))
        assertEquals(1, maxUsesOf(JsonPrimitive(1)))
        assertEquals(1000, maxUsesOf(JsonPrimitive(1000)))
        // 小于 1 夹到 1（含 0 与负数），大于 1000 夹到 1000。
        assertEquals(1, maxUsesOf(JsonPrimitive(0)))
        assertEquals(1, maxUsesOf(JsonPrimitive(-5)))
        assertEquals(1, maxUsesOf(JsonPrimitive("0")))
        assertEquals(1000, maxUsesOf(JsonPrimitive(5000)))
        assertEquals(1000, maxUsesOf(JsonPrimitive("1001")))
    }

    @Test
    fun noTrimOnChatId() {
        // 无 trim：首尾空白原样保留。
        val spaced = okOf(JsonObject(mapOf("chatId" to JsonPrimitive(" c1 "))))
        assertEquals(" c1 ", spaced.chatId)
    }

    @Test
    fun loudFailureCounterexamples() {
        fun badField(field: String, value: JsonElement): JsonObject {
            val base = mutableMapOf<String, JsonElement>("chatId" to JsonPrimitive("c1"))
            base[field] = value
            return JsonObject(base)
        }
        val objVal = JsonObject(mapOf("a" to JsonPrimitive(1)))
        val arrVal = JsonArray(listOf(JsonPrimitive("c")))
        // 对象 / 数组型已知字段在 ?.jsonPrimitive 处大声失败（路由层映射 400，不是 500）。
        for (field in listOf("chatId", "rotate", "expiresInSeconds", "maxUses")) {
            assertFailsWith<IllegalArgumentException>("对象型 " + field + " 应大声失败") { parseOf(badField(field, objVal)) }
            assertFailsWith<IllegalArgumentException>("数组型 " + field + " 应大声失败") { parseOf(badField(field, arrVal)) }
        }
        // 反证：显式 null 不抛——chatId 得字面量 "null"→Ok；
        // rotate 显式 null→false、expiresInSeconds 显式 null→604800、maxUses 显式 null→100。
        val nullsOk = okOf(
            JsonObject(
                mapOf(
                    "chatId" to JsonNull,
                    "rotate" to JsonNull,
                    "expiresInSeconds" to JsonNull,
                    "maxUses" to JsonNull,
                )
            )
        )
        assertEquals("null", nullsOk.chatId)
        assertEquals(false, nullsOk.rotate)
        assertEquals(BOT_EXPORT_CHAT_INVITE_DEFAULT_EXPIRES_IN_SECONDS, nullsOk.expiresInSeconds)
        assertEquals(BOT_EXPORT_CHAT_INVITE_DEFAULT_MAX_USES, nullsOk.maxUses)
    }

    @Test
    fun nearMissFieldNamesAreIgnored() {
        // 近似字段名按未知键忽略：真字段缺席→MissingRequired。
        val nearMiss = JsonObject(
            mapOf(
                "ChatId" to JsonPrimitive("c1"),
                "chatid2" to JsonPrimitive("c1"),
                "Rotate" to JsonPrimitive(true),
                "expiresIn" to JsonPrimitive(3600),
                "maxuses" to JsonPrimitive(50),
            )
        )
        assertTrue(parseOf(nearMiss) is BotExportChatInviteLinkFieldsResult.MissingRequired)
    }
}
