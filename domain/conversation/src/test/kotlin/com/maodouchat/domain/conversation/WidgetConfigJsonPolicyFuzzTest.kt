package com.maodouchat.domain.conversation

import com.maodouchat.core.model.ConversationWidgetContract
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// WidgetConfigJsonPolicy 的 fuzz：小组件配置 JSON 编解码的往返恒等与边界钉住。
// 只加测试、零生产改动；断言依据是编解码的归一化契约（chatIds 非字符串元素时
// getString 抛错、整串回默认配置；超过 MAX_CONFIG_CHATS 截断；badge 默认 true、
// compact 默认 false，org.json optBoolean 只认布尔与 "true"/"false" 字符串；
// 损坏 JSON 与重复键回默认配置）。
class WidgetConfigJsonPolicyFuzzTest {

    private companion object {
        private const val ITERATIONS = 150
    }

    private fun randomToken(random: Random, length: Int): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        return buildString { repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) } }
    }

    private fun randomConfig(random: Random, index: Int): WidgetConfig {
        val count = random.nextInt(0, ConversationWidgetContract.MAX_CONFIG_CHATS + 1)
        val chatIds = (0 until count).map { j -> "c$index-$j-" + randomToken(random, random.nextInt(1, 8)) }
        return WidgetConfig(
            chatIds = chatIds,
            showUnreadBadge = random.nextBoolean(),
            compact = random.nextBoolean()
        )
    }

    @Test
    fun `config round-trips on seeded random configs`() {
        val random = Random(31008)
        repeat(ITERATIONS) { i ->
            val config = randomConfig(random, i)
            val decoded = WidgetConfigJsonPolicy.decodeConfig(WidgetConfigJsonPolicy.encodeConfig(config))
            assertEquals(config, decoded, "iteration $i: config must round-trip")
        }
    }

    @Test
    fun `decode never throws on seeded random garbage`() {
        val random = Random(31009)
        repeat(200) { i ->
            val raw = when (random.nextInt(5)) {
                0 -> randomToken(random, random.nextInt(0, 60))
                1 -> "[" + randomToken(random, random.nextInt(0, 40)) + "]"
                2 -> "x".repeat(random.nextInt(0, 120))
                3 -> """{"chatIds":["a"""
                else -> """{"chatIds":["a"],"badge":true,"unknown":{"x":[1,2,{"y":"z"}]}}"""
            }
            val first = WidgetConfigJsonPolicy.decodeConfig(raw)
            val second = WidgetConfigJsonPolicy.decodeConfig(raw)
            assertEquals(first, second, "iteration $i: decode must be deterministic")
            assertTrue(
                first.chatIds.size <= ConversationWidgetContract.MAX_CONFIG_CHATS,
                "iteration $i: decoded chatIds must be truncated to MAX_CONFIG_CHATS"
            )
        }
        assertEquals(WidgetConfig(), WidgetConfigJsonPolicy.decodeConfig("not json"))
        assertEquals(WidgetConfig(), WidgetConfigJsonPolicy.decodeConfig(""))
        assertEquals(WidgetConfig(), WidgetConfigJsonPolicy.decodeConfig("null"))
        assertEquals(WidgetConfig(), WidgetConfigJsonPolicy.decodeConfig("[1,2]"))
        assertEquals(WidgetConfig(), WidgetConfigJsonPolicy.decodeConfig("123"))
    }

    @Test
    fun `non-string chatIds element falls back to the default config`() {
        assertEquals(WidgetConfig(), WidgetConfigJsonPolicy.decodeConfig("""{"chatIds":["a",123,"b"]}"""))
        assertEquals(WidgetConfig(), WidgetConfigJsonPolicy.decodeConfig("""{"chatIds":[true]}"""))
        assertEquals(WidgetConfig(), WidgetConfigJsonPolicy.decodeConfig("""{"chatIds":[null]}"""))
        assertEquals(WidgetConfig(), WidgetConfigJsonPolicy.decodeConfig("""{"chatIds":[{"id":"a"}]}"""))
        assertEquals(WidgetConfig(), WidgetConfigJsonPolicy.decodeConfig("""{"chatIds":[["a"]]}"""))
        // 整串回默认：badge/compact 也不保留已给的值。
        assertEquals(
            WidgetConfig(),
            WidgetConfigJsonPolicy.decodeConfig("""{"chatIds":[1],"badge":false,"compact":true}""")
        )
    }

    @Test
    fun `chatIds truncate to max config chats`() {
        val ids = (1..8).map { it.toString() }
        val decoded = WidgetConfigJsonPolicy.decodeConfig(
            """{"chatIds":["1","2","3","4","5","6","7","8"]}"""
        )
        assertEquals(ids.take(ConversationWidgetContract.MAX_CONFIG_CHATS), decoded.chatIds)
        val roundTripped = WidgetConfigJsonPolicy.decodeConfig(
            WidgetConfigJsonPolicy.encodeConfig(WidgetConfig(chatIds = ids))
        )
        assertEquals(ids.take(ConversationWidgetContract.MAX_CONFIG_CHATS), roundTripped.chatIds)
    }

    @Test
    fun `missing or non-array chatIds falls back to empty ids`() {
        assertEquals(WidgetConfig(), WidgetConfigJsonPolicy.decodeConfig("{}"))
        assertEquals(WidgetConfig(), WidgetConfigJsonPolicy.decodeConfig("""{"chatIds":[]}"""))
        assertEquals(
            WidgetConfig(chatIds = emptyList(), showUnreadBadge = false, compact = false),
            WidgetConfigJsonPolicy.decodeConfig("""{"badge":false}""")
        )
        assertEquals(emptyList(), WidgetConfigJsonPolicy.decodeConfig("""{"chatIds":null}""").chatIds)
        assertEquals(emptyList(), WidgetConfigJsonPolicy.decodeConfig("""{"chatIds":"abc"}""").chatIds)
        assertEquals(emptyList(), WidgetConfigJsonPolicy.decodeConfig("""{"chatIds":{"a":1}}""").chatIds)
    }

    @Test
    fun `badge and compact type coercion is pinned`() {
        // 布尔直取。
        assertEquals(
            WidgetConfig(showUnreadBadge = false, compact = true),
            WidgetConfigJsonPolicy.decodeConfig("""{"badge":false,"compact":true}""")
        )
        // "true"/"false" 字符串（大小写不敏感）被 optBoolean 强制转换。
        assertEquals(
            WidgetConfig(showUnreadBadge = true, compact = false),
            WidgetConfigJsonPolicy.decodeConfig("""{"badge":"true","compact":"false"}""")
        )
        assertEquals(
            WidgetConfig(showUnreadBadge = true, compact = false),
            WidgetConfigJsonPolicy.decodeConfig("""{"badge":"TRUE","compact":"FALSE"}""")
        )
        // 数字、null、其他字符串、对象、数组一律回默认值。
        assertEquals(
            WidgetConfig(showUnreadBadge = true, compact = false),
            WidgetConfigJsonPolicy.decodeConfig("""{"badge":1,"compact":0}""")
        )
        assertEquals(
            WidgetConfig(showUnreadBadge = true, compact = false),
            WidgetConfigJsonPolicy.decodeConfig("""{"badge":null,"compact":null}""")
        )
        assertEquals(
            WidgetConfig(showUnreadBadge = true, compact = false),
            WidgetConfigJsonPolicy.decodeConfig("""{"badge":"yes","compact":"no"}""")
        )
        assertEquals(
            WidgetConfig(showUnreadBadge = true, compact = false),
            WidgetConfigJsonPolicy.decodeConfig("""{"badge":{},"compact":[]}""")
        )
    }

    @Test
    fun `unknown fields are ignored and duplicate keys fall back to default`() {
        assertEquals(
            WidgetConfig(chatIds = listOf("a"), showUnreadBadge = false, compact = false),
            WidgetConfigJsonPolicy.decodeConfig("""{"chatIds":["a"],"extra":{"x":1},"badge":false}""")
        )
        // 重复键在 JSONObject 构造时抛错，整串回默认配置。
        assertEquals(
            WidgetConfig(),
            WidgetConfigJsonPolicy.decodeConfig("""{"badge":true,"badge":false}""")
        )
    }
}
