package com.maodouchat.data

import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageMeta
import com.maodouchat.data.model.MessageReaction
import com.maodouchat.data.model.MessageStatus
import com.maodouchat.data.model.MessageType
import com.maodouchat.data.repository.mergeDeliveryStatusForPersistence
import com.maodouchat.data.repository.mergeLocalMediaMetaForPersistence
import com.maodouchat.data.repository.mergeMessageForPersistence
import com.maodouchat.util.ViewOncePolicy
import kotlin.test.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessagePersistencePolicyTest {

    @Test
    fun `equal revision can unstar and clear reactions`() {
        val existing = base("m1").copy(
            starred = true,
            reactions = listOf(MessageReaction("u1", "❤")),
            status = MessageStatus.READ
        )
        val incoming = existing.copy(starred = false, reactions = emptyList(), status = MessageStatus.SENT)

        val merged = mergeMessageForPersistence(existing, incoming)

        assertFalse(merged.starred)
        assertTrue(merged.reactions.isEmpty())
        assertEquals(MessageStatus.READ, merged.status)
    }

    @Test
    fun `newer revision can unstar and clear reactions`() {
        val existing = base("m1").copy(
            content = "old",
            editedAt = 10L,
            starred = true,
            reactions = listOf(MessageReaction("u1", "👍"))
        )
        val incoming = base("m1").copy(
            content = "new",
            editedAt = 20L,
            starred = false,
            reactions = emptyList()
        )

        val merged = mergeMessageForPersistence(existing, incoming)

        assertEquals("new", merged.content)
        assertFalse(merged.starred)
        assertTrue(merged.reactions.isEmpty())
    }

    @Test
    fun `older revision cannot wipe newer star and reactions`() {
        val reactions = listOf(MessageReaction("u2", "🎉", reactedAt = 1L))
        val existing = base("m1").copy(
            content = "new",
            editedAt = 20L,
            starred = true,
            reactions = reactions
        )
        val incoming = base("m1").copy(
            content = "old",
            editedAt = 10L,
            starred = false,
            reactions = emptyList()
        )

        val merged = mergeMessageForPersistence(existing, incoming)

        assertEquals("new", merged.content)
        assertTrue(merged.starred)
        assertEquals(reactions, merged.reactions)
    }

    @Test
    fun `readable plaintext is preferred over ciphertext envelope`() {
        val reactions = listOf(MessageReaction("u1", "❤", reactedAt = 1L))
        val existing = base("m1").copy(content = "hello readable")
        val incoming = base("m1").copy(
            content = """{"algorithm":"signal-v2","senderDeviceId":1,"payloadType":"TEXT","ciphertextType":"signal","ciphertext":"Y2lwaGVy"}""",
            starred = true,
            reactions = reactions
        )

        val merged = mergeMessageForPersistence(existing, incoming)

        assertEquals("hello readable", merged.content)
        assertTrue(merged.starred)
        assertEquals(reactions, merged.reactions)
    }

    @Test
    fun `sealed sender cannot be downgraded by stale snapshot`() {
        val sealed = base("m1").copy(sealedSender = true)
        val stale = base("m1").copy(sealedSender = false, status = MessageStatus.SENT)

        val merged = mergeMessageForPersistence(sealed, stale)

        assertTrue(merged.sealedSender)
    }

    @Test
    fun `delivery status does not regress READ to SENT`() {
        assertEquals(
            MessageStatus.READ,
            mergeDeliveryStatusForPersistence(MessageStatus.READ, MessageStatus.SENT)
        )
    }

    @Test
    fun `view once opened state is encoded into persisted content`() {
        val message = base("m1").copy(type = MessageType.IMAGE)
            .withEncodedMeta(MessageMeta(viewOnce = true))

        val opened = ViewOncePolicy.markOpened(message)

        assertEquals("text", opened.parsedContent())
        assertTrue(opened.parsedMeta().viewOnce)
        assertTrue(opened.parsedMeta().viewOnceOpened)
    }

    @Test
    fun `stale snapshot cannot reopen view once media`() {
        val opened = base("m1").copy(type = MessageType.IMAGE)
            .withEncodedMeta(MessageMeta(viewOnce = true, viewOnceOpened = true))
        val stale = base("m1").copy(type = MessageType.IMAGE)
            .withEncodedMeta(MessageMeta(viewOnce = true, viewOnceOpened = false))

        val merged = mergeMessageForPersistence(opened, stale)

        assertTrue(merged.parsedMeta().viewOnce)
        assertTrue(merged.parsedMeta().viewOnceOpened)
    }

    @Test
    fun `local media flags do not overwrite newer body or metadata`() {
        val current = base("m1").copy(content = "new body")
            .withEncodedMeta(MessageMeta(replyToId = "new-reply", spoilerMedia = true))
        val staleLocal = base("m1").copy(content = "old body")
            .withEncodedMeta(
                MessageMeta(
                    replyToId = "old-reply",
                    spoilerMedia = true,
                    spoilerRevealed = true
                )
            )

        val merged = mergeLocalMediaMetaForPersistence(current, staleLocal)

        assertEquals("new body", merged.parsedContent())
        assertEquals("new-reply", merged.parsedMeta().replyToId)
        assertTrue(merged.parsedMeta().spoilerRevealed)
    }

    private fun base(id: String) = Message(
        id = id,
        chatId = "c1",
        senderId = "u1",
        content = "text",
        type = MessageType.TEXT,
        timestamp = 1L,
        status = MessageStatus.SENT
    )

    // ---- G185c 追加：以下为原 9 例未覆盖的部分 ----

    @Test
    fun `an already revoked row is not resurrected by a newer non revoked snapshot`() {
        val existing = base("m1").copy(content = "旧内容", type = MessageType.REVOKED, editedAt = 100)
        val incoming = base("m1").copy(content = "新内容", editedAt = 200)
        val merged = mergeMessageForPersistence(existing, incoming)
        kotlin.test.assertEquals(MessageType.REVOKED, merged.type)
        kotlin.test.assertEquals("旧内容", merged.content, "已撤销的行内容不可被新快照顶掉")
    }

    @Test
    fun `an incoming revoke wins over everything`() {
        val existing = base("m1").copy(content = "旧内容", editedAt = 500)
        val incoming = base("m1").copy(content = "", type = MessageType.REVOKED, editedAt = 100)
        kotlin.test.assertEquals(
            MessageType.REVOKED,
            mergeMessageForPersistence(existing, incoming).type,
            "撤销指令一律生效，即使它比现有行旧",
        )
    }

    @Test
    fun `delivery status climbs the ladder monotonically`() {
        kotlin.test.assertEquals(
            MessageStatus.DELIVERED,
            mergeDeliveryStatusForPersistence(MessageStatus.SENT, MessageStatus.DELIVERED),
        )
        kotlin.test.assertEquals(
            MessageStatus.READ,
            mergeDeliveryStatusForPersistence(MessageStatus.DELIVERED, MessageStatus.READ),
        )
    }

    @Test
    fun `failed only replaces sending and never clobbers a delivered or read row`() {
        kotlin.test.assertEquals(
            MessageStatus.FAILED,
            mergeDeliveryStatusForPersistence(MessageStatus.SENDING, MessageStatus.FAILED),
            "本地发送失败应把 SENDING 置为 FAILED",
        )
        listOf(MessageStatus.SENT, MessageStatus.DELIVERED, MessageStatus.READ).forEach { s ->
            kotlin.test.assertEquals(
                s,
                mergeDeliveryStatusForPersistence(s, MessageStatus.FAILED),
                "已投递/已读的行不可被一个迟到的 FAILED 快照抹掉",
            )
        }
    }

    @Test
    fun `a local failed row can be rescued only by sending or sent`() {
        kotlin.test.assertEquals(
            MessageStatus.SENDING,
            mergeDeliveryStatusForPersistence(MessageStatus.FAILED, MessageStatus.SENDING),
            "重试路径：FAILED → SENDING 必须放行",
        )
        kotlin.test.assertEquals(
            MessageStatus.SENT,
            mergeDeliveryStatusForPersistence(MessageStatus.FAILED, MessageStatus.SENT),
            "重试路径：FAILED → SENT 必须放行",
        )
        listOf(MessageStatus.DELIVERED, MessageStatus.READ).forEach { s ->
            kotlin.test.assertEquals(
                MessageStatus.FAILED,
                mergeDeliveryStatusForPersistence(MessageStatus.FAILED, s),
                "FAILED 不可被 $s 顶掉——否则待重试的本地失败被静默吞掉",
            )
        }
    }

    @Test
    fun `same status on both sides is returned unchanged`() {
        MessageStatus.values().forEach { s ->
            kotlin.test.assertEquals(s, mergeDeliveryStatusForPersistence(s, s))
        }
    }

    @Test
    fun `merge local media meta only ORs the two given sides`() {
        // 与 preserveLocalMediaFlags（三方 OR）不同，这个只 OR current/local 两方
        val current = base("m1").copy(meta = MessageMeta(viewOnce = true))
        val local = base("m1").copy(meta = MessageMeta(spoilerMedia = true))
        val meta = mergeLocalMediaMetaForPersistence(current, local).parsedMeta()
        assertTrue(meta.viewOnce, "current 侧的 flag 要留下")
        assertTrue(meta.spoilerMedia, "local 侧的 flag 也要留下")
    }

    @Test
    fun `readable plaintext wins over wire ciphertext in both orders`() {
        // 已有那条只测了一个方向；这里补反向，证明顺序无关
        val wire = "eyJhbGciOiJkaXIiLCJ0eXBlIjoiZGlzY3JldGUifQ"
        val plain = "明天见"
        kotlin.test.assertEquals(
            plain,
            mergeMessageForPersistence(
                base("m1").copy(content = plain),
                base("m1").copy(content = wire),
            ).content,
            "明文在左、密文在右时也必须胜出",
        )
    }

    @Test
    fun `wire ciphertext is preferred over a decrypt placeholder`() {
        // 密文可被 session repair 后解开；UI 占位符不能——所以密文更优
        val wire = "eyJhbGciOiJkaXIiLCJ0eXBlIjoiZGlzY3JldGUifQ"
        kotlin.test.assertEquals(
            wire,
            mergeMessageForPersistence(
                base("m1").copy(content = "[无法解密的消息]"),
                base("m1").copy(content = wire),
            ).content,
        )
    }
}
