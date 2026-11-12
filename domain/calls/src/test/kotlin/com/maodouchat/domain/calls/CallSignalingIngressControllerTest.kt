package com.maodouchat.domain.calls

import com.maodouchat.core.model.CallSessionGate
import com.maodouchat.core.model.CallState
import com.maodouchat.core.model.CallType
import com.maodouchat.core.model.CallUiState
import com.maodouchat.core.model.GroupPeerConnectionState
import com.maodouchat.core.model.SignalMessage
import com.maodouchat.core.realtime.RealtimeDomainEvent
import com.maodouchat.core.session.CallSignalingOutboundCursor
import com.maodouchat.core.session.Cursor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class CallSignalingIngressControllerTest {

    private class FakeWebRtc : CallSignalingWebRtc {
        val answers = mutableListOf<String>()
        val groupAnswers = mutableListOf<Pair<String, String>>()
        val iceCandidates = mutableListOf<Triple<String?, Int, String>>()
        val groupIceCandidates = mutableListOf<Triple<String, String, String>>()
        val removedPeers = mutableListOf<String>()
        val peers = mutableSetOf<String>()
        val acceptedOffers = mutableListOf<String>()
        var lastIceCallback: ((String?, Int, String) -> Unit)? = null
        var lastAnswerCallback: ((String) -> Unit)? = null

        override fun handleAnswer(payload: String) {
            answers += payload
        }

        override fun handleGroupAnswer(fromUserId: String, payload: String) {
            groupAnswers += fromUserId to payload
        }

        override fun addIceCandidate(sdpMid: String?, sdpMLineIndex: Int, sdp: String) {
            iceCandidates += Triple(sdpMid, sdpMLineIndex, sdp)
        }

        override fun addGroupIceCandidate(fromUserId: String, sdpMid: String?, sdpMLineIndex: Int, sdp: String) {
            groupIceCandidates += Triple(fromUserId, sdpMid ?: "", sdp)
        }

        override fun removeGroupPeer(fromUserId: String) {
            removedPeers += fromUserId
        }

        override fun hasGroupPeer(fromUserId: String): Boolean = fromUserId in peers

        override fun acceptGroupOffer(
            peerUserId: String,
            remoteOfferSdp: String,
            type: CallType,
            onIceCandidate: (sdpMid: String?, sdpMLineIndex: Int, sdp: String) -> Unit,
            onAnswerCreated: (description: String) -> Unit,
        ) {
            acceptedOffers += peerUserId
            lastIceCallback = onIceCandidate
            lastAnswerCallback = onAnswerCreated
        }
    }

    private class FakeMesh : CallSignalingGroupMesh {
        val cancelledInvites = mutableListOf<String>()
        val scheduledTimeouts = mutableListOf<String>()
        val terminals = mutableListOf<Pair<String, GroupPeerConnectionState>>()

        override fun cancelInviteTimeout(userId: String) {
            cancelledInvites += userId
        }

        override fun scheduleGroupPeerTimeout(userId: String) {
            scheduledTimeouts += userId
        }

        override fun markGroupPeerTerminal(userId: String, state: GroupPeerConnectionState) {
            terminals += userId to state
        }
    }

    private class RecordingGateway : CallSignalingGateway {
        val webSocketCalls = mutableListOf<Triple<String, String, String>>()
        val restCalls = mutableListOf<Triple<String, String, String>>()

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
            webSocketCalls += Triple(toUserId, type, payload)
            return true
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
            restCalls += Triple(toUserId, type, payload)
            return Result.success(Unit)
        }
    }

    // Fixture 用 runTest 自身的 TestScope：launch 进 backgroundScope 时
    // advanceUntilIdle 泵不到，信令协程不跑（CallHangUpSenderTest 同款）。
    private class Fixture(private val scope: CoroutineScope) {
        var state = CallUiState()
        var token = "token-1"
        var owner = "owner-1"
        var mayContinue = true
        var rememberResult = true
        var callId = ""
        var groupId = ""
        var meshMembers = listOf<String>()
        var groupMembers = setOf<String>()
        var webRtc: FakeWebRtc? = FakeWebRtc()
        val mesh = FakeMesh()
        val events = MutableSharedFlow<RealtimeDomainEvent>(extraBufferCapacity = 64)
        var fetchResult: Result<List<SignalMessage>> = Result.success(emptyList())
        val prepared = mutableListOf<String>()
        val missedCalls = mutableListOf<String>()
        var clearedIncoming = 0
        val warnings = mutableListOf<String>()
        val ended = mutableListOf<Pair<Boolean, String?>>()
        val noAnswers = mutableListOf<String>()
        val gateway = RecordingGateway()
        val gate = CallSessionGate()
        val session = gate.begin()
        val cursors = mutableMapOf<String, Cursor>()

        val ringingTimeout = CallRingingTimeout(
            scope = scope,
            sessionGate = gate,
            activeCallSession = { session },
            currentState = { state },
            activeCallId = { callId },
            timeoutMs = 30_000L,
            text = { it.name },
            recordMissedCall = { signalingCallId, fromUserId, _, _, _ ->
                missedCalls += "$signalingCallId|$fromUserId"
            },
            logWarn = { message, _ -> warnings += message },
            onNoAnswer = { noAnswers += it },
        )

        fun controller() = CallSignalingIngressController(
            scope = scope,
            currentState = { state },
            updateState = { transform -> state = transform(state) },
            token = { token },
            ownerUserId = { owner },
            mayContinueSession = { mayContinue },
            signalingEvents = events,
            fetchPendingSignaling = { fetchResult },
            isCurrentCallSession = { s, _ -> gate.isCurrent(s) },
            lastAcceptedCursor = { key -> cursors[key] },
            recordAcceptedCursor = { key, cursor -> cursors[key] = cursor },
            rememberSignaling = { _, _, _, _, _ -> rememberResult },
            activeCallId = { callId },
            activeGroupId = { groupId },
            meshGroupMemberIds = { meshMembers },
            groupMemberIds = { groupMembers },
            activeCallSession = { session },
            webRtc = { webRtc },
            groupMesh = mesh,
            signalingSender = CallSignalingSender(
                scope = scope,
                token = { token },
                activeCallId = { callId },
                activeGroupId = { groupId },
                meshGroupMemberIds = { meshMembers },
                activeCallSession = { session },
                callSessionGate = gate,
                outboundSignalingCursor = CallSignalingOutboundCursor(),
                gateway = gateway,
                updateState = { transform -> state = transform(state) },
                text = { message, args -> "${message.name}:${args.joinToString()}" },
                failureReason = { it.message ?: "err" },
                onEndCall = { ended += false to it },
            ),
            ringingTimeout = ringingTimeout,
            prepareIncomingCall = { contactId, _, _, _, _, callIdArg, _, _ -> prepared += "$contactId|$callIdArg" },
            recordMissedCall = { signalingCallId, fromUserId, _, _, _ ->
                missedCalls += "$signalingCallId|$fromUserId"
            },
            clearIncomingCall = { clearedIncoming++ },
            logWarning = { message, _ -> warnings += message },
            text = { message, args -> "${message.name}:${args.joinToString()}" },
            failureReason = { it.message ?: "err" },
            endCall = { notifyPeer, errorMessage -> ended += notifyPeer to errorMessage },
        )
    }

    private fun signaling(
        type: String,
        fromUserId: String = "c1",
        payload: String = "",
        callId: String = "call-1",
        groupId: String = "",
        groupMemberIds: List<String> = emptyList(),
        groupInvite: Boolean = false,
    ) = RealtimeDomainEvent.CallSignaling(
        fromUserId = fromUserId,
        type = type,
        payload = payload,
        callId = callId,
        groupId = groupId,
        groupMemberIds = groupMemberIds,
        groupInvite = groupInvite,
    )

    @Test
    fun wsOfferWhenIdlePreparesIncomingCall() = runTest {
        val f = Fixture(this)
        f.state = CallUiState(contactId = "c1", callState = CallState.IDLE)
        val c = f.controller()
        c.observeSignaling()
        f.events.tryEmit(signaling("offer", payload = "v=0 offer"))
        advanceTimeBy(100)
        runCurrent()
        c.stop()
        advanceUntilIdle()
        assertEquals(listOf("c1|call-1"), f.prepared)
    }

    @Test
    fun wsAnswerCancelsRingingTimeoutAndClearsInitializing() = runTest {
        val f = Fixture(this)
        f.state = CallUiState(contactId = "c1", callState = CallState.CALLING, isInitializing = true)
        f.callId = "call-1"
        val c = f.controller()
        f.ringingTimeout.start("c1")
        c.observeSignaling()
        f.events.tryEmit(signaling("answer", payload = "v=0 answer"))
        advanceTimeBy(100)
        runCurrent()
        c.stop()
        advanceUntilIdle()
        assertEquals(listOf("v=0 answer"), f.webRtc!!.answers)
        assertFalse(f.state.isInitializing)
        advanceTimeBy(31_000)
        runCurrent()
        assertTrue(f.noAnswers.isEmpty())
    }

    @Test
    fun wsIceCandidateRoutesByCallKind() = runTest {
        val f = Fixture(this)
        f.state = CallUiState(contactId = "c1", callState = CallState.CONNECTED)
        val c = f.controller()
        c.observeSignaling()
        f.events.tryEmit(signaling("ice-candidate", payload = "mid|2|candidate-sdp"))
        advanceTimeBy(100)
        runCurrent()
        c.stop()
        advanceUntilIdle()
        assertEquals(listOf(Triple<String?, Int, String>("mid", 2, "candidate-sdp")), f.webRtc!!.iceCandidates)
    }

    @Test
    fun wsIceCandidateGroupRoutesToGroupPeer() = runTest {
        val f = Fixture(this)
        f.state = CallUiState(contactId = "c1", callState = CallState.CONNECTED, isGroupCall = true)
        f.groupId = "g1"
        f.groupMembers = setOf("c1")
        f.meshMembers = listOf("c1")
        val c = f.controller()
        c.observeSignaling()
        f.events.tryEmit(signaling("ice-candidate", payload = "mid|0|s", groupId = "g1", groupMemberIds = listOf("c1")))
        advanceTimeBy(100)
        runCurrent()
        c.stop()
        advanceUntilIdle()
        assertEquals(listOf(Triple("c1", "mid", "s")), f.webRtc!!.groupIceCandidates)
    }

    @Test
    fun wsHangUpWhileRingingRecordsMissedAndEndsCall() = runTest {
        val f = Fixture(this)
        f.state = CallUiState(contactId = "c1", contactName = "C1", callState = CallState.RINGING, isIncoming = true)
        f.callId = "call-1"
        val c = f.controller()
        c.observeSignaling()
        f.events.tryEmit(signaling("hang-up"))
        advanceTimeBy(100)
        runCurrent()
        c.stop()
        advanceUntilIdle()
        assertEquals(listOf("call-1|c1"), f.missedCalls)
        assertEquals(1, f.clearedIncoming)
        assertEquals(listOf<Pair<Boolean, String?>>(false to null), f.ended)
    }

    @Test
    fun wsHangUpFromGroupPeerRemovesPeerAndMarksTerminal() = runTest {
        val f = Fixture(this)
        f.state = CallUiState(contactId = "c1", callState = CallState.CONNECTED, isGroupCall = true)
        f.groupId = "g1"
        f.groupMembers = setOf("c1", "m1")
        f.meshMembers = listOf("c1", "m1")
        val c = f.controller()
        c.observeSignaling()
        f.events.tryEmit(signaling("hang-up", fromUserId = "m1", groupId = "g1", groupMemberIds = listOf("c1", "m1")))
        advanceTimeBy(100)
        runCurrent()
        c.stop()
        advanceUntilIdle()
        assertEquals(listOf("m1"), f.webRtc!!.removedPeers)
        assertEquals(listOf("m1" to GroupPeerConnectionState.DISCONNECTED), f.mesh.terminals)
        assertTrue(f.ended.isEmpty())
    }

    @Test
    fun wsRejectAndBusyEndCallWithPeerText() = runTest {
        val f = Fixture(this)
        f.state = CallUiState(contactId = "c1", callState = CallState.CALLING)
        val c = f.controller()
        c.observeSignaling()
        f.events.tryEmit(signaling("reject"))
        advanceTimeBy(100)
        runCurrent()
        f.events.tryEmit(signaling("busy"))
        advanceTimeBy(100)
        runCurrent()
        c.stop()
        advanceUntilIdle()
        assertEquals(
            listOf<Pair<Boolean, String?>>(false to "PEER_REJECTED:", false to "PEER_BUSY:"),
            f.ended,
        )
    }

    @Test
    fun wsOfferForOtherGroupWhileBusySendsBusySignal() = runTest {
        val f = Fixture(this)
        f.state = CallUiState(contactId = "c1", callState = CallState.CALLING, isGroupCall = true)
        f.callId = "call-1"
        f.groupId = "g1"
        f.groupMembers = setOf("c1", "m1")
        f.meshMembers = listOf("c1", "m1")
        val c = f.controller()
        c.observeSignaling()
        f.events.tryEmit(
            signaling("offer", fromUserId = "m1", callId = "call-2", payload = "v=0", groupId = "g2", groupMemberIds = listOf("c1", "m1")),
        )
        advanceTimeBy(100)
        runCurrent()
        c.stop()
        advanceUntilIdle()
        assertTrue(f.prepared.isEmpty())
        assertTrue(f.gateway.webSocketCalls.any { it.first == "m1" && it.second == "busy" })
    }

    @Test
    fun wsSignalFromUnexpectedPeerIsDropped() = runTest {
        val f = Fixture(this)
        f.state = CallUiState(contactId = "c1", callState = CallState.CONNECTED)
        val c = f.controller()
        c.observeSignaling()
        f.events.tryEmit(signaling("answer", fromUserId = "stranger", payload = "v=0"))
        advanceTimeBy(100)
        runCurrent()
        c.stop()
        advanceUntilIdle()
        assertTrue(f.webRtc!!.answers.isEmpty())
        assertTrue(f.ended.isEmpty())
    }

    @Test
    fun wsDuplicateSignalIsSkippedByIdempotency() = runTest {
        val f = Fixture(this)
        f.state = CallUiState(contactId = "c1", callState = CallState.IDLE)
        f.rememberResult = false
        val c = f.controller()
        c.observeSignaling()
        f.events.tryEmit(signaling("offer", payload = "v=0"))
        advanceTimeBy(100)
        runCurrent()
        c.stop()
        advanceUntilIdle()
        assertTrue(f.prepared.isEmpty())
    }

    @Test
    fun wsRateLimitedErrorEndsCallWhileCalling() = runTest {
        val f = Fixture(this)
        f.state = CallUiState(contactId = "c1", callState = CallState.CALLING)
        val c = f.controller()
        c.observeSignaling()
        f.events.tryEmit(RealtimeDomainEvent.RealtimeError(code = "CALL_INVITE_RATE_LIMITED", retryAfterSeconds = 45))
        advanceTimeBy(100)
        runCurrent()
        c.stop()
        advanceUntilIdle()
        assertEquals(listOf<Pair<Boolean, String?>>(false to "RATE_LIMITED:45"), f.ended)
    }

    @Test
    fun pollingFetchDeliversPendingOffer() = runTest {
        val f = Fixture(this)
        f.state = CallUiState(contactId = "c1", callState = CallState.IDLE)
        f.fetchResult = Result.success(
            listOf(
                SignalMessage(
                    id = "m1",
                    fromUserId = "c1",
                    type = "offer",
                    payload = "v=0 offer",
                    timestamp = 1L,
                    callId = "call-9",
                ),
            ),
        )
        val c = f.controller()
        c.observeSignaling()
        advanceTimeBy(2100)
        runCurrent()
        c.stop()
        advanceUntilIdle()
        assertEquals(listOf("c1|call-9"), f.prepared)
    }

    @Test
    fun pollingFetchFailureWritesErrorMessage() = runTest {
        val f = Fixture(this)
        f.state = CallUiState(contactId = "c1", callState = CallState.IDLE)
        f.fetchResult = Result.failure(RuntimeException("boom"))
        val c = f.controller()
        c.observeSignaling()
        advanceTimeBy(2100)
        runCurrent()
        c.stop()
        advanceUntilIdle()
        assertEquals("FETCH_SIGNALING_FAILED:boom", f.state.errorMessage)
    }

    @Test
    fun stopCancelsBothReceivers() = runTest {
        val f = Fixture(this)
        f.state = CallUiState(contactId = "c1", callState = CallState.IDLE)
        val c = f.controller()
        c.observeSignaling()
        c.stop()
        f.events.tryEmit(signaling("offer", payload = "v=0"))
        advanceUntilIdle()
        assertTrue(f.prepared.isEmpty())
    }

    @Test
    fun groupOfferBuffersWhileManagerMissingAndFlushesOnReady() = runTest {
        val f = Fixture(this)
        f.state = CallUiState(contactId = "c1", callState = CallState.RINGING, isGroupCall = true)
        f.groupId = "g1"
        f.groupMembers = setOf("c1", "m1")
        f.meshMembers = listOf("c1", "m1")
        f.webRtc = null
        val c = f.controller()
        c.observeSignaling()
        f.events.tryEmit(signaling("offer", fromUserId = "m1", payload = "v=0 group-offer", groupId = "g1", groupMemberIds = listOf("c1", "m1")))
        advanceTimeBy(100)
        runCurrent()
        c.stop()
        advanceUntilIdle()
        assertTrue(f.prepared.isEmpty())
        assertEquals(listOf("m1"), f.mesh.scheduledTimeouts)
        val manager = FakeWebRtc()
        c.flushPendingGroupOffers(manager, f.session)
        assertEquals(listOf("m1"), manager.acceptedOffers)
        manager.lastIceCallback?.invoke("mid", 0, "cand")
        manager.lastAnswerCallback?.invoke("v=0 answer")
        advanceUntilIdle()
        assertTrue(f.gateway.webSocketCalls.any { it.second == "ice-candidate" })
        assertTrue(f.gateway.webSocketCalls.any { it.second == "answer" })
    }

    @Test
    fun clearPendingGroupOffersDropsBufferedOffer() = runTest {
        val f = Fixture(this)
        f.state = CallUiState(contactId = "c1", callState = CallState.RINGING, isGroupCall = true)
        f.groupId = "g1"
        f.groupMembers = setOf("c1", "m1")
        f.meshMembers = listOf("c1", "m1")
        f.webRtc = null
        val c = f.controller()
        c.observeSignaling()
        f.events.tryEmit(signaling("offer", fromUserId = "m1", payload = "v=0 group-offer", groupId = "g1", groupMemberIds = listOf("c1", "m1")))
        advanceTimeBy(100)
        runCurrent()
        c.stop()
        advanceUntilIdle()
        c.clearPendingGroupOffers()
        val manager = FakeWebRtc()
        c.flushPendingGroupOffers(manager, f.session)
        assertTrue(manager.acceptedOffers.isEmpty())
    }
}
