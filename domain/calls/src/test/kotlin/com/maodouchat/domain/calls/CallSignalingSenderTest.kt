package com.maodouchat.domain.calls

import com.maodouchat.core.model.CallSessionGate
import com.maodouchat.core.model.CallState
import com.maodouchat.core.model.CallUiState
import com.maodouchat.core.model.FailureOperation
import com.maodouchat.core.model.SignalingException
import com.maodouchat.core.session.CallSignalingOutboundCursor
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CallSignalingSenderTest {

    private class FakeGateway(
        var wsResult: Boolean = true,
        var restResult: Result<Unit> = Result.success(Unit),
    ) : CallSignalingGateway {
        data class WsCall(
            val toUserId: String,
            val type: String,
            val payload: String,
            val callId: String,
            val groupId: String,
            val groupMemberIds: List<String>,
            val groupInvite: Boolean,
            val epoch: Long,
            val sequence: Long,
            val idempotencyKey: String,
        )

        data class RestCall(
            val token: String,
            val toUserId: String,
            val type: String,
            val payload: String,
            val callId: String,
            val groupId: String,
            val groupMemberIds: List<String>,
            val groupInvite: Boolean,
            val epoch: Long,
            val sequence: Long,
            val idempotencyKey: String,
        )

        val wsCalls = mutableListOf<WsCall>()
        val restCalls = mutableListOf<RestCall>()

        override fun sendViaWebSocket(
            toUserId: String,
            type: String,
            payload: String,
            callId: String,
            groupId: String,
            groupMemberIds: List<String>,
            groupInvite: Boolean,
            epoch: Long,
            sequence: Long,
            idempotencyKey: String,
        ): Boolean {
            wsCalls += WsCall(toUserId, type, payload, callId, groupId, groupMemberIds, groupInvite, epoch, sequence, idempotencyKey)
            return wsResult
        }

        override suspend fun sendViaRest(
            token: String,
            toUserId: String,
            type: String,
            payload: String,
            callId: String,
            groupId: String,
            groupMemberIds: List<String>,
            groupInvite: Boolean,
            epoch: Long,
            sequence: Long,
            idempotencyKey: String,
        ): Result<Unit> {
            restCalls += RestCall(token, toUserId, type, payload, callId, groupId, groupMemberIds, groupInvite, epoch, sequence, idempotencyKey)
            return restResult
        }
    }

    private class Fixture(
        val gateway: FakeGateway = FakeGateway(),
        var token: String = "tok",
    ) {
        val gate = CallSessionGate()
        val session = gate.begin()
        var state = CallUiState(callState = CallState.CONNECTED)
        val endCalls = mutableListOf<String?>()

        fun sender(scope: kotlinx.coroutines.CoroutineScope) = CallSignalingSender(
            scope = scope,
            token = { token },
            activeCallId = { "c1" },
            activeGroupId = { "" },
            meshGroupMemberIds = { emptyList() },
            activeCallSession = { session },
            callSessionGate = gate,
            outboundSignalingCursor = CallSignalingOutboundCursor(),
            gateway = gateway,
            updateState = { transform -> state = transform(state) },
            text = { message, args -> "${message.name}(${args.joinToString(",")})" },
            failureReason = { it.message ?: "?" },
            onEndCall = { endCalls += it },
        )
    }

    @Test
    fun blankTokenWritesSessionExpiredAndSendsNothing() = runTest {
        val f = Fixture(token = "")
        f.sender(this).sendSignalWithFallback("u1", "offer", "p", "prefix")
        advanceUntilIdle()
        assertTrue(f.gateway.wsCalls.isEmpty())
        assertTrue(f.gateway.restCalls.isEmpty())
        assertEquals("SESSION_EXPIRED()", f.state.errorMessage)
    }

    @Test
    fun nonCriticalTypeWithWsSuccessSkipsRest() = runTest {
        val f = Fixture()
        f.sender(this).sendSignalWithFallback("u1", "ice-candidate", "p", "prefix")
        advanceUntilIdle()
        assertEquals(1, f.gateway.wsCalls.size)
        assertTrue(f.gateway.restCalls.isEmpty())
        val ws = f.gateway.wsCalls.single()
        assertEquals(1L, ws.epoch)
        assertEquals(1L, ws.sequence)
        assertEquals("c1|1|1|ice-candidate", ws.idempotencyKey)
    }

    @Test
    fun criticalTypeAlwaysRestsWithSameTicket() = runTest {
        val f = Fixture()
        f.sender(this).sendSignalWithFallback("u1", "offer", "p", "prefix")
        advanceUntilIdle()
        assertEquals(1, f.gateway.wsCalls.size)
        assertEquals(1, f.gateway.restCalls.size)
        val rest = f.gateway.restCalls.single()
        assertEquals("tok", rest.token)
        assertEquals(1L, rest.epoch)
        assertEquals(1L, rest.sequence)
        assertEquals("c1|1|1|offer", rest.idempotencyKey)
    }

    @Test
    fun wsFailureFallsBackToRest() = runTest {
        val f = Fixture(FakeGateway(wsResult = false))
        f.sender(this).sendSignalWithFallback("u1", "ice-candidate", "p", "prefix")
        advanceUntilIdle()
        assertEquals(1, f.gateway.wsCalls.size)
        assertEquals(1, f.gateway.restCalls.size)
    }

    @Test
    fun restFailureWritesErrorMessage() = runTest {
        val f = Fixture(FakeGateway(restResult = Result.failure(SignalingException(FailureOperation.SEND, code = "OTHER"))))
        f.sender(this).sendSignalWithFallback("u1", "offer", "p", "prefix")
        advanceUntilIdle()
        assertEquals("ERROR_WITH_REASON(prefix,WebRTC signaling send failed)", f.state.errorMessage)
        assertTrue(f.endCalls.isEmpty())
    }

    @Test
    fun rateLimitedOfferEndsCallWithoutErrorMessage() = runTest {
        val f = Fixture(FakeGateway(restResult = Result.failure(SignalingException(FailureOperation.SEND, code = "CALL_INVITE_RATE_LIMITED"))))
        f.sender(this).sendSignalWithFallback("u1", "offer", "p", "prefix")
        advanceUntilIdle()
        assertEquals(listOf<String?>("WebRTC signaling send failed"), f.endCalls)
        assertNull(f.state.errorMessage)
    }

    @Test
    fun hangUpRestFailureWritesNothing() = runTest {
        val f = Fixture(FakeGateway(restResult = Result.failure(SignalingException(FailureOperation.SEND, code = "OTHER"))))
        f.sender(this).sendSignalWithFallback("u1", "hang-up", "p", "prefix")
        advanceUntilIdle()
        assertNull(f.state.errorMessage)
        assertTrue(f.endCalls.isEmpty())
    }

    @Test
    fun sendSdpUsesDescriptionAndFailedPrefix() = runTest {
        val f = Fixture(FakeGateway(wsResult = false, restResult = Result.failure(SignalingException(FailureOperation.SEND, code = "OTHER"))))
        f.sender(this).sendSdp("u1", "offer", "v=0-sdp")
        advanceUntilIdle()
        assertEquals("v=0-sdp", f.gateway.wsCalls.single().payload)
        assertEquals("ERROR_WITH_REASON(SEND_SDP_FAILED(OFFER),WebRTC signaling send failed)", f.state.errorMessage)
    }

    @Test
    fun sendIceCandidateBuildsPayload() = runTest {
        val f = Fixture()
        f.sender(this).sendIceCandidate("u1", "mid", 0, "sdp")
        advanceUntilIdle()
        val ws = f.gateway.wsCalls.single()
        assertEquals("ice-candidate", ws.type)
        assertEquals("mid|0|sdp", ws.payload)
        assertTrue(f.gateway.restCalls.isEmpty())
    }
}
