package com.maodouchat.chatlist

import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageStatus
import com.maodouchat.data.model.MessageType
import com.maodouchat.data.repository.ChatListPreviewPolicy
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
    fun ordinaryJsonTextWithCiphertextWordStaysReadable() {
        val msg = sample(
            type = MessageType.TEXT,
            content = """{"type":"ciphertext","body":"..."}""",
            ts = 9L
        )
        val preview = ChatListPreviewPolicy.fromLatestMessage(msg, mediaLabel, "[enc]", "[revoked]")
        assertEquals("{\"type\":\"ciphertext\",\"body\":\"...\"}", preview.text)
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
    fun textWithMetaTagShowsOnlyBody() {
        val msg = sample(
            type = MessageType.TEXT,
            content = "hello<meta>{\"attachmentId\":\"att_abc\"}</meta>",
            ts = 9L
        )
        val preview = ChatListPreviewPolicy.fromLatestMessage(msg, mediaLabel, "[enc]", "[revoked]")
        assertEquals("hello", preview.text)
    }

    @Test
    fun decryptRelatedUserPlaintextRemainsVisibleAndCanBeListHead() {
        val placeholder = "[enc]"
        listOf(
            "这个怎么解密？",
            "decrypt this message",
            "How do I decrypt this?",
            "session missing 是什么意思？",
        ).forEachIndexed { index, content ->
            assertFalse(ChatListPreviewPolicy.looksLikeDecryptFailurePlaceholder(content))
            assertEquals(content, ChatListPreviewPolicy.listVisibleText(content, placeholder))

            val readable = sample(type = MessageType.TEXT, content = content, ts = 100L + index)
                .copy(id = "readable-$index")
            val older = sample(type = MessageType.TEXT, content = "older", ts = 1L)
                .copy(id = "older-$index")
            val preview = ChatListPreviewPolicy.fromLatestMessages(
                candidatesNewestFirst = listOf(readable, older),
                mediaLabel = mediaLabel,
                encryptedPlaceholder = placeholder,
                revokedPlaceholder = "[revoked]",
            )
            assertEquals(content, preview.text)
            assertEquals(readable.timestamp, preview.timestamp)
        }
    }

    @Test
    fun leftoverYunhuJoinUrlIsPlaceholderNotRawDump() {
        val placeholder = "[enc]"
        val leftover = "https://www.yhfx.jwznb.com/share?id=abc123"
        // User-typed 云湖/https join URL is ordinary plaintext — keep it.
        assertFalse(ChatListPreviewPolicy.looksLikeLeftoverPreviewGarbage(leftover))
        assertEquals(leftover, ChatListPreviewPolicy.listVisibleText(leftover, placeholder))
        assertEquals(
            "https://example.com/notes",
            ChatListPreviewPolicy.listVisibleText("https://example.com/notes", placeholder)
        )
        assertEquals("see https://example.com/notes", ChatListPreviewPolicy.listVisibleText("see https://example.com/notes", placeholder))
        val withMeta = leftover + "<meta>{\"attachmentId\":\"att\"}</meta>"
        assertEquals(leftover, ChatListPreviewPolicy.listVisibleText(withMeta, placeholder))
        val msg = sample(type = MessageType.TEXT, content = leftover, ts = 9L)
        val preview = ChatListPreviewPolicy.fromLatestMessage(msg, mediaLabel, placeholder, "[revoked]")
        assertEquals(leftover, preview.text)
        val attachmentDump = "https://host/api/attachments/att_abc"
        assertTrue(ChatListPreviewPolicy.looksLikeLeftoverPreviewGarbage(attachmentDump))
        assertEquals(placeholder, ChatListPreviewPolicy.listVisibleText(attachmentDump, placeholder))
        val compactWire =
            """{"senderDeviceId":76,"payloadType":"TEXT","entries":[{"ciphertextType":"prekey","ciphertext":"NAgB"}]}"""
        assertTrue(ChatListPreviewPolicy.looksLikeLeftoverPreviewGarbage(compactWire))
        assertEquals(placeholder, ChatListPreviewPolicy.listVisibleText(compactWire, placeholder))
        assertEquals(placeholder, ChatListPreviewPolicy.listVisibleText("无法解密", placeholder))
        assertEquals(
            placeholder,
            ChatListPreviewPolicy.fromLatestMessage(
                sample(type = MessageType.TEXT, content = compactWire, ts = 9L),
                mediaLabel,
                placeholder,
                "[revoked]"
            ).text
        )
    }

    @Test
    fun localFileUriIsNotShownAsPreview() {
        val msg = sample(type = MessageType.TEXT, content = "file:///data/cache/img.jpg", ts = 9L)
        val preview = ChatListPreviewPolicy.fromLatestMessage(msg, mediaLabel, "[enc]", "[revoked]")
        assertEquals("[enc]", preview.text)
        assertEquals("", ChatListPreviewPolicy.visiblePreviewText("content://media/1"))
        assertEquals("hi", ChatListPreviewPolicy.visiblePreviewText("hi<meta>{}</meta>"))
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

    @Test
    fun fromLatestMessagesSkipsNewerCiphertextToReadableTail() {
        val wire = sample(
            type = MessageType.TEXT,
            content = """{"version":3,"algorithm":"signal-multi-device-v1","ciphertext":"abc"}""",
            ts = 100L
        ).copy(id = "wire")
        val failed = sample(type = MessageType.TEXT, content = "[无法解密的消息]", ts = 95L).copy(id = "fail")
        val textMsg = sample(type = MessageType.TEXT, content = "t11b_group_from_alex", ts = 90L).copy(id = "t1")
        val preview = ChatListPreviewPolicy.fromLatestMessages(
            candidatesNewestFirst = listOf(wire, failed, textMsg),
            mediaLabel = mediaLabel,
            encryptedPlaceholder = "[enc]",
            revokedPlaceholder = "[revoked]"
        )
        assertEquals("t11b_group_from_alex", preview.text)
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
