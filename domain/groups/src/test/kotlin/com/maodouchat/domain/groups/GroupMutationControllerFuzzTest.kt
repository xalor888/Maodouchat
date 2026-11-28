package com.maodouchat.domain.groups

import com.maodouchat.core.model.ApiException
import com.maodouchat.core.model.ApiFailureKind
import com.maodouchat.core.model.ChatDto
import com.maodouchat.core.model.GroupLifecycleService
import com.maodouchat.core.model.GroupMutationCommit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import java.io.IOException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// UTF-16 码元 hex 编码：串里出现分隔符也不怕。
private fun encStr210a(s: String): String {
    val out = StringBuilder()
    for (ch in s) out.append("%04x".format(ch.code))
    return out.toString()
}

private fun bit210a(b: Boolean): String = if (b) "1" else "0"

private fun opt210a(s: String?): String = s?.let { encStr210a(it) } ?: "~"

// Kotlin trim：只去 <= 0x20 的前后字符（与 Python 侧 kotlin_trim 对齐）。
private fun kotlinTrim210a(s: String): String = s.trim { it <= ' ' }

// op kind：0 rename，1 announcement，2 nickname，3 addMember，4 removeMember，
// 5 updateRole，6 transferOwnership，7 updateTitle，8 updateMemberMute，
// 9 muteAllMembers，10 uploadGroupAvatar。
// fault（0-9 号 mutation 的 service 结果）：0 成功、1 抛 RT 带消息、2 抛 RT 空消息、
// 3 抛 CancellationException、4 ApiException 403 GROUP_PERMISSION_DENIED、
// 5 ApiException 401、6 ApiException 404、7 ApiException 429、8 ApiException 500、
// 9 ApiException TIMEOUT、10 ApiException NETWORK、11 IOException、
// 12 ApiException 409 GROUP_NOT_FOUND、13 ApiException INVALID_RESPONSE。
// commitMode：0 刷新全字段、1 刷新 null、2 刷新 memberRevision 0、3 刷新 memberRevision -5。
// encFault（头像编码）：0 成功、1 null、2 抛 RT 带消息、3 抛 RT 空消息、4 抛 CE。
// avFault（uploadAvatar 的 Result）：0 成功、1 失败 RT 带消息、2 失败 RT 空消息、
// 3 失败 CE、4 失败 ApiException 403 GROUP_PERMISSION_DENIED、
// 5 失败 ApiException TIMEOUT、6 失败 IOException。
private data class GrpOp210a(
    val kind: Int,
    val strArg: String = "",
    val strArg2: String = "",
    val muteDelta: Long = 300000L,
    val fault: Int = 0,
    val commitMode: Int = 0,
    val encFault: Int = 0,
    val avFault: Int = 0,
)

private data class GrpCase210a(
    val chatId: String = "g1",
    val token: String = "tok",
    val owner: String = "me",
    val myRole: String = "OWNER",
    val groupName: String = "G",
    val groupAnnouncement: String = "",
    val groupAvatar: String? = null,
    val memberRevision: Long = 0,
    val nowBase: Long = 0L,
    val sessFlipAfter: Int = 999,
    val ops: List<GrpOp210a> = emptyList(),
)

// oracle 侧的错误描述（与生产侧的真实异常一一对应）。
private data class GrpErr210a(
    val isIo: Boolean = false,
    val message: String? = null,
    val apiKind: String? = null,
    val status: Int? = null,
    val serverMessage: String? = null,
    val serverCode: String? = null,
)

private fun grpErrForFault210a(fault: Int): GrpErr210a? = when (fault) {
    0 -> null
    1 -> GrpErr210a(message = "boom")
    2 -> GrpErr210a(message = null)
    4 -> GrpErr210a(message = "denied", apiKind = "HTTP", status = 403, serverMessage = "denied", serverCode = "GROUP_PERMISSION_DENIED")
    5 -> GrpErr210a(message = "unauth", apiKind = "HTTP", status = 401, serverMessage = "unauth")
    6 -> GrpErr210a(message = "nf", apiKind = "HTTP", status = 404, serverMessage = "nf")
    7 -> GrpErr210a(message = "slow", apiKind = "HTTP", status = 429, serverMessage = "slow")
    8 -> GrpErr210a(message = "srv", apiKind = "HTTP", status = 500, serverMessage = "srv")
    9 -> GrpErr210a(message = "to", apiKind = "TIMEOUT", serverMessage = "to")
    10 -> GrpErr210a(message = "net", apiKind = "NETWORK", serverMessage = "net")
    11 -> GrpErr210a(isIo = true, message = "io")
    12 -> GrpErr210a(message = "conf", apiKind = "HTTP", status = 409, serverMessage = "conf", serverCode = "GROUP_NOT_FOUND")
    13 -> GrpErr210a(message = "bad", apiKind = "INVALID_RESPONSE", serverMessage = "bad")
    else -> GrpErr210a(message = "boom")
}

