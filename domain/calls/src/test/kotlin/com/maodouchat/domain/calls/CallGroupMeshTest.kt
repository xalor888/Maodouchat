package com.maodouchat.domain.calls

import com.maodouchat.core.model.CallSessionGate
import com.maodouchat.core.model.CallState
import com.maodouchat.core.model.CallType
import com.maodouchat.core.model.CallUiState
import com.maodouchat.core.model.GroupCallParticipantUi
import com.maodouchat.core.model.GroupPeerConnectionState
import com.maodouchat.core.session.CallSessionMachine
import com.maodouchat.core.session.CallSignalingOutboundCursor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CallGroupMeshTest {

    private class FakeWebRtc : CallWebRtcSession {
        data class GroupCall(
            val peerUserId: String,
            val type: CallType,
            val onIceCandidate: (String?, Int, String) -> Unit,
            val onOfferCreated: (String) -> Unit,
        )

        val existingPeers = mutableSetOf<String>()
        val removedPeers = mutableListOf<String>()
        val groupCalls = mutableListOf<GroupCall>()

        override fun handleAnswer(payload: String) = Unit
        override fun handleGroupAnswer(fromUserId: String, payload: String) = Unit
        override fun addIceCandidate(sdpMid: String?, sdpMLineIndex: Int, sdp: String) = Unit
        override fun addGroupIceCandidate(fromUserId: String, sdpMid: String?, sdpMLineIndex: Int, sdp: String) = Unit
        override fun removeGroupPeer(fromUserId: String) {
            removedPeers += fromUserId
        }

        override fun hasGroupPeer(fromUserId: String): Boolean = fromUserId in existingPeers

        override fun acceptGroupOffer(
            peerUserId: String,
            remoteOfferSdp: String,
            type: CallType,
            onIceCandidate: (sdpMid: String?, sdpMLineIndex: Int, sdp: String) -> Unit,
            onAnswerCreated: (description: String) -> Unit,
        ) = Unit

        override fun initialize() = Unit
        override fun release() = Unit

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
        ) = Unit

        override fun startGroupCallToPeer(
            peerUserId: String,
            type: CallType,
            onIceCandidate: (sdpMid: String?, sdpMLineIndex: Int, sdp: String) -> Unit,
            onOfferCreated: (description: String) -> Unit,
        ) {
            groupCalls += GroupCall(peerUserId, type, onIceCandidate, onOfferCreated)
        }
    }

    private class FakeGateway : CallSignalingGateway {
        data class WsCall(val toUserId: String, val type: String, val payload: String)

        val wsCalls = mutableListOf<WsCall>()

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
            wsCalls += WsCall(toUserId, type, payload)
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
        val machine = CallSessionMachine()
        var state = CallUiState(isGroupCall = true, callState = CallState.CONNECTED)
        var token = "tok-1"
        var selfUserId = "alice"
        var mayContinueResult = true
        var profilesResult: Result<List<CallGroupMeshProfile>> = Result.success(emptyList())
        val loadedTokens = mutableListOf<String>()
        var domainSession = 0L
        var memberIds: Set<String> = emptySet()
        val removedMembers = mutableListOf<String>()
        var ringingCancellations = 0
        var ringingTimeoutMs = 100L
        var answeredLogs = 0
        val fakeWebRtc = FakeWebRtc()
        val gateway = FakeGateway()
        val noActivePeersMessages = mutableListOf<String>()

        fun timers(scope: CoroutineScope) = CallSessionTimersController(
            scope = scope,
            currentState = { state },
            updateState = { transform -> state = transform(state) },
            getConnectionStats = { null },
            refreshIceServers = {},
            fetchIceServers = { Result.success(emptyList()) },
            elapsedRealtimeMs = { 0L },
            activeCallSession = { session },
            callSessionGate = gate,
            token = { token },
            onLogAnswered = { answeredLogs++ },
        )

        fun sender(scope: CoroutineScope) = CallSignalingSender(
            scope = scope,
            token = { token },
            activeCallId = { "call-1" },
            activeGroupId = { "group-1" },
            meshGroupMemberIds = { emptyList() },
            activeCallSession = { session },
            callSessionGate = gate,
            outboundSignalingCursor = CallSignalingOutboundCursor(),
            gateway = gateway,
            updateState = { transform -> state = transform(state) },
            text = { _, _ -> "" },
            failureReason = { it.message ?: "" },
            onEndCall = {},
        )

        fun mesh(scope: CoroutineScope) = CallGroupMesh(
            scope = scope,
            token = { token },
            ownerUserId = { selfUserId },
            mayContinue = { mayContinueResult },
            loadProfiles = { liveToken ->
                loadedTokens += liveToken
                profilesResult
            },
            endingCall = { false },
            activeCallSession = { session },
            activeDomainSession = { domainSession },
            groupMemberIds = { memberIds },
            removeGroupMemberId = { removedMembers += it },
            cancelRingingTimeout = { ringingCancellations++ },
            ringingTimeoutMs = ringingTimeoutMs,
            updateState = { transform -> state = transform(state) },
            currentState = { state },
            callSessionMachine = machine,
            callSessionGate = gate,
            timersController = timers(scope),
            webRtc = { fakeWebRtc },
            isCurrentCallSession = { s, _ -> gate.isCurrent(s) },
            signalingSender = sender(scope),
            noActivePeersMessage = { "no active peers" },
            onNoActivePeers = { noActivePeersMessages += it },
        )
    }

    private fun participant(userId: String, state: GroupPeerConnectionState = GroupPeerConnectionState.CONNECTING) =
        GroupCallParticipantUi(userId = userId, connectionState = state)

    @Test
    fun markGroupPeerTerminalUpdatesStateAndEndsCallWhenLastPeerLeaves() = runTest {
        val f = Fixture()
        f.state = f.state.copy(groupParticipants = listOf(participant("bob")))
        val mesh = f.mesh(this)
        mesh.markGroupPeerTerminal("bob", GroupPeerConnectionState.FAILED)
        assertEquals(GroupPeerConnectionState.FAILED, f.state.groupParticipants.single().connectionState)
        assertFalse(f.state.groupParticipants.single().videoAvailable)
        assertEquals(listOf("bob"), f.removedMembers)
        assertEquals(listOf("no active peers"), f.noActivePeersMessages)
    }

    // CONNECTED 分支会启动时长计时 / 网络轮询 / ICE 热刷新三个永不结束的循环：
    // 须用 backgroundScope，runTest 结束时自动取消；若传 runTest 自身的 TestScope，
    // 收尾会报 UncompletedCoroutinesError（与 CallLifecycleControllerTest 同一惯例）。
    @Test
    fun connectedPeerCancelsJobsAndStartsTimers() = runTest {
        val f = Fixture()
        f.domainSession = f.machine.beginOutgoing("bob").epoch
        f.state = f.state.copy(groupParticipants = listOf(participant("bob")), isInitializing = true)
        val mesh = f.mesh(backgroundScope)
        mesh.scheduleGroupPeerTimeout("bob")
        mesh.onGroupPeerStateChanged("bob", GroupPeerConnectionState.CONNECTED)
        runCurrent()
        assertEquals(GroupPeerConnectionState.CONNECTED, f.state.groupParticipants.single().connectionState)
        assertEquals(CallState.CONNECTED, f.state.callState)
        assertFalse(f.state.isInitializing)
        assertEquals(1, f.ringingCancellations)
        assertEquals(1, f.answeredLogs)
        // 邀请超时已被取消：推进时间也不应再标记 NO_ANSWER。
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(GroupPeerConnectionState.CONNECTED, f.state.groupParticipants.single().connectionState)
        assertTrue(f.fakeWebRtc.removedPeers.isEmpty())
    }

    @Test
    fun reconnectingPeerRemovedAfterGraceWhenStillReconnecting() = runTest {
        val f = Fixture()
        f.state = f.state.copy(groupParticipants = listOf(participant("bob", GroupPeerConnectionState.RECONNECTING)))
        val mesh = f.mesh(this)
        mesh.onGroupPeerStateChanged("bob", GroupPeerConnectionState.RECONNECTING)
        runCurrent()
        assertTrue(f.fakeWebRtc.removedPeers.isEmpty())
        advanceTimeBy(10_000L)
        runCurrent()
        assertEquals(listOf("bob"), f.fakeWebRtc.removedPeers)
        assertEquals(GroupPeerConnectionState.FAILED, f.state.groupParticipants.single().connectionState)
        assertEquals(listOf("bob"), f.removedMembers)
    }

    @Test
    fun inviteTimeoutMarksNoAnswerWhenStillConnecting() = runTest {
        val f = Fixture()
        f.state = f.state.copy(groupParticipants = listOf(participant("bob")))
        val mesh = f.mesh(this)
        mesh.scheduleGroupPeerTimeout("bob")
        advanceTimeBy(99L)
        runCurrent()
        assertTrue(f.fakeWebRtc.removedPeers.isEmpty())
        advanceTimeBy(1L)
        runCurrent()
        assertEquals(listOf("bob"), f.fakeWebRtc.removedPeers)
        assertEquals(GroupPeerConnectionState.NO_ANSWER, f.state.groupParticipants.single().connectionState)
        assertEquals(listOf("bob"), f.removedMembers)
    }

    @Test
    fun deterministicMeshEdgesOnlyForEligibleNewPeers() = runTest {
        val f = Fixture()
        f.memberIds = setOf("alice", "bob", "carol", "dave")
        f.fakeWebRtc.existingPeers += "carol"
        val mesh = f.mesh(this)
        // bob 是主叫方（排除）、carol 已有边（跳过）、dave 满足 alice < dave（建边）。
        mesh.startDeterministicMeshEdges(f.fakeWebRtc, "bob", f.session)
        runCurrent()
        assertEquals(listOf("dave"), f.fakeWebRtc.groupCalls.map { it.peerUserId })
        val call = f.fakeWebRtc.groupCalls.single()
        assertEquals(CallType.AUDIO, call.type)
        call.onIceCandidate("mid-1", 0, "cand-1")
        call.onOfferCreated("offer-sdp")
        runCurrent()
        assertEquals("ice-candidate", f.gateway.wsCalls[0].type)
        assertEquals("mid-1|0|cand-1", f.gateway.wsCalls[0].payload)
        assertEquals("dave", f.gateway.wsCalls[0].toUserId)
        assertEquals("offer", f.gateway.wsCalls[1].type)
        assertEquals("offer-sdp", f.gateway.wsCalls[1].payload)
    }

    @Test
    fun loadProfilesUpdatesNamesAndAvatarsOnlyWhenSessionMayContinue() = runTest {
        val f = Fixture()
        f.state = f.state.copy(groupParticipants = listOf(participant("bob"), participant("carol")))
        f.profilesResult = Result.success(
            listOf(
                CallGroupMeshProfile(id = "bob", name = "Bob", avatar = "avatar-bob"),
                CallGroupMeshProfile(id = "carol", name = "Carol", avatar = null),
            )
        )
        val mesh = f.mesh(this)
        mesh.loadGroupParticipantProfiles()
        runCurrent()
        assertEquals(listOf("tok-1"), f.loadedTokens)
        assertEquals("Bob", f.state.groupParticipants[0].name)
        assertEquals("avatar-bob", f.state.groupParticipants[0].avatar)
        assertEquals("Carol", f.state.groupParticipants[1].name)
        // 空 token 不加载；会话不可继续也不加载。
        f.token = ""
        mesh.loadGroupParticipantProfiles()
        runCurrent()
        assertEquals(listOf("tok-1"), f.loadedTokens)
        f.token = "tok-1"
        f.mayContinueResult = false
        mesh.loadGroupParticipantProfiles()
        runCurrent()
        assertEquals(listOf("tok-1"), f.loadedTokens)
    }
}
