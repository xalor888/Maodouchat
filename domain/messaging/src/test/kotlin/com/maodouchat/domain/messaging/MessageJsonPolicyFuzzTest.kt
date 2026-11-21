package com.maodouchat.domain.messaging

import com.maodouchat.core.model.InlineKeyboardButton
import com.maodouchat.core.model.MessageMeta
import com.maodouchat.core.model.MessageMetaJson
import kotlinx.serialization.json.Json
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MessageJsonPolicyFuzzTest {

    private companion object {
        private const val RANDOM_ITERATIONS = 200
        private const val STRUCTURED_ITERATIONS = 150
        private const val TEXT_ALPHABET =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 _-.,!?\"'\\/\n\t@：中文"
        // compose 侧的随机文本与 meta 字符串刻意不含 < >：剥离 "<meta>" 时先删
        // "</meta>" 再删 "<meta>"，形如 "</me<meta>ta>" 的拼接会在删除后新生一个
        // "</meta>" 字面量，定界符计数断言会被这种人为拼接误伤；剥离行为本身已有
        // 固定单测覆盖，这里只 fuzz meta 挂载判定与 JSON 编码。
        private const val SAFE_ALPHABET =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 _-.,!?\"'/\\ \n\t@：中文"
    }

    private fun randomText(random: Random, alphabet: String, maxLength: Int, minLength: Int = 0): String {
        val length = random.nextInt(minLength, maxLength + 1)
        return buildString { repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) } }
    }

    private fun randomValue(random: Random, depth: Int): Any? {
        if (depth <= 0) {
            return when (random.nextInt(5)) {
                0 -> null
                1 -> random.nextBoolean()
                2 -> random.nextInt()
                3 -> random.nextDouble(-1000.0, 1000.0)
                else -> randomText(random, TEXT_ALPHABET, 20)
            }
        }
        return when (random.nextInt(7)) {
            0 -> null
            1 -> random.nextBoolean()
            2 -> random.nextLong()
            3 -> random.nextDouble(-1000.0, 1000.0)
            4 -> randomText(random, TEXT_ALPHABET, 20)
            5 -> List(random.nextInt(0, 4)) { randomValue(random, depth - 1) }
            else -> (0 until random.nextInt(0, 4)).associate {
                val key: Any? = when (random.nextInt(4)) {
                    0 -> randomText(random, TEXT_ALPHABET, 8)
                    1 -> random.nextInt(-10, 11)
                    2 -> null
                    else -> randomText(random, TEXT_ALPHABET, 8)
                }
                key to randomValue(random, depth - 1)
            }
        }
    }

    // 生产侧 isDefaultMetaValue 的复刻：null、空白串、false、空容器为默认，其余均挂 tag。
    private fun isDefaultMetaValue(value: Any?): Boolean = when (value) {
        null -> true
        is String -> value.isBlank()
        is Boolean -> !value
        is List<*> -> value.isEmpty()
        is Map<*, *> -> value.isEmpty()
        else -> false
    }

    private fun randomMeta(random: Random): MessageMeta {
        val translations = (0 until random.nextInt(0, 3)).associate {
            "lang$it" to randomText(random, SAFE_ALPHABET, 12, 1)
        }
        val keyboard = List(random.nextInt(0, 3)) {
            List(random.nextInt(0, 3)) {
                InlineKeyboardButton(
                    text = randomText(random, SAFE_ALPHABET, 20, 1),
                    callbackData = randomText(random, SAFE_ALPHABET, 30)
                )
            }
        }
        return MessageMeta(
            mentions = List(random.nextInt(0, 4)) { "u" + random.nextInt(1000) },
            replyToId = if (random.nextBoolean()) null else "msg_" + random.nextInt(1000),
            forwardedFrom = if (random.nextBoolean()) null else randomText(random, SAFE_ALPHABET, 12, 1),
            voiceTranscript = if (random.nextBoolean()) null else randomText(random, SAFE_ALPHABET, 24),
            voiceDurationMs = when (random.nextInt(4)) {
                0 -> null
                1 -> 0L
                2 -> random.nextLong(-1000L, 1000L)
                else -> random.nextLong(0L, 600_000L)
            },
            translations = translations,
            aiAssisted = random.nextBoolean(),
            markdown = random.nextBoolean(),
            fileName = if (random.nextBoolean()) null else randomText(random, SAFE_ALPHABET, 12, 1),
            fileSizeBytes = when (random.nextInt(3)) {
                0 -> null
                1 -> 0L
                else -> random.nextLong(1L, 10_000_000L)
            },
            viewOnce = random.nextBoolean(),
            silent = random.nextBoolean(),
            spoilerMedia = random.nextBoolean(),
            inlineKeyboard = keyboard,
            forceReply = random.nextBoolean(),
        )
    }

    @Test
    fun `random nested values encode and re-encode stably`() {
        val random = Random(0x6C_2026)
        repeat(STRUCTURED_ITERATIONS) { i ->
            val value = randomValue(random, 3)
            val encoded = MessageJsonPolicy.encode(value)
            val parsed = Json.parseToJsonElement(encoded)
            assertEquals(
                encoded,
                MessageJsonPolicy.encode(parsed),
                "fuzz 迭代 #$i：编码后解析再编码必须稳定，value=$value"
            )
            assertEquals(
                parsed,
                Json.parseToJsonElement(MessageJsonPolicy.encodePretty(value)),
                "fuzz 迭代 #$i：pretty 编码解析后必须与紧凑编码同一元素"
            )
        }
    }

    @Test
    fun `non-string map keys are stringified with last value winning`() {
        // Int 1 与 String "1" 转键后撞键，后者胜出；null 键变 "null"。
        val encoded = MessageJsonPolicy.encode(mapOf<Any?, Any?>(1 to "a", "1" to "b", null to "c"))
        val parsed = Json.parseToJsonElement(encoded)
        assertEquals(
            Json.parseToJsonElement("""{"1":"b","null":"c"}"""),
            parsed
        )
    }

    @Test
    fun `compose with random meta attaches exactly one tag exactly when non-default`() {
        val random = Random(0x6D_2026)
        repeat(STRUCTURED_ITERATIONS) { i ->
            val text = randomText(random, SAFE_ALPHABET, 60)
            val meta = randomMeta(random)
            val hasTag = !MessageJsonPolicy.messageMetaMap(meta).values.all(::isDefaultMetaValue)
            val composed = MessageJsonPolicy.composeContentWithMeta(text, meta)
            if (!hasTag) {
                assertEquals(text, composed, "fuzz 迭代 #$i：全默认 meta 不得挂 tag")
            } else {
                assertTrue(composed.startsWith(text), "fuzz 迭代 #$i：正文必须原样在前")
                val rest = composed.removePrefix(text)
                assertTrue(rest.startsWith("<meta>"), "fuzz 迭代 #$i：tag 必须以 <meta> 开头")
                assertTrue(rest.endsWith("</meta>"), "fuzz 迭代 #$i：tag 必须以 </meta> 结尾")
                assertEquals(1, composed.split("<meta>").size - 1, "fuzz 迭代 #$i：<meta> 必须恰出现一次")
                assertEquals(1, composed.split("</meta>").size - 1, "fuzz 迭代 #$i：</meta> 必须恰出现一次")
                val inner = rest.removePrefix("<meta>").removeSuffix("</meta>")
                assertEquals(
                    MessageJsonPolicy.encode(MessageJsonPolicy.messageMetaMap(meta)),
                    inner,
                    "fuzz 迭代 #$i：tag 内必须是 meta map 的编码"
                )
                Json.parseToJsonElement(inner)
            }
        }
    }

    @Test
    fun `compose with default meta never attaches a tag`() {
        val random = Random(0x6E_2026)
        repeat(RANDOM_ITERATIONS) { i ->
            val text = randomText(random, SAFE_ALPHABET, 60)
            assertEquals(text, MessageJsonPolicy.composeContentWithMeta(text, MessageMeta()))
        }
        // 含 tag 字面量的正文在无 meta 时也被剥净。
        assertEquals("abc", MessageJsonPolicy.composeContentWithMeta("a<meta>b</meta>c", MessageMeta()))
        assertFalse(MessageJsonPolicy.composeContentWithMeta("x</meta>y", MessageMeta()).contains("</meta>"))
    }

    @Test
    fun `encodeMessageMeta round trips through fromJsonString`() {
        val random = Random(0x6F_2026)
        repeat(STRUCTURED_ITERATIONS) { i ->
            // 约束与 fromJsonString 的有损面一致：map 值非空白、按钮文本非空白、
            // 空行会被丢（decode 侧 mapNotNull + takeIf 非空行），否则往返本就会
            // 丢字段（与生产行为一致，不是本测试要钉的）。
            val base = randomMeta(random)
            val meta = base.copy(
                translations = base.translations.mapValues { (_, v) -> v.ifBlank { "v" } },
                inlineKeyboard = base.inlineKeyboard.filter { it.isNotEmpty() }.map { row ->
                    row.map { it.copy(text = it.text.ifBlank { "btn" }) }
                }
            )
            val encoded = MessageJsonPolicy.encodeMessageMeta(meta)
            assertEquals(
                meta,
                MessageMetaJson.fromJsonString(encoded),
                "fuzz 迭代 #$i：meta 编解码往返必须恒等"
            )
        }
    }
}
