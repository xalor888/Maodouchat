package com.maodouchat.chatlist

import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageStatus
import com.maodouchat.data.model.MessageType
import com.maodouchat.ui.screen.chatlist.ChatListPreviewPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatListPreviewPolicyTest {

    private val mediaLabel: (MessageType) -> String = { type ->
        when (type) {
            MessageType.IMAGE -> "[图片]"
            MessageType.GIF -> "[GIF]"
            MessageType.STICKER -> "[贴纸]"
            MessageType.LOCATION -> "[位置]"
            MessageType.VOICE -> "[语音]"
            MessageType.VIDEO -> "[视频]"
            MessageType.FILE -> "[文件]"
            else -> "[?]"
        }
    }

    @Test
    fun emptyChatClearsPreviewWithZeroTimestamp() {
        val preview = ChatListPreviewPolicy.fromLatestMessage(
            latest = null,
            mediaLabel = mediaLabel,
            encryptedPlaceholder = "[enc]",
            revokedPlaceholder = "[revoked]"
        )
        assertEquals("", preview.text)
        assertEquals(MessageType.TEXT, preview.type)
        assertEquals(0L, preview.timestamp)
    }

    @Test
    fun revokedUsesPlaceholderAndType() {
        val msg = sample(type = MessageType.REVOKED, content = "gone", ts = 42L)
        val preview = ChatListPreviewPolicy.fromLatestMessage(msg, mediaLabel, "[enc]", "[revoked]")
        assertEquals("[revoked]", preview.text)
        assertEquals(MessageType.REVOKED, preview.type)
        assertEquals(42L, preview.timestamp)
    }

    @Test
    fun plaintextTextKeepsBody() {
        val msg = sample(type = MessageType.TEXT, content = "hello", ts = 9L)
        val preview = ChatListPreviewPolicy.fromLatestMessage(msg, mediaLabel, "[enc]", "[revoked]")
        assertEquals("hello", preview.text)
        assertEquals(MessageType.TEXT, preview.type)
    }

    @Test
    fun wireEnvelopeTextUsesEncryptedPlaceholder() {
        val msg = sample(
            type = MessageType.TEXT,
            content = """{"type":"ciphertext","body":"..."}""",
            ts = 9L
        )
        val preview = ChatListPreviewPolicy.fromLatestMessage(msg, mediaLabel, "[enc]", "[revoked]")
        assertEquals("[enc]", preview.text)
    }

    @Test
    fun nudgeKeepsServerContentWithoutRewriter() {
        val msg = sample(type = MessageType.NUDGE, content = "你拍了拍张三", ts = 3L)
        val preview = ChatListPreviewPolicy.fromLatestMessage(msg, mediaLabel, "[enc]", "[revoked]")
        assertEquals("你拍了拍张三", preview.text)
        assertEquals(MessageType.NUDGE, preview.type)
    }

    @Test
    fun nudgeUsesOptionalPovRewriter() {
        val msg = sample(type = MessageType.NUDGE, content = "你拍了拍张三", ts = 3L)
        val preview = ChatListPreviewPolicy.fromLatestMessage(
            latest = msg,
            mediaLabel = mediaLabel,
            encryptedPlaceholder = "[enc]",
            revokedPlaceholder = "[revoked]",
            nudgeText = { "张三 拍了拍你" }
        )
        assertEquals("张三 拍了拍你", preview.text)
        assertEquals(MessageType.NUDGE, preview.type)
    }

    @Test
    fun imageUsesMediaLabel() {
        val msg = sample(type = MessageType.IMAGE, content = "blob", ts = 5L)
        val preview = ChatListPreviewPolicy.fromLatestMessage(msg, mediaLabel, "[enc]", "[revoked]")
        assertEquals("[图片]", preview.text)
        assertEquals(MessageType.IMAGE, preview.type)
    }

    @Test
    fun affectsListHeadOnlyWhenSameOrUnknown() {
        assertTrue(ChatListPreviewPolicy.affectsListHead(null, "m1"))
        assertTrue(ChatListPreviewPolicy.affectsListHead("", "m1"))
        assertTrue(ChatListPreviewPolicy.affectsListHead("m1", "m1"))
        assertFalse(ChatListPreviewPolicy.affectsListHead("m2", "m1"))
        assertFalse(ChatListPreviewPolicy.affectsListHead("m1", ""))
    }

    @Test
    fun skDistIsListPreviewNoise() {
        assertTrue(ChatListPreviewPolicy.isListPreviewNoise(MessageType.SK_DIST))
        assertFalse(ChatListPreviewPolicy.isListPreviewNoise(MessageType.TEXT))
        assertFalse(ChatListPreviewPolicy.isListPreviewNoise(MessageType.SYSTEM))
        assertFalse(ChatListPreviewPolicy.isListPreviewNoise(MessageType.NUDGE))
    }

    @Test
    fun shouldKeepExistingOwnPreviewOnlyForSameMessageReadableBody() {
        // Room plaintext for this message id (local send echo).
        assertTrue(
            ChatListPreviewPolicy.shouldKeepExistingOwnPreview(
                isOwnMessage = true,
                messageType = MessageType.TEXT,
                existingSameMessageContent = "hello",
                encryptedPlaceholder = "[enc]"
            )
        )
        assertTrue(
            ChatListPreviewPolicy.shouldKeepExistingOwnPreview(
                isOwnMessage = true,
                messageType = MessageType.IMAGE,
                existingSameMessageContent = "blob-or-meta",
                encryptedPlaceholder = "[enc]"
            )
        )
        // Previous chat-list tail alone is not enough — multi-device new send must not freeze.
        assertFalse(
            ChatListPreviewPolicy.shouldKeepExistingOwnPreview(
                isOwnMessage = true,
                messageType = MessageType.TEXT,
                existingSameMessageContent = null,
                encryptedPlaceholder = "[enc]"
            )
        )
        assertFalse(
            ChatListPreviewPolicy.shouldKeepExistingOwnPreview(
                isOwnMessage = false,
                messageType = MessageType.TEXT,
                existingSameMessageContent = "hello",
                encryptedPlaceholder = "[enc]"
            )
        )
        assertFalse(
            ChatListPreviewPolicy.shouldKeepExistingOwnPreview(
                isOwnMessage = true,
                messageType = MessageType.TEXT,
                existingSameMessageContent = """{"type":"ciphertext"}""",
                encryptedPlaceholder = "[enc]"
            )
        )
        assertFalse(
            ChatListPreviewPolicy.shouldKeepExistingOwnPreview(
                isOwnMessage = true,
                messageType = MessageType.TEXT,
                existingSameMessageContent = "[enc]",
                encryptedPlaceholder = "[enc]"
            )
        )
    }

    @Test
    fun ownEchoListPreviewUsesPlaintextOrMediaLabel() {
        assertEquals(
            "hello",
            ChatListPreviewPolicy.ownEchoListPreview(
                messageType = MessageType.TEXT,
                sameMessagePlainOrLabel = "hello",
                existingListPreview = "old tail",
                mediaLabel = mediaLabel
            )
        )
        assertEquals(
            "[图片]",
            ChatListPreviewPolicy.ownEchoListPreview(
                messageType = MessageType.IMAGE,
                sameMessagePlainOrLabel = "cipher-or-meta",
                existingListPreview = "old tail",
                mediaLabel = mediaLabel
            )
        )
        assertEquals(
            "[GIF]",
            ChatListPreviewPolicy.ownEchoListPreview(
                messageType = MessageType.GIF,
                sameMessagePlainOrLabel = "x",
                existingListPreview = null,
                mediaLabel = mediaLabel
            )
        )
    }

    @Test
    fun textPreviewFromPlainOrEncryptedNeverSurfacesWire() {
        assertEquals(
            "hi there",
            ChatListPreviewPolicy.textPreviewFromPlainOrEncrypted("hi there", "[enc]")
        )
        assertEquals(
            "[enc]",
            ChatListPreviewPolicy.textPreviewFromPlainOrEncrypted("""{"v":1}""", "[enc]")
        )
        assertEquals(
            "[enc]",
            ChatListPreviewPolicy.textPreviewFromPlainOrEncrypted(null, "[enc]")
        )
        assertEquals(
            "[enc]",
            ChatListPreviewPolicy.textPreviewFromPlainOrEncrypted("  ", "[enc]")
        )
        assertEquals(
            "a".repeat(200),
            ChatListPreviewPolicy.textPreviewFromPlainOrEncrypted("a".repeat(250), "[enc]")
        )
    }

    @Test
    fun looksLikeWireEnvelopeDoesNotTreatMediaLabelsAsJson() {
        assertTrue(ChatListPreviewPolicy.looksLikeWireEnvelope("""{"type":"ciphertext"}"""))
        assertTrue(ChatListPreviewPolicy.looksLikeWireEnvelope("""[{"deviceId":1}]"""))
        assertTrue(ChatListPreviewPolicy.looksLikeWireEnvelope("""["entry"]"""))
        assertFalse(ChatListPreviewPolicy.looksLikeWireEnvelope("[图片]"))
        assertFalse(ChatListPreviewPolicy.looksLikeWireEnvelope("[GIF]"))
        assertFalse(ChatListPreviewPolicy.looksLikeWireEnvelope("[Image]"))
        assertFalse(ChatListPreviewPolicy.looksLikeWireEnvelope("hello"))
        assertFalse(ChatListPreviewPolicy.looksLikeWireEnvelope(""))
    }

    @Test
    fun fromLatestMessagesSkipsTrailingSkDist() {
        val sk = sample(type = MessageType.SK_DIST, content = "dist", ts = 100L).copy(id = "sk")
        val textMsg = sample(type = MessageType.TEXT, content = "hello", ts = 90L).copy(id = "t1")
        val preview = ChatListPreviewPolicy.fromLatestMessages(
            candidatesNewestFirst = listOf(sk, textMsg),
            mediaLabel = mediaLabel,
            encryptedPlaceholder = "[enc]",
            revokedPlaceholder = "[revoked]"
        )
        assertEquals("hello", preview.text)
        assertEquals(MessageType.TEXT, preview.type)
        assertEquals(90L, preview.timestamp)
    }

    private fun sample(
        type: MessageType,
        content: String,
        ts: Long
    ) = Message(
        id = "m1",
        chatId = "c1",
        senderId = "u1",
        content = content,
        type = type,
        timestamp = ts,
        status = MessageStatus.SENT
    )
}
