package com.maodouchat.domain.calls

import com.maodouchat.core.model.CallType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// UTF-16 码元 hex 编码：串里出现分隔符也不怕。
private fun encStr201c(s: String): String {
    val out = StringBuilder()
    for (ch in s) {
        val cp = ch.code
        if (cp >= 0x10000) {
            val v = cp - 0x10000
            out.append("%04x".format(0xD800 + (v shr 10)))
            out.append("%04x".format(0xDC00 + (v and 0x3FF)))
        } else {
            out.append("%04x".format(cp))
        }
    }
    return out.toString()
}

private fun encIds201c(ids: Collection<String>): String =
    ids.joinToString(";") { encStr201c(it) }

// 状态全量编码：字段按声明顺序以 | 连接，null 记 ~。
private fun encState201c(st: CallUiState): String {
    val parts = listOf(
        encStr201c(st.contactId),
        encStr201c(st.contactName),
        st.contactAvatar?.let { encStr201c(it) } ?: "~",
        st.callType.name,
        st.callState.name,
        if (st.isIncoming) "1" else "0",
        if (st.isGroupCall) "1" else "0",
        encStr201c(st.duration),
        if (st.isInitializing) "1" else "0",
        if (st.networkReconnecting) "1" else "0",
        st.networkStats.name,
        if (st.iceStunOnly) "1" else "0",
        st.availableAudioRoutes.joinToString(",") { it.name },
        st.selectedAudioRoute?.name ?: "~",
        st.groupParticipants.joinToString(";") { p ->
            listOf(
                encStr201c(p.userId),
                encStr201c(p.name),
                p.avatar?.let { encStr201c(it) } ?: "~",
                p.connectionState.name,
                if (p.videoAvailable) "1" else "0",
            ).joinToString(":")
        },
        st.errorMessage?.let { encStr201c(it) } ?: "~",
        st.nativeDownloadProgress.toString(),
    )
    return parts.joinToString("|")
}

// 文案：返回串与效应记同一份，Int 记 i<十进制>，String 记 s<hex>。
private fun txt201c(msg: Enum<*>, args: Array<out Any>): String {
    val sb = StringBuilder("T:")
    sb.append(msg.name)
    for (a in args) {
        when (a) {
            is Int -> sb.append(":i").append(a)
            is String -> sb.append(":s").append(encStr201c(a))
            else -> sb.append(":?").append(encStr201c(a.toString()))
        }
    }
    return sb.toString()
}

// launch 出的协程永不执行：只测同步前缀，异步建联不跑。
private val dropDispatcher201c = object : CoroutineDispatcher() {
    override fun dispatch(context: CoroutineContext, block: Runnable) = Unit
}

private val INIT_STATES201c = listOf(
    CallUiState(),
    CallUiState(
        callState = CallState.RINGING, isIncoming = true, contactId = "peer1",
        contactName = "Alice", callType = CallType.AUDIO,
    ),
    CallUiState(
        callState = CallState.RINGING, isIncoming = true, contactId = "peer1",
        contactName = "Alice", callType = CallType.VIDEO, isInitializing = true,
    ),
    CallUiState(
        callState = CallState.RINGING, isIncoming = false, contactId = "peer1",
        contactName = "Alice",
    ),
    CallUiState(callState = CallState.CALLING, contactId = "peer1", contactName = "Alice"),
    CallUiState(callState = CallState.CONNECTED, contactId = "peer9", duration = "03:12"),
    CallUiState(
        callState = CallState.DISCONNECTED, contactId = "peer1",
        contactName = "Alice", errorMessage = "old",
    ),
    CallUiState(
        callState = CallState.RINGING, isIncoming = true, contactId = "peer2",
        contactName = "Bob", contactAvatar = "av2",
        groupParticipants = listOf(GroupCallParticipantUi("u1")),
    ),
)

// phase：0 IDLE、1 OUTGOING_RINGING、2 INCOMING_RINGING、3 CONNECTING。
private class IncMachine201c {
    var epoch: Long = 0L
    var phase: Int = 0

    fun markConnecting(session: Long) {
        if (epoch == session && (phase == 1 || phase == 2)) phase = 3
    }
}

// op kind: 0 prepareIncomingCall，1 answerCall，2 rejectIncomingCall。
// kind 1 的 null 参数表示“用当前状态值”。
private data class IncOp201c(
    val kind: Int,
    val contactId: String? = null,
    val contactName: String? = null,
    val contactAvatar: String? = null,
    val callType: CallType? = null,
    val offerSdp: String? = null,
    val callId: String = "",
    val groupId: String = "",
    val members: List<String> = emptyList(),
)

