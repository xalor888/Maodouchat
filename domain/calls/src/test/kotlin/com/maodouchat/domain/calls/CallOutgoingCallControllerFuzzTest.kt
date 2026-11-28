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
private fun encStr201b(s: String): String {
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

private fun encIds201b(ids: Collection<String>): String =
    ids.joinToString(";") { encStr201b(it) }

// 状态全量编码：字段按声明顺序以 | 连接，null 记 ~。
private fun encState201b(st: CallUiState): String {
    val parts = listOf(
        encStr201b(st.contactId),
        encStr201b(st.contactName),
        st.contactAvatar?.let { encStr201b(it) } ?: "~",
        st.callType.name,
        st.callState.name,
        if (st.isIncoming) "1" else "0",
        if (st.isGroupCall) "1" else "0",
        encStr201b(st.duration),
        if (st.isInitializing) "1" else "0",
        if (st.networkReconnecting) "1" else "0",
        st.networkStats.name,
        if (st.iceStunOnly) "1" else "0",
        st.availableAudioRoutes.joinToString(",") { it.name },
        st.selectedAudioRoute?.name ?: "~",
        st.groupParticipants.joinToString(";") { p ->
            listOf(
                encStr201b(p.userId),
                encStr201b(p.name),
                p.avatar?.let { encStr201b(it) } ?: "~",
                p.connectionState.name,
                if (p.videoAvailable) "1" else "0",
            ).joinToString(":")
        },
        st.errorMessage?.let { encStr201b(it) } ?: "~",
        st.nativeDownloadProgress.toString(),
    )
    return parts.joinToString("|")
}

// 文案：返回串与效应记同一份，Int 记 i<十进制>，String 记 s<hex>。
private fun txt201b(msg: Enum<*>, args: Array<out Any>): String {
    val sb = StringBuilder("T:")
    sb.append(msg.name)
    for (a in args) {
        when (a) {
            is Int -> sb.append(":i").append(a)
            is String -> sb.append(":s").append(encStr201b(a))
            else -> sb.append(":?").append(encStr201b(a.toString()))
        }
    }
    return sb.toString()
}

// launch 出的协程永不执行：只测同步前缀，异步建联不跑。
private val dropDispatcher201b = object : CoroutineDispatcher() {
    override fun dispatch(context: CoroutineContext, block: Runnable) = Unit
}

private val INIT_STATES201b = listOf(
    CallUiState(),
    CallUiState(
        callState = CallState.CALLING, contactId = "peer1",
        contactName = "Alice", callType = CallType.VIDEO,
    ),
    CallUiState(
        callState = CallState.RINGING, isIncoming = true, contactId = "peer2",
        groupParticipants = listOf(
            GroupCallParticipantUi("u1"),
            GroupCallParticipantUi("u2", videoAvailable = true),
        ),
    ),
    CallUiState(callState = CallState.DISCONNECTED, errorMessage = "old"),
    CallUiState(
        callState = CallState.CONNECTED, contactId = "peer9", duration = "03:12",
        nativeDownloadProgress = 42,
        availableAudioRoutes = setOf(CallAudioRoute.SPEAKER, CallAudioRoute.EARPIECE),
        selectedAudioRoute = CallAudioRoute.SPEAKER, iceStunOnly = true,
        networkReconnecting = true, networkStats = NetworkQuality.POOR,
    ),
    CallUiState(
        isGroupCall = true,
        groupParticipants = listOf(
            GroupCallParticipantUi(
                "u1", name = "A", avatar = "av",
                connectionState = GroupPeerConnectionState.CONNECTED,
            ),
        ),
    ),
    CallUiState(
        callState = CallState.RINGING, contactId = "peer1",
        contactAvatar = "av1", isInitializing = true,
    ),
    CallUiState(contactId = "x", contactName = "", callType = CallType.GROUP),
)

// op kind: 0 startCall，1 startGroupCall。
private data class OutOp201b(
    val kind: Int,
    val contactId: String = "",
    val contactName: String = "",
    val contactAvatar: String? = null,
    val callType: CallType = CallType.AUDIO,
    val chatId: String = "",
    val members: List<String> = emptyList(),
)

private data class OutCase201b(
    val init: Int = 0,
    val token: String = "",
    val callsEnabled: Boolean = true,
    val audioEnabled: Boolean = true,
    val videoEnabled: Boolean = true,
    val groupEnabled: Boolean = true,
    val owner: String = "self",
    val callId: String = "",
    val resetSession: Long = 0L,
    val gateCurrent: Boolean = true,
    val ops: List<OutOp201b> = emptyList(),
)

// 效应按发生顺序记：S 读状态，U 写状态，CE 呼叫总开关，TE 分类型开关，
// K 读 token，RO/RG 复位会话，F 起前台服务，W 写通话记录，P 拉群画像，
// SG 置群成员，O 读自己。launch 后的异步体不记。
private fun outOracle201b(c: OutCase201b): String {
    val effects = mutableListOf<String>()
    var state = INIT_STATES201b[c.init]
    fun fgStart() {
        effects += "S:" + encState201b(state)
        effects += "I:" + encStr201b(c.callId)
        val name = state.contactName.ifBlank { "Unknown" }
        val isVideo = state.callType == CallType.VIDEO
        effects += "F:" + encStr201b(name) + ":" + (if (isVideo) "1" else "0") +
            ":" + encStr201b(c.callId)
    }
    fun typeEnabled(t: CallType): Boolean {
        val en = when (t) {
            CallType.AUDIO -> c.audioEnabled
            CallType.VIDEO -> c.videoEnabled
            CallType.GROUP -> c.groupEnabled
        }
        effects += "TE:" + t.name + ":" + if (en) "1" else "0"
        return en
    }
    for (op in c.ops) {
        when (op.kind) {
            0 -> {
                effects += "S:" + encState201b(state)
                if (state.callState != CallState.IDLE) continue
                effects += "CE:" + if (c.callsEnabled) "1" else "0"
                if (!c.callsEnabled) {
                    val err = txt201b(CallOutgoingCallMessage.CALLS_DISABLED, emptyArray())
                    effects += err
                    state = state.copy(
                        contactId = op.contactId, contactName = op.contactName,
                        contactAvatar = op.contactAvatar, callType = op.callType,
                        callState = CallState.DISCONNECTED, isIncoming = false,
                        isGroupCall = false, isInitializing = false, errorMessage = err,
                    )
                    effects += "U:" + encState201b(state)
                    continue
                }
                if (!typeEnabled(op.callType)) {
                    val msg = if (op.callType == CallType.VIDEO) {
                        CallOutgoingCallMessage.VIDEO_CALL_DISABLED
                    } else {
                        CallOutgoingCallMessage.VOICE_CALL_DISABLED
                    }
                    val err = txt201b(msg, emptyArray())
                    effects += err
                    state = state.copy(
                        contactId = op.contactId, contactName = op.contactName,
                        contactAvatar = op.contactAvatar, callType = op.callType,
                        callState = CallState.DISCONNECTED, isIncoming = false,
                        isGroupCall = false, isInitializing = false, errorMessage = err,
                    )
                    effects += "U:" + encState201b(state)
                    continue
                }
                effects += "K:" + encStr201b(c.token)
                if (c.token.isBlank()) {
                    val err = txt201b(CallOutgoingCallMessage.SESSION_EXPIRED, emptyArray())
                    effects += err
                    state = state.copy(
                        contactId = op.contactId, contactName = op.contactName,
                        contactAvatar = op.contactAvatar, callType = op.callType,
                        callState = CallState.DISCONNECTED, isIncoming = false,
                        isGroupCall = false, isInitializing = false, errorMessage = err,
                    )
                    effects += "U:" + encState201b(state)
                    continue
                }
                effects += "RO:" + encStr201b(op.contactId) + ":" + c.resetSession
                state = state.copy(
                    contactId = op.contactId, contactName = op.contactName,
                    contactAvatar = op.contactAvatar, callType = op.callType,
                    callState = CallState.CALLING, isIncoming = false,
                    isGroupCall = false, isInitializing = true,
                    networkReconnecting = false, networkStats = NetworkQuality.UNKNOWN,
                    errorMessage = null,
                )
                effects += "U:" + encState201b(state)
                fgStart()
                effects += "W:MISSED"
            }
            1 -> {
                effects += "S:" + encState201b(state)
                if (state.callState != CallState.IDLE) continue
                val self = c.owner
                effects += "O:" + encStr201b(self)
                val remote = op.members.filter { it.isNotBlank() && it != self }.distinct()
                if (remote.isEmpty()) continue
                effects += "K:" + encStr201b(c.token)
                if (c.token.isBlank()) {
                    val name = txt201b(CallOutgoingCallMessage.GROUP_CALL, emptyArray())
                    effects += name
                    val err = txt201b(CallOutgoingCallMessage.SESSION_EXPIRED, emptyArray())
                    effects += err
                    state = state.copy(
                        contactId = op.chatId, contactName = name, callType = op.callType,
                        callState = CallState.DISCONNECTED, isGroupCall = true,
                        isInitializing = false, errorMessage = err,
                    )
                    effects += "U:" + encState201b(state)
                    continue
                }
                if (self.isBlank() || !GroupCallCapabilities.canStartMesh(remote.size + 1)) {
                    val name = txt201b(CallOutgoingCallMessage.GROUP_CALL, emptyArray())
                    effects += name
                    val err = txt201b(
                        CallOutgoingCallMessage.GROUP_MESH_LIMIT,
                        arrayOf(GroupCallCapabilities.MAX_MESH_MEMBERS),
                    )
                    effects += err
                    state = state.copy(
                        contactId = op.chatId, contactName = name, callType = op.callType,
                        callState = CallState.DISCONNECTED, errorMessage = err,
                    )
                    effects += "U:" + encState201b(state)
                    continue
                }
                effects += "RG:" + encStr201b(op.chatId) + ":" + encIds201b(remote) +
                    ":" + encStr201b(self) + ":" + c.resetSession
                val gname = txt201b(
                    CallOutgoingCallMessage.GROUP_NAME, arrayOf(remote.size),
                )
                effects += gname
                state = state.copy(
                    contactId = op.chatId, contactName = gname, callType = op.callType,
                    callState = CallState.CALLING, isIncoming = false,
                    isGroupCall = true, isInitializing = true,
                    groupParticipants = remote.map { GroupCallParticipantUi(it) },
                    errorMessage = null,
                )
                effects += "U:" + encState201b(state)
                effects += "SG:" + encIds201b(remote.toSet())
                effects += "P"
                fgStart()
            }
            else -> throw IllegalArgumentException("unknown op ${op.kind}")
        }
    }
    return effects.joinToString(",")
}

private fun outProd201b(c: OutCase201b): String {
    val effects = mutableListOf<String>()
    var state = INIT_STATES201b[c.init]
    var memberSet: Set<String> = emptySet()
    val dropScope = CoroutineScope(SupervisorJob() + dropDispatcher201b)
    val readState: () -> CallUiState = {
        effects += "S:" + encState201b(state)
        state
    }
    val writeState: ((CallUiState) -> CallUiState) -> Unit = { f ->
        state = f(state)
        effects += "U:" + encState201b(state)
    }
    val sender = CallSignalingSender(
        scope = dropScope,
        token = { c.token },
        activeCallId = {
            effects += "SI:" + encStr201b(c.callId)
            c.callId
        },
        activeGroupId = {
            effects += "SG2:"
            ""
        },
        meshGroupMemberIds = {
            effects += "SM:" + encIds201b(memberSet)
            memberSet.toList()
        },
        activeCallSession = {
            effects += "SS:" + c.resetSession
            c.resetSession
        },
        callSessionGate = CallSessionGate(),
        outboundSignalingCursor = CallSignalingOutboundCursor(),
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
            val s = txt201b(msg, args)
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
            effects += "RA:" + c.resetSession
            c.resetSession
        },
        currentState = readState,
        activeCallId = {
            effects += "I:" + encStr201b(c.callId)
            c.callId
        },
        timeoutMs = 30000L,
        text = { "RT:" + it.name },
        recordMissedCall = { _, _, _, _, _ -> },
        logWarn = { _, _ -> },
        onNoAnswer = { effects += "NA:" + encStr201b(it) },
    )
    val foreground = CallForegroundServiceController(
        startCallForeground = { name, isVideo, callId ->
            effects += "F:" + encStr201b(name) + ":" + (if (isVideo) "1" else "0") +
                ":" + encStr201b(callId)
        },
        stopCallForeground = { effects += "FS" },
        currentState = readState,
        activeCallId = {
            effects += "I:" + encStr201b(c.callId)
            c.callId
        },
        unknownCallerLabel = "Unknown",
    )
    val mesh = object : CallSignalingGroupMesh {
        override fun cancelInviteTimeout(userId: String) {
            effects += "CI2:" + encStr201b(userId)
        }

        override fun scheduleGroupPeerTimeout(userId: String) {
            effects += "SP:" + encStr201b(userId)
        }

        override fun markGroupPeerTerminal(userId: String, state: GroupPeerConnectionState) {
            effects += "MT:" + encStr201b(userId) + ":" + state.name
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
    val controller = CallOutgoingCallController(
        scope = dropScope,
        currentState = readState,
        updateState = writeState,
        token = {
            effects += "K:" + encStr201b(c.token)
            c.token
        },
        text = { msg, args ->
            val s = txt201b(msg, args)
            effects += s
            s
        },
        callsEnabled = {
            effects += "CE:" + if (c.callsEnabled) "1" else "0"
            c.callsEnabled
        },
        callTypeEnabled = { t ->
            val en = when (t) {
                CallType.AUDIO -> c.audioEnabled
                CallType.VIDEO -> c.videoEnabled
                CallType.GROUP -> c.groupEnabled
            }
            effects += "TE:" + t.name + ":" + if (en) "1" else "0"
            en
        },
        resetForNewOutgoingCall = { peer ->
            effects += "RO:" + encStr201b(peer) + ":" + c.resetSession
            c.resetSession
        },
        resetForNewGroupCall = { chat, members, self ->
            effects += "RG:" + encStr201b(chat) + ":" + encIds201b(members) +
                ":" + encStr201b(self) + ":" + c.resetSession
            c.resetSession
        },
        groupMemberIds = {
            effects += "GM:" + encIds201b(memberSet)
            memberSet
        },
        setGroupMemberIds = { s ->
            memberSet = s
            effects += "SG:" + encIds201b(s)
        },
        currentOwnerUserId = {
            effects += "O:" + encStr201b(c.owner)
            c.owner
        },
        writeCallLog = { st -> effects += "W:" + st.name },
        createWebRtc = { error("must not run") },
        configureWebRtc = { _, _ -> },
        bindMedia = { },
        groupMesh = mesh,
        sessionGateIsCurrent = { s ->
            effects += "GC:" + s + ":" + if (c.gateCurrent) "1" else "0"
            c.gateCurrent
        },
        isCurrentCallSession = { _, _ ->
            effects += "IC"
            c.gateCurrent
        },
        flushPendingGroupOffers = { _, _ -> effects += "FG" },
        signalingSender = sender,
        ringingTimeout = timeout,
        foregroundService = foreground,
        observeSignaling = { effects += "OB" },
        endCall = { n, e, m ->
            effects += "EC:" + if (n) "1" else "0" + ":" + encStr201b(e ?: "") +
                ":" + if (m) "1" else "0"
        },
    )
    for (op in c.ops) {
        when (op.kind) {
            0 -> controller.startCall(op.contactId, op.contactName, op.contactAvatar, op.callType)
            1 -> controller.startGroupCall(op.chatId, op.members, op.callType)
            else -> throw IllegalArgumentException("unknown op ${op.kind}")
        }
    }
    return effects.joinToString(",")
}

private val OUT_IDS201b = listOf(
    "", " ", "\t", "\u00a0", "\u0085", "peer1", "chat-9", "u1", "u2", "u3",
    "Alice", "a", "x\ty", "ünïcödé", "10", "2", "A", "Z", "é", "self",
)

private val OUT_TOKENS201b = listOf("", " ", "tok", "  tok  ", "\u00a0")

private fun randomOutOp201b(r: Random): OutOp201b = when (r.nextInt(2)) {
    0 -> OutOp201b(
        0,
        contactId = OUT_IDS201b[r.nextInt(OUT_IDS201b.size)],
        contactName = OUT_IDS201b[r.nextInt(OUT_IDS201b.size)],
        contactAvatar = if (r.nextBoolean()) null else OUT_IDS201b[r.nextInt(OUT_IDS201b.size)],
        callType = CallType.entries[r.nextInt(CallType.entries.size)],
    )
    else -> OutOp201b(
        1,
        chatId = OUT_IDS201b[r.nextInt(OUT_IDS201b.size)],
        members = List(r.nextInt(0, 6)) { OUT_IDS201b[r.nextInt(OUT_IDS201b.size)] },
        callType = CallType.entries[r.nextInt(CallType.entries.size)],
    )
}

private fun randomOutCase201b(r: Random): OutCase201b {
    val ops = mutableListOf<OutOp201b>()
    repeat(r.nextInt(0, 9)) { ops += randomOutOp201b(r) }
    return OutCase201b(
        init = r.nextInt(INIT_STATES201b.size),
        token = OUT_TOKENS201b[r.nextInt(OUT_TOKENS201b.size)],
        callsEnabled = r.nextBoolean(),
        audioEnabled = r.nextBoolean(),
        videoEnabled = r.nextBoolean(),
        groupEnabled = r.nextBoolean(),
        owner = OUT_IDS201b[r.nextInt(OUT_IDS201b.size)],
        callId = OUT_IDS201b[r.nextInt(OUT_IDS201b.size)],
        resetSession = listOf(0L, 1L, 7L, 42L, -1L)[r.nextInt(5)],
        gateCurrent = r.nextBoolean(),
        ops = ops,
    )
}

private fun boundaryOutCase201b(r: Random): OutCase201b {
    val cases = listOf(
        OutCase201b(0, "tok", true, true, true, true, "self", "c1", 7L, true, listOf(OutOp201b(0, "peer1", "Alice", null, CallType.AUDIO))),
        OutCase201b(0, "tok", true, true, true, true, "self", "c1", 7L, true, listOf(OutOp201b(0, "peer1", "", "av", CallType.VIDEO))),
        OutCase201b(0, "tok", false, true, true, true, "self", "c1", 7L, true, listOf(OutOp201b(0, "peer1", "Alice", null, CallType.AUDIO))),
        OutCase201b(0, "tok", true, false, true, true, "self", "c1", 7L, true, listOf(OutOp201b(0, "peer1", "Alice", null, CallType.AUDIO))),
        OutCase201b(0, "tok", true, true, false, true, "self", "c1", 7L, true, listOf(OutOp201b(0, "peer1", "Alice", null, CallType.VIDEO))),
        OutCase201b(0, "tok", true, true, true, false, "self", "c1", 7L, true, listOf(OutOp201b(0, "peer1", "Alice", null, CallType.GROUP))),
        OutCase201b(0, "", true, true, true, true, "self", "c1", 7L, true, listOf(OutOp201b(0, "peer1", "Alice", null, CallType.AUDIO))),
        OutCase201b(0, " ", true, true, true, true, "self", "c1", 7L, true, listOf(OutOp201b(0, "peer1", "Alice", null, CallType.AUDIO))),
        OutCase201b(1, "tok", true, true, true, true, "self", "c1", 7L, true, listOf(OutOp201b(0, "peer1", "Alice", null, CallType.AUDIO))),
        OutCase201b(4, "tok", true, true, true, true, "self", "c1", 7L, true, listOf(OutOp201b(0, "peer1", "Alice", null, CallType.AUDIO))),
        OutCase201b(0, "tok", true, true, true, true, "self", "c1", 7L, true, listOf(OutOp201b(1, chatId = "chat-9", members = listOf("u1", "u2"), callType = CallType.AUDIO))),
        OutCase201b(0, "tok", true, true, true, true, "self", "c1", 7L, true, listOf(OutOp201b(1, chatId = "chat-9", members = listOf("u1"), callType = CallType.AUDIO))),
        OutCase201b(0, "tok", true, true, true, true, "self", "c1", 7L, true, listOf(OutOp201b(1, chatId = "chat-9", members = listOf("u1", "u2", "u3", "u4", "u5", "u6"), callType = CallType.AUDIO))),
        OutCase201b(0, "tok", true, true, true, true, "self", "c1", 7L, true, listOf(OutOp201b(1, chatId = "chat-9", members = listOf(" ", "u1", "u1", "\u00a0", "u2", "self"), callType = CallType.VIDEO))),
        OutCase201b(0, "tok", true, true, true, true, "self", "c1", 7L, true, listOf(OutOp201b(1, chatId = "chat-9", members = listOf("self"), callType = CallType.AUDIO))),
        OutCase201b(0, "tok", true, true, true, true, "self", "c1", 7L, true, listOf(OutOp201b(1, chatId = "chat-9", members = emptyList(), callType = CallType.AUDIO))),
        OutCase201b(0, "", true, true, true, true, "self", "c1", 7L, true, listOf(OutOp201b(1, chatId = "chat-9", members = listOf("u1", "u2"), callType = CallType.AUDIO))),
        OutCase201b(0, "tok", true, true, true, true, "", "c1", 7L, true, listOf(OutOp201b(1, chatId = "chat-9", members = listOf("u1", "u2"), callType = CallType.AUDIO))),
        OutCase201b(0, "tok", true, true, true, true, "self", "c1", 7L, true, listOf(OutOp201b(1, chatId = "chat-9", members = listOf("u1", "u2", "u3", "u4", "u5"), callType = CallType.GROUP))),
        OutCase201b(
            0, "tok", true, true, true, true, "self", "c1", 7L, true,
            listOf(
                OutOp201b(0, "peer1", "Alice", null, CallType.AUDIO),
                OutOp201b(0, "peer2", "Bob", null, CallType.VIDEO),
                OutOp201b(1, chatId = "chat-9", members = listOf("u1", "u2"), callType = CallType.AUDIO),
            ),
        ),
        OutCase201b(0, "tok", true, true, true, true, "self", "c1", 7L, true, emptyList()),
    )
    return cases[r.nextInt(cases.size)]
}

// ---------- tests ----------

class CallOutgoingCallControllerFuzzTest {

    @Test
    fun outDifferential201() {
        val r = Random(201201)
        repeat(300) {
            val c = randomOutCase201b(r)
            assertEquals(outOracle201b(c), outProd201b(c))
        }
    }

    @Test
    fun outBoundaryDifferential201() {
        val r = Random(201202)
        repeat(300) {
            val c = boundaryOutCase201b(r)
            assertEquals(outOracle201b(c), outProd201b(c))
        }
    }

    @Test
    fun outPinned201() {
        // 呼出全通：IDLE→CALLING，记 MISSED。
        val ok = OutCase201b(0, "tok", true, true, true, true, "self", "c1", 7L, true, listOf(OutOp201b(0, "peer1", "Alice", null, CallType.AUDIO)))
        assertEquals(outOracle201b(ok), outProd201b(ok))
        // 总开关关：DISCONNECTED + CALLS_DISABLED。
        val disabled = OutCase201b(0, "tok", false, true, true, true, "self", "c1", 7L, true, listOf(OutOp201b(0, "peer1", "Alice", null, CallType.AUDIO)))
        assertEquals(outOracle201b(disabled), outProd201b(disabled))
        // 视频开关关：DISCONNECTED + VIDEO_CALL_DISABLED。
        val noVideo = OutCase201b(0, "tok", true, true, false, true, "self", "c1", 7L, true, listOf(OutOp201b(0, "peer1", "Alice", null, CallType.VIDEO)))
        assertEquals(outOracle201b(noVideo), outProd201b(noVideo))
        // 群呼 6 人满员：5 远端 + 自己 = 6，可建。
        val mesh6 = OutCase201b(0, "tok", true, true, true, true, "self", "c1", 7L, true, listOf(OutOp201b(1, chatId = "chat-9", members = listOf("u1", "u2", "u3", "u4", "u5"), callType = CallType.AUDIO)))
        assertEquals(outOracle201b(mesh6), outProd201b(mesh6))
        // 群呼 7 人超限：DISCONNECTED + GROUP_MESH_LIMIT。
        val mesh7 = OutCase201b(0, "tok", true, true, true, true, "self", "c1", 7L, true, listOf(OutOp201b(1, chatId = "chat-9", members = listOf("u1", "u2", "u3", "u4", "u5", "u6"), callType = CallType.AUDIO)))
        assertEquals(outOracle201b(mesh7), outProd201b(mesh7))
        // 空白成员先过滤：U+00A0 是空白、自己剔除。
        val blanks = OutCase201b(0, "tok", true, true, true, true, "self", "c1", 7L, true, listOf(OutOp201b(1, chatId = "chat-9", members = listOf(" ", "u1", "u1", "\u00a0", "u2", "self"), callType = CallType.VIDEO)))
        assertEquals(outOracle201b(blanks), outProd201b(blanks))
    }
}
