package com.maodouchat.chatdetail

import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageType
import com.maodouchat.ui.screen.chatdetail.MessagePreviewText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class MessagePreviewTextTest {

    @Test
    fun wireEnvelopeIsNeverShownInReply() {
        val wire = Message(
            id = "m1",
            chatId = "c1",
            senderId = "u1",
            content = """{"version":1,"algorithm":"signal-sender-key-v1","ciphertext":"abc"}""",
            type = MessageType.TEXT,
        )
        val preview = MessagePreviewText.replyOrQuote(
            message = wire,
            mediaLabel = { "[media]" },
            encryptedPlaceholder = "[encrypted]",
        )
        assertEquals("[encrypted]", preview)
        assertFalse(preview.contains("algorithm"))
        assertFalse(preview.contains("version"))
    }

    @Test
    fun plaintextReplyKeepsBody() {
        val msg = Message(
            id = "m1",
            chatId = "c1",
            senderId = "u1",
            content = "hello world",
            type = MessageType.TEXT,
        )
        assertEquals(
            "hello world",
            MessagePreviewText.replyOrQuote(msg, { "[media]" }, "[encrypted]"),
        )
    }

    @Test
    fun imageReplyUsesMediaLabel() {
        val msg = Message(
            id = "m1",
            chatId = "c1",
            senderId = "u1",
            content = "file://x",
            type = MessageType.IMAGE,
        )
        assertEquals(
            "[image]",
            MessagePreviewText.replyOrQuote(msg, { "[image]" }, "[encrypted]"),
        )
    }

    @Test
    fun revokedMessageShowsEncryptedPlaceholder() {
        val msg = Message(
            id = "m1",
            chatId = "c1",
            senderId = "u1",
            content = "撤回了一条消息",
            type = MessageType.REVOKED,
        )
        assertEquals(
            "[encrypted]",
            MessagePreviewText.replyOrQuote(msg, { "[media]" }, "[encrypted]"),
        )
    }

    @Test
    fun senderKeyDistributionEnvelopeShowsPlaceholder() {
        val msg = Message(
            id = "m1",
            chatId = "c1",
            senderId = "u1",
            content = """{"distributionMessage":"abc"}""",
            type = MessageType.SK_DIST,
        )
        assertEquals(
            "[encrypted]",
            MessagePreviewText.replyOrQuote(msg, { "[media]" }, "[encrypted]"),
        )
    }

    @Test
    fun newlinesInTextReplyAreFoldedToSpaces() {
        val msg = Message(
            id = "m1",
            chatId = "c1",
            senderId = "u1",
            content = "hello\nworld",
            type = MessageType.TEXT,
        )
        assertEquals(
            "hello world",
            MessagePreviewText.replyOrQuote(msg, { "[media]" }, "[encrypted]"),
        )
    }

    @Test
    fun blankTextFallsBackToEncryptedPlaceholder() {
        val msg = Message(
            id = "m1",
            chatId = "c1",
            senderId = "u1",
            content = "   ",
            type = MessageType.TEXT,
        )
        assertEquals(
            "[encrypted]",
            MessagePreviewText.replyOrQuote(msg, { "[media]" }, "[encrypted]"),
        )
    }

    @Test
    fun voiceReplyUsesMediaLabel() {
        val msg = Message(
            id = "m1",
            chatId = "c1",
            senderId = "u1",
            content = "file://voice",
            type = MessageType.VOICE,
        )
        assertEquals(
            "[voice]",
            MessagePreviewText.replyOrQuote(msg, { "[voice]" }, "[encrypted]"),
        )
    }

    @Test
    fun videoReplyUsesMediaLabel() {
        val msg = Message(
            id = "m1",
            chatId = "c1",
            senderId = "u1",
            content = "file://video",
            type = MessageType.VIDEO,
        )
        assertEquals(
            "[video]",
            MessagePreviewText.replyOrQuote(msg, { "[video]" }, "[encrypted]"),
        )
    }

    @Test
    fun fileReplyUsesMediaLabel() {
        val msg = Message(
            id = "m1",
            chatId = "c1",
            senderId = "u1",
            content = "file://doc",
            type = MessageType.FILE,
        )
        assertEquals(
            "[file]",
            MessagePreviewText.replyOrQuote(msg, { "[file]" }, "[encrypted]"),
        )
    }

    @Test
    fun locationReplyUsesMediaLabel() {
        val msg = Message(
            id = "m1",
            chatId = "c1",
            senderId = "u1",
            content = "39.9,116.4",
            type = MessageType.LOCATION,
        )
        assertEquals(
            "[location]",
            MessagePreviewText.replyOrQuote(msg, { "[location]" }, "[encrypted]"),
        )
    }

    @Test
    fun gifReplyUsesMediaLabel() {
        val msg = Message(
            id = "m1",
            chatId = "c1",
            senderId = "u1",
            content = "file://anim",
            type = MessageType.GIF,
        )
        assertEquals(
            "[gif]",
            MessagePreviewText.replyOrQuote(msg, { "[gif]" }, "[encrypted]"),
        )
    }

    @Test
    fun stickerReplyUsesMediaLabel() {
        val msg = Message(
            id = "m1",
            chatId = "c1",
            senderId = "u1",
            content = "sticker://1",
            type = MessageType.STICKER,
        )
        assertEquals(
            "[sticker]",
            MessagePreviewText.replyOrQuote(msg, { "[sticker]" }, "[encrypted]"),
        )
    }

}
