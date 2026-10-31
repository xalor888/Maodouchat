package com.maodouchat.chatdetail

import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageStatus
import com.maodouchat.data.model.MessageType
import com.maodouchat.data.model.MessageReaction
import com.maodouchat.ui.screen.chatdetail.mergeMessageVersions
import org.junit.Assert.assertEquals
import org.junit.Test

class DecryptFailureDisplayPolicyTest {

    @Test
    fun `plaintext wins over decrypt failure placeholder on equal revision`() {
        val readable = message("m1", "hello")
        val failed = message("m1", "无法解密")

        assertEquals("hello", mergeMessageVersions(listOf(readable), listOf(failed)).single().content)
        assertEquals("hello", mergeMessageVersions(listOf(failed), listOf(readable)).single().content)
    }

    @Test
    fun `plaintext wins over english decrypt failed and session missing placeholders`() {
        val readable = message("m1", "ok")
        val decryptFailed = message("m1", "decrypt failed")
        val sessionMissing = message("m1", "session missing")

        assertEquals("ok", mergeMessageVersions(listOf(readable), listOf(decryptFailed)).single().content)
        assertEquals("ok", mergeMessageVersions(listOf(sessionMissing), listOf(readable)).single().content)
    }

    @Test
    fun `plaintext wins over group key missing placeholder`() {
        val readable = message("m1", "群消息")
        val keyMissing = message("m1", "群密钥缺失")

        assertEquals("群消息", mergeMessageVersions(listOf(readable), listOf(keyMissing)).single().content)
    }

    @Test
    fun `plaintext wins over wire ciphertext envelope`() {
        val readable = message("m1", "hello")
        val wire = message(
            "m1",
            """{"version":1,"algorithm":"signal-sender-key-v1","ciphertext":"abc"}"""
        )

        assertEquals("hello", mergeMessageVersions(listOf(readable), listOf(wire)).single().content)
        assertEquals("hello", mergeMessageVersions(listOf(wire), listOf(readable)).single().content)
    }

    @Test
    fun `both decrypt failure placeholders keep the current stable copy`() {
        val current = message("m1", "无法解密")
        val incoming = message("m1", "decrypt failed")

        assertEquals("无法解密", mergeMessageVersions(listOf(current), listOf(incoming)).single().content)
    }

    @Test
    fun `newer edit still cannot replace plaintext with decrypt placeholder`() {
        val readable = message("m1", "hello").copy(editedAt = 100L)
        val failed = message("m1", "无法解密").copy(editedAt = 200L)

        val merged = mergeMessageVersions(listOf(readable), listOf(failed)).single()
        assertEquals("hello", merged.content)
        assertEquals(200L, merged.editedAt)
    }

    @Test
    fun `decrypt related user plaintext is kept when merged with wire`() {
        val wire = message(
            "m1",
            """{"senderDeviceId":76,"payloadType":"TEXT","entries":[{"ciphertext":"NAgB"}]}""",
        )
        listOf(
            "这个怎么解密？",
            "decrypt this message",
            "How do I decrypt this?",
            "session missing 是什么意思？",
        ).forEach { content ->
            val readable = message("m1", content)
            assertEquals(content, mergeMessageVersions(listOf(readable), listOf(wire)).single().content)
            assertEquals(content, mergeMessageVersions(listOf(wire), listOf(readable)).single().content)
        }
    }

    @Test
    fun `revoked is terminal and never replaced by plaintext`() {
        val revoked = message("m1", "hello").copy(type = MessageType.REVOKED)
        val readable = message("m1", "hello")

        assertEquals(revoked, mergeMessageVersions(listOf(revoked), listOf(readable)).single())
        assertEquals(revoked, mergeMessageVersions(listOf(readable), listOf(revoked)).single())
    }

