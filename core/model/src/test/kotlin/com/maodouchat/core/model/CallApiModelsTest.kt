package com.maodouchat.core.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallApiModelsTest {
    private val json = Json { ignoreUnknownKeys = true }

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
    @Test
    fun iceServerDtoMultipleUrlsPreserveOrder() {
        val server = IceServerDto(
            urls = listOf("turn:turn.example:3478", "turns:turn.example:5349"),
            username = "u",
            credential = "p"
        )
        val decoded = json.decodeFromString<IceServerDto>(json.encodeToString(server))
        assertEquals(listOf("turn:turn.example:3478", "turns:turn.example:5349"), decoded.urls)
    }
    @Test
    fun signalMessageDtoGroupKeysDefaultWhenAbsent() {
        val decoded = json.decodeFromString<SignalMessageDto>(
            """{"id":"s4","fromUserId":"u1","type":"OFFER","payload":"{}","timestamp":5}"""
        )
        assertEquals("", decoded.callId)
        assertEquals("", decoded.groupId)
        assertEquals(emptyList<String>(), decoded.groupMemberIds)
        assertFalse(decoded.groupInvite)
        assertEquals("", decoded.idempotencyKey)
    }
    @Test
    fun iceConfigDtoTurnEnabledFalseKeepsTurnServers() {
        val decoded = json.decodeFromString<IceConfigDto>(
            """{"iceServers":[{"urls":["turn:turn.example"],"username":"u","credential":"p"}],"expiresAt":8}"""
        )
        assertEquals(1, decoded.iceServers.size)
        assertEquals("u", decoded.iceServers[0].username)
        assertFalse(decoded.turnEnabled)
    }
    @Test
    fun signalMessageDtoEmptyPayloadRoundTrips() {
        val dto = SignalMessageDto(
            id = "s8",
            fromUserId = "u1",
            type = "OFFER",
            payload = "",
            timestamp = 3L
        )
        assertEquals(dto, json.decodeFromString<SignalMessageDto>(json.encodeToString(dto)))
    }
    @Test
    fun iceConfigDtoExplicitEmptyServersRoundTrips() {
        val config = IceConfigDto(iceServers = emptyList(), expiresAt = 0L, turnEnabled = false)
        assertEquals(config, json.decodeFromString<IceConfigDto>(json.encodeToString(config)))
    }
    @Test
    fun iceServerDtoUsernameWithEmptyCredentialRoundTrips() {
        val server = IceServerDto(urls = listOf("turn:turn.example"), username = "u", credential = "")
        assertEquals(server, json.decodeFromString<IceServerDto>(json.encodeToString(server)))
    }
    @Test
    fun signalMessageDtoNegativeTimestampRoundTrips() {
        val dto = SignalMessageDto(
            id = "s10", fromUserId = "u1", type = "OFFER", payload = "{}",
            timestamp = -1L
        )
        assertEquals(dto, json.decodeFromString<SignalMessageDto>(json.encodeToString(dto)))
    }
    @Test
    fun iceConfigDtoNegativeExpiresAtDecodesAsIs() {
        val decoded = json.decodeFromString<IceConfigDto>("""{"expiresAt":-1}""")
        assertEquals(-1L, decoded.expiresAt)
        assertEquals(emptyList<IceServerDto>(), decoded.iceServers)
        assertFalse(decoded.turnEnabled)
    }
    @Test
    fun signalMessageDtoGroupInviteTrueWithoutMembersRoundTrips() {
        val dto = SignalMessageDto(
            id = "s11", fromUserId = "u1", type = "OFFER", payload = "{}",
            timestamp = 4L, groupInvite = true
        )
        assertEquals(dto, json.decodeFromString<SignalMessageDto>(json.encodeToString(dto)))
        assertTrue(dto.groupInvite)
        assertEquals(emptyList<String>(), dto.groupMemberIds)
    }
    @Test
    fun signalMessageDtoMaxEpochAndSequenceRoundTrip() {
        val dto = SignalMessageDto(
            id = "s12",
            fromUserId = "u1",
            type = "OFFER",
            payload = "{}",
            timestamp = 1L,
            epoch = Long.MAX_VALUE,
            sequence = Long.MAX_VALUE
        )
        assertEquals(dto, json.decodeFromString<SignalMessageDto>(json.encodeToString(dto)))
    }
    @Test
    fun iceConfigDtoMaxExpiresAtRoundTrips() {
        val config = IceConfigDto(
            iceServers = listOf(IceServerDto(urls = listOf("turn:turn.example"), username = "u", credential = "p")),
            expiresAt = Long.MAX_VALUE,
            turnEnabled = true
        )
        assertEquals(config, json.decodeFromString<IceConfigDto>(json.encodeToString(config)))
    }
    @Test
    fun signalMessageDtoIgnoresUnknownKeysKeepsGroupFields() {
        val decoded = json.decodeFromString<SignalMessageDto>(
            """{"id":"s13","fromUserId":"u1","type":"OFFER","payload":"{}","timestamp":2,"groupId":"g9","groupMemberIds":["u2"],"groupInvite":true,"future":true}"""
        )
        assertEquals("g9", decoded.groupId)
        assertEquals(listOf("u2"), decoded.groupMemberIds)
        assertTrue(decoded.groupInvite)
    }
    @Test
    fun signalMessageDtoAnswerTypeRoundTrips() {
        val dto = SignalMessageDto(
            id = "s20", fromUserId = "u1", type = "ANSWER", payload = "{}",
            timestamp = 9L, epoch = 2, sequence = 3, idempotencyKey = "k"
        )
        assertEquals(dto, json.decodeFromString<SignalMessageDto>(json.encodeToString(dto)))
    }
    @Test
    fun signalMessageDtoMaxTimestampRoundTrips() {
        val dto = SignalMessageDto(
            id = "s21", fromUserId = "u1", type = "OFFER", payload = "{}", timestamp = Long.MAX_VALUE
        )
        assertEquals(dto, json.decodeFromString<SignalMessageDto>(json.encodeToString(dto)))
    }
    @Test
    fun iceConfigDtoTurnEnabledTrueWithEmptyServersRoundTrips() {
        val config = IceConfigDto(iceServers = emptyList(), expiresAt = 0L, turnEnabled = true)
        assertEquals(config, json.decodeFromString<IceConfigDto>(json.encodeToString(config)))
    }
    @Test
    fun iceServerDtoEmptyUsernameAndCredentialRoundTrips() {
        val server = IceServerDto(urls = listOf("stun:stun.example"), username = "", credential = "")
        assertEquals(server, json.decodeFromString<IceServerDto>(json.encodeToString(server)))
    }
    @Test
    fun iceConfigDtoTurnEnabledTrueWithStunOnlyRoundTrips() {
        val config = IceConfigDto(
            iceServers = listOf(IceServerDto(urls = listOf("stun:stun.example"), username = "", credential = "")),
            expiresAt = 10L,
            turnEnabled = true
        )
        assertEquals(config, json.decodeFromString<IceConfigDto>(json.encodeToString(config)))
        assertTrue(config.turnEnabled)
    }
    @Test
    fun signalMessageDtoMinTimestampRoundTrips() {
        val dto = SignalMessageDto(
            id = "s22", fromUserId = "u1", type = "OFFER", payload = "{}",
            timestamp = Long.MIN_VALUE
        )
        assertEquals(dto, json.decodeFromString<SignalMessageDto>(json.encodeToString(dto)))
    }
    @Test
    fun groupInviteSignalMessageDtoRoundTripsMemberIds() {
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
}
