package com.maodouchat.domain.messaging

import com.maodouchat.core.model.Message
import com.maodouchat.core.model.MessageMeta
import com.maodouchat.core.model.MessageType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatExportBodyTest {

    @Test
    fun renderBody_coversAllMessageTypes() {
        val meta = MessageMeta(fileName = "presentation.pdf")
        val fileMsg = Message(id = "1", chatId = "c", senderId = "u", content = "", type = MessageType.FILE, meta = meta)
        assertEquals("[文件] presentation.pdf", ChatExportBody.renderBody(fileMsg))

        val voiceMsg = Message(id = "2", chatId = "c", senderId = "u", content = "", type = MessageType.VOICE)
        assertEquals("[语音]", ChatExportBody.renderBody(voiceMsg))

        val locMsg = Message(id = "3", chatId = "c", senderId = "u", content = "", type = MessageType.LOCATION)
        assertEquals("[位置]", ChatExportBody.renderBody(locMsg))

        val nudgeMsg = Message(id = "4", chatId = "c", senderId = "u", content = "", type = MessageType.NUDGE)
        assertEquals("[戳一戳]", ChatExportBody.renderBody(nudgeMsg))
    }

    @Test
    fun renderBody_coversMarkdownStickerVideoGif() {
        val mdMsg = Message(id = "1", chatId = "c", senderId = "u", content = "**加粗**", type = MessageType.MARKDOWN)
        assertEquals("**加粗**", ChatExportBody.renderBody(mdMsg))

        val stickerMsg = Message(id = "2", chatId = "c", senderId = "u", content = "", type = MessageType.STICKER)
        assertEquals("[表情]", ChatExportBody.renderBody(stickerMsg))

        val videoMsg = Message(id = "3", chatId = "c", senderId = "u", content = "", type = MessageType.VIDEO)
        assertEquals("[视频]", ChatExportBody.renderBody(videoMsg))

        val gifMsg = Message(id = "4", chatId = "c", senderId = "u", content = "content://cached/a.gif", type = MessageType.GIF)
        assertEquals("content://cached/a.gif", ChatExportBody.renderBody(gifMsg))
    }

    @Test
    fun renderBody_fileWithoutNameRendersBareTag() {
        val fileMsg = Message(id = "1", chatId = "c", senderId = "u", content = "", type = MessageType.FILE)
        assertEquals("[文件]", ChatExportBody.renderBody(fileMsg))
    }

    @Test
    fun renderBody_systemAndSkDistRenderEmpty() {
        val sysMsg = Message(id = "1", chatId = "c", senderId = "u", content = "alert", type = MessageType.SYSTEM)
        assertEquals("", ChatExportBody.renderBody(sysMsg))
        val distMsg = Message(id = "2", chatId = "c", senderId = "u", content = "payload", type = MessageType.SK_DIST)
        assertEquals("", ChatExportBody.renderBody(distMsg))
    }

    @Test
    fun renderBody_textReturnsContentVerbatim() {
        val msg = Message(id = "1", chatId = "c", senderId = "u", content = "**bold** text", type = MessageType.TEXT)
        assertEquals("**bold** text", ChatExportBody.renderBody(msg))
    }

    @Test
    fun renderBody_revokedRendersTagRegardlessOfContent() {
        val msg = Message(id = "1", chatId = "c", senderId = "u", content = "gone", type = MessageType.REVOKED)
        assertEquals("[消息已撤回]", ChatExportBody.renderBody(msg))
    }

    @Test
    fun renderBody_fileWithEmptyNameRendersTagWithTrailingSpace() {
        val fileMsg = Message(id = "1", chatId = "c", senderId = "u", content = "", type = MessageType.FILE, meta = MessageMeta(fileName = ""))
        assertEquals("[文件] ", ChatExportBody.renderBody(fileMsg))
    }

    @Test
    fun renderBody_imageRendersTagRegardlessOfContent() {
        val imageMsg = Message(id = "1", chatId = "c", senderId = "u", content = "file:///x.png", type = MessageType.IMAGE)
        assertEquals("[图片]", ChatExportBody.renderBody(imageMsg))
    }

    @Test
    fun sanitizesUnsafeChars() {
        assertEquals("a_b_c", ChatExportBody.sanitizeExportFileName("a/b:c"))
        assertEquals("___", ChatExportBody.sanitizeExportFileName("***"))
        assertEquals("a_b_c", ChatExportBody.sanitizeExportFileName("a?b\"c"))
        assertEquals("a_b_c_d", ChatExportBody.sanitizeExportFileName("a\\b|c*d"))
        assertEquals("a_b_c_d_e_f_g", ChatExportBody.sanitizeExportFileName("a/b:c<d>e|f*g"))
    }

    @Test
    fun truncatesLongFileNameToEightyChars() {
        assertEquals("x".repeat(80), ChatExportBody.sanitizeExportFileName("x".repeat(100)))
        assertEquals(80, ChatExportBody.sanitizeExportFileName("x".repeat(100)).length)
    }

    @Test
    fun keepsExactlyEightyCharFileName() {
        assertEquals("x".repeat(80), ChatExportBody.sanitizeExportFileName("x".repeat(80)))
    }

    @Test
    fun emptyFileNameStaysEmpty() {
        assertEquals("", ChatExportBody.sanitizeExportFileName(""))
    }

    @Test
    fun buildText_includesFormatVersionAndHeader() {
        val messages = listOf(
            Message(id = "m1", chatId = "c1", senderId = "me", content = "Hello world", type = MessageType.TEXT, timestamp = 1600000000000L),
            Message(id = "m2", chatId = "c1", senderId = "friend", content = "file:///img.png", type = MessageType.IMAGE, timestamp = 1600000005000L),
            Message(id = "m3", chatId = "c1", senderId = "me", content = "hidden", type = MessageType.REVOKED, timestamp = 1600000010000L),
            Message(id = "m4", chatId = "c1", senderId = "sys", content = "system alert", type = MessageType.SYSTEM, timestamp = 1600000015000L)
        )
        val text = ChatExportBody.buildText(
            chatName = "Test Chat",
            ownerId = "me",
            resolveSenderName = { if (it == "friend") "Alice" else it },
            messages = messages,
            exportedAtText = "2020-09-13 12:28:20"
        )
        assertTrue(text.contains("=== Test Chat ==="), "Should include chat name")
        assertTrue(text.contains("format_version=${ChatExportBody.FORMAT_VERSION}"), "Should specify format version")
        assertTrue(text.contains("exported_at=2020-09-13 12:28:20"), "Should include exported timestamp")
        assertTrue(text.contains("我: Hello world"), "Should display '我' for owner")
        assertTrue(text.contains("Alice: [图片]"), "Should display Alice for friend")
        assertTrue(text.contains("我: [消息已撤回]"), "Should format revoked message")
        assertFalse(text.contains("system alert"), "Should skip SYSTEM message")
    }

    @Test
    fun buildText_blankChatNameRendersDash() {
        val text = ChatExportBody.buildText(
            chatName = "",
            ownerId = "me",
            resolveSenderName = { it },
            messages = emptyList(),
            exportedAtText = "2020-09-13 12:28:20"
        )
        assertTrue(text.contains("=== - ==="), "Should render dash for blank chat name")
    }

    @Test
    fun buildText_skipsSkDistAndEmptyBodyMessages() {
        val messages = listOf(
            Message(id = "d", chatId = "c", senderId = "me", content = "dist-payload", type = MessageType.SK_DIST, timestamp = 1600000000000L),
            Message(id = "e", chatId = "c", senderId = "me", content = "", type = MessageType.TEXT, timestamp = 1600000001000L)
        )
        val text = ChatExportBody.buildText(
            chatName = "C",
            ownerId = "me",
            resolveSenderName = { it },
            messages = messages,
            exportedAtText = "2020-09-13 12:28:20"
        )
        assertFalse(text.contains("dist-payload"), "Should skip SK_DIST")
        assertTrue(!text.contains("[2020-09-13"), "Only header lines for fully skipped body")
    }

    @Test
    fun buildText_senderBlankNameFallsBackToId() {
        val text = ChatExportBody.buildText(
            chatName = "C",
            ownerId = "me",
            resolveSenderName = { "" },
            messages = listOf(
                Message(id = "m1", chatId = "c", senderId = "friend", content = "hi", type = MessageType.TEXT, timestamp = 1600000000000L)
            ),
            exportedAtText = "2020-09-13 12:28:20"
        )
        assertTrue(text.contains("friend: hi"))
    }

    @Test
    fun buildText_countIncludesSkippedMessages() {
        val text = ChatExportBody.buildText(
            chatName = "C",
            ownerId = "me",
            resolveSenderName = { it },
            messages = listOf(
                Message(id = "s", chatId = "c", senderId = "sys", content = "system alert", type = MessageType.SYSTEM, timestamp = 1600000000000L),
                Message(id = "m1", chatId = "c", senderId = "me", content = "hi", type = MessageType.TEXT, timestamp = 1600000001000L)
            ),
            exportedAtText = "2020-09-13 12:28:20"
        )
        assertTrue(text.contains("count=2"))
        assertFalse(text.contains("system alert"))
    }

    @Test
    fun buildText_preservesInputOrderNotTimestampOrder() {
        val text = ChatExportBody.buildText(
            chatName = "C",
            ownerId = "me",
            resolveSenderName = { it },
            messages = listOf(
                Message(id = "m1", chatId = "c", senderId = "me", content = "laterFirst", type = MessageType.TEXT, timestamp = 1600000003000L),
                Message(id = "m2", chatId = "c", senderId = "me", content = "earlierSecond", type = MessageType.TEXT, timestamp = 1600000001000L)
            ),
            exportedAtText = "2020-09-13 12:28:20"
        )
        assertTrue(text.indexOf("laterFirst") < text.indexOf("earlierSecond"))
    }

    @Test
    fun buildText_revokedBodyRendersForNonOwner() {
        val text = ChatExportBody.buildText(
            chatName = "C",
            ownerId = "me",
            resolveSenderName = { if (it == "friend") "Alice" else it },
            messages = listOf(
                Message(id = "m1", chatId = "c", senderId = "friend", content = "gone", type = MessageType.REVOKED, timestamp = 1600000000000L)
            ),
            exportedAtText = "2020-09-13 12:28:20"
        )
        assertTrue(text.contains("Alice: [消息已撤回]"))
    }

    @Test
    fun buildText_textWithEmptyBodySkippedButCounted() {
        val text = ChatExportBody.buildText(
            chatName = "C",
            ownerId = "me",
            resolveSenderName = { it },
            messages = listOf(
                Message(id = "m1", chatId = "c", senderId = "me", content = "", type = MessageType.TEXT, timestamp = 1600000000000L)
            ),
            exportedAtText = "2020-09-13 12:28:20"
        )
        assertTrue(text.contains("count=1"))
        assertTrue(text.lines().none { it.startsWith("[") })
    }

    @Test
    fun buildText_gifRendersRawContent() {
        val text = ChatExportBody.buildText(
            chatName = "C",
            ownerId = "me",
            resolveSenderName = { it },
            messages = listOf(
                Message(id = "m1", chatId = "c", senderId = "me", content = "content://cached/a.gif", type = MessageType.GIF, timestamp = 1600000000000L)
            ),
            exportedAtText = "2020-09-13 12:28:20"
        )
        assertTrue(text.contains("我: content://cached/a.gif"))
        assertFalse(text.contains("[图片]"))
    }

    @Test
    fun buildText_ownerAlwaysShowsWoEvenWhenResolverRenamesOwner() {
        val text = ChatExportBody.buildText(
            chatName = "C",
            ownerId = "me",
            resolveSenderName = { "Boss" },
            messages = listOf(
                Message(id = "m1", chatId = "c", senderId = "me", content = "hi", type = MessageType.TEXT, timestamp = 1600000000000L)
            ),
            exportedAtText = "2020-09-13 12:28:20"
        )
        assertTrue(text.contains("我: hi"))
        assertFalse(text.contains("Boss"))
    }

    @Test
    fun buildText_fileMessageRendersFileNameInBody() {
        val meta = MessageMeta(fileName = "deck.pdf")
        val text = ChatExportBody.buildText(
            chatName = "C",
            ownerId = "me",
            resolveSenderName = { it },
            messages = listOf(
                Message(id = "m1", chatId = "c", senderId = "friend", content = "", type = MessageType.FILE, meta = meta, timestamp = 1600000000000L)
            ),
            exportedAtText = "2020-09-13 12:28:20"
        )
        assertTrue(text.contains("friend: [文件] deck.pdf"))
    }

    @Test
    fun buildText_timestampsUseDateTimeFormats() {
        val text = ChatExportBody.buildText(
            chatName = "C",
            ownerId = "me",
            resolveSenderName = { it },
            messages = listOf(
                Message(id = "m1", chatId = "c", senderId = "me", content = "hi", type = MessageType.TEXT, timestamp = 1600000000000L)
            ),
            exportedAtText = "2020-09-13 12:28:20"
        )
        assertTrue(text.contains("exported_at=2020-09-13 12:28:20"))
        assertTrue(text.contains(Regex("\\[\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}\\] 我: hi")))
    }

    @Test
    fun buildText_nudgeAndVoiceRenderTagsForNonOwner() {
        val text = ChatExportBody.buildText(
            chatName = "C",
            ownerId = "me",
            resolveSenderName = { it },
            messages = listOf(
                Message(id = "m1", chatId = "c", senderId = "friend", content = "poke", type = MessageType.NUDGE, timestamp = 1600000000000L),
                Message(id = "m2", chatId = "c", senderId = "friend", content = "audio", type = MessageType.VOICE, timestamp = 1600000001000L)
            ),
            exportedAtText = "2020-09-13 12:28:20"
        )
        assertTrue(text.contains("[戳一戳]"))
        assertTrue(text.contains("[语音]"))
        assertTrue(text.contains("count=2"))
    }

    @Test
    fun buildText_locationAndStickerRenderTags() {
        val text = ChatExportBody.buildText(
            chatName = "C",
            ownerId = "me",
            resolveSenderName = { it },
            messages = listOf(
                Message(id = "m1", chatId = "c", senderId = "friend", content = "here", type = MessageType.LOCATION, timestamp = 1600000000000L),
                Message(id = "m2", chatId = "c", senderId = "friend", content = "", type = MessageType.STICKER, timestamp = 1600000001000L)
            ),
            exportedAtText = "2020-09-13 12:28:20"
        )
        assertTrue(text.contains("[位置]"))
        assertTrue(text.contains("[表情]"))
        assertTrue(text.contains("count=2"))
    }

    @Test
    fun formatLine_skipsSystemSkDistAndEmptyBody() {
        assertEquals(null, ChatExportBody.formatLine(
            Message(id = "s", chatId = "c", senderId = "sys", content = "alert", type = MessageType.SYSTEM, timestamp = 1600000000000L), "me", { it }))
        assertEquals(null, ChatExportBody.formatLine(
            Message(id = "d", chatId = "c", senderId = "me", content = "dist", type = MessageType.SK_DIST, timestamp = 1600000000000L), "me", { it }))
        assertEquals(null, ChatExportBody.formatLine(
            Message(id = "e", chatId = "c", senderId = "me", content = "", type = MessageType.TEXT, timestamp = 1600000000000L), "me", { it }))
    }

    @Test
    fun formatLine_rendersOwnerAndFallsBackToSenderId() {
        val owner = ChatExportBody.formatLine(
            Message(id = "m1", chatId = "c", senderId = "me", content = "hi", type = MessageType.TEXT, timestamp = 1600000000000L), "me", { "Boss" })
        assertTrue(owner != null && owner.endsWith("我: hi"))
        val peer = ChatExportBody.formatLine(
            Message(id = "m2", chatId = "c", senderId = "friend", content = "hi", type = MessageType.TEXT, timestamp = 1600000000000L), "me", { "" })
        assertTrue(peer != null && peer.endsWith("friend: hi"))
    }
}