private data class IncCase201c(
    val init: Int = 0,
    val token: String = "",
    val owner: String = "self",
    val callId: String = "",
    val domainSession: Long = 0L,
    val gateCurrent: Boolean = true,
    val pendingSdp: String? = null,
    val ops: List<IncOp201c> = emptyList(),
)

// 效应按发生顺序记：S 读状态，U 写状态，K 读 token，T 文案，
// RI 复位呼入，CI 取消来电托盘，MO 标记会话归属，P 拉群画像，
// RA/AD/AS 读会话世代，I 读 callId，PO 读挂起 offer，
// SI/SG2/SM/SS 信令发送方读端口，EC 挂断，OB 监听信令，
// CE 每 op 末的信令游标 epoch（发过信令为 1，否则 0）。
private fun incOracle201c(c: IncCase201c): String {
    val effects = mutableListOf<String>()
    var state = INIT_STATES201c[c.init]
    var cursorSent = false
    val machine = IncMachine201c()
    fun fgStart() {
        effects += "S:" + encState201c(state)
        effects += "I:" + encStr201c(c.callId)
        val name = state.contactName.ifBlank { "Unknown" }
        val isVideo = state.callType == CallType.VIDEO
        effects += "F:" + encStr201c(name) + ":" + (if (isVideo) "1" else "0") +
            ":" + encStr201c(c.callId)
    }
    fun sendWithFallback(contactId: String, err: String, callId: String?, groupId: String?, members: List<String>?) {
        effects += err
        if (callId == null) {
            effects += "SI:" + encStr201c(c.callId)
        }
        if (groupId == null) {
            effects += "SG2:"
        }
        if (members == null) {
            effects += "SM:"
        }
        effects += "SS:" + c.domainSession
        cursorSent = true
    }
    for (op in c.ops) {
        when (op.kind) {
            0 -> {
                effects += "S:" + encState201c(state)
                if (state.callState != CallState.IDLE && state.callState != CallState.DISCONNECTED) {
                    if (state.contactId != (op.contactId ?: "")) {
                        val err = txt201c(CallIncomingCallMessage.NOTIFY_BUSY_FAILED, emptyArray())
                        sendWithFallback(op.contactId ?: "", err, op.callId, op.groupId, op.members)
                    }
                } else {
                    val isGroup = op.groupId.isNotBlank() && op.members.size >= 2
                    val participants = op.members.map { GroupCallParticipantUi(it) }
                    effects += "RI:" + encStr201c(op.contactId ?: "") + ":" +
                        encStr201c(op.offerSdp ?: "") + ":" + encStr201c(op.callId) +
                        ":" + encStr201c(op.groupId) + ":" + encIds201c(op.members) +
                        ":" + (if (isGroup) "1" else "0") + ":" +
                        encIds201c(participants.map { it.userId })
                    if (op.callId.isNotBlank()) {
                        effects += "CI:" + encStr201c(op.callId)
                    }
                    val name = if (isGroup) {
                        val t = txt201c(
                            CallIncomingCallMessage.GROUP_NAME,
                            arrayOf(participants.size),
                        )
                        effects += t
                        t
                    } else {
                        op.contactName ?: ""
                    }
                    state = state.copy(
                        contactId = op.contactId ?: "", contactName = name,
                        contactAvatar = op.contactAvatar,
                        callType = op.callType ?: CallType.AUDIO,
                        callState = CallState.RINGING, isIncoming = true,
                        isGroupCall = isGroup, isInitializing = false,
                        groupParticipants = participants, errorMessage = null,
                    )
                    effects += "U:" + encState201c(state)
                    effects += "MO"
                    if (isGroup) effects += "P"
                    effects += "RA:" + c.domainSession
                    effects += "OB"
                }
            }
            1 -> {
                effects += "S:" + encState201c(state)
                val targetContactId = op.contactId ?: state.contactId
                val targetContactName = op.contactName ?: state.contactName
                val targetCallType = op.callType ?: state.callType
                val targetOfferSdp = op.offerSdp ?: run {
                    effects += "PO:" + (c.pendingSdp?.let { encStr201c(it) } ?: "~")
                    c.pendingSdp
                }
                if (targetContactId.isBlank() || targetOfferSdp.isNullOrBlank()) {
                    val err = txt201c(CallIncomingCallMessage.INCOMING_INCOMPLETE, emptyArray())
                    effects += err
                    state = state.copy(errorMessage = err)
                    effects += "U:" + encState201c(state)
                } else if (state.callState != CallState.RINGING || !state.isIncoming || state.isInitializing) {
                    // 未接听：无任何写效应。
                } else {
                    effects += "K:" + encStr201c(c.token)
                    if (c.token.isBlank()) {
                        val err = txt201c(CallIncomingCallMessage.SESSION_EXPIRED, emptyArray())
                        effects += err
                        effects += "EC:0:" + encStr201c(err) + ":1"
                    } else {
                        effects += "AD:" + c.domainSession
                        machine.markConnecting(c.domainSession)
                        effects += "I:" + encStr201c(c.callId)
                        if (c.callId.isNotBlank()) {
                            effects += "I:" + encStr201c(c.callId)
                            effects += "CI:" + encStr201c(c.callId)
                        }
                        state = state.copy(
                            contactId = targetContactId, contactName = targetContactName,
                            contactAvatar = op.contactAvatar ?: state.contactAvatar,
                            callType = targetCallType, callState = CallState.RINGING,
                            isIncoming = true, isInitializing = true, errorMessage = null,
                        )
                        effects += "U:" + encState201c(state)
                        fgStart()
                        effects += "AS:" + c.domainSession
                    }
                }
            }
            2 -> {
                effects += "S:" + encState201c(state)
                effects += "I:" + encStr201c(c.callId)
                if (state.contactId.isNotBlank()) {
                    val err = txt201c(CallIncomingCallMessage.NOTIFY_REJECT_FAILED, emptyArray())
                    sendWithFallback(state.contactId, err, null, null, null)
                }
                if (c.callId.isNotBlank()) {
                    effects += "CI:" + encStr201c(c.callId)
                }
                effects += "EC:0::0"
            }
            else -> throw IllegalArgumentException("unknown op ${op.kind}")
        }
        effects += "CE:" + if (cursorSent) "1" else "0"
    }
    return effects.joinToString(",")
}

