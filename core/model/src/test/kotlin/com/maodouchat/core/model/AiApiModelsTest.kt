package com.maodouchat.core.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AiApiModelsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun groupTaskRoundTrips() {
        val task = AiGroupTask(title = "确认方案", owner = "小猫", dueAt = 42L)
        assertEquals(task, json.decodeFromString<AiGroupTask>(json.encodeToString(task)))
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
    fun groupTaskFullFieldsRoundTrip() {
        val task = AiGroupTask(title = "复查", owner = "u2", dueText = "周五前", dueAt = 12345L)
        assertEquals(task, json.decodeFromString<AiGroupTask>(json.encodeToString(task)))
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
    fun groupTaskDueAtZeroDecodesAsZeroNotNull() {
        val decoded = json.decodeFromString<AiGroupTask>("""{"title":"t","dueAt":0}""")
        assertEquals(0L, decoded.dueAt)
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

    @Test
    fun titleOnlyGroupTaskRoundTrips() {
        val task = AiGroupTask(title = "t")
        assertEquals(task, json.decodeFromString<AiGroupTask>(json.encodeToString(task)))
    }

    @Test
    fun auditResponseMaxIntCountersRoundTrip() {
        val response = AiAuditLogResponse(
            id = "a9",
            feature = "chat",
            status = "ok",
            inputChars = Int.MAX_VALUE,
            contextMessages = Int.MAX_VALUE,
            createdAt = 1L
        )
        assertEquals(response, json.decodeFromString<AiAuditLogResponse>(json.encodeToString(response)))
    }

    @Test
    fun groupTaskMaxDueAtRoundTrips() {
        val task = AiGroupTask(title = "t", dueAt = Long.MAX_VALUE)
        assertEquals(task, json.decodeFromString<AiGroupTask>(json.encodeToString(task)))
    }

    @Test
    fun auditLogResponseNegativeCountersDecodeAsIs() {
        val decoded = json.decodeFromString<AiAuditLogResponse>(
            """{"id":"a","feature":"f","status":"ok","inputChars":-3,"contextMessages":-1,"createdAt":1}"""
        )
        assertEquals(-3, decoded.inputChars)
        assertEquals(-1, decoded.contextMessages)
    }

    @Test
    fun auditLogResponseEmptyErrorRoundTrips() {
        val response = AiAuditLogResponse(
            id = "a", feature = "f", status = "error", error = "", createdAt = 1L
        )
        assertEquals(response, json.decodeFromString<AiAuditLogResponse>(json.encodeToString(response)))
    }

    @Test
    fun groupTaskIgnoresUnknownKeys() {
        val decoded = json.decodeFromString<AiGroupTask>("""{"title":"t","priority":1,"done":true}""")
        assertEquals("t", decoded.title)
        assertNull(decoded.owner)
        assertNull(decoded.dueText)
        assertNull(decoded.dueAt)
    }

    @Test
    fun auditLogResponseIgnoresUnknownKeys() {
        val decoded = json.decodeFromString<AiAuditLogResponse>(
            """{"id":"a","feature":"f","status":"ok","createdAt":1,"traceId":"x","serverTs":9}"""
        )
        assertEquals("a", decoded.id)
        assertEquals(0, decoded.inputChars)
        assertEquals(0, decoded.contextMessages)
        assertNull(decoded.error)
    }

    @Test
    fun groupTaskEmptyOwnerRoundTripsAsEmptyNotNull() {
        val task = AiGroupTask(title = "t", owner = "")
        assertEquals(task, json.decodeFromString<AiGroupTask>(json.encodeToString(task)))
    }

    @Test
    fun auditLogResponseZeroDurationDecodesAsZeroNotNull() {
        val decoded = json.decodeFromString<AiAuditLogResponse>(
            """{"id":"a","feature":"f","status":"ok","createdAt":1,"durationMs":0}"""
        )
        assertEquals(0L, decoded.durationMs)
    }

    @Test
    fun groupTaskEmptyDueTextRoundTrips() {
        val task = AiGroupTask(title = "t", dueText = "")
        assertEquals(task, json.decodeFromString<AiGroupTask>(json.encodeToString(task)))
        val decoded = json.decodeFromString<AiGroupTask>("""{"title":"t","dueText":""}""")
        assertEquals("", decoded.dueText)
    }

    @Test
    fun auditLogResponseMinCreatedAtRoundTrips() {
        val response = AiAuditLogResponse(
            id = "a", feature = "f", status = "ok", createdAt = Long.MIN_VALUE
        )
        assertEquals(response, json.decodeFromString<AiAuditLogResponse>(json.encodeToString(response)))
    }

    @Test
    fun auditLogResponseArbitraryStatusDecodes() {
        val decoded = json.decodeFromString<AiAuditLogResponse>(
            """{"id":"a","feature":"f","status":"pending-review","createdAt":1}"""
        )
        assertEquals("pending-review", decoded.status)
        assertNull(decoded.error)
    }

}
