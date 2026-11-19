package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class AiSemanticSearchCandidateTest {
    private val json = Json { ignoreUnknownKeys = true }

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
    fun semanticSearchCandidateEmptyTextZeroTimestampRoundTrips() {
        val candidate = AiSemanticSearchCandidate(messageId = "m0", sender = "", text = "", timestamp = 0L)
        assertEquals(
            candidate,
            json.decodeFromString<AiSemanticSearchCandidate>(json.encodeToString(candidate))
        )
    }

    @Test
    fun semanticSearchCandidateNegativeTimestampRoundTrips() {
        val candidate = AiSemanticSearchCandidate(messageId = "m1", sender = "u1", text = "x", timestamp = -1L)
        assertEquals(candidate, json.decodeFromString<AiSemanticSearchCandidate>(json.encodeToString(candidate)))
    }

    @Test
    fun semanticCandidateMaxTimestampRoundTrips() {
        val candidate = AiSemanticSearchCandidate(
            messageId = "m1", sender = "u1", text = "x", timestamp = Long.MAX_VALUE
        )
        assertEquals(
            candidate,
            json.decodeFromString<AiSemanticSearchCandidate>(json.encodeToString(candidate))
        )
    }

    @Test
    fun semanticCandidateIgnoresUnknownKeys() {
        val decoded = json.decodeFromString<AiSemanticSearchCandidate>(
            """{"messageId":"m2","sender":"u1","text":"hi","timestamp":3,"score":0.9,"snippet":"x"}"""
        )
        assertEquals("m2", decoded.messageId)
        assertEquals("u1", decoded.sender)
        assertEquals("hi", decoded.text)
        assertEquals(3L, decoded.timestamp)
    }

    @Test
    fun semanticCandidateMinTimestampRoundTrips() {
        val candidate = AiSemanticSearchCandidate(
            messageId = "m1", sender = "u1", text = "x", timestamp = Long.MIN_VALUE
        )
        assertEquals(
            candidate,
            json.decodeFromString<AiSemanticSearchCandidate>(json.encodeToString(candidate))
        )
    }

    @Test
    fun semanticCandidateEmptyMessageIdRoundTrips() {
        val candidate = AiSemanticSearchCandidate(messageId = "", sender = "u1", text = "x", timestamp = 5L)
        assertEquals(candidate, json.decodeFromString<AiSemanticSearchCandidate>(json.encodeToString(candidate)))
    }

    @Test
    fun semanticCandidateWhitespaceTextRoundTrips() {
        val candidate = AiSemanticSearchCandidate(messageId = "m1", sender = "u1", text = " ", timestamp = 5L)
        assertEquals(
            candidate,
            json.decodeFromString<AiSemanticSearchCandidate>(json.encodeToString(candidate))
        )
    }
}
