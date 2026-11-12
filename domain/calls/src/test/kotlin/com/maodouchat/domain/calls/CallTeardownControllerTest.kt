package com.maodouchat.domain.calls

import com.maodouchat.core.model.CallSessionGate
import com.maodouchat.core.model.CallState
import com.maodouchat.core.model.CallUiState
import com.maodouchat.core.model.GroupCallParticipantUi
import com.maodouchat.core.model.State
import com.maodouchat.core.session.CallSessionMachine
import com.maodouchat.core.session.CallSignalingOutboundCursor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class CallTeardownControllerTest {

    private class RecordingGateway : CallHangUpGateway {
        val hangUps = mutableListOf<String>()
        override suspend fun hangUp(
            token: String,
            toUserId: String,
            callId: String,
            groupId: String,
            groupMemberIds: List<String>,
            epoch: Long,
            sequence: Long,
            idempotencyKey: String,
        ): Result<Unit> {
            hangUps += toUserId
            return Result.success(Unit)
        }

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
        ): Boolean = true
    }

    private class RecordingSession(
        private val owner: String = "owner-1",
        private val tokenValue: String = "token-1",
    ) : CallHangUpSession {
        override fun ownerUserId(): String = owner
        override fun token(): String = tokenValue
        override fun mayContinue(expectedUserId: String): Boolean = expectedUserId == owner
    }

    // scope 用 runTest 自身的 TestScope：launch 进 backgroundScope 时
    // advanceUntilIdle 泵不到，挂断信令协程不跑（CallHangUpSenderTest 同款）。
    private class Fixture(private val scope: CoroutineScope) {
        var state = CallUiState()
        var token = "token-1"
        var ending = false
        var callId = "call-1"
        var groupId = "group-1"
        var meshMembers = listOf("m1", "m2")
        var groupMembers = setOf("m1", "m2")
        var callSession = 0L
        var domainSession = 0L
        var clearedOfferSdp = 0
        var clearedIdempotency = 0
        var clearedInbound = 0
        var clearedOutbound = 0
        val cancelledIncoming = mutableListOf<String>()
        val finishedTelecom = mutableListOf<String>()
        var releasedMedia = 0
        var cancelledGroupJobs = 0
        var stoppedIngress = 0
        var clearedGroupOffers = 0
        var foregroundStops = 0
        val writtenLogs = mutableListOf<State>()
        val gateway = RecordingGateway()
        val session = RecordingSession()
        val gate = CallSessionGate()
        val machine = CallSessionMachine()

        init {
            machine.beginOutgoing("c1")
            domainSession = machine.snapshot().epoch
            callSession = domainSession
            gate.begin()
        }

        fun controller() = CallTeardownController(
            currentState = { state },
            updateState = { transform -> state = transform(state) },
            token = { token },
            endingCall = { ending },
            setEndingCall = { ending = it },
            activeCallId = { callId },
            setActiveCallId = { callId = it },
            activeGroupId = { groupId },
            setActiveGroupId = { groupId = it },
            meshGroupMemberIds = { meshMembers },
            setMeshGroupMemberIds = { meshMembers = it },
            groupMemberIds = { groupMembers },
            setGroupMemberIds = { groupMembers = it },
            activeCallSession = { callSession },
            setActiveCallSession = { callSession = it },
            activeDomainSession = { domainSession },
            setActiveDomainSession = { domainSession = it },
            clearPendingOfferSdp = { clearedOfferSdp++ },
            clearSignalingIdempotency = { clearedIdempotency++ },
            clearInboundSignalingCursors = { clearedInbound++ },
            clearOutboundSignalingCursor = { clearedOutbound++ },
            callSessionGate = gate,
            callSessionMachine = machine,
            timersController = CallSessionTimersController(
                scope = scope,
                currentState = { state },
                updateState = { transform -> state = transform(state) },
                getConnectionStats = { null },
                refreshIceServers = {},
                fetchIceServers = { Result.success(emptyList()) },
                elapsedRealtimeMs = { 0L },
                activeCallSession = { callSession },
                callSessionGate = gate,
                token = { token },
                onLogAnswered = {},
            ),
            stopSignalingIngress = { stoppedIngress++ },
            clearPendingGroupOffers = { clearedGroupOffers++ },
            ringingTimeout = CallRingingTimeout(
                scope = scope,
                sessionGate = gate,
                activeCallSession = { callSession },
                currentState = { state },
                activeCallId = { callId },
                timeoutMs = 30_000L,
                text = { "" },
                recordMissedCall = { _, _, _, _, _ -> },
                logWarn = { _, _ -> },
                onNoAnswer = {},
            ),
            iceRecovery = CallIceRecovery(
                scope = scope,
                endingCall = { ending },
                callSessionMachine = machine,
                activeDomainSession = { domainSession },
                startTimers = {},
                activeCallSession = { callSession },
                callSessionGate = gate,
                updateState = { transform -> state = transform(state) },
                currentState = { state },
                onIceGiveUp = {},
                text = { _, _ -> "" },
                restartIce = {},
            ),
            cancelGroupJobs = { cancelledGroupJobs++ },
            releaseMedia = { releasedMedia++ },
            hangUpSender = CallHangUpSender(
                outboundSignalingCursor = CallSignalingOutboundCursor(),
                gateway = gateway,
                session = session,
                scope = scope,
            ),
            foregroundService = CallForegroundServiceController(
                startCallForeground = { _, _, _ -> },
                stopCallForeground = { foregroundStops++ },
                currentState = { state },
                activeCallId = { callId },
                unknownCallerLabel = "Unknown",
            ),
            cancelIncomingCall = { cancelledIncoming += it },
            finishTelecomConnection = { finishedTelecom += it },
            writeCallLog = { writtenLogs += it },
        )
    }

    @Test
    fun endCallResetsStateClearsSessionsAndNotifiesPeerWhenConnected() = runTest {
        val f = Fixture(this)
        f.state = CallUiState(
            contactId = "c1",
            contactName = "C1",
            callState = CallState.CONNECTED,
            groupParticipants = listOf(GroupCallParticipantUi("m1")),
            duration = "12:34",
            errorMessage = "old",
        )
        f.controller().endCall(notifyPeer = true, errorMessage = "boom")
        advanceUntilIdle()
        assertEquals(CallState.DISCONNECTED, f.state.callState)
        assertEquals("00:00", f.state.duration)
        assertTrue(f.state.groupParticipants.isEmpty())
        assertEquals("boom", f.state.errorMessage)
        assertTrue(f.ending)
        assertEquals("", f.callId)
        assertEquals("", f.groupId)
        assertTrue(f.meshMembers.isEmpty())
        assertTrue(f.groupMembers.isEmpty())
        assertEquals(0L, f.callSession)
        assertEquals(0L, f.domainSession)
        assertFalse(f.machine.isCurrent(1L))
        assertFalse(f.gate.isCurrent(1L))
        assertEquals(listOf("call-1"), f.cancelledIncoming)
        assertEquals(listOf("call-1"), f.finishedTelecom)
        assertEquals(1, f.releasedMedia)
        assertEquals(1, f.cancelledGroupJobs)
        assertEquals(1, f.stoppedIngress)
        assertEquals(1, f.clearedGroupOffers)
        assertEquals(1, f.clearedOfferSdp)
        assertEquals(1, f.clearedIdempotency)
        assertEquals(1, f.clearedInbound)
        assertEquals(1, f.clearedOutbound)
        assertEquals(1, f.foregroundStops)
        assertEquals(listOf(State.ANSWERED), f.writtenLogs)
        assertEquals(listOf("c1"), f.gateway.hangUps)
    }

    @Test
    fun endCallLogsMissedWhenNotConnected() = runTest {
        val f = Fixture(this)
        f.state = CallUiState(contactId = "c1", callState = CallState.RINGING)
        f.controller().endCall(notifyPeer = true)
        advanceUntilIdle()
        assertEquals(listOf(State.MISSED), f.writtenLogs)
        assertEquals(listOf("c1"), f.gateway.hangUps)
    }

    @Test
    fun endCallWithLogMissedFalseSkipsLogWhenNotConnected() = runTest {
        val f = Fixture(this)
        f.state = CallUiState(contactId = "c1", callState = CallState.RINGING)
        f.controller().endCall(notifyPeer = true, logMissed = false)
        advanceUntilIdle()
        assertTrue(f.writtenLogs.isEmpty())
    }

    @Test
    fun endCallWithLogMissedFalseStillLogsAnsweredWhenConnected() = runTest {
        val f = Fixture(this)
        f.state = CallUiState(contactId = "c1", callState = CallState.CONNECTED)
        f.controller().endCall(notifyPeer = true, logMissed = false)
        advanceUntilIdle()
        assertEquals(listOf(State.ANSWERED), f.writtenLogs)
    }

    @Test
    fun secondEndCallIsNoOp() = runTest {
        val f = Fixture(this)
        f.state = CallUiState(contactId = "c1", callState = CallState.CONNECTED)
        val controller = f.controller()
        controller.endCall(notifyPeer = true)
        advanceUntilIdle()
        controller.endCall(notifyPeer = true)
        advanceUntilIdle()
        assertEquals(1, f.cancelledIncoming.size)
        assertEquals(1, f.writtenLogs.size)
        assertEquals(1, f.foregroundStops)
        assertEquals(1, f.gateway.hangUps.size)
    }

    @Test
    fun endCallWithBlankCallIdSkipsNotificationAndTelecomCleanup() = runTest {
        val f = Fixture(this)
        f.callId = ""
        f.state = CallUiState(contactId = "c1", callState = CallState.RINGING)
        f.controller().endCall(notifyPeer = true)
        advanceUntilIdle()
        assertTrue(f.cancelledIncoming.isEmpty())
        assertTrue(f.finishedTelecom.isEmpty())
        assertEquals(CallState.DISCONNECTED, f.state.callState)
        assertEquals(listOf(State.MISSED), f.writtenLogs)
    }

    @Test
    fun endCallWithoutNotifyPeerSendsNoHangUp() = runTest {
        val f = Fixture(this)
        f.state = CallUiState(contactId = "c1", callState = CallState.CONNECTED)
        f.controller().endCall(notifyPeer = false)
        advanceUntilIdle()
        assertTrue(f.gateway.hangUps.isEmpty())
        assertEquals(listOf(State.ANSWERED), f.writtenLogs)
    }

    @Test
    fun endCallWithBlankTokenSendsNoHangUp() = runTest {
        val f = Fixture(this)
        f.token = ""
        f.state = CallUiState(contactId = "c1", callState = CallState.CONNECTED)
        f.controller().endCall(notifyPeer = true)
        advanceUntilIdle()
        assertTrue(f.gateway.hangUps.isEmpty())
    }

    @Test
    fun endCallKeepsExistingErrorMessageWhenNullPassed() = runTest {
        val f = Fixture(this)
        f.state = CallUiState(contactId = "c1", callState = CallState.RINGING, errorMessage = "old")
        f.controller().endCall(notifyPeer = true, errorMessage = null)
        advanceUntilIdle()
        assertEquals("old", f.state.errorMessage)
    }

    @Test
    fun endCallSendsHangUpToGroupMembersForGroupCalls() = runTest {
        val f = Fixture(this)
        f.state = CallUiState(contactId = "chat-1", callState = CallState.CONNECTED, isGroupCall = true)
        f.controller().endCall(notifyPeer = true)
        advanceUntilIdle()
        assertEquals(setOf("m1", "m2"), f.gateway.hangUps.toSet())
    }
}