    @Test
    fun `equal revision never regresses local delivery status`() {
        val delivered = message("m1", "hello").copy(status = MessageStatus.DELIVERED)
        val read = message("m1", "hello").copy(status = MessageStatus.READ)

        assertEquals(MessageStatus.READ, mergeMessageVersions(listOf(delivered), listOf(read)).single().status)
        assertEquals(MessageStatus.READ, mergeMessageVersions(listOf(read), listOf(delivered)).single().status)
    }

    @Test
    fun `failed side channel never lingers when server state arrives`() {
        val failed = message("m1", "hello").copy(status = MessageStatus.FAILED)
        val sent = message("m1", "hello").copy(status = MessageStatus.SENT)

        assertEquals(MessageStatus.SENT, mergeMessageVersions(listOf(failed), listOf(sent)).single().status)
        assertEquals(MessageStatus.SENT, mergeMessageVersions(listOf(sent), listOf(failed)).single().status)
    }

    @Test
    fun `plaintext wins over base64 wire envelope`() {
        val readable = message("m1", "hello")
        val wire = message("m1", "eyJ2ZXJzaW9uIjoxLCJhbGdvcml0aG0iOiJzaWduYWwifQ==")

        assertEquals("hello", mergeMessageVersions(listOf(readable), listOf(wire)).single().content)
        assertEquals("hello", mergeMessageVersions(listOf(wire), listOf(readable)).single().content)
    }

    @Test
    fun `padded placeholder is still recognized as placeholder`() {
        val readable = message("m1", "hello")
        val padded = message("m1", "  无法解密  ")

        assertEquals("hello", mergeMessageVersions(listOf(readable), listOf(padded)).single().content)
        assertEquals("hello", mergeMessageVersions(listOf(padded), listOf(readable)).single().content)
    }

    private fun message(id: String, content: String) = Message(
        id = id,
        chatId = "chat-1",
        senderId = "user-1",
        content = content,
        type = MessageType.TEXT,
        timestamp = 100L,
        status = MessageStatus.SENT
    )

    @Test
    fun `plaintext wins over MD prefixed envelope`() {
        val readable = message("m1", "hello")
        val wire = message("m1", "MD:abcdefghijklm")

        assertEquals("hello", mergeMessageVersions(listOf(readable), listOf(wire)).single().content)
        assertEquals("hello", mergeMessageVersions(listOf(wire), listOf(readable)).single().content)
    }

    @Test
    fun `plaintext wins over SK prefixed envelope`() {
        val readable = message("m1", "hello")
        val wire = message("m1", "SK:abcdefghijklm")

        assertEquals("hello", mergeMessageVersions(listOf(readable), listOf(wire)).single().content)
        assertEquals("hello", mergeMessageVersions(listOf(wire), listOf(readable)).single().content)
    }

    @Test
    fun `newer edit replaces placeholder with plaintext`() {
        val failed = message("m1", "无法解密")
        val readable = message("m1", "hello").copy(editedAt = 200L)

        val merged = mergeMessageVersions(listOf(failed), listOf(readable)).single()
        assertEquals("hello", merged.content)
        assertEquals(200L, merged.editedAt)
    }

    @Test
    fun `equal revision plaintext versus plaintext takes incoming`() {
        val old = message("m1", "old")
        val new = message("m1", "new")

        assertEquals("new", mergeMessageVersions(listOf(old), listOf(new)).single().content)
        assertEquals("old", mergeMessageVersions(listOf(new), listOf(old)).single().content)
    }

    @Test
    fun `revoked incoming replaces plaintext regardless of older edit`() {
        val readable = message("m1", "hello").copy(editedAt = 200L)
        val revoked = message("m1", "hello").copy(type = MessageType.REVOKED, editedAt = 100L)

        assertEquals(revoked, mergeMessageVersions(listOf(readable), listOf(revoked)).single())
        assertEquals(revoked, mergeMessageVersions(listOf(revoked), listOf(readable)).single())
    }

