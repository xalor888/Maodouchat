package com.maodouchat.domain.calls

import com.maodouchat.core.model.CallSessionGate
import com.maodouchat.core.model.CallState
import com.maodouchat.core.model.CallType
import com.maodouchat.core.model.CallUiState
import com.maodouchat.core.model.GroupCallParticipantUi
import com.maodouchat.core.model.GroupPeerConnectionState
import com.maodouchat.core.model.WebRtcNativeLoadException
import com.maodouchat.core.session.CallSessionMachine
import com.maodouchat.core.session.CallSignalingOutboundCursor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CallIncomingCallControllerTest {

    private class FakeWebRtc : CallWebRtcSession {
        var initialized = false
        var released = false
        val answers = mutableListOf<Triple<String, CallType, String>>()
        val groupOffers = mutableListOf<String>()
        var lastIceCallback: ((String?, Int, String) -> Unit)? = null
        var lastAnswerCallback: ((String) -> Unit)? = null

        override fun handleAnswer(payload: String) = Unit
        override fun handleGroupAnswer(fromUserId: String, payload: String) = Unit
        override fun addIceCandidate(sdpMid: String?, sdpMLineIndex: Int, sdp: String) = Unit
        override fun addGroupIceCandidate(fromUserId: String, sdpMid: String?, sdpMLineIndex: Int, sdp: String) = Unit
        override fun removeGroupPeer(fromUserId: String) = Unit
        override fun hasGroupPeer(fromUserId: String): Boolean = false

        override fun acceptGroupOffer(
            peerUserId: String,
            remoteOfferSdp: String,
            type: CallType,
            onIceCandidate: (sdpMid: String?, sdpMLineIndex: Int, sdp: String) -> Unit,
            onAnswerCreated: (description: String) -> Unit,
        ) {
            groupOffers += peerUserId
            lastIceCallback = onIceCandidate
            lastAnswerCallback = onAnswerCreated
        }

        override fun initialize() {
            initialized = true
        }

        override fun release() {
            released = true
        }

        override fun answerCall(
            remoteOfferSdp: String,
            type: CallType,
            onIceCandidate: (sdpMid: String?, sdpMLineIndex: Int, sdp: String) -> Unit,
            onAnswerCreated: (description: String) -> Unit,
        ) {
            answers += Triple(remoteOfferSdp, type, "")
            lastIceCallback = onIceCandidate
            lastAnswerCallback = onAnswerCreated
        }

        override fun startCall(
            type: CallType,
            onIceCandidate: (sdpMid: String?, sdpMLineIndex: Int, sdp: String) -> Unit,
            onOfferCreated: (description: String) -> Unit,
        ) = Unit

        override fun startGroupCallToPeer(
            peerUserId: String,
            type: CallType,
            onIceCandidate: (sdpMid: String?, sdpMLineIndex: Int, sdp: String) -> Unit,
            onOfferCreated: (description: String) -> Unit,
        ) = Unit
    }

    private class FakeMesh : CallSignalingGroupMesh {
        val cancelledInvites = mutableListOf<String>()
        val scheduledTimeouts = mutableListOf<String>()
        val terminals = mutableListOf<Pair<String, GroupPeerConnectionState>>()
        var profilesLoaded = 0
        val meshEdges = mutableListOf<Pair<String, Long>>()

        override fun cancelInviteTimeout(userId: String) {
            cancelledInvites += userId
        }

        override fun scheduleGroupPeerTimeout(userId: String) {
            scheduledTimeouts += userId
        }

        override fun markGroupPeerTerminal(userId: String, state: GroupPeerConnectionState) {
            terminals += userId to state
        }

        override fun loadGroupParticipantProfiles() {
            profilesLoaded++
        }

        override fun startDeterministicMeshEdges(webRtc: CallWebRtcSession, primaryPeerId: String, session: Long) {
            meshEdges += primaryPeerId to session
        }
    }

    private class FakeGateway : CallSignalingGateway {
        val wsCalls = mutableListOf<Triple<String, String, String>>()
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
            wsCalls += Triple(toUserId, type, payload)
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

    private class Fixture {
        val gate = CallSessionGate()
        val session = gate.begin()
        val machine = CallSessionMachine()
        val webRtc = FakeWebRtc()
        val mesh = FakeMesh()
        val gateway = FakeGateway()
        var token = "tok"
        var state = CallUiState()
        var pendingOfferSdp: String? = "offer-sdp"
        var createFails: Throwable? = null
        var isGroupReset = false
        val cancelledTrays = mutableListOf<String>()
        val missedCalls = mutableListOf<String>()
        val noAnswers = mutableListOf<String>()
        var observeCalls = 0
        var markOwnerCalls = 0
        val ended = mutableListOf<Triple<Boolean, String?, Boolean>>()
        val configured = mutableListOf<Pair<CallWebRtcSession, Long>>()
        val bound = mutableListOf<CallWebRtcSession>()
        val flushed = mutableListOf<Pair<CallWebRtcSession, Long>>()

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
            onEndCall = { ended += Triple(false, it, true) },
        )

        fun ringingTimeout(scope: kotlinx.coroutines.CoroutineScope) = CallRingingTimeout(
            scope = scope,
            sessionGate = gate,
            activeCallSession = { session },
            currentState = { state },
            activeCallId = { "c1" },
            timeoutMs = 60_000L,
            text = { it.name },
            recordMissedCall = { signalingCallId, _, _, _, _ -> missedCalls += signalingCallId },
            logWarn = { _, _ -> },
            onNoAnswer = { noAnswers += it },
        )

        fun foregroundService() = CallForegroundServiceController(
            startCallForeground = { _, _, _ -> },
            stopCallForeground = {},
            currentState = { state },
            activeCallId = { "c1" },
            unknownCallerLabel = "unknown",
        )

        fun controller(scope: kotlinx.coroutines.CoroutineScope) = CallIncomingCallController(
            scope = scope,
            currentState = { state },
            updateState = { transform -> state = transform(state) },
            token = { token },
            text = { message, args -> "${message.name}(${args.joinToString(",")})" },
            cancelIncomingCall = { cancelledTrays += it },
            activeCallId = { "c1" },
            activeCallSession = { session },
            pendingOfferSdp = { pendingOfferSdp },
            clearPendingOfferSdp = { pendingOfferSdp = null },
            resetForNewIncomingCall = { _, _, _, _, _ ->
                isGroupReset to if (isGroupReset) listOf(GroupCallParticipantUi("u2")) else emptyList()
            },
            markIncomingSessionOwner = { markOwnerCalls++ },
            signalingSender = sender(scope),
            groupMesh = mesh,
            createWebRtc = { createFails?.let { throw it }; webRtc },
            configureWebRtc = { s, id -> configured += s to id },
            bindMedia = { bound += it },
            sessionGateIsCurrent = { gate.isCurrent(it) },
            isCurrentCallSession = { s, _ -> gate.isCurrent(s) },
            flushPendingGroupOffers = { s, id -> flushed += s to id },
            callSessionMachine = machine,
            activeDomainSession = { machine.snapshot().epoch },
            ringingTimeout = ringingTimeout(scope),
            foregroundService = foregroundService(),
            observeSignaling = { observeCalls++ },
            endCall = { notifyPeer, errorMessage, logMissed -> ended += Triple(notifyPeer, errorMessage, logMissed) },
        )
    }

    @Test
    fun busyWhenAlreadyInCallSendsBusyAndKeepsState() = runTest {
        val f = Fixture()
        f.state = CallUiState(callState = CallState.CONNECTED, contactId = "u1")
        f.controller(this).prepareIncomingCall("u2", "Bob", null, CallType.AUDIO, "sdp", callId = "c2")
        advanceUntilIdle()
        assertEquals(CallState.CONNECTED, f.state.callState)
        assertEquals("u1", f.state.contactId)
        assertTrue(f.gateway.wsCalls.any { it.second == "busy" })
        assertEquals(0, f.observeCalls)
    }

    @Test
    fun prepareIdleOneToOneRingsAndCancelsTray() = runTest {
        val f = Fixture()
        f.controller(this).prepareIncomingCall("u2", "Bob", null, CallType.AUDIO, "sdp", callId = "c2")
        assertEquals(CallState.RINGING, f.state.callState)
        assertTrue(f.state.isIncoming)
        assertFalse(f.state.isGroupCall)
        assertEquals("Bob", f.state.contactName)
        assertEquals(listOf("c2"), f.cancelledTrays)
        assertEquals(1, f.markOwnerCalls)
        assertEquals(1, f.observeCalls)
    }

    @Test
    fun prepareGroupRingsWithGroupNameAndLoadsProfiles() = runTest {
        val f = Fixture()
        f.isGroupReset = true
        f.controller(this).prepareIncomingCall("g1", "Group", null, CallType.GROUP, "sdp", callId = "c2", groupId = "g1", groupMemberIds = listOf("u2"))
        assertTrue(f.state.isGroupCall)
        assertEquals("GROUP_NAME(1)", f.state.contactName)
        assertEquals(listOf(GroupCallParticipantUi("u2")), f.state.groupParticipants)
        assertEquals(1, f.mesh.profilesLoaded)
    }

    @Test
    fun answerWithBlankOfferWritesIncompleteError() = runTest {
        val f = Fixture()
        f.state = CallUiState(callState = CallState.RINGING, isIncoming = true)
        f.pendingOfferSdp = null
        f.controller(this).answerCall()
        assertEquals("INCOMING_INCOMPLETE()", f.state.errorMessage)
        assertFalse(f.webRtc.initialized)
    }

    @Test
    fun answerWhenNotRingingDoesNothing() = runTest {
        val f = Fixture()
        f.controller(this).answerCall()
        advanceUntilIdle()
        assertFalse(f.webRtc.initialized)
        assertTrue(f.webRtc.answers.isEmpty())
        assertTrue(f.ended.isEmpty())
    }

    @Test
    fun answerHappyPathInitializesAndSendsAnswer() = runTest {
        val f = Fixture()
        f.state = CallUiState(callState = CallState.RINGING, isIncoming = true, contactId = "u2")
        f.controller(this).answerCall()
        advanceUntilIdle()
        assertTrue(f.webRtc.initialized)
        assertEquals(1, f.bound.size)
        assertTrue(f.bound.single() === f.webRtc)
        assertEquals(1, f.configured.size)
        assertEquals(1, f.webRtc.answers.size)
        f.webRtc.lastAnswerCallback?.invoke("answer-sdp")
        advanceUntilIdle()
        assertEquals("answer", f.gateway.wsCalls.single { it.second == "answer" }.second)
        assertEquals("answer-sdp", f.gateway.wsCalls.single { it.second == "answer" }.third)
        assertFalse(f.state.isInitializing)
        assertEquals(null, f.pendingOfferSdp)
    }

    @Test
    fun answerGroupPathFlushesAndStartsMeshEdges() = runTest {
        val f = Fixture()
        f.state = CallUiState(callState = CallState.RINGING, isIncoming = true, isGroupCall = true, contactId = "u2")
        f.controller(this).answerCall()
        advanceUntilIdle()
        assertEquals(1, f.webRtc.groupOffers.size)
        assertEquals(1, f.flushed.size)
        assertEquals(listOf("u2"), f.mesh.scheduledTimeouts)
        assertEquals(1, f.mesh.meshEdges.size)
        assertEquals("u2", f.mesh.meshEdges.single().first)
    }

    @Test
    fun answerWhenCreateFailsEndsCallWithDownloadError() = runTest {
        val f = Fixture()
        f.state = CallUiState(callState = CallState.RINGING, isIncoming = true, contactId = "u2")
        f.createFails = WebRtcNativeLoadException("dl failed")
        f.controller(this).answerCall()
        advanceUntilIdle()
        assertEquals(1, f.ended.size)
        assertTrue(f.ended.single().second?.startsWith("WEBRTC_DOWNLOAD_FAILED(") == true)
        assertTrue(f.ended.single().third)
    }

    @Test
    fun rejectSendsRejectCancelsTrayAndEndsWithoutMissed() = runTest {
        val f = Fixture()
        f.state = CallUiState(callState = CallState.RINGING, isIncoming = true, contactId = "u2")
        f.controller(this).rejectIncomingCall()
        advanceUntilIdle()
        assertTrue(f.gateway.wsCalls.any { it.second == "reject" })
        assertEquals(listOf("c1"), f.cancelledTrays)
        assertEquals(1, f.ended.size)
        assertEquals(Triple(false, null, false), f.ended.single())
    }
}
