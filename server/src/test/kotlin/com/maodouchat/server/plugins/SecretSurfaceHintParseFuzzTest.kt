package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SecretSurfaceHintParseFuzzTest {

    private companion object {
        private val KNOWN_FIELDS = setOf("chatId", "hint")
        private const val ITERATIONS = 150
        private const val DEFAULT_HINT = "默认文案 default"
    }

    // 旧内联写法的逐字复刻，只供等价性比对。
    private fun oldWay(obj: JsonObject, defaultHint: String): SecretSurfaceHintFields {
        val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
        val hint = sanitizeBotHint(obj["hint"]?.jsonPrimitive?.content).ifBlank { defaultHint }
        return SecretSurfaceHintFields(chatId, hint)
    }

    private fun randomString(random: Random, length: Int): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 _-.,!?~"
        return buildString { repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) } }
    }

    private fun randomFieldName(random: Random): String {
        var name = "fuzz_" + randomString(random, random.nextInt(3, 10)).replace(" ", "_")
        while (name in KNOWN_FIELDS) name = "z$name"
        return name
    }

    private fun randomScalar(random: Random): JsonElement = when (random.nextInt(5)) {
        0 -> JsonPrimitive(random.nextBoolean())
        1 -> JsonPrimitive(random.nextLong(-1000, 1000))
        2 -> JsonPrimitive(randomString(random, random.nextInt(0, 24)))
        3 -> JsonNull
        else -> JsonPrimitive(random.nextDouble())
    }

    private fun randomHintValue(random: Random): JsonElement = when (random.nextInt(6)) {
        0 -> JsonPrimitive(randomString(random, random.nextInt(0, 60)))
        1 -> JsonPrimitive(randomString(random, random.nextInt(0, 60)) + "\n\t\u0007 end")
        2 -> JsonPrimitive(random.nextLong())
        3 -> JsonPrimitive(random.nextBoolean())
        4 -> JsonNull
        else -> JsonObject(mapOf(randomFieldName(random) to JsonPrimitive("x")))
    }

    @Test
    fun `secret surface hint parse survives seeded fuzz`() {
        val random = Random(2026100403)
        repeat(ITERATIONS) { i ->
            val fields = mutableMapOf<String, JsonElement>()
            if (random.nextBoolean()) fields["chatId"] = randomScalar(random)
            if (random.nextBoolean()) fields["hint"] = randomHintValue(random)
            repeat(random.nextInt(0, 5)) { fields[randomFieldName(random)] = randomScalar(random) }
            val obj = JsonObject(fields)
            val old = runCatching { oldWay(obj, DEFAULT_HINT) }
            val new = runCatching { parseSecretSurfaceHint(obj, DEFAULT_HINT) }
            assertEquals(old.getOrNull(), new.getOrNull(), "hint 解析 fuzz #$i：值不一致")
            assertEquals(
                old.exceptionOrNull()?.let { it::class },
                new.exceptionOrNull()?.let { it::class },
                "hint 解析 fuzz #$i：异常不一致",
            )
        }
    }

    @Test
    fun `hint sanitization and default fallback are pinned`() {
        val dirty = JsonObject(
            mapOf("chatId" to JsonPrimitive("c1"), "hint" to JsonPrimitive("a\u0000b\nc"))
        )
        assertEquals("a b c", parseSecretSurfaceHint(dirty, DEFAULT_HINT).hint, "控制字符/换行必须被清洗")

        val blank = JsonObject(mapOf("chatId" to JsonPrimitive("c1"), "hint" to JsonPrimitive("   ")))
        assertEquals(DEFAULT_HINT, parseSecretSurfaceHint(blank, DEFAULT_HINT).hint, "空白 hint 回退默认文案")

        val missing = JsonObject(mapOf("chatId" to JsonPrimitive("c1")))
        assertEquals(DEFAULT_HINT, parseSecretSurfaceHint(missing, DEFAULT_HINT).hint, "缺失 hint 回退默认文案")

        val noChat = JsonObject(emptyMap())
        assertEquals("", parseSecretSurfaceHint(noChat, DEFAULT_HINT).chatId, "chatId 缺失保持空串（路由报 400）")

        val long = JsonObject(mapOf("hint" to JsonPrimitive("x".repeat(200))))
        assertEquals(120, parseSecretSurfaceHint(long, DEFAULT_HINT).hint.length, "hint 截断 120")
    }

    @Test
    fun `wrong-type chatId still throws`() {
        val obj = JsonObject(mapOf("chatId" to JsonArray(listOf(JsonPrimitive(1)))))
        assertFailsWith<IllegalArgumentException>("chatId 传数组必须大声失败") {
            parseSecretSurfaceHint(obj, DEFAULT_HINT)
        }
    }
}