// GroupMutationFeedbackPolicy.fromThrowable 的纯镜像。
private fun grpFeedback210a(action: GroupMutationAction, err: GrpErr210a): GroupMutationFeedback {
    val code = err.serverCode?.trim()?.takeIf { it.isNotEmpty() }
    val status = err.status
    val detail = err.serverMessage?.takeIf { it.isNotBlank() }
        ?: err.message?.takeIf { it.isNotBlank() }
    when (code) {
        "GROUP_PERMISSION_DENIED",
        "GROUP_ACTOR_NOT_MEMBER",
        "GROUP_OWNER_PROTECTED",
        "GROUP_ADMIN_PEER_PROTECTED",
        "GROUP_MEMBER_BLOCKED" -> {
            return GroupMutationFeedback(GroupMutationFeedbackKind.ERROR_PERMISSION, action, detail, code, false, true)
        }
        "GROUP_NOT_FOUND",
        "GROUP_TARGET_NOT_MEMBER",
        "GROUP_USER_NOT_FOUND",
        "GROUP_MEMBER_LIMIT_EXCEEDED",
        "GROUP_OWNER_TRANSFER_REQUIRED" -> {
            return GroupMutationFeedback(GroupMutationFeedbackKind.ERROR_CONFLICT, action, detail, code, false, true)
        }
    }
    if (status == 401 || status == 403) {
        return GroupMutationFeedback(GroupMutationFeedbackKind.ERROR_PERMISSION, action, detail, code, false, true)
    }
    if (status == 404 || status == 409 || status == 410) {
        return GroupMutationFeedback(GroupMutationFeedbackKind.ERROR_CONFLICT, action, detail, code, false, true)
    }
    if (status != null && status in 400..499) {
        return GroupMutationFeedback(GroupMutationFeedbackKind.ERROR_FATAL, action, detail, code, false, false)
    }
    val kind = when (err.apiKind) {
        "TIMEOUT", "NETWORK" -> GroupMutationFeedbackKind.ERROR_RETRYABLE
        "INVALID_RESPONSE" -> GroupMutationFeedbackKind.ERROR_RETRYABLE
        "HTTP" -> if ((status ?: 500) >= 500) GroupMutationFeedbackKind.ERROR_RETRYABLE else GroupMutationFeedbackKind.ERROR_FATAL
        else -> if (err.isIo) GroupMutationFeedbackKind.ERROR_RETRYABLE else GroupMutationFeedbackKind.ERROR_FATAL
    }
    return GroupMutationFeedback(
        kind = kind,
        action = action,
        detail = detail,
        serverCode = code,
        canRetry = kind == GroupMutationFeedbackKind.ERROR_RETRYABLE,
        shouldReload = kind == GroupMutationFeedbackKind.ERROR_PERMISSION ||
            kind == GroupMutationFeedbackKind.ERROR_CONFLICT,
    )
}

private fun grpFeedbackEnc210a(fb: GroupMutationFeedback?): String {
    if (fb == null) return "~"
    return listOf(
        fb.kind.name,
        fb.action.name,
        opt210a(fb.detail),
        opt210a(fb.serverCode),
        bit210a(fb.canRetry),
        bit210a(fb.shouldReload),
    ).joinToString(":")
}

private fun grpStateEnc210a(state: GroupDetailUiState, hasRetry: Boolean): String = listOf(
    encStr210a(state.groupName),
    encStr210a(state.groupAnnouncement),
    opt210a(state.groupAvatar),
    state.memberRevision.toString(),
    bit210a(state.isUpdating),
    bit210a(state.isUploadingAvatar),
    opt210a(state.message),
    grpFeedbackEnc210a(state.feedback),
    bit210a(hasRetry),
).joinToString(":")

private fun grpText210a(msg: GroupMutationMessage): String = "T:" + msg.name

private sealed interface GrpSvcOut210a {
    data class Ok(val refreshed: ChatDto?) : GrpSvcOut210a
    data class Err(val err: GrpErr210a) : GrpSvcOut210a
    data object Cancel : GrpSvcOut210a
}

