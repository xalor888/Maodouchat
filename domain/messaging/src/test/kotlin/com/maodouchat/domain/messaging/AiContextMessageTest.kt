package com.maodouchat.domain.messaging

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AiContextMessageTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun localContextMessageRoundTrips() {
        val message = AiContextMessage(sender = "小猫", text = "hello")
        assertEquals(message, json.decodeFromString<AiContextMessage>(json.encodeToString(message)))
    }
    @Test
    fun contextMessageSenderDefaultsEmptyWhenOmitted() {
        val decoded = json.decodeFromString<AiContextMessage>("""{"text":"hi"}""")
        assertEquals("", decoded.sender)
        assertEquals("hi", decoded.text)
    }
    @Test
    fun contextMessageIgnoresUnknownKeys() {
        val decoded = json.decodeFromString<AiContextMessage>(
            """{"sender":"u1","text":"hi","traceId":"t1","serverTs":99}"""
        )
        assertEquals("u1", decoded.sender)
        assertEquals("hi", decoded.text)
    }
    @Test
    fun contextMessageUnicodeRoundTrips() {
        val message = AiContextMessage(sender = "用户1", text = "你好，世界 🌍")
        assertEquals(message, json.decodeFromString<AiContextMessage>(json.encodeToString(message)))
    }
    @Test
    fun contextMessageEmptyTextRoundTrips() {
        val message = AiContextMessage(sender = "u1", text = "")
        assertEquals(message, json.decodeFromString<AiContextMessage>(json.encodeToString(message)))
    }
    @Test
    fun contextMessageEscapesRoundTrip() {
        val message = AiContextMessage(sender = "u1", text = "line1\nline2\t\"quoted\"")
        assertEquals(message, json.decodeFromString<AiContextMessage>(json.encodeToString(message)))
    }
    @Test
    fun emptyContextMessageRoundTrips() {
        val message = AiContextMessage(text = "")
        assertEquals(message, json.decodeFromString<AiContextMessage>(json.encodeToString(message)))
    }
    @Test
    fun contextMessageWhitespaceSenderRoundTrips() {
        val message = AiContextMessage(sender = " ", text = "hi")
        assertEquals(message, json.decodeFromString<AiContextMessage>(json.encodeToString(message)))
    }
    @Test
    fun contextMessageLongTextRoundTrips() {
        val message = AiContextMessage(sender = "u1", text = "x".repeat(2000))
        assertEquals(message, json.decodeFromString<AiContextMessage>(json.encodeToString(message)))
    }
}
