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

    @Test
    fun contextMessageEmptyTextRoundTrips() {
        val message = AiContextMessage(sender = "u1", text = "")
        assertEquals(message, json.decodeFromString<AiContextMessage>(json.encodeToString(message)))
    }

    @Test
    fun groupTaskDueAtZeroDecodesAsZeroNotNull() {
        val decoded = json.decodeFromString<AiGroupTask>("""{"title":"t","dueAt":0}""")
        assertEquals(0L, decoded.dueAt)
    }

    @Test
    fun semanticSearchCandidateEmptyTextZeroTimestampRoundTrips() {
        val candidate = AiSemanticSearchCandidate(messageId = "m0", sender = "", text = "", timestamp = 0L)
        assertEquals(
            candidate,
            json.decodeFromString<AiSemanticSearchCandidate>(json.encodeToString(candidate))
        )
    }

    @Test
    fun auditLogResponseFullFieldsRoundTrip() {
        val response = AiAuditLogResponse(
            id = "a9",
            chatId = "c1",
            feature = "chat",
            model = "gpt",
            status = "ok",
            inputChars = 10,
            contextMessages = 4,
            durationMs = 250L,
            error = null,
            createdAt = 55L
        )
        assertEquals(response, json.decodeFromString<AiAuditLogResponse>(json.encodeToString(response)))
    }

    @Test
    fun groupTaskExplicitNullsDecodeAsNull() {
        val decoded = json.decodeFromString<AiGroupTask>(
            """{"title":"t","owner":null,"dueText":null,"dueAt":null}"""
        )
        assertNull(decoded.owner)
        assertNull(decoded.dueText)
        assertNull(decoded.dueAt)
    }

    @Test
    fun auditLogResponseExplicitNullsDecodeAsNull() {
        val decoded = json.decodeFromString<AiAuditLogResponse>(
            """{"id":"a","feature":"f","status":"ok","createdAt":1,"chatId":null,"model":null,"durationMs":null,"error":null}"""
        )
        assertNull(decoded.chatId)
        assertNull(decoded.model)
        assertNull(decoded.durationMs)
        assertNull(decoded.error)
        assertEquals(0, decoded.inputChars)
        assertEquals(0, decoded.contextMessages)
    }

    @Test
    fun semanticSearchCandidateNegativeTimestampRoundTrips() {
        val candidate = AiSemanticSearchCandidate(messageId = "m1", sender = "u1", text = "x", timestamp = -1L)
        assertEquals(candidate, json.decodeFromString<AiSemanticSearchCandidate>(json.encodeToString(candidate)))
    }

    @Test
    fun contextMessageEscapesRoundTrip() {
        val message = AiContextMessage(sender = "u1", text = "line1\nline2\t\"quoted\"")
        assertEquals(message, json.decodeFromString<AiContextMessage>(json.encodeToString(message)))
    }

    @Test
    fun groupTaskNegativeDueAtDecodesAsIs() {
        val decoded = json.decodeFromString<AiGroupTask>("""{"title":"t","dueAt":-100}""")
        assertEquals(-100L, decoded.dueAt)
    }

    @Test
    fun auditLogResponseChatIdAndDurationDecode() {
        val decoded = json.decodeFromString<AiAuditLogResponse>(
            """{"id":"a8","chatId":"c2","feature":"chat","status":"ok","inputChars":7,"contextMessages":2,"durationMs":90,"createdAt":6}"""
        )
        assertEquals("c2", decoded.chatId)
        assertEquals(90L, decoded.durationMs)
        assertEquals(7, decoded.inputChars)
        assertEquals(2, decoded.contextMessages)
        assertNull(decoded.error)
    }
}