private fun grpOracle210a(c: GrpCase210a): String {
    val effects = mutableListOf<String>()
    var state = GroupDetailUiState(
        groupName = c.groupName,
        groupAnnouncement = c.groupAnnouncement,
        groupAvatar = c.groupAvatar,
        memberRevision = c.memberRevision,
        myRole = c.myRole,
    )
    var hasRetry = false
    var sessCalls = 0
    fun snap(prefix: String) {
        effects += prefix + ":" + grpStateEnc210a(state, hasRetry)
    }
    fun updateState(transform: (GroupDetailUiState) -> GroupDetailUiState) {
        state = transform(state)
        snap("ST")
    }
    fun sessionActive(): Boolean {
        effects += "CALL:session"
        val idx = sessCalls++
        return idx < c.sessFlipAfter
    }
    fun commitFor(op: GrpOp210a): ChatDto? = when (op.commitMode) {
        0 -> ChatDto(id = c.chatId, groupName = "g-new", groupAnnouncement = "a-new", groupAvatar = "av-new", memberRevision = 7)
        1 -> null
        2 -> ChatDto(id = c.chatId, memberRevision = 0)
        else -> ChatDto(id = c.chatId, memberRevision = -5)
    }
    fun svcCall(method: String, argEnc: String, op: GrpOp210a): GrpSvcOut210a {
        effects += "CALL:svc:" + method + ":" + argEnc
        if (op.fault == 3) return GrpSvcOut210a.Cancel
        val err = grpErrForFault210a(op.fault)
        if (err != null) return GrpSvcOut210a.Err(err)
        return GrpSvcOut210a.Ok(commitFor(op))
    }
    fun pendingRetrySet(retry: Boolean) {
        effects += "CALL:pendingRetry:" + bit210a(retry)
        hasRetry = retry
    }
    fun onCommitted(msg: String) {
        effects += "COMMIT:" + encStr210a(msg)
    }
    fun expiredFeedback(action: GroupMutationAction): GroupMutationFeedback =
        grpFeedback210a(action, GrpErr210a(message = grpText210a(GroupMutationMessage.SESSION_EXPIRED)))
    // 生产 updateGroup 的逐分支镜像；mutation 返回 service 结果（不抛异常）。
    fun updateGroup(action: GroupMutationAction, successMessage: String, retry: Boolean, mutation: (GrpOp210a) -> GrpSvcOut210a, op: GrpOp210a) {
        if (state.isUpdating) return
        if (c.chatId.isBlank() || c.token.isBlank()) {
            updateState {
                it.copy(
                    isUpdating = false,
                    message = grpText210a(GroupMutationMessage.SESSION_EXPIRED),
                    feedback = expiredFeedback(action),
                )
            }
            return
        }
        updateState { it.copy(isUpdating = true, message = null, feedback = null) }
        if (!sessionActive()) {
            updateState {
                it.copy(
                    isUpdating = false,
                    message = grpText210a(GroupMutationMessage.SESSION_EXPIRED),
                    feedback = expiredFeedback(action),
                )
            }
            return
        }
        when (val out = mutation(op)) {
            is GrpSvcOut210a.Cancel -> {
                // 生产把 CE 重抛到 launch 外，CoroutineExceptionHandler 按设计不收 CE：静默结束。
                updateState { it.copy(isUpdating = false) }
            }
            is GrpSvcOut210a.Err -> {
                val fb = grpFeedback210a(action, out.err)
                pendingRetrySet(fb.canRetry && retry)
                updateState {
                    it.copy(
                        isUpdating = false,
                        message = fb.detail ?: grpText210a(GroupMutationMessage.OPERATION_FAILED),
                        feedback = fb,
                    )
                }
            }
            is GrpSvcOut210a.Ok -> {
                if (!sessionActive()) {
                    updateState { it.copy(isUpdating = false) }
                    return
                }
                pendingRetrySet(false)
                updateState { st ->
                    val refreshed = out.refreshed
                    st.copy(
                        groupName = refreshed?.groupName ?: st.groupName,
                        groupAnnouncement = refreshed?.groupAnnouncement ?: st.groupAnnouncement,
                        groupAvatar = refreshed?.groupAvatar ?: st.groupAvatar,
                        memberRevision = refreshed?.memberRevision
                            ?.takeIf { it > 0L }
                            ?: st.memberRevision,
                        isUpdating = false,
                        message = successMessage,
                        feedback = GroupMutationFeedback(
                            kind = GroupMutationFeedbackKind.SUCCESS,
                            action = action,
                            detail = successMessage,
                            canRetry = false,
                            shouldReload = false,
                        ),
                    )
                }
                onCommitted(successMessage)
            }
        }
    }
    fun encodeAvatar(uri: String, op: GrpOp210a): String? {
        effects += "CALL:encode:" + encStr210a(uri)
        return when (op.encFault) {
            0 -> "b64data"
            1 -> null
            2 -> throw RuntimeException("boom")
            3 -> throw RuntimeException()
            else -> throw CancellationException("cancel-boom")
        }
    }
    fun uploadGroupAvatar(op: GrpOp210a) {
        if (!state.canManageGroup) return
        if (c.token.isBlank() || c.chatId.isBlank()) {
            updateState {
                it.copy(
                    isUploadingAvatar = false,
                    message = grpText210a(GroupMutationMessage.SESSION_EXPIRED),
                    feedback = expiredFeedback(GroupMutationAction.AVATAR),
                )
            }
            return
        }
        updateState { it.copy(isUploadingAvatar = true, message = null, feedback = null) }
        if (!sessionActive()) {
            updateState {
                it.copy(
                    isUploadingAvatar = false,
                    message = grpText210a(GroupMutationMessage.SESSION_EXPIRED),
                    feedback = expiredFeedback(GroupMutationAction.AVATAR),
                )
            }
            return
        }
        val base64: String? = try {
            encodeAvatar(op.strArg, op)
        } catch (e: CancellationException) {
            // 同上：launch 内的 CE 不可观测，只复位标记。
            updateState { it.copy(isUploadingAvatar = false) }
            return
        } catch (e: RuntimeException) {
            effects += "THROW:RuntimeException"
            return
        }
        if (base64 == null) {
            pendingRetrySet(true)
            val fb = GroupMutationFeedback(
                kind = GroupMutationFeedbackKind.ERROR_RETRYABLE,
                action = GroupMutationAction.AVATAR,
                detail = grpText210a(GroupMutationMessage.IMAGE_PROCESS_FAILED),
                canRetry = true,
                shouldReload = false,
            )
            updateState { it.copy(isUploadingAvatar = false, message = fb.detail, feedback = fb) }
            return
        }
        if (!sessionActive()) {
            updateState {
                it.copy(
                    isUploadingAvatar = false,
                    message = grpText210a(GroupMutationMessage.SESSION_EXPIRED),
                    feedback = expiredFeedback(GroupMutationAction.AVATAR),
                )
            }
            return
        }
        effects += "CALL:svc:uploadAvatar:" + encStr210a(c.chatId) + ":" + encStr210a(base64)
        when (op.avFault) {
            0 -> {
                if (!sessionActive()) return
                pendingRetrySet(false)
                updateState { it.copy(groupAvatar = "https://avatar/x", isUploadingAvatar = false) }
                onCommitted(grpText210a(GroupMutationMessage.AVATAR_UPDATED))
            }
            3 -> {
                val fb = grpFeedback210a(GroupMutationAction.AVATAR, GrpErr210a(message = "cancel-boom"))
                pendingRetrySet(true)
                updateState {
                    it.copy(
                        isUploadingAvatar = false,
                        message = fb.detail ?: grpText210a(GroupMutationMessage.AVATAR_FAILED),
                        feedback = fb,
                    )
                }
            }
            else -> {
                val err = when (op.avFault) {
                    1 -> GrpErr210a(message = "boom")
                    2 -> GrpErr210a(message = null)
                    4 -> GrpErr210a(message = "denied", apiKind = "HTTP", status = 403, serverMessage = "denied", serverCode = "GROUP_PERMISSION_DENIED")
                    5 -> GrpErr210a(message = "to", apiKind = "TIMEOUT", serverMessage = "to")
                    else -> GrpErr210a(isIo = true, message = "io")
                }
                val fb = grpFeedback210a(GroupMutationAction.AVATAR, err)
                pendingRetrySet(true)
                updateState {
                    it.copy(
                        isUploadingAvatar = false,
                        message = fb.detail ?: grpText210a(GroupMutationMessage.AVATAR_FAILED),
                        feedback = fb,
                    )
                }
            }
        }
    }
    for (op in c.ops) {
        try {
            when (op.kind) {
                0 -> {
                    val trimmed = kotlinTrim210a(op.strArg)
                    updateGroup(GroupMutationAction.RENAME, grpText210a(GroupMutationMessage.GROUP_NAME_UPDATED), true, { o ->
                        svcCall("updateGroupInfo", encStr210a(c.chatId) + ":" + encStr210a(trimmed) + ":~:~", o)
                    }, op)
                }
                1 -> {
                    val trimmed = kotlinTrim210a(op.strArg)
                    val msg = if (trimmed.isBlank()) grpText210a(GroupMutationMessage.ANNOUNCEMENT_CLEARED) else grpText210a(GroupMutationMessage.ANNOUNCEMENT_UPDATED)
                    updateGroup(GroupMutationAction.ANNOUNCEMENT, msg, true, { o ->
                        svcCall("updateGroupInfo", encStr210a(c.chatId) + ":~:" + encStr210a(trimmed) + ":~", o)
                    }, op)
                }
                2 -> {
                    val trimmed = kotlinTrim210a(op.strArg)
                    updateGroup(GroupMutationAction.NICKNAME, grpText210a(GroupMutationMessage.NICKNAME_UPDATED), true, { o ->
                        svcCall("updateMyNickname", encStr210a(c.chatId) + ":" + encStr210a(trimmed), o)
                    }, op)
                }
                3 -> updateGroup(GroupMutationAction.ADD_MEMBER, grpText210a(GroupMutationMessage.MEMBER_ADDED), true, { o ->
                    svcCall("addMembers", encStr210a(c.chatId) + ":" + encStr210a(op.strArg), o)
                }, op)
                4 -> {
                    if (op.strArg != c.owner) {
                        updateGroup(GroupMutationAction.REMOVE_MEMBER, grpText210a(GroupMutationMessage.MEMBER_REMOVED), true, { o ->
                            svcCall("removeMember", encStr210a(c.chatId) + ":" + encStr210a(op.strArg), o)
                        }, op)
                    }
                }
                5 -> updateGroup(GroupMutationAction.ROLE, grpText210a(GroupMutationMessage.ROLE_UPDATED), true, { o ->
                    svcCall("setRole", encStr210a(c.chatId) + ":" + encStr210a(op.strArg) + ":" + encStr210a(op.strArg2), o)
                }, op)
                6 -> {
                    if (state.isOwner && op.strArg != c.owner) {
                        updateGroup(GroupMutationAction.TRANSFER_OWNER, grpText210a(GroupMutationMessage.TRANSFER_SUCCESS), true, { o ->
                            svcCall("transferOwnership", encStr210a(c.chatId) + ":" + encStr210a(op.strArg), o)
                        }, op)
                    }
                }
                7 -> {
                    val trimmed = kotlinTrim210a(op.strArg2)
                    updateGroup(GroupMutationAction.TITLE, grpText210a(GroupMutationMessage.TITLE_UPDATED), true, { o ->
                        svcCall("setTitle", encStr210a(c.chatId) + ":" + encStr210a(op.strArg) + ":" + encStr210a(trimmed), o)
                    }, op)
                }
                8 -> {
                    val mutedUntil = c.nowBase + op.muteDelta
                    val msg = if (op.muteDelta > 0) grpText210a(GroupMutationMessage.MUTE_SET) else grpText210a(GroupMutationMessage.MUTE_CLEARED)
                    updateGroup(GroupMutationAction.MUTE, msg, true, { o ->
                        svcCall("setMemberMute", encStr210a(c.chatId) + ":" + encStr210a(op.strArg) + ":" + mutedUntil, o)
                    }, op)
                }
                9 -> {
                    val mutedUntil = c.nowBase + op.muteDelta
                    val msg = if (op.muteDelta > 0) grpText210a(GroupMutationMessage.MUTE_ALL_SET) else grpText210a(GroupMutationMessage.MUTE_ALL_CLEARED)
                    val muted = op.muteDelta > 0
                    updateGroup(GroupMutationAction.MUTE, msg, true, { o ->
                        svcCall("setMuteAll", encStr210a(c.chatId) + ":" + bit210a(muted), o)
                    }, op)
                }
                else -> uploadGroupAvatar(op)
            }
        } catch (e: Exception) {
            effects += "THROW:" + e.javaClass.simpleName
        }
        snap("FIN")
    }
    return effects.joinToString(",")
}

