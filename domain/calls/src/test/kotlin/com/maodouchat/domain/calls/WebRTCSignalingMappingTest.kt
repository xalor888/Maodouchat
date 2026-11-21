package com.maodouchat.domain.calls

import com.maodouchat.core.model.ApiException
import com.maodouchat.core.model.ApiFailureKind
import com.maodouchat.core.model.IceConfigDto
import com.maodouchat.core.model.IceServerDto
import com.maodouchat.core.model.SendSignalRequest
import com.maodouchat.core.model.SignalMessageDto
import com.maodouchat.core.model.WsMessage
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class WebRTCSignalingMappingTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun mapPendingSignalsCopiesAllFields() {
        val dto = SignalMessageDto(
            id = "m1", fromUserId = "u2", type = "offer", payload = "sdp",
            timestamp = 42L, callId = "c1", groupId = "g1",
            groupMemberIds = listOf("u3", "u4"), groupInvite = true,
            epoch = 7L, sequence = 9L, idempotencyKey = "k1",
        )
        assertEquals(
            listOf(
                SignalMessage(
                    id = "m1", fromUserId = "u2", type = "offer", payload = "sdp",
                    timestamp = 42L, callId = "c1", groupId = "g1",
                    groupMemberIds = listOf("u3", "u4"), groupInvite = true,
                    epoch = 7L, sequence = 9L, idempotencyKey = "k1",
                )
            ),
            mapPendingSignals(listOf(dto))
        )
    }

    @Test
    fun mapPendingSignalsAppliesDtoDefaults() {
        val dto = SignalMessageDto(
            id = "m1", fromUserId = "u2", type = "hangup", payload = "", timestamp = 0L,
        )
        assertEquals(
            listOf(
                SignalMessage(
                    id = "m1", fromUserId = "u2", type = "hangup", payload = "",
                    timestamp = 0L,
                )
            ),
            mapPendingSignals(listOf(dto))
        )
    }

    @Test
    fun mapIceServersDropsNonStunTurnUrlsAndFailsOnEmpty() {
        val config = IceConfigDto(
            iceServers = listOf(
                IceServerDto(
                    urls = listOf("stun:stun.example.com", "https://example.com", "turn:turn.example.com"),
                    username = "user", credential = "pass",
                ),
                IceServerDto(urls = listOf("wss://example.com")),
            )
        )
        val result = mapIceServers(config)
        assertTrue(result.isSuccess)
        assertEquals(
            listOf(CallIceServer(listOf("stun:stun.example.com", "turn:turn.example.com"), "user", "pass")),
            result.getOrThrow()
        )

        val empty = mapIceServers(IceConfigDto(iceServers = listOf(IceServerDto(urls = listOf("https://x")))))
        assertTrue(empty.isFailure)
        val err = assertFailsWith<SignalingException> { empty.getOrThrow() }
        assertEquals(FailureOperation.FETCH, err.operation)
    }

    @Test
    fun buildSignalPayloadRoundTripsThroughWsEnvelope() {
        val raw = buildSignalPayload(
            toUserId = "u2", type = "answer", payload = "sdp-body",
            callId = "c1", groupId = "g1", groupMemberIds = listOf("u3"),
            groupInvite = true, epoch = 3L, sequence = 5L, idempotencyKey = "k9",
        )
        val envelope = json.decodeFromString(WsMessage.serializer(), raw)
        assertEquals("SIGNALING", envelope.type)
        val inner = json.decodeFromString(SendSignalRequest.serializer(), envelope.payload)
        assertEquals(
            SendSignalRequest(
                toUserId = "u2", type = "answer", payload = "sdp-body",
                callId = "c1", groupId = "g1", groupMemberIds = listOf("u3"),
                groupInvite = true, epoch = 3L, sequence = 5L, idempotencyKey = "k9",
            ),
            inner
        )
    }

    @Test
    fun mapSignalingResultPassesSuccessAndNonApiFailureThrough() {
        val ok = Result.success(Unit)
        assertSame(ok, mapSignalingResult(ok, FailureOperation.SEND))

        val plain = RuntimeException("boom")
        val failed = Result.failure<Unit>(plain)
        val mapped = mapSignalingResult(failed, FailureOperation.HANG_UP)
        assertTrue(mapped.isFailure)
        assertSame(plain, mapped.exceptionOrNull())
    }

    @Test
    fun mapSignalingResultMapsHttpApiExceptionAndKeepsSignalingException() {
        val apiErr = ApiException(ApiFailureKind.HTTP, statusCode = 401, serverCode = "UNAUTHORIZED")
        val mapped = mapSignalingResult(Result.failure(apiErr), FailureOperation.SEND)
        val err = assertFailsWith<SignalingException> { mapped.getOrThrow() }
        assertEquals(FailureOperation.SEND, err.operation)
        assertEquals(401, err.statusCode)
        assertEquals("UNAUTHORIZED", err.code)

        val already = SignalingException(FailureOperation.HANG_UP, statusCode = 500)
        val kept = Result.failure<Unit>(already)
        assertSame(kept, mapSignalingResult(kept, FailureOperation.HANG_UP))
    }

    @Test
    fun mapFetchFailureMapsApiExceptionAndPassesOtherErrorsThrough() {
        val apiErr = ApiException(ApiFailureKind.NETWORK, statusCode = 503, serverCode = "DOWN")
        val mapped = mapFetchFailure(apiErr)
        assertTrue(mapped is SignalingException)
        assertEquals(FailureOperation.FETCH, (mapped as SignalingException).operation)
        assertEquals(503, mapped.statusCode)
        assertEquals("DOWN", mapped.code)

        val plain = IllegalStateException("boom")
        assertSame(plain, mapFetchFailure(plain))
    }
}