    @Test
    fun `wire versus wire takes candidate`() {
        val ey = message("m1", "eyJ2ZXJzaW9uIjoxLCJhbGdvcml0aG0iOiJzaWduYWwifQ==")
        val md = message("m1", "MD:abcdefghijklmnop")
        assertEquals("MD:abcdefghijklmnop", mergeMessageVersions(listOf(ey), listOf(md)).single().content)
        assertEquals("eyJ2ZXJzaW9uIjoxLCJhbGdvcml0aG0iOiJzaWduYWwifQ==", mergeMessageVersions(listOf(md), listOf(ey)).single().content)
    }

    @Test
    fun `failed never beats delivered in either order`() {
        val failed = message("m1", "hello").copy(status = MessageStatus.FAILED)
        val delivered = message("m1", "hello").copy(status = MessageStatus.DELIVERED)
        assertEquals(MessageStatus.DELIVERED, mergeMessageVersions(listOf(failed), listOf(delivered)).single().status)
        assertEquals(MessageStatus.DELIVERED, mergeMessageVersions(listOf(delivered), listOf(failed)).single().status)
    }

    @Test
    fun `sending never regresses delivered`() {
        val sending = message("m1", "hello").copy(status = MessageStatus.SENDING)
        val delivered = message("m1", "hello").copy(status = MessageStatus.DELIVERED)
        assertEquals(MessageStatus.DELIVERED, mergeMessageVersions(listOf(sending), listOf(delivered)).single().status)
        assertEquals(MessageStatus.DELIVERED, mergeMessageVersions(listOf(delivered), listOf(sending)).single().status)
    }

    @Test
    fun `star follows candidate on equal revision`() {
        val current = message("m1", "hello").copy(starred = true)
        val candidate = message("m1", "hello").copy(starred = false)
        assertEquals(false, mergeMessageVersions(listOf(current), listOf(candidate)).single().starred)
        val unstarred = message("m1", "hello").copy(starred = false)
        val starring = message("m1", "hello").copy(starred = true)
        assertEquals(true, mergeMessageVersions(listOf(unstarred), listOf(starring)).single().starred)
    }

    @Test
    fun `distinct ids merge independently in timestamp order`() {
        val old = message("m2", "hello").copy(timestamp = 100L)
        val oldFailed = message("m2", "无法解密").copy(timestamp = 100L)
        val new = message("m1", "world").copy(timestamp = 200L)
        val merged = mergeMessageVersions(listOf(old, new), listOf(oldFailed))
        assertEquals(listOf("m2", "m1"), merged.map { it.id })
        assertEquals("hello", merged.first { it.id == "m2" }.content)
        assertEquals("world", merged.first { it.id == "m1" }.content)
    }


    @Test
    fun `older edit never overwrites newer plaintext with placeholder`() {
        val failed = message("m1", "无法解密").copy(editedAt = 200L)
        val readable = message("m1", "hello").copy(editedAt = 100L)

        val merged = mergeMessageVersions(listOf(failed), listOf(readable)).single()
        assertEquals("hello", merged.content)
        assertEquals(200L, merged.editedAt)
    }

    @Test
    fun `star follows newer edit revision in both directions`() {
        val starred = message("m1", "hello").copy(starred = true, editedAt = 100L)
        val unstarred = message("m1", "hello").copy(starred = false, editedAt = 200L)
        assertEquals(false, mergeMessageVersions(listOf(starred), listOf(unstarred)).single().starred)
        assertEquals(false, mergeMessageVersions(listOf(unstarred), listOf(starred)).single().starred)
    }

    @Test
    fun `delivery status never regresses on newer edit`() {
        val read = message("m1", "hello").copy(status = MessageStatus.READ, editedAt = 100L)
        val sent = message("m1", "hello").copy(status = MessageStatus.SENT, editedAt = 200L)
        assertEquals(MessageStatus.READ, mergeMessageVersions(listOf(read), listOf(sent)).single().status)
        assertEquals(MessageStatus.READ, mergeMessageVersions(listOf(sent), listOf(read)).single().status)
    }