private fun grpThrowForFault210a(fault: Int): Nothing {
    when (fault) {
        1 -> throw RuntimeException("boom")
        2 -> throw RuntimeException()
        3 -> throw CancellationException("cancel-boom")
        4 -> throw ApiException(ApiFailureKind.HTTP, 403, "denied", "GROUP_PERMISSION_DENIED")
        5 -> throw ApiException(ApiFailureKind.HTTP, 401, "unauth", null)
        6 -> throw ApiException(ApiFailureKind.HTTP, 404, "nf", null)
        7 -> throw ApiException(ApiFailureKind.HTTP, 429, "slow", null)
        8 -> throw ApiException(ApiFailureKind.HTTP, 500, "srv", null)
        9 -> throw ApiException(ApiFailureKind.TIMEOUT, null, "to", null)
        10 -> throw ApiException(ApiFailureKind.NETWORK, null, "net", null)
        11 -> throw IOException("io")
        12 -> throw ApiException(ApiFailureKind.HTTP, 409, "conf", "GROUP_NOT_FOUND")
        13 -> throw ApiException(ApiFailureKind.INVALID_RESPONSE, null, "bad", null)
        else -> throw RuntimeException("boom")
    }
}

private fun grpProd210a(c: GrpCase210a): String {
    val effects = mutableListOf<String>()
    var state = GroupDetailUiState(
        groupName = c.groupName,
        groupAnnouncement = c.groupAnnouncement,
        groupAvatar = c.groupAvatar,
        memberRevision = c.memberRevision,
        myRole = c.myRole,
    )
    var hasRetry = false
    var sessCalls = 0
    fun snap(prefix: String) {
        effects += prefix + ":" + grpStateEnc210a(state, hasRetry)
    }
    val handler = CoroutineExceptionHandler { _, e ->
        effects += "THROW:" + e.javaClass.simpleName
    }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined + handler)
    fun drain() {
        runBlocking {
            repeat(100) {
                val kids = scope.coroutineContext[Job]?.children?.toList() ?: emptyList()
                if (kids.isEmpty()) return@runBlocking
                for (child in kids) runCatching { child.join() }
            }
        }
    }
    var curOp = GrpOp210a(0)
    fun commitFor(op: GrpOp210a): ChatDto? = when (op.commitMode) {
        0 -> ChatDto(id = c.chatId, groupName = "g-new", groupAnnouncement = "a-new", groupAvatar = "av-new", memberRevision = 7)
        1 -> null
        2 -> ChatDto(id = c.chatId, memberRevision = 0)
        else -> ChatDto(id = c.chatId, memberRevision = -5)
    }
    fun svcResult(op: GrpOp210a): GroupMutationCommit {
        if (op.fault != 0) grpThrowForFault210a(op.fault)
        return GroupMutationCommit(refreshedChat = commitFor(op))
    }
    val service = object : GroupLifecycleService {
        override suspend fun addMembers(chatId: String, userIds: List<String>): GroupMutationCommit {
            effects += "CALL:svc:addMembers:" + encStr210a(chatId) + ":" + encStr210a(userIds.joinToString(","))
            return svcResult(curOp)
        }
        override suspend fun removeMember(chatId: String, userId: String): GroupMutationCommit {
            effects += "CALL:svc:removeMember:" + encStr210a(chatId) + ":" + encStr210a(userId)
            return svcResult(curOp)
        }
        override suspend fun setRole(chatId: String, userId: String, role: String): GroupMutationCommit {
            effects += "CALL:svc:setRole:" + encStr210a(chatId) + ":" + encStr210a(userId) + ":" + encStr210a(role)
            return svcResult(curOp)
        }
        override suspend fun transferOwnership(chatId: String, newOwnerUserId: String): GroupMutationCommit {
            effects += "CALL:svc:transferOwnership:" + encStr210a(chatId) + ":" + encStr210a(newOwnerUserId)
            return svcResult(curOp)
        }
        override suspend fun setTitle(chatId: String, userId: String, title: String): GroupMutationCommit {
            effects += "CALL:svc:setTitle:" + encStr210a(chatId) + ":" + encStr210a(userId) + ":" + encStr210a(title)
            return svcResult(curOp)
        }
        override suspend fun setMemberMute(chatId: String, userId: String, mutedUntil: Long): GroupMutationCommit {
            effects += "CALL:svc:setMemberMute:" + encStr210a(chatId) + ":" + encStr210a(userId) + ":" + mutedUntil
            return svcResult(curOp)
        }
        override suspend fun setMuteAll(chatId: String, muted: Boolean): GroupMutationCommit {
            effects += "CALL:svc:setMuteAll:" + encStr210a(chatId) + ":" + bit210a(muted)
            return svcResult(curOp)
        }
        override suspend fun updateGroupInfo(chatId: String, name: String?, announcement: String?, avatar: String?): GroupMutationCommit {
            effects += "CALL:svc:updateGroupInfo:" + encStr210a(chatId) + ":" + opt210a(name) + ":" + opt210a(announcement) + ":" + opt210a(avatar)
            return svcResult(curOp)
        }
        override suspend fun updateMyNickname(chatId: String, nickname: String): GroupMutationCommit {
            effects += "CALL:svc:updateMyNickname:" + encStr210a(chatId) + ":" + encStr210a(nickname)
            return svcResult(curOp)
        }
        override suspend fun leaveGroup(chatId: String): GroupMutationCommit = throw UnsupportedOperationException()
        override suspend fun dissolveGroup(chatId: String): GroupMutationCommit = throw UnsupportedOperationException()
        override suspend fun fetchGroupDetails(chatId: String): Result<com.maodouchat.core.model.ChatDto?> = throw UnsupportedOperationException()
        override suspend fun fetchGroupMembers(chatId: String): Result<List<com.maodouchat.core.model.GroupMemberDto>> = throw UnsupportedOperationException()
        override suspend fun fetchCandidates(excludeMemberIds: Set<String>, excludeUserId: String): Result<List<com.maodouchat.core.model.User>> = throw UnsupportedOperationException()
        override suspend fun uploadAvatar(chatId: String, base64: String): Result<String> {
            effects += "CALL:svc:uploadAvatar:" + encStr210a(chatId) + ":" + encStr210a(base64)
            return when (curOp.avFault) {
                0 -> Result.success("https://avatar/x")
                1 -> Result.failure(RuntimeException("boom"))
                2 -> Result.failure(RuntimeException())
                3 -> Result.failure(CancellationException("cancel-boom"))
                4 -> Result.failure(ApiException(ApiFailureKind.HTTP, 403, "denied", "GROUP_PERMISSION_DENIED"))
                5 -> Result.failure(ApiException(ApiFailureKind.TIMEOUT, null, "to", null))
                else -> Result.failure(IOException("io"))
            }
        }
    }
    val controller = GroupMutationController(
        scope = scope,
        currentState = { state },
        updateState = { transform ->
            state = transform(state)
            snap("ST")
        },
        text = { msg, _ -> grpText210a(msg) },
        chatId = { c.chatId },
        token = { c.token },
        ownerUserId = { c.owner },
        sessionActive = {
            effects += "CALL:session"
            val idx = sessCalls++
            idx < c.sessFlipAfter
        },
        encodeAvatar = { uri ->
            effects += "CALL:encode:" + encStr210a(uri)
            when (curOp.encFault) {
                0 -> "b64data"
                1 -> null
                2 -> throw RuntimeException("boom")
                3 -> throw RuntimeException()
                else -> throw CancellationException("cancel-boom")
            }
        },
        ioDispatcher = Dispatchers.Unconfined,
        groupLifecycleService = service,
        onCommitted = { msg -> effects += "COMMIT:" + encStr210a(msg ?: "") },
        pendingRetrySet = { retry ->
            effects += "CALL:pendingRetry:" + bit210a(retry != null)
            hasRetry = retry != null
        },
    )
    for (op in c.ops) {
        curOp = op
        try {
            when (op.kind) {
                0 -> controller.renameGroup(op.strArg)
                1 -> controller.updateAnnouncement(op.strArg)
                2 -> controller.setMyNickname(op.strArg)
                3 -> controller.addMember(op.strArg)
                4 -> controller.removeMember(op.strArg)
                5 -> controller.updateRole(op.strArg, op.strArg2)
                6 -> controller.transferOwnership(op.strArg)
                7 -> controller.updateTitle(op.strArg, op.strArg2)
                8 -> controller.updateMemberMute(op.strArg, c.nowBase + op.muteDelta)
                9 -> controller.muteAllMembers(c.nowBase + op.muteDelta)
                else -> controller.uploadGroupAvatar(op.strArg)
            }
        } catch (e: Exception) {
            effects += "THROW:" + e.javaClass.simpleName
        }
        drain()
        snap("FIN")
    }
    return effects.joinToString(",")
}