private fun incProd201c(c: IncCase201c): String {
    val effects = mutableListOf<String>()
    var state = INIT_STATES201c[c.init]
    val cursor = CallSignalingOutboundCursor()
    val dropScope = CoroutineScope(SupervisorJob() + dropDispatcher201c)
    val readState: () -> CallUiState = {
        effects += "S:" + encState201c(state)
        state
    }
    val writeState: ((CallUiState) -> CallUiState) -> Unit = { f ->
        state = f(state)
        effects += "U:" + encState201c(state)
    }
    val sender = CallSignalingSender(
        scope = dropScope,
        token = { c.token },
        activeCallId = {
            effects += "SI:" + encStr201c(c.callId)
            c.callId
        },
        activeGroupId = {
            effects += "SG2:"
            ""
        },
        meshGroupMemberIds = {
            effects += "SM:"
            emptyList()
        },
        activeCallSession = {
            effects += "SS:" + c.domainSession
            c.domainSession
        },
        callSessionGate = CallSessionGate(),
        outboundSignalingCursor = cursor,
        gateway = object : CallSignalingGateway {
            override fun sendViaWebSocket(
                toUserId: String, type: String, payload: String, callId: String,
                groupId: String, groupMemberIds: List<String>, groupInvite: Boolean,
                epoch: Long, sequence: Long, idempotencyKey: String,
            ): Boolean = true

            override suspend fun sendViaRest(
                token: String, toUserId: String, type: String, payload: String,
                callId: String, groupId: String, groupMemberIds: List<String>,
                groupInvite: Boolean, epoch: Long, sequence: Long, idempotencyKey: String,
            ): Result<Unit> = Result.success(Unit)
        },
        updateState = writeState,
        text = { msg, args ->
            val s = txt201c(msg, args)
            effects += s
            s
        },
        failureReason = { "FR" },
        onEndCall = { effects += "EC2" },
    )
    val timeout = CallRingingTimeout(
        scope = dropScope,
        sessionGate = CallSessionGate(),
        activeCallSession = {
            effects += "RA:" + c.domainSession
            c.domainSession
        },
        currentState = readState,
        activeCallId = {
            effects += "I:" + encStr201c(c.callId)
            c.callId
        },
        timeoutMs = 30000L,
        text = { "RT:" + it.name },
        recordMissedCall = { _, _, _, _, _ -> },
        logWarn = { _, _ -> },
        onNoAnswer = { effects += "NA:" + encStr201c(it) },
    )
    val foreground = CallForegroundServiceController(
        startCallForeground = { name, isVideo, callId ->
            effects += "F:" + encStr201c(name) + ":" + (if (isVideo) "1" else "0") +
                ":" + encStr201c(callId)
        },
        stopCallForeground = { effects += "FS" },
        currentState = readState,
        activeCallId = {
            effects += "I:" + encStr201c(c.callId)
            c.callId
        },
        unknownCallerLabel = "Unknown",
    )
    val mesh = object : CallSignalingGroupMesh {
        override fun cancelInviteTimeout(userId: String) {
            effects += "CI2:" + encStr201c(userId)
        }

        override fun scheduleGroupPeerTimeout(userId: String) {
            effects += "SP:" + encStr201c(userId)
        }

        override fun markGroupPeerTerminal(userId: String, state: GroupPeerConnectionState) {
            effects += "MT:" + encStr201c(userId) + ":" + state.name
        }

        override fun loadGroupParticipantProfiles() {
            effects += "P"
        }

        override fun startDeterministicMeshEdges(
            webRtc: CallWebRtcSession, primaryPeerId: String, session: Long,
        ) {
            effects += "DE"
        }
    }
    val machine = CallSessionMachine()
    val controller = CallIncomingCallController(
        scope = dropScope,
        currentState = readState,
        updateState = writeState,
        token = {
            effects += "K:" + encStr201c(c.token)
            c.token
        },
        text = { msg, args ->
            val s = txt201c(msg, args)
            effects += s
            s
        },
        cancelIncomingCall = { effects += "CI:" + encStr201c(it) },
        activeCallId = {
            effects += "I:" + encStr201c(c.callId)
            c.callId
        },
        activeCallSession = {
            effects += "AS:" + c.domainSession
            c.domainSession
        },
        pendingOfferSdp = {
            effects += "PO:" + (c.pendingSdp?.let { encStr201c(it) } ?: "~")
            c.pendingSdp
        },
        clearPendingOfferSdp = { effects += "CPO" },
        resetForNewIncomingCall = { contactId, offerSdp, callId, groupId, groupMemberIds ->
            val isGroup = groupId.isNotBlank() && groupMemberIds.size >= 2
            val participants = groupMemberIds.map { GroupCallParticipantUi(it) }
            effects += "RI:" + encStr201c(contactId) + ":" + encStr201c(offerSdp) +
                ":" + encStr201c(callId) + ":" + encStr201c(groupId) + ":" +
                encIds201c(groupMemberIds) + ":" + (if (isGroup) "1" else "0") + ":" +
                encIds201c(participants.map { it.userId })
            isGroup to participants
        },
        markIncomingSessionOwner = { effects += "MO" },
        signalingSender = sender,
        groupMesh = mesh,
        createWebRtc = { error("must not run") },
        configureWebRtc = { _, _ -> },
        bindMedia = { },
        sessionGateIsCurrent = { s ->
            effects += "GC:" + s + ":" + if (c.gateCurrent) "1" else "0"
            c.gateCurrent
        },
        isCurrentCallSession = { _, _ ->
            effects += "IC"
            c.gateCurrent
        },
        flushPendingGroupOffers = { _, _ -> effects += "FG" },
        callSessionMachine = machine,
        activeDomainSession = {
            effects += "AD:" + c.domainSession
            c.domainSession
        },
        ringingTimeout = timeout,
        foregroundService = foreground,
        observeSignaling = { effects += "OB" },
        endCall = { n, e, m ->
            effects += "EC:" + if (n) "1" else "0" + ":" + encStr201c(e ?: "") +
                ":" + if (m) "1" else "0"
        },
    )
    for (op in c.ops) {
        when (op.kind) {
            0 -> controller.prepareIncomingCall(
                contactId = op.contactId ?: "",
                contactName = op.contactName ?: "",
                contactAvatar = op.contactAvatar,
                callType = op.callType ?: CallType.AUDIO,
                offerSdp = op.offerSdp ?: "",
                callId = op.callId,
                groupId = op.groupId,
                groupMemberIds = op.members,
            )
            1 -> controller.answerCall(
                contactId = op.contactId,
                contactName = op.contactName,
                contactAvatar = op.contactAvatar,
                callType = op.callType,
                offerSdp = op.offerSdp,
            )
            2 -> controller.rejectIncomingCall()
            else -> throw IllegalArgumentException("unknown op ${op.kind}")
        }
        effects += "CE:" + cursor.currentEpoch()
    }
    return effects.joinToString(",")
}