    @Test
    fun `incoming-only ids sort by timestamp after merge`() {
        val current = message("m1", "current").copy(timestamp = 300L)
        val earlier = message("m2", "earlier").copy(timestamp = 100L)
        val middle = message("m3", "middle").copy(timestamp = 200L)

        val merged = mergeMessageVersions(listOf(current), listOf(earlier, middle))
        assertEquals(listOf("m2", "m3", "m1"), merged.map { it.id })
    }

    @Test
    fun `both placeholders on newer edit keep current stable copy`() {
        val current = message("m1", "无法解密").copy(editedAt = 100L)
        val candidate = message("m1", "decrypt failed").copy(editedAt = 200L)

        val merged = mergeMessageVersions(listOf(current), listOf(candidate)).single()
        assertEquals("无法解密", merged.content)
        assertEquals(200L, merged.editedAt)
    }


    @Test
    fun `newer plaintext edit wins content and editedAt`() {
        val old = message("m1", "old").copy(editedAt = 100L)
        val new = message("m1", "new").copy(editedAt = 200L)

        val merged = mergeMessageVersions(listOf(old), listOf(new)).single()
        assertEquals("new", merged.content)
        assertEquals(200L, merged.editedAt)
    }

    @Test
    fun `older plaintext edit never overwrites newer plaintext`() {
        val current = message("m1", "new").copy(editedAt = 200L)
        val candidate = message("m1", "old").copy(editedAt = 100L)

        val merged = mergeMessageVersions(listOf(current), listOf(candidate)).single()
        assertEquals("new", merged.content)
        assertEquals(200L, merged.editedAt)
    }

    @Test
    fun `revoked versus revoked takes incoming`() {
        val current = message("m1", "hello").copy(type = MessageType.REVOKED)
        val candidate = message("m1", "bye").copy(type = MessageType.REVOKED)

        assertEquals(candidate, mergeMessageVersions(listOf(current), listOf(candidate)).single())
        assertEquals(current, mergeMessageVersions(listOf(candidate), listOf(current)).single())
    }

    @Test
    fun `sent never regresses read on equal revision`() {
        val sent = message("m1", "hello").copy(status = MessageStatus.SENT)
        val read = message("m1", "hello").copy(status = MessageStatus.READ)

        assertEquals(MessageStatus.READ, mergeMessageVersions(listOf(sent), listOf(read)).single().status)
        assertEquals(MessageStatus.READ, mergeMessageVersions(listOf(read), listOf(sent)).single().status)
    }

    @Test
    fun `equal timestamps sort by id`() {
        val current = message("m2", "later").copy(timestamp = 100L)
        val incoming = message("m1", "earlier").copy(timestamp = 100L)

        val merged = mergeMessageVersions(listOf(current), listOf(incoming))
        assertEquals(listOf("m1", "m2"), merged.map { it.id })
    }

    @Test
    fun `plaintext wins over padded english placeholder`() {
        val readable = message("m1", "hello")
        val padded = message("m1", "  decrypt failed  ")

        assertEquals("hello", mergeMessageVersions(listOf(readable), listOf(padded)).single().content)
        assertEquals("hello", mergeMessageVersions(listOf(padded), listOf(readable)).single().content)
    }

    @Test
    fun `star follows candidate when readable content is kept`() {
        val plain = message("m1", "hello").copy(starred = false)
        val starredPlaceholder = message("m1", "无法解密").copy(starred = true)

        val kept = mergeMessageVersions(listOf(plain), listOf(starredPlaceholder)).single()
        assertEquals("hello", kept.content)
        assertEquals(true, kept.starred)

        val unstarredPlain = message("m1", "hello").copy(starred = false)
        val starredCurrent = message("m1", "decrypt failed").copy(starred = true)
        val keptReverse = mergeMessageVersions(listOf(starredCurrent), listOf(unstarredPlain)).single()
        assertEquals("hello", keptReverse.content)
        assertEquals(false, keptReverse.starred)
    }

