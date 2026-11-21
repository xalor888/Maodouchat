package com.maodouchat.domain.messaging

import com.maodouchat.core.model.MessageMeta
import com.maodouchat.core.model.MessageType
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContentPayloadCodecFuzzTest {

    private companion object {
        private const val ROUND_TRIP_ITERATIONS = 150
        private const val LEGACY_ITERATIONS = 150
        // 注意：刻意不含 '&'——unescapeHtmlEntities 遇不到 '&' 时原样返回，
        // legacy 解码后的正文才是原正文的严格前缀，断言才站得住。
        private val BODY_FRAGMENTS = listOf(
            "hello", " ", "world", "\n", "<meta>", "</meta>",
            "{\"forwardedFrom\":\"Alice\"}", "{not json", "\"", "'", ":", ",", "{", "}", "[", "]",
            "😀", "中文", "a<b", "x>y", "100%", "a/b", "a\\b"
        )
    }

    private fun randomBody(random: Random): String {
        val count = random.nextInt(0, 6)
        return buildString { repeat(count) { append(BODY_FRAGMENTS.random(random)) } }
    }

    private fun randomMeta(random: Random): MessageMeta {
        val mentions = List(random.nextInt(0, 3)) { "u_${random.nextInt(100)}" }
        val attachmentId = if (random.nextBoolean()) "att_${random.nextInt(100)}" else null
        return MessageMeta(
            mentions = mentions,
            replyToId = if (random.nextBoolean()) "r_${random.nextInt(100)}" else null,
            forwardedFrom = if (random.nextBoolean()) "Alice_${random.nextInt(10)}" else null,
            attachmentId = attachmentId,
            fileName = if (random.nextBoolean()) "file_${random.nextInt(10)}.jpg" else null,
            markdown = random.nextBoolean(),
            aiAssisted = random.nextBoolean(),
            translations = if (random.nextBoolean()) mapOf("en" to "hello") else emptyMap(),
            voiceDurationMs = if (random.nextBoolean()) random.nextLong(0, 60000) else null
        )
    }

    @Test
    fun `structured round trip preserves random payloads`() {
        val random = Random(0x71_2026)
        repeat(ROUND_TRIP_ITERATIONS) { i ->
            val meta = randomMeta(random)
            val payload = DecodedContentPayload(
                type = MessageType.entries.random(random),
                body = randomBody(random),
                metadata = meta,
                replyToMessageId = meta.replyToId,
                attachmentIds = meta.attachmentId?.let(::listOf) ?: emptyList(),
                mentionedUserIds = meta.mentions,
                expiresInSeconds = random.nextInt(0, 86401),
                attributes = if (random.nextBoolean()) mapOf("w" to "800", "h" to "600") else emptyMap()
            )
            assertEquals(payload, ContentPayloadCodec.decode(ContentPayloadCodec.encode(payload)), "fuzz 迭代 #$i：结构化往返必须恒等")
        }
    }

    @Test
    fun `legacy decode never throws and body is always a prefix`() {
        val random = Random(0x72_2026)
        repeat(LEGACY_ITERATIONS) { i ->
            val body = randomBody(random)
            val decoded = ContentPayloadCodec.decode(MessagingV2Content(type = "TEXT", body = body))
            assertTrue(body.startsWith(decoded.body), "fuzz 迭代 #$i：legacy 解码后正文必须是原正文前缀：$body")
            if (!body.contains("<meta>")) {
                assertEquals(body, decoded.body, "fuzz 迭代 #$i：无 <meta> 标记时正文必须原样保留")
                assertEquals(MessageMeta(), decoded.metadata, "fuzz 迭代 #$i：无 <meta> 标记时 metadata 必须为空")
            }
        }
    }

    @Test
    fun `unknown wire types fall back to text and known names resolve`() {
        val random = Random(0x73_2026)
        repeat(LEGACY_ITERATIONS) { i ->
            val type = MessageType.entries.random(random)
            val spelled = when (random.nextInt(3)) {
                0 -> type.name
                1 -> type.name.lowercase()
                else -> type.name.replaceFirstChar { it.lowercaseChar() }
            }
            val decoded = ContentPayloadCodec.decode(MessagingV2Content(type = spelled, body = "hi"))
            assertEquals(type, decoded.type, "fuzz 迭代 #$i：已知类型名 $spelled 必须解析为 $type")
        }
        listOf("FROBNICATOR", "", "text2", "IMAGE ", " IMAGE").forEach { wire ->
            val decoded = ContentPayloadCodec.decode(MessagingV2Content(type = wire, body = "hi"))
            assertEquals(MessageType.TEXT, decoded.type, "wire=$wire 必须回落到 TEXT")
        }
    }
}