private val INC_IDS201c = listOf(
    "", " ", "\t", "\u00a0", "\u0085", "peer1", "chat-9", "u1", "u2", "u3",
    "Alice", "a", "x\ty", "ünïcödé", "10", "2", "A", "Z", "é", "self",
)

private val INC_TOKENS201c = listOf("", " ", "tok", "  tok  ", "\u00a0")

private fun maybeId201c(r: Random): String? =
    if (r.nextBoolean()) null else INC_IDS201c[r.nextInt(INC_IDS201c.size)]

private fun randomIncOp201c(r: Random): IncOp201c = when (r.nextInt(3)) {
    0 -> IncOp201c(
        0,
        contactId = INC_IDS201c[r.nextInt(INC_IDS201c.size)],
        contactName = INC_IDS201c[r.nextInt(INC_IDS201c.size)],
        contactAvatar = if (r.nextBoolean()) null else INC_IDS201c[r.nextInt(INC_IDS201c.size)],
        callType = CallType.entries[r.nextInt(CallType.entries.size)],
        offerSdp = INC_IDS201c[r.nextInt(INC_IDS201c.size)],
        callId = INC_IDS201c[r.nextInt(INC_IDS201c.size)],
        groupId = INC_IDS201c[r.nextInt(INC_IDS201c.size)],
        members = List(r.nextInt(0, 5)) { INC_IDS201c[r.nextInt(INC_IDS201c.size)] },
    )
    1 -> IncOp201c(
        1,
        contactId = maybeId201c(r),
        contactName = maybeId201c(r),
        contactAvatar = if (r.nextBoolean()) null else INC_IDS201c[r.nextInt(INC_IDS201c.size)],
        callType = if (r.nextBoolean()) null else CallType.entries[r.nextInt(CallType.entries.size)],
        offerSdp = maybeId201c(r),
    )
    else -> IncOp201c(2)
}

