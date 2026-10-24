package com.maodouchat.network

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
    fun signalMessageDtoFillsCallDefaultsWhenServerOmitsGroupKeys() {
        val decoded = json.decodeFromString<SignalMessageDto>(
            """{"id":"s1","fromUserId":"u1","type":"OFFER","payload":"{}","timestamp":42}"""
        )
        assertEquals("s1", decoded.id)
        assertEquals("", decoded.callId)
        assertEquals("", decoded.groupId)
        assertEquals(emptyList<String>(), decoded.groupMemberIds)
        assertFalse(decoded.groupInvite)
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
        val dto = SignalMessageDto(
            id = "s2",
            fromUserId = "u1",
            type = "OFFER",
            payload = "{}",
            timestamp = 7L,
            callId = "call-9",
            groupId = "g1",
            groupMemberIds = listOf("u2", "u3"),
            groupInvite = true
        )
        assertEquals(dto, json.decodeFromString<SignalMessageDto>(json.encodeToString(dto)))
    }

    @Test
    fun iceConfigDtoDefaultsAndRoundTripsTurnServers() {
        val empty = json.decodeFromString<IceConfigDto>("{}")
        assertEquals(emptyList<IceServerDto>(), empty.iceServers)
        assertEquals(0L, empty.expiresAt)
        assertFalse(empty.turnEnabled)

        val config = IceConfigDto(
            iceServers = listOf(
                IceServerDto(urls = listOf("stun:stun.example"), username = "", credential = ""),
                IceServerDto(urls = listOf("turn:turn.example"), username = "u", credential = "p")
            ),
            expiresAt = 99L,
            turnEnabled = true
        )
        assertEquals(config, json.decodeFromString<IceConfigDto>(json.encodeToString(config)))
        val stunOnly = json.decodeFromString<IceServerDto>("""{"urls":["stun:stun.example"]}""")
        assertEquals("", stunOnly.username)
        assertEquals("", stunOnly.credential)
        assertTrue(stunOnly.urls.contains("stun:stun.example"))
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
    fun signalMessageDtoIgnoresUnknownKeys() {
        val decoded = json.decodeFromString<SignalMessageDto>(
            """{"id":"s9","fromUserId":"u1","type":"ANSWER","payload":"{}","timestamp":1,"sdpMid":"audio0","future":true}"""
        )
        assertEquals("s9", decoded.id)
        assertEquals(0L, decoded.epoch)
        assertEquals(0L, decoded.sequence)
        assertEquals("", decoded.idempotencyKey)
    }

    @Test
    fun signalMessageDtoRoundTripsEpochAndIdempotencyKey() {
        val dto = SignalMessageDto(
            id = "s3",
            fromUserId = "u4",
            type = "ICE",
            payload = "{}",
            timestamp = 11L,
            callId = "call-2",
            epoch = 3,
            sequence = 8,
            idempotencyKey = "dup-guard"
        )
        assertEquals(dto, json.decodeFromString<SignalMessageDto>(json.encodeToString(dto)))
    }

    @Test
    fun iceServerDtoDecodesEmptyUrls() {
        val decoded = json.decodeFromString<IceServerDto>("""{"urls":[]}""")
        assertEquals(emptyList<String>(), decoded.urls)
        assertEquals(decoded, json.decodeFromString<IceServerDto>(json.encodeToString(decoded)))
    }

    @Test
    fun iceConfigDtoIgnoresUnknownKeysAndKeepsDefaults() {
        val decoded = json.decodeFromString<IceConfigDto>(
            """{"iceServers":[{"urls":["turn:turn.example"],"username":"u","credential":"p","ttl":99}],"expiresAt":5}"""
        )
        assertEquals(1, decoded.iceServers.size)
        assertEquals(5L, decoded.expiresAt)
        assertFalse(decoded.turnEnabled)
    }
}