private val GRP_NAMES210a = listOf("", " ", "G", "  padded  ", "新群名", "x\ty", "ünïcödé")
private val GRP_USERS210a = listOf("", " ", "u1", "u2", "me", "owner")
private val GRP_ROLES210a = listOf("", "MEMBER", "ADMIN", "OWNER", "  ")
private val GRP_URIS210a = listOf("", " ", "file:///a.png", "content://m/x")

private fun randomGrpOp210a(r: Random): GrpOp210a {
    val kind = r.nextInt(11)
    return GrpOp210a(
        kind = kind,
        strArg = when (kind) {
            5, 7 -> GRP_USERS210a[r.nextInt(GRP_USERS210a.size)]
            10 -> GRP_URIS210a[r.nextInt(GRP_URIS210a.size)]
            else -> if (kind == 0 || kind == 1 || kind == 2) GRP_NAMES210a[r.nextInt(GRP_NAMES210a.size)] else GRP_USERS210a[r.nextInt(GRP_USERS210a.size)]
        },
        strArg2 = when (kind) {
            5 -> GRP_ROLES210a[r.nextInt(GRP_ROLES210a.size)]
            7 -> GRP_NAMES210a[r.nextInt(GRP_NAMES210a.size)]
            else -> ""
        },
        muteDelta = listOf(-3600000L, -300000L, 300000L, 3600000L)[r.nextInt(4)],
        fault = r.nextInt(14),
        commitMode = r.nextInt(4),
        encFault = r.nextInt(5),
        avFault = r.nextInt(7),
    )
}

