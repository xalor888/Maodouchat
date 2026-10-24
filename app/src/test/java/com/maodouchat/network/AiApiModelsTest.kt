package com.maodouchat.network

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AiApiModelsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun localContextMessageRoundTrips() {
        val message = AiContextMessage(sender = "小猫", text = "hello")
        assertEquals(message, json.decodeFromString<AiContextMessage>(json.encodeToString(message)))
    }

    @Test
    fun groupTaskRoundTrips() {
        val task = AiGroupTask(title = "确认方案", owner = "小猫", dueAt = 42L)
        assertEquals(task, json.decodeFromString<AiGroupTask>(json.encodeToString(task)))
    }

    @Test
    fun contextMessageSenderDefaultsEmptyWhenOmitted() {
        val decoded = json.decodeFromString<AiContextMessage>("""{"text":"hi"}""")
        assertEquals("", decoded.sender)
        assertEquals("hi", decoded.text)
    }

    @Test
    fun groupTaskNullableFieldsDefaultNull() {
        val decoded = json.decodeFromString<AiGroupTask>("""{"title":"t"}""")
        assertNull(decoded.owner)
        assertNull(decoded.dueText)
        assertNull(decoded.dueAt)
    }

    @Test
    fun groupTaskDueTextRoundTrips() {
        val task = AiGroupTask(title = "开会", dueText = "明天下午", dueAt = 99L)
        assertEquals(task, json.decodeFromString<AiGroupTask>(json.encodeToString(task)))
    }

    @Test
    fun semanticSearchCandidateDecodesAllFields() {
        val decoded = json.decodeFromString<AiSemanticSearchCandidate>(
            """{"messageId":"m1","sender":"u1","text":"hello","timestamp":5}"""
        )
        assertEquals("m1", decoded.messageId)
        assertEquals("u1", decoded.sender)
        assertEquals("hello", decoded.text)
        assertEquals(5L, decoded.timestamp)
        val bare = json.decodeFromString<AiSemanticSearchCandidate>(
            """{"messageId":"m2","text":"x","timestamp":1}"""
        )
        assertEquals("", bare.sender)
    }

    @Test
    fun auditLogResponseFillsNumericDefaults() {
        val decoded = json.decodeFromString<AiAuditLogResponse>(
            """{"id":"a1","feature":"chat","model":"m","status":"ok","createdAt":7}"""
        )
        assertEquals(0, decoded.inputChars)
        assertEquals(0, decoded.contextMessages)
        assertNull(decoded.chatId)
        assertNull(decoded.durationMs)
        assertNull(decoded.error)
        val withError = json.decodeFromString<AiAuditLogResponse>(
            """{"id":"a2","feature":"chat","status":"error","error":"boom","createdAt":8}"""
        )
        assertEquals("boom", withError.error)
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
    fun groupTaskFullFieldsRoundTrip() {
        val task = AiGroupTask(title = "复查", owner = "u2", dueText = "周五前", dueAt = 12345L)
        assertEquals(task, json.decodeFromString<AiGroupTask>(json.encodeToString(task)))
    }

    @Test
    fun semanticSearchCandidateRoundTrips() {
        val candidate = AiSemanticSearchCandidate(
            messageId = "m9", sender = "u3", text = "关键词", timestamp = 777L
        )
        assertEquals(
            candidate,
            json.decodeFromString<AiSemanticSearchCandidate>(json.encodeToString(candidate))
        )
    }

    @Test
    fun auditLogResponseModelDefaultsNullWhenOmitted() {
        val decoded = json.decodeFromString<AiAuditLogResponse>(
            """{"id":"a3","feature":"chat","status":"ok","createdAt":9}"""
        )
        assertNull(decoded.model)
        assertNull(decoded.chatId)
        assertEquals(0, decoded.inputChars)
    }
}
