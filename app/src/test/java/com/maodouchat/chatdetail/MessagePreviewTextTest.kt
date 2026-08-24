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
}
