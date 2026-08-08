package com.maodouchat.data

import com.maodouchat.data.model.LocationPayload
import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageType
import com.maodouchat.data.model.semanticSearchText
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MessageSearchTextTest {

    @Test
    fun textUsesBody() {
        val msg = Message(id = "1", chatId = "c", senderId = "s", content = "hello world", type = MessageType.TEXT)
        assertEquals("hello world", msg.semanticSearchText())
    }

    @Test
    fun locationUsesLabelNotRawJson() {
        val payload = LocationPayload(latitude = 31.2, longitude = 121.5, label = "外滩观景台")
        val body = Json.encodeToString(payload)
        val msg = Message(id = "2", chatId = "c", senderId = "s", content = body, type = MessageType.LOCATION)
        val text = msg.semanticSearchText()
        assertEquals("外滩观景台", text)
        assertFalse(text.contains("latitude"))
        assertFalse(text.contains("121.5"))
    }

    @Test
    fun locationWithoutLabelIsBlank() {
        val payload = LocationPayload(latitude = 1.0, longitude = 2.0, label = "")
        val body = Json.encodeToString(payload)
        val msg = Message(id = "3", chatId = "c", senderId = "s", content = body, type = MessageType.LOCATION)
        assertEquals("", msg.semanticSearchText())
    }

    @Test
    fun nudgeIndexesStoredBody() {
        val msg = Message(
            id = "4",
            chatId = "c",
            senderId = "s",
            content = "你拍了拍张三",
            type = MessageType.NUDGE
        )
        assertTrue(msg.semanticSearchText().contains("拍了拍"))
    }

    @Test
    fun stickerNotIndexedByBody() {
        val msg = Message(id = "5", chatId = "c", senderId = "s", content = "sticker_id_42", type = MessageType.STICKER)
        assertEquals("", msg.semanticSearchText())
    }
}
