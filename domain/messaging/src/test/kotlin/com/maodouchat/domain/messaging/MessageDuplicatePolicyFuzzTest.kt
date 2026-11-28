package com.maodouchat.domain.messaging

import com.maodouchat.core.model.Message
import com.maodouchat.core.model.MessageMeta
import com.maodouchat.core.model.MessageReaction
import com.maodouchat.core.model.MessageStatus
import com.maodouchat.core.model.MessageType
import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * MessageDuplicatePolicy fuzz（清单 931 行第十六块，簇 3）。
 *
 * 源码实测的不变式（Python 迷你模型校验实体解码与解析规则）：
 * - isRedundantWrite 是 12 字段原始 content 全等（meta 不在其中，改 meta 仍判冗余）；
 * - isSameDelivery 只看 chatId/senderId/timestamp/type + 解析后 content（剥最后一个 <meta> 后缀再解实体）；
 * - pickCanonical 只换 id、其余全取 incoming；两判定均对称；
 * - raw 与 parsed 的区分：仅改实体编码形式（"a&amp;b" vs "a&b"）时冗余 false、同投递 true。
 */
class MessageDuplicatePolicyFuzzTest {

    private companion object {
        private const val RANDOM_ITERATIONS = 200
        private val decimalEntity = Regex("&#(\\d{1,7});")
        private val hexEntity = Regex("&#x([0-9a-fA-F]{1,6});", RegexOption.IGNORE_CASE)
        private val contentFragments = listOf(
            "hello", "hi", "a", "b", " ", "x", "hello world", "&amp;", "&lt;", "&gt;",
            "&quot;", "&apos;", "&nbsp;", "&#65;", "&#x41;", "&#0;", "&#x110000;",
            "&#9999999;", "&#12345678;", "&#10;", "&unknown;", "&#;", "<meta>", "</meta>",
            "<meta>{}", "<meta>{\"a\":1}</meta>", "a<b", "100%"
        )
        private val idAlphabet = ('a'..'z') + ('0'..'9')
    }

    private fun randomId(random: Random): String = buildString {
        repeat(random.nextInt(0, 7)) { append(idAlphabet[random.nextInt(idAlphabet.size)]) }
    }

    private fun randomContent(random: Random): String = buildString {
        repeat(random.nextInt(0, 6)) { append(contentFragments[random.nextInt(contentFragments.size)]) }
    }

    private fun base(id: String) = Message(
        id = id,
        chatId = "c1",
        senderId = "u1",
        content = "hello",
        type = MessageType.TEXT,
        timestamp = 42L,
        status = MessageStatus.SENT
    )

    private fun randomMessage(random: Random): Message {
        val reactions = List(random.nextInt(0, 3)) {
            MessageReaction("u${random.nextInt(0, 4)}", listOf("heart", "👍", "🔥")[random.nextInt(3)], random.nextLong(0, 1000))
        }
        return Message(
            id = randomId(random),
            chatId = "c${random.nextInt(0, 3)}",
            senderId = "u${random.nextInt(0, 4)}",
            content = randomContent(random),
            type = MessageType.entries[random.nextInt(MessageType.entries.size)],
            timestamp = listOf(0L, 1L, 42L, -5L, Long.MAX_VALUE, random.nextLong())[random.nextInt(6)],
            status = MessageStatus.entries[random.nextInt(MessageStatus.entries.size)],
            editedAt = if (random.nextBoolean()) null else random.nextLong(0, 1000),
            starred = random.nextBoolean(),
            meta = if (random.nextBoolean()) MessageMeta() else MessageMeta(replyToId = randomId(random)),
            reactions = reactions,
            expiresAt = if (random.nextBoolean()) null else random.nextLong(0, 1000),
            sealedSender = random.nextBoolean()
        )
    }

    private fun twelveFieldsEqual(a: Message, b: Message): Boolean {
        return a.id == b.id && a.chatId == b.chatId && a.senderId == b.senderId &&
            a.content == b.content && a.type == b.type && a.timestamp == b.timestamp &&
            a.status == b.status && a.editedAt == b.editedAt && a.starred == b.starred &&
            a.reactions == b.reactions && a.expiresAt == b.expiresAt && a.sealedSender == b.sealedSender
    }