private fun randomIncCase201c(r: Random): IncCase201c {
    val ops = mutableListOf<IncOp201c>()
    repeat(r.nextInt(0, 9)) { ops += randomIncOp201c(r) }
    return IncCase201c(
        init = r.nextInt(INIT_STATES201c.size),
        token = INC_TOKENS201c[r.nextInt(INC_TOKENS201c.size)],
        owner = INC_IDS201c[r.nextInt(INC_IDS201c.size)],
        callId = INC_IDS201c[r.nextInt(INC_IDS201c.size)],
        domainSession = listOf(0L, 1L, 7L, 42L, -1L)[r.nextInt(5)],
        gateCurrent = r.nextBoolean(),
        pendingSdp = if (r.nextBoolean()) null else INC_IDS201c[r.nextInt(INC_IDS201c.size)],
        ops = ops,
    )
}

private fun boundaryIncCase201c(r: Random): IncCase201c {
    val cases = listOf(
        IncCase201c(0, "tok", "self", "c1", 7L, true, "sdp", listOf(IncOp201c(0, "peer1", "Alice", null, CallType.AUDIO, "sdp", "c1", "", emptyList()))),
        IncCase201c(0, "tok", "self", "c1", 7L, true, "sdp", listOf(IncOp201c(0, "peer1", "Alice", null, CallType.AUDIO, "sdp", "c1", "g1", listOf("u1", "u2")))),
        IncCase201c(0, "tok", "self", "c1", 7L, true, "sdp", listOf(IncOp201c(0, "peer1", "Alice", null, CallType.AUDIO, "sdp", "c1", "g1", listOf("u1")))),
        IncCase201c(0, "tok", "self", "c1", 7L, true, "sdp", listOf(IncOp201c(0, "peer1", "Alice", null, CallType.AUDIO, "sdp", "", "g1", listOf("u1", "u2")))),
        IncCase201c(1, "tok", "self", "c1", 7L, true, "sdp", listOf(IncOp201c(0, "peer2", "Bob", null, CallType.VIDEO, "sdp2", "c2", "", emptyList()))),
        IncCase201c(1, "tok", "self", "c1", 7L, true, "sdp", listOf(IncOp201c(0, "peer1", "Alice", null, CallType.AUDIO, "sdp", "c1", "", emptyList()))),
        IncCase201c(1, "tok", "self", "c1", 7L, true, "sdp", listOf(IncOp201c(1, null, null, null, null, null))),
        IncCase201c(1, "tok", "self", "c1", 7L, true, null, listOf(IncOp201c(1, null, null, null, null, null))),
        IncCase201c(1, "tok", "self", "c1", 7L, true, "", listOf(IncOp201c(1, null, null, null, null, null))),
        IncCase201c(1, "", "self", "c1", 7L, true, "sdp", listOf(IncOp201c(1, null, null, null, null, null))),
        IncCase201c(1, " ", "self", "c1", 7L, true, "sdp", listOf(IncOp201c(1, null, null, null, null, null))),
        IncCase201c(1, "tok", "self", "", 7L, true, "sdp", listOf(IncOp201c(1, null, null, null, null, null))),
        IncCase201c(2, "tok", "self", "c1", 7L, true, "sdp", listOf(IncOp201c(1, null, null, null, null, null))),
        IncCase201c(3, "tok", "self", "c1", 7L, true, "sdp", listOf(IncOp201c(1, null, null, null, null, null))),
        IncCase201c(1, "tok", "self", "c1", 7L, true, "sdp", listOf(IncOp201c(1, "peer1", "Alice", "av", CallType.VIDEO, "sdp",))),
        IncCase201c(1, "tok", "self", "c1", 7L, true, "sdp", listOf(IncOp201c(1, "", null, null, null, null))),
        IncCase201c(1, "tok", "self", "c1", 7L, true, "sdp", listOf(IncOp201c(1, null, null, null, null, ""))),
        IncCase201c(0, "tok", "self", "c1", 7L, true, "sdp", listOf(IncOp201c(2))),
        IncCase201c(6, "tok", "self", "c1", 7L, true, "sdp", listOf(IncOp201c(2))),
        IncCase201c(1, "tok", "self", "", 7L, true, "sdp", listOf(IncOp201c(2))),
        IncCase201c(
            0, "tok", "self", "c1", 7L, true, "sdp",
            listOf(
                IncOp201c(0, "peer1", "Alice", null, CallType.AUDIO, "sdp", "c1", "", emptyList()),
                IncOp201c(1, null, null, null, null, null),
                IncOp201c(2),
            ),
        ),
        IncCase201c(0, "tok", "self", "c1", 7L, true, "sdp", emptyList()),
    )
    return cases[r.nextInt(cases.size)]
}

