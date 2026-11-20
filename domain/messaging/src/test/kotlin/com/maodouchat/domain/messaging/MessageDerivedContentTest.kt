package com.maodouchat.domain.messaging

import com.maodouchat.core.model.LocationPayload
import com.maodouchat.core.model.Message
import com.maodouchat.core.model.MessageMeta
import com.maodouchat.core.model.MessageType
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// Message.withEncodedMeta / parsedLocation 随成员函数迁入 messaging 的测试；
// parsedContent / parsedMeta 的测试留守 core 的 MessageContentTest。
class MessageDerivedContentTest {

    private fun text(content: String) = Message(
        id = "m1", chatId = "c1", senderId = "u1",
        content = content, type = MessageType.TEXT,
    )

    @Test
    fun `withEncodedMeta round trips through parsedMeta`() {
        val msg = text("hello").withEncodedMeta(MessageMeta(forwardedFrom = "Alice"))
        assertEquals("hello", msg.parsedContent())
        assertEquals("Alice", msg.parsedMeta().forwardedFrom)
    }

    @Test
    fun `parsedLocation accepts valid payload and rejects bad latitude`() {
        val payload = LocationPayload(latitude = 31.2, longitude = 121.5, label = "外滩")
        val body = Json.encodeToString(payload)
        val loc = Message(
            id = "m2", chatId = "c1", senderId = "u1",
            content = body, type = MessageType.LOCATION,
        )
        assertEquals("外滩", loc.parsedLocation()?.label)
        val bad = Message(
            id = "m3", chatId = "c1", senderId = "u1",
            content = "{\"latitude\": 91.0, \"longitude\": 0.0}",
            type = MessageType.LOCATION,
        )
        assertNull(bad.parsedLocation())
        assertNull(text(body).parsedLocation())
    }
}