    @Test
    fun `revoked current survives newer plaintext edit`() {
        val revoked = message("m1", "hello").copy(type = MessageType.REVOKED, editedAt = 100L)
        val newerPlain = message("m1", "hello").copy(editedAt = 200L)

        assertEquals(revoked, mergeMessageVersions(listOf(revoked), listOf(newerPlain)).single())
    }

    @Test
    fun `failed never beats sending in either order`() {
        val failed = message("m1", "hello").copy(status = MessageStatus.FAILED)
        val sending = message("m1", "hello").copy(status = MessageStatus.SENDING)

        assertEquals(MessageStatus.SENDING, mergeMessageVersions(listOf(failed), listOf(sending)).single().status)
        assertEquals(MessageStatus.SENDING, mergeMessageVersions(listOf(sending), listOf(failed)).single().status)
    }

    @Test
    fun `equal revision editedAt falls back to current when candidate has none`() {
        val current = message("m1", "hello").copy(editedAt = 100L)
        val candidate = message("m1", "hello")

        val merged = mergeMessageVersions(listOf(current), listOf(candidate)).single()
        assertEquals("hello", merged.content)
        assertEquals(100L, merged.editedAt)
    }

    @Test
    fun `both wire envelopes on newer edit take the candidate`() {
        val current = message("m1", "eyJ2ZXJzaW9uIjoxLCJhbGdvcml0aG0iOiJzaWduYWwifQ==").copy(editedAt = 100L)
        val candidate = message("m1", "MD:abcdefghijklmnop").copy(editedAt = 200L)

        val merged = mergeMessageVersions(listOf(current), listOf(candidate)).single()
        assertEquals("MD:abcdefghijklmnop", merged.content)
        assertEquals(200L, merged.editedAt)
    }

    @Test
    fun `newer plaintext edit replaces a wire envelope`() {
        val wire = message(
            "m1",
            """{"version":1,"algorithm":"signal-sender-key-v1","ciphertext":"abc"}"""
        ).copy(editedAt = 100L)
        val plain = message("m1", "hello").copy(editedAt = 200L)

        val merged = mergeMessageVersions(listOf(wire), listOf(plain)).single()
        assertEquals("hello", merged.content)
        assertEquals(200L, merged.editedAt)
    }

    @Test
    fun `older wire edit never overwrites newer plaintext`() {
        val plain = message("m1", "hello").copy(editedAt = 200L)
        val wire = message("m1", "MD:abcdefghijklmnop").copy(editedAt = 100L)

        val merged = mergeMessageVersions(listOf(plain), listOf(wire)).single()
        assertEquals("hello", merged.content)
        assertEquals(200L, merged.editedAt)
    }

    @Test
    fun `newer placeholder edit keeps plaintext but takes reactions`() {
        val plain = message("m1", "hello").copy(
            editedAt = 100L,
            reactions = listOf(MessageReaction("u1", "❤"))
        )
        val failed = message("m1", "无法解密").copy(editedAt = 200L, reactions = emptyList())

        val merged = mergeMessageVersions(listOf(plain), listOf(failed)).single()
        assertEquals("hello", merged.content)
        assertEquals(200L, merged.editedAt)
        assertTrue(merged.reactions.isEmpty())
    }

    @Test
    fun `short eyJ string under sixteen chars is not an envelope`() {
        val old = message("m1", "old")
        val short = message("m1", "eyJshort")

        assertEquals("eyJshort", mergeMessageVersions(listOf(old), listOf(short)).single().content)
        assertEquals("new", mergeMessageVersions(listOf(short), listOf(message("m1", "new"))).single().content)
    }
}