// ---------- tests ----------

class CallIncomingCallControllerFuzzTest {

    @Test
    fun incDifferential201() {
        val r = Random(201301)
        repeat(300) {
            val c = randomIncCase201c(r)
            assertEquals(incOracle201c(c), incProd201c(c))
        }
    }

    @Test
    fun incBoundaryDifferential201() {
        val r = Random(201302)
        repeat(300) {
            val c = boundaryIncCase201c(r)
            assertEquals(incOracle201c(c), incProd201c(c))
        }
    }

    @Test
    fun incPinned201() {
        // 呼入展示：IDLE→RINGING，振铃超时与信令监听都挂上。
        val show = IncCase201c(0, "tok", "self", "c1", 7L, true, "sdp", listOf(IncOp201c(0, "peer1", "Alice", null, CallType.AUDIO, "sdp", "c1", "", emptyList())))
        assertEquals(incOracle201c(show), incProd201c(show))
        // 忙线：已在通话且非本人，busy 信令只读会话世代。
        val busy = IncCase201c(1, "tok", "self", "c1", 7L, true, "sdp", listOf(IncOp201c(0, "peer2", "Bob", null, CallType.VIDEO, "sdp2", "c2", "", emptyList())))
        assertEquals(incOracle201c(busy), incProd201c(busy))
        // 接听全通：RINGING→初始化中，前台服务启动。
        val answer = IncCase201c(1, "tok", "self", "c1", 7L, true, "sdp", listOf(IncOp201c(1, null, null, null, null, null)))
        assertEquals(incOracle201c(answer), incProd201c(answer))
        // 接听缺 offer：只写 INCOMING_INCOMPLETE。
        val noOffer = IncCase201c(1, "tok", "self", "c1", 7L, true, null, listOf(IncOp201c(1, null, null, null, null, null)))
        assertEquals(incOracle201c(noOffer), incProd201c(noOffer))
        // token 空：接听转挂断，记 SESSION_EXPIRED。
        val expired = IncCase201c(1, "", "self", "c1", 7L, true, "sdp", listOf(IncOp201c(1, null, null, null, null, null)))
        assertEquals(incOracle201c(expired), incProd201c(expired))
        // 拒接：发 reject、清托盘、挂断不记未接。
        val reject = IncCase201c(0, "tok", "self", "c1", 7L, true, "sdp", listOf(IncOp201c(2)))
        assertEquals(incOracle201c(reject), incProd201c(reject))
    }
}
