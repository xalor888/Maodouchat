package com.maodouchat.domain.calls

import com.maodouchat.core.model.CallSessionGate
import com.maodouchat.core.model.CallState
import com.maodouchat.core.model.CallType
import com.maodouchat.core.model.CallUiState
import com.maodouchat.core.model.GroupPeerConnectionState
import com.maodouchat.core.model.State
import com.maodouchat.core.model.WebRtcNativeLoadException
import com.maodouchat.core.session.CallSignalingOutboundCursor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CallOutgoingCallControllerTest {

    private class FakeWebRtc : CallWebRtcSession {
        var initialized = false
        var released = false
        val startedCalls = mutableListOf<CallType>()
        val groupStarts = mutableListOf<String>()
        var lastIceCallback: ((String?, Int, String) -> Unit)? = null
        var lastOfferCallback: ((String) -> Unit)? = null

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
        ) = Unit

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
        ) = Unit

        override fun startCall(
            type: CallType,
            onIceCandidate: (sdpMid: String?, sdpMLineIndex: Int, sdp: String) -> Unit,
            onOfferCreated: (description: String) -> Unit,
        ) {
            startedCalls += type
            lastIceCallback = onIceCandidate
            lastOfferCallback = onOfferCreated
        }

        override fun startGroupCallToPeer(
            peerUserId: String,
            type: CallType,
            onIceCandidate: (sdpMid: String?, sdpMLineIndex: Int, sdp: String) -> Unit,
            onOfferCreated: (description: String) -> Unit,
        ) {
            groupStarts += peerUserId
            lastIceCallback = onIceCandidate
            lastOfferCallback = onOfferCreated
        }
    }

    private class FakeMesh : CallSignalingGroupMesh {
        val scheduledTimeouts = mutableListOf<String>()
        var profilesLoaded = 0
        val meshEdges = mutableListOf<Pair<String, Long>>()

        override fun cancelInviteTimeout(userId: String) = Unit
        override fun scheduleGroupPeerTimeout(userId: String) {
            scheduledTimeouts += userId
        }

        override fun markGroupPeerTerminal(userId: String, state: GroupPeerConnectionState) = Unit
        override fun loadGroupParticipantProfiles() {
            profilesLoaded++
        }

        override fun startDeterministicMeshEdges(webRtc: CallWebRtcSession, primaryPeerId: String, session: Long) {
            meshEdges += primaryPeerId to session
        }
    }

    private class FakeGateway : CallSignalingGateway {
        val wsCalls = mutableListOf<Triple<String, String, String>>()

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
        ): Result<Unit> = Result.success(Unit)
    }

    private class Fixture {
        val gate = CallSessionGate()
        val session = gate.begin()
        val webRtc = FakeWebRtc()
        val mesh = FakeMesh()
        val gateway = FakeGateway()
        var token = "tok"
        var callsEnabled = true
        var videoEnabled = true
        var state = CallUiState()
        var groupMemberIds: Set<String> = emptySet()
        var createFails: Throwable? = null
        val writtenLogs = mutableListOf<State>()
        var foregroundStarts = 0
        var observeCalls = 0
        val ended = mutableListOf<Triple<Boolean, String?, Boolean>>()
        val configured = mutableListOf<Pair<CallWebRtcSession, Long>>()
        val bound = mutableListOf<CallWebRtcSession>()
        val flushed = mutableListOf<Pair<CallWebRtcSession, Long>>()

        fun sender(scope: kotlinx.coroutines.CoroutineScope) = CallSignalingSender(
            scope = scope,
            token = { token },
            activeCallId = { "c1" },
            activeGroupId = { "" },
            meshGroupMemberIds = { groupMemberIds.toList() },
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
            recordMissedCall = { _, _, _, _, _ -> },
            logWarn = { _, _ -> },
            onNoAnswer = {},
        )

        fun foregroundService() = CallForegroundServiceController(
            startCallForeground = { _, _, _ -> foregroundStarts++ },
            stopCallForeground = {},
            currentState = { state },
            activeCallId = { "c1" },
            unknownCallerLabel = "unknown",
        )

        fun controller(scope: kotlinx.coroutines.CoroutineScope) = CallOutgoingCallController(
            scope = scope,
            currentState = { state },
            updateState = { transform -> state = transform(state) },
            token = { token },
            text = { message, args -> "${message.name}(${args.joinToString(",")})" },
            callsEnabled = { callsEnabled },
            callTypeEnabled = { type -> if (type == CallType.VIDEO) videoEnabled else true },
            resetForNewOutgoingCall = { session },
            resetForNewGroupCall = { _, _, _ -> session },
            groupMemberIds = { groupMemberIds },
            setGroupMemberIds = { groupMemberIds = it },
            currentOwnerUserId = { "me" },
            writeCallLog = { writtenLogs += it },
            createWebRtc = { createFails?.let { throw it }; webRtc },
            configureWebRtc = { s, id -> configured += s to id },
            bindMedia = { bound += it },
            groupMesh = mesh,
            sessionGateIsCurrent = { gate.isCurrent(it) },
            isCurrentCallSession = { s, _ -> gate.isCurrent(s) },
            flushPendingGroupOffers = { s, id -> flushed += s to id },
            signalingSender = sender(scope),
            ringingTimeout = ringingTimeout(scope),
            foregroundService = foregroundService(),
            observeSignaling = { observeCalls++ },
            endCall = { notifyPeer, errorMessage, logMissed -> ended += Triple(notifyPeer, errorMessage, logMissed) },
        )
    }

    @Test
    fun startCallWhenNotIdleDoesNothing() = runTest {
        val f = Fixture()
        f.state = CallUiState(callState = CallState.CALLING, contactId = "u1")
        f.controller(this).startCall("u2", "Bob", null, CallType.AUDIO)
        advanceUntilIdle()
        assertFalse(f.webRtc.initialized)
        assertTrue(f.writtenLogs.isEmpty())
    }

    @Test
    fun startCallWhenCallsDisabledWritesDisabledState() = runTest {
        val f = Fixture()
        f.callsEnabled = false
        f.controller(this).startCall("u2", "Bob", null, CallType.AUDIO)
        assertEquals(CallState.DISCONNECTED, f.state.callState)
        assertEquals("CALLS_DISABLED()", f.state.errorMessage)
        assertFalse(f.webRtc.initialized)
    }

    @Test
    fun startCallVideoWhenVideoDisabledWritesVideoDisabled() = runTest {
        val f = Fixture()
        f.videoEnabled = false
        f.controller(this).startCall("u2", "Bob", null, CallType.VIDEO)
        assertEquals(CallState.DISCONNECTED, f.state.callState)
        assertEquals("VIDEO_CALL_DISABLED()", f.state.errorMessage)
    }

    @Test
    fun startCallBlankTokenWritesSessionExpired() = runTest {
        val f = Fixture()
        f.token = ""
        f.controller(this).startCall("u2", "Bob", null, CallType.AUDIO)
        assertEquals(CallState.DISCONNECTED, f.state.callState)
        assertEquals("SESSION_EXPIRED()", f.state.errorMessage)
        assertFalse(f.webRtc.initialized)
    }

    @Test
    fun startCallHappyPathStartsCallAndSendsOffer() = runTest {
        val f = Fixture()
        f.controller(this).startCall("u2", "Bob", null, CallType.AUDIO)
        assertEquals(CallState.CALLING, f.state.callState)
        assertEquals(listOf(State.MISSED), f.writtenLogs)
        assertEquals(1, f.foregroundStarts)
        advanceUntilIdle()
        assertTrue(f.webRtc.initialized)
        assertTrue(f.bound.single() === f.webRtc)
        assertEquals(1, f.configured.size)
        assertEquals(listOf(CallType.AUDIO), f.webRtc.startedCalls)
        assertEquals(1, f.flushed.size)
        f.webRtc.lastOfferCallback?.invoke("offer-sdp")
        advanceUntilIdle()
        assertFalse(f.state.isInitializing)
        assertEquals("offer", f.gateway.wsCalls.single { it.second == "offer" }.second)
        assertEquals("offer-sdp", f.gateway.wsCalls.single { it.second == "offer" }.third)
        assertEquals(1, f.observeCalls)
    }

    @Test
    fun startCallStaleSessionReleasesWithoutStarting() = runTest {
        val f = Fixture()
        f.controller(this).startCall("u2", "Bob", null, CallType.AUDIO)
        f.gate.invalidate(f.session)
        advanceUntilIdle()
        assertTrue(f.webRtc.released)
        assertTrue(f.webRtc.startedCalls.isEmpty())
    }

    @Test
    fun startCallCreateFailsEndsWithWebRtcDownloadFailed() = runTest {
        val f = Fixture()
        f.createFails = WebRtcNativeLoadException("dl failed")
        f.controller(this).startCall("u2", "Bob", null, CallType.AUDIO)
        advanceUntilIdle()
        assertEquals(1, f.ended.size)
        assertEquals("WEBRTC_DOWNLOAD_FAILED(dl failed)", f.ended.single().second)
    }

    @Test
    fun startCallCreateFailsWithGenericErrorEndsWithInitializationFailed() = runTest {
        val f = Fixture()
        f.createFails = IllegalStateException("boom")
        f.controller(this).startCall("u2", "Bob", null, CallType.AUDIO)
        advanceUntilIdle()
        assertEquals(1, f.ended.size)
        assertEquals("INITIALIZATION_FAILED(boom)", f.ended.single().second)
    }

    @Test
    fun startGroupCallEmptyMembersDoesNothing() = runTest {
        val f = Fixture()
        f.controller(this).startGroupCall("g1", emptyList(), CallType.GROUP)
        advanceUntilIdle()
        assertFalse(f.webRtc.initialized)
        assertTrue(f.webRtc.groupStarts.isEmpty())
    }

    @Test
    fun startGroupCallBlankTokenWritesSessionExpired() = runTest {
        val f = Fixture()
        f.token = ""
        f.controller(this).startGroupCall("g1", listOf("m1"), CallType.GROUP)
        assertEquals(CallState.DISCONNECTED, f.state.callState)
        assertEquals("SESSION_EXPIRED()", f.state.errorMessage)
    }

    @Test
    fun startGroupCallTooManyMembersWritesMeshLimit() = runTest {
        val f = Fixture()
        f.controller(this).startGroupCall("g1", listOf("m1", "m2", "m3", "m4", "m5", "m6"), CallType.GROUP)
        assertEquals(CallState.DISCONNECTED, f.state.callState)
        assertEquals("GROUP_MESH_LIMIT(6)", f.state.errorMessage)
        assertFalse(f.webRtc.initialized)
    }

    @Test
    fun startGroupCallHappyPathStartsPeerCalls() = runTest {
        val f = Fixture()
        f.controller(this).startGroupCall("g1", listOf("m1", "m2"), CallType.GROUP)
        assertEquals(CallState.CALLING, f.state.callState)
        assertTrue(f.state.isGroupCall)
        assertEquals(setOf("m1", "m2"), f.groupMemberIds)
        assertEquals(1, f.mesh.profilesLoaded)
        assertEquals("GROUP_NAME(2)", f.state.contactName)
        advanceUntilIdle()
        assertTrue(f.webRtc.initialized)
        assertEquals(listOf("m1", "m2"), f.webRtc.groupStarts)
        assertEquals(listOf("m1", "m2"), f.mesh.scheduledTimeouts)
        f.webRtc.lastOfferCallback?.invoke("offer-sdp")
        advanceUntilIdle()
        assertTrue(f.gateway.wsCalls.any { it.first == "m2" && it.second == "offer" })
    }
}
