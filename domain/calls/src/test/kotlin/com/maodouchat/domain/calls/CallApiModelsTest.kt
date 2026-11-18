package com.maodouchat.domain.calls

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.Test

class CallApiModelsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun signalingSendRequestDefaultsEmptyCallAndGroupFields() {
        val request = SignalingSendRequest(toUserId = "u2", type = "OFFER", payload = "{\"sdp\":\"x\"}")
        assertEquals("", request.callId)
        assertEquals("", request.groupId)
        assertEquals(emptyList<String>(), request.groupMemberIds)
        assertFalse(request.groupInvite)
        val decoded = json.decodeFromString<SignalingSendRequest>(json.encodeToString(request))
        assertEquals(request, decoded)
    }
    @Test
    fun signalingRequestEpochAndSequenceRoundTrip() {
        val request = SignalingSendRequest(
            toUserId = "u2",
            type = "OFFER",
            payload = "{}",
            callId = "call-1",
            epoch = 7,
            sequence = 42,
            idempotencyKey = "key-9"
        )
        assertEquals(request, json.decodeFromString<SignalingSendRequest>(json.encodeToString(request)))
    }
    @Test
    fun signalingSendRequestAnswerTypeRoundTrips() {
        val request = SignalingSendRequest(
            toUserId = "u5",
            type = "ANSWER",
            payload = "{\"sdp\":\"y\"}",
            callId = "call-3",
            epoch = Long.MAX_VALUE,
            sequence = Long.MAX_VALUE
        )
        assertEquals(request, json.decodeFromString<SignalingSendRequest>(json.encodeToString(request)))
    }
    @Test
    fun signalingSendRequestIgnoresUnknownKeys() {
        val decoded = json.decodeFromString<SignalingSendRequest>(
            """{"toUserId":"u2","type":"OFFER","payload":"{}","retransmit":true}"""
        )
        assertEquals("u2", decoded.toUserId)
        assertEquals("OFFER", decoded.type)
    }
    @Test
    fun signalingSendRequestMinimalDecodesZeroEpochAndSequence() {
        val decoded = json.decodeFromString<SignalingSendRequest>(
            """{"toUserId":"u2","type":"OFFER","payload":"{}"}"""
        )
        assertEquals(0L, decoded.epoch)
        assertEquals(0L, decoded.sequence)
        assertEquals("", decoded.idempotencyKey)
        assertEquals("", decoded.callId)
        assertEquals(emptyList<String>(), decoded.groupMemberIds)
        assertFalse(decoded.groupInvite)
    }
    @Test
    fun signalingSendRequestNegativeEpochAndSequenceRoundTrip() {
        val request = SignalingSendRequest(
            toUserId = "u2", type = "OFFER", payload = "{}",
            callId = "call-4", epoch = -1, sequence = -5, idempotencyKey = "k"
        )
        assertEquals(request, json.decodeFromString<SignalingSendRequest>(json.encodeToString(request)))
    }
    @Test
    fun signalingSendRequestPayloadWithEscapesRoundTrips() {
        val request = SignalingSendRequest(
            toUserId = "u2", type = "OFFER", payload = "line1\nline2\"quoted\""
        )
        assertEquals(request, json.decodeFromString<SignalingSendRequest>(json.encodeToString(request)))
    }
    @Test
    fun signalingSendRequestGroupMembersWithoutInviteRoundTrips() {
        val request = SignalingSendRequest(
            toUserId = "u2",
            type = "OFFER",
            payload = "{}",
            groupId = "g1",
            groupMemberIds = listOf("u2", "u3"),
            groupInvite = false
        )
        val decoded = json.decodeFromString<SignalingSendRequest>(json.encodeToString(request))
        assertEquals(request, decoded)
        assertFalse(decoded.groupInvite)
        assertEquals(listOf("u2", "u3"), decoded.groupMemberIds)
    }
    @Test
    fun signalingSendRequestEmptyPayloadRoundTrips() {
        val request = SignalingSendRequest(toUserId = "u2", type = "OFFER", payload = "")
        assertEquals(request, json.decodeFromString<SignalingSendRequest>(json.encodeToString(request)))
    }
    @Test
    fun signalingSendRequestUnicodePayloadRoundTrips() {
        val request = SignalingSendRequest(
            toUserId = "u2", type = "OFFER", payload = "你好 🌍 sdp"
        )
        assertEquals(request, json.decodeFromString<SignalingSendRequest>(json.encodeToString(request)))
    }
    @Test
    fun signalingSendRequestIceTypeRoundTrips() {
        val request = SignalingSendRequest(toUserId = "u2", type = "ICE", payload = "{}")
        assertEquals(request, json.decodeFromString<SignalingSendRequest>(json.encodeToString(request)))
    }
    @Test
    fun signalingSendRequestDuplicateMemberIdsPreserveOrder() {
        val request = SignalingSendRequest(
            toUserId = "u2", type = "OFFER", payload = "{}",
            groupId = "g1", groupMemberIds = listOf("u2", "u2", "u3"), groupInvite = true
        )
        val decoded = json.decodeFromString<SignalingSendRequest>(json.encodeToString(request))
        assertEquals(request, decoded)
        assertEquals(listOf("u2", "u2", "u3"), decoded.groupMemberIds)
    }
    @Test
    fun signalingSendRequestCandidateTypeRoundTrips() {
        val request = SignalingSendRequest(toUserId = "u2", type = "CANDIDATE", payload = "{}")
        assertEquals(request, json.decodeFromString<SignalingSendRequest>(json.encodeToString(request)))
    }
    @Test
    fun signalingSendRequestMinEpochAndSequenceRoundTrip() {
        val request = SignalingSendRequest(
            toUserId = "u2", type = "OFFER", payload = "{}",
            callId = "call-5", epoch = Long.MIN_VALUE, sequence = Long.MIN_VALUE, idempotencyKey = "k"
        )
        assertEquals(request, json.decodeFromString<SignalingSendRequest>(json.encodeToString(request)))
    }
    @Test
    fun groupInviteSignalingRoundTripsMemberIds() {
        val request = SignalingSendRequest(
            toUserId = "u2",
            type = "OFFER",
            payload = "{}",
            callId = "call-9",
            groupId = "g1",
            groupMemberIds = listOf("u2", "u3"),
            groupInvite = true
        )
        assertEquals(request, json.decodeFromString<SignalingSendRequest>(json.encodeToString(request)))
    }
}