private fun randomGrpCase210a(r: Random): GrpCase210a = GrpCase210a(
    chatId = listOf("g1", "g2", "", " ")[r.nextInt(4)],
    token = listOf("tok", "", " ")[r.nextInt(3)],
    owner = listOf("me", "u1", "")[r.nextInt(3)],
    myRole = listOf("OWNER", "ADMIN", "MEMBER")[r.nextInt(3)],
    groupName = GRP_NAMES210a[r.nextInt(GRP_NAMES210a.size)],
    groupAnnouncement = GRP_NAMES210a[r.nextInt(GRP_NAMES210a.size)],
    groupAvatar = listOf<String?>(null, "av0", "")[r.nextInt(3)],
    memberRevision = listOf(0L, 1L, 7L)[r.nextInt(3)],
    nowBase = System.currentTimeMillis(),
    sessFlipAfter = listOf(0, 1, 2, 3, 999)[r.nextInt(5)],
    ops = List(r.nextInt(0, 9)) { randomGrpOp210a(r) },
)

private fun boundaryGrpCase210a(r: Random): GrpCase210a {
    val base = GrpCase210a(nowBase = System.currentTimeMillis())
    return when (r.nextInt(28)) {
        0 -> base.copy(ops = listOf(GrpOp210a(0, strArg = "")))
        1 -> base.copy(ops = listOf(GrpOp210a(0, strArg = "  New  ")))
        2 -> base.copy(ops = listOf(GrpOp210a(1, strArg = "")))
        3 -> base.copy(ops = listOf(GrpOp210a(1, strArg = "  hi  ")))
        4 -> base.copy(ops = listOf(GrpOp210a(2, strArg = "  nick  ")))
        5 -> base.copy(ops = listOf(GrpOp210a(3, strArg = "u1")))
        6 -> base.copy(ops = listOf(GrpOp210a(4, strArg = "me")), owner = "me")
        7 -> base.copy(ops = listOf(GrpOp210a(4, strArg = "u1")))
        8 -> base.copy(ops = listOf(GrpOp210a(5, strArg = "u1", strArg2 = "ADMIN")))
        9 -> base.copy(ops = listOf(GrpOp210a(6, strArg = "u1")), myRole = "OWNER")
        10 -> base.copy(ops = listOf(GrpOp210a(6, strArg = "me")), myRole = "OWNER", owner = "me")
        11 -> base.copy(ops = listOf(GrpOp210a(6, strArg = "u1")), myRole = "MEMBER")
        12 -> base.copy(ops = listOf(GrpOp210a(7, strArg = "u1", strArg2 = "  Boss  ")))
        13 -> base.copy(ops = listOf(GrpOp210a(8, strArg = "u1", muteDelta = 300000L)))
        14 -> base.copy(ops = listOf(GrpOp210a(8, strArg = "u1", muteDelta = -300000L)))
        15 -> base.copy(ops = listOf(GrpOp210a(9, muteDelta = 300000L)))
        16 -> base.copy(ops = listOf(GrpOp210a(9, muteDelta = -300000L)))
        17 -> base.copy(ops = listOf(GrpOp210a(10, strArg = "file:///a.png")))
        18 -> base.copy(ops = listOf(GrpOp210a(10, strArg = "file:///a.png", encFault = 1)))
        19 -> base.copy(ops = listOf(GrpOp210a(10, strArg = "file:///a.png", encFault = 2)))
        20 -> base.copy(ops = listOf(GrpOp210a(10, strArg = "file:///a.png", encFault = 4)))
        21 -> base.copy(ops = listOf(GrpOp210a(10, strArg = "file:///a.png", avFault = 4)))
        22 -> base.copy(ops = listOf(GrpOp210a(0, strArg = "N", fault = 4)))
        23 -> base.copy(ops = listOf(GrpOp210a(0, strArg = "N", fault = 12)))
        24 -> base.copy(ops = listOf(GrpOp210a(0, strArg = "N", fault = 3)))
        25 -> base.copy(ops = listOf(GrpOp210a(0, strArg = "N")), token = "")
        26 -> base.copy(ops = listOf(GrpOp210a(0, strArg = "N")), sessFlipAfter = 0)
        else -> base.copy(ops = listOf(GrpOp210a(0, strArg = "N")), sessFlipAfter = 1, myRole = "ADMIN")
    }
}

