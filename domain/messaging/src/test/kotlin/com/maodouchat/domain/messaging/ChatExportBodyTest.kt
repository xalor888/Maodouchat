package com.maodouchat.domain.messaging

import com.maodouchat.core.model.Message
import com.maodouchat.core.model.MessageMeta
import com.maodouchat.core.model.MessageType
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