    private fun unescapeOracle(raw: String): String {
        if (raw.isEmpty() || '&' !in raw) return raw
        var out = decimalEntity.replace(raw) { match ->
            match.groupValues[1].toIntOrNull()?.takeIf { it in 1..0x10FFFF }?.let { code ->
                String(intArrayOf(code), 0, 1)
            } ?: match.value
        }
        out = hexEntity.replace(out) { match ->
            match.groupValues[1].toIntOrNull(16)?.takeIf { it in 1..0x10FFFF }?.let { code ->
                String(intArrayOf(code), 0, 1)
            } ?: match.value
        }
        return out
            .replace("&nbsp;", " ")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
    }

    private fun parsedContentOracle(content: String): String {
        val idx = content.lastIndexOf("<meta>")
        val body = if (idx < 0) content else content.substring(0, idx)
        return unescapeOracle(body)
    }

    private fun sameDeliveryOracle(a: Message, b: Message): Boolean {
        return a.chatId == b.chatId && a.senderId == b.senderId &&
            a.timestamp == b.timestamp && a.type == b.type &&
            parsedContentOracle(a.content) == parsedContentOracle(b.content)
    }

    private fun mutate(message: Message, random: Random): Message {
        var out = message
        val fields = (0..11).shuffled(random).take(random.nextInt(0, 4))
        for (field in fields) {
            out = when (field) {
                0 -> out.copy(id = randomId(random))
                1 -> out.copy(chatId = "c${random.nextInt(3, 9)}")
                2 -> out.copy(senderId = "u${random.nextInt(4, 9)}")
                3 -> out.copy(content = randomContent(random))
                4 -> out.copy(type = MessageType.entries[random.nextInt(MessageType.entries.size)])
                5 -> out.copy(timestamp = out.timestamp + 1 + random.nextLong(0, 100))
                6 -> out.copy(status = MessageStatus.entries[random.nextInt(MessageStatus.entries.size)])
                7 -> out.copy(editedAt = if (out.editedAt == null) 7L else null)
                8 -> out.copy(starred = !out.starred)
                9 -> out.copy(reactions = out.reactions + MessageReaction("u9", "heart", 9L))
                10 -> out.copy(expiresAt = if (out.expiresAt == null) 99L else null)
                else -> out.copy(sealedSender = !out.sealedSender)
            }
        }
        if (random.nextInt(0, 4) == 0) out = out.copy(meta = MessageMeta(replyToId = "mx"))
        return out
    }

    @Test
    fun `redundantWriteMatchesTwelveFieldOracle`() {
        val random = Random(0x16_2026)
        repeat(RANDOM_ITERATIONS) { i ->
            val existing = randomMessage(random)
            val merged = mutate(existing, random)
            assertEquals(
                twelveFieldsEqual(existing, merged),
                MessageDuplicatePolicy.isRedundantWrite(existing, merged),
                "fuzz 迭代 #$i：改动后 12 字段全等当且仅当冗余"
            )
        }
    }

    @Test
    fun `sameDeliveryMatchesOracle`() {
        val random = Random(0x16_2026)
        repeat(RANDOM_ITERATIONS) { i ->
            val a = randomMessage(random)
            val b = randomMessage(random)
            assertEquals(
                sameDeliveryOracle(a, b),
                MessageDuplicatePolicy.isSameDelivery(a, b),
                "fuzz 迭代 #$i：(${a.chatId}, ${a.senderId}, ${a.timestamp}, ${a.type}, ${a.content}) vs (${b.chatId}, ${b.senderId}, ${b.timestamp}, ${b.type}, ${b.content})"
            )
        }
    }