// ---------- tests ----------

class GroupMutationControllerFuzzTest {

    @Test
    fun randomCases() {
        val r = Random(210105)
        repeat(300) {
            val c = randomGrpCase210a(r)
            assertEquals(grpOracle210a(c), grpProd210a(c))
        }
    }

    @Test
    fun boundaryCases() {
        val r = Random(210106)
        repeat(200) {
            val c = boundaryGrpCase210a(r)
            assertEquals(grpOracle210a(c), grpProd210a(c))
        }
    }

    @Test
    fun pinnedCases() {
        val now = System.currentTimeMillis()
        val cases = listOf(
            GrpCase210a(nowBase = now, ops = listOf(GrpOp210a(0, strArg = "New"))),
            GrpCase210a(nowBase = now, ops = listOf(GrpOp210a(0, strArg = "New", fault = 9))),
            GrpCase210a(nowBase = now, ops = listOf(GrpOp210a(0, strArg = "New", fault = 4, commitMode = 1))),
            GrpCase210a(nowBase = now, ops = listOf(GrpOp210a(1, strArg = ""))),
            GrpCase210a(nowBase = now, ops = listOf(GrpOp210a(4, strArg = "me")), owner = "me"),
            GrpCase210a(nowBase = now, ops = listOf(GrpOp210a(6, strArg = "u1")), myRole = "OWNER"),
            GrpCase210a(nowBase = now, ops = listOf(GrpOp210a(6, strArg = "u1")), myRole = "MEMBER"),
            GrpCase210a(nowBase = now, ops = listOf(GrpOp210a(8, strArg = "u1", muteDelta = 300000L))),
            GrpCase210a(nowBase = now, ops = listOf(GrpOp210a(8, strArg = "u1", muteDelta = -300000L))),
            GrpCase210a(nowBase = now, ops = listOf(GrpOp210a(9, muteDelta = -300000L))),
            GrpCase210a(nowBase = now, ops = listOf(GrpOp210a(10, strArg = "file:///a.png"))),
            GrpCase210a(nowBase = now, ops = listOf(GrpOp210a(10, strArg = "file:///a.png", encFault = 1))),
            GrpCase210a(nowBase = now, ops = listOf(GrpOp210a(0, strArg = "N")), sessFlipAfter = 0),
            GrpCase210a(nowBase = now, ops = listOf(GrpOp210a(0, strArg = "N")), sessFlipAfter = 1),
            GrpCase210a(nowBase = now, ops = listOf(GrpOp210a(0, strArg = "N", fault = 3))),
            GrpCase210a(nowBase = now, ops = emptyList()),
        )
        for (c in cases) {
            assertEquals(grpOracle210a(c), grpProd210a(c))
        }
    }
}