    @Test
    fun `metaSuffixAndEntitiesUseParsedContent`() {
        val random = Random(0x16_2026)
        val plainFragments = listOf("hello", "hi", "a", "b", "x", "hello world", "&amp;", "a&b", "&#65;")
        repeat(150) { i ->
            val body = buildString {
                repeat(random.nextInt(0, 4)) { append(plainFragments[random.nextInt(plainFragments.size)]) }
            }
            val plain = base("m1").copy(content = body)
            val withMeta = plain.copy(id = "m2", content = body + "<meta>{}")
            assertTrue(
                MessageDuplicatePolicy.isSameDelivery(plain, withMeta),
                "fuzz 迭代 #$i：正文 + <meta> 后缀与原文同投递"
            )
            val doubleMeta = plain.copy(id = "m3", content = body + "<meta>{}<meta>{}")
            assertFalse(
                MessageDuplicatePolicy.isSameDelivery(withMeta, doubleMeta),
                "fuzz 迭代 #$i：第二个 <meta> 后缀改变解析后正文（只剥最后一个）"
            )
            assertEquals(
                parsedContentOracle(body),
                parsedContentOracle(body + "<meta>{}"),
                "fuzz 迭代 #$i：oracle 自身须一致"
            )
        }
        val encoded = base("local-tmp").copy(content = "a&amp;b")
        val decoded = encoded.copy(id = "srv-1", content = "a&b")
        assertTrue(MessageDuplicatePolicy.isSameDelivery(encoded, decoded), "仅实体编码不同仍同投递")
        assertFalse(MessageDuplicatePolicy.isRedundantWrite(encoded, decoded), "仅实体编码不同不算冗余写入")
    }

    @Test
    fun `pickCanonicalKeepsExistingId`() {
        val random = Random(0x16_2026)
        repeat(150) { i ->
            val existing = randomMessage(random)
            val incoming = randomMessage(random)
            val canonical = MessageDuplicatePolicy.pickCanonical(existing, incoming)
            assertEquals(existing.id, canonical.id, "fuzz 迭代 #$i：结果 id 取 existing")
            assertEquals(incoming.copy(id = existing.id), canonical, "fuzz 迭代 #$i：其余字段全取 incoming")
            assertEquals(
                canonical,
                MessageDuplicatePolicy.pickCanonical(canonical, incoming),
                "fuzz 迭代 #$i：pickCanonical 幂等"
            )
        }
    }

    @Test
    fun `bothPredicatesAreSymmetric`() {
        val random = Random(0x16_2026)
        repeat(100) { i ->
            val a = randomMessage(random)
            val b = randomMessage(random)
            assertEquals(
                MessageDuplicatePolicy.isRedundantWrite(b, a),
                MessageDuplicatePolicy.isRedundantWrite(a, b),
                "fuzz 迭代 #$i：isRedundantWrite 必须对称"
            )
            assertEquals(
                MessageDuplicatePolicy.isSameDelivery(b, a),
                MessageDuplicatePolicy.isSameDelivery(a, b),
                "fuzz 迭代 #$i：isSameDelivery 必须对称"
            )
        }
    }

    @Test
    fun `singleFieldFlipBreaksRedundancy`() {
        val existing = base("srv-1")
        assertFalse(MessageDuplicatePolicy.isRedundantWrite(existing, existing.copy(id = "srv-2")))
        assertFalse(MessageDuplicatePolicy.isRedundantWrite(existing, existing.copy(chatId = "c2")))
        assertFalse(MessageDuplicatePolicy.isRedundantWrite(existing, existing.copy(senderId = "u2")))
        assertFalse(MessageDuplicatePolicy.isRedundantWrite(existing, existing.copy(content = "bye")))
        assertFalse(MessageDuplicatePolicy.isRedundantWrite(existing, existing.copy(type = MessageType.IMAGE)))
        assertFalse(MessageDuplicatePolicy.isRedundantWrite(existing, existing.copy(timestamp = 43L)))
        assertFalse(MessageDuplicatePolicy.isRedundantWrite(existing, existing.copy(status = MessageStatus.READ)))
        assertFalse(MessageDuplicatePolicy.isRedundantWrite(existing, existing.copy(editedAt = 5L)))
        assertFalse(MessageDuplicatePolicy.isRedundantWrite(existing, existing.copy(starred = true)))
        assertFalse(
            MessageDuplicatePolicy.isRedundantWrite(
                existing,
                existing.copy(reactions = listOf(MessageReaction("bob", "heart", 1L)))
            )
        )
        assertFalse(MessageDuplicatePolicy.isRedundantWrite(existing, existing.copy(expiresAt = 99L)))
        assertFalse(MessageDuplicatePolicy.isRedundantWrite(existing, existing.copy(sealedSender = true)))
        assertTrue(
            MessageDuplicatePolicy.isRedundantWrite(
                existing,
                existing.copy(meta = existing.meta.copy(replyToId = "m0"))
            ),
            "只改 meta 仍判冗余（meta 不在 12 字段内）"
        )
    }
}
