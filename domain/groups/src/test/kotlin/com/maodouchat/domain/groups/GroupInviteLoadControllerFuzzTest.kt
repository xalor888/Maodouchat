package com.maodouchat.domain.groups

import com.maodouchat.core.model.ApiException
import com.maodouchat.core.model.ApiFailureKind
import com.maodouchat.core.model.ChatDto
import com.maodouchat.core.model.GroupAuditLogDto
import com.maodouchat.core.model.GroupInviteResponse
import com.maodouchat.core.model.GroupMemberDto
import com.maodouchat.core.model.SenderKeyDistributionStatusDto
import com.maodouchat.core.model.UserDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// UTF-16 码元 hex 编码：串里出现分隔符也不怕。
private fun encStr208c(s: String): String {
    val out = StringBuilder()
    for (ch in s) out.append("%04x".format(ch.code))
    return out.toString()
}

private fun bit208c(b: Boolean): String = if (b) "1" else "0"

private fun opt208c(s: String?): String = s?.let { encStr208c(it) } ?: "~"

// op kind：只有 0 loadGroupInvite。
// mode：0 成功、1 失败 Result（错误由 errKind 选）、2 失败 Result 空消息、
// 3 抛 errKind 选的错、4 抛 RuntimeException 空消息、5 抛 CancellationException。
// errKind：0 RT("boom")、1 RT() 空消息、2 ApiException TIMEOUT、3 ApiException HTTP 500、
// 4 ApiException HTTP 401 + GROUP_PERMISSION_DENIED、5 ApiException HTTP 404 + GROUP_NOT_FOUND、
// 6 ApiException HTTP 422、7 IOException("io-boom")、8 ApiException NETWORK、
// 9 ApiException INVALID_RESPONSE、10 ApiException HTTP 403、11 ApiException HTTP 409。
private data class GilOp208c(
    val kind: Int = 0,
    val rotate: Boolean = false,
    val expires: Long = 604800L,
    val maxUses: Int = 100,
    val mode: Int = 0,
    val errKind: Int = 0,
    val payload: String = "pay",
    val expiresAt: Long = 1700000000L,
    val respMaxUses: Int = 100,
    val usedCount: Int = 0,
    val remainingUses: Int = 100,
)

private data class GilCase208c(
    val enabled: Boolean = true,
    val chatId: String = "g1",
    val token: String = "tok",
    val gatewayToken: String = "tok",
    val owner: String = "u1",
    // sessionActive 在第 flipAfter 次调用起返回 false（调用计数跨 op 累计）。
    val sessFlipAfter: Int = 999,
    val ops: List<GilOp208c> = emptyList(),
)

private fun gilText208c(msg: GroupDetailLoadMessage): String = when (msg) {
    GroupDetailLoadMessage.SESSION_EXPIRED -> "session-expired"
    GroupDetailLoadMessage.INVITE_REFRESHED -> "invite-refreshed"
    GroupDetailLoadMessage.INVITE_FAILED -> "invite-failed"
    else -> "txt-" + msg.name
}

private fun gilErr208c(errKind: Int): Throwable = when (errKind) {
    0 -> RuntimeException("boom")
    1 -> RuntimeException()
    2 -> ApiException(ApiFailureKind.TIMEOUT, serverMessage = "timeout-msg")
    3 -> ApiException(ApiFailureKind.HTTP, statusCode = 500, serverMessage = "server-boom")
    4 -> ApiException(
        ApiFailureKind.HTTP, statusCode = 401, serverMessage = "unauth",
        serverCode = "GROUP_PERMISSION_DENIED",
    )
    5 -> ApiException(
        ApiFailureKind.HTTP, statusCode = 404, serverMessage = "nf",
        serverCode = "GROUP_NOT_FOUND",
    )
    6 -> ApiException(ApiFailureKind.HTTP, statusCode = 422, serverMessage = "bad")
    7 -> java.io.IOException("io-boom")
    8 -> ApiException(ApiFailureKind.NETWORK, serverMessage = "net-down")
    9 -> ApiException(ApiFailureKind.INVALID_RESPONSE, serverMessage = "bad-json")
    10 -> ApiException(ApiFailureKind.HTTP, statusCode = 403, serverMessage = "forbidden")
    else -> ApiException(ApiFailureKind.HTTP, statusCode = 409, serverMessage = "conflict")
}

private fun gilEncFeedback208c(fb: GroupMutationFeedback?): String = if (fb == null) {
    "~"
} else {
    listOf(
        fb.kind.name,
        fb.action.name,
        opt208c(fb.detail),
        opt208c(fb.serverCode),
        bit208c(fb.canRetry),
        bit208c(fb.shouldReload),
    ).joinToString(":")
}

private fun gilState208c(
    state: GroupDetailUiState,
    pendingRetry: Triple<Boolean, Long, Int>?,
): String = listOf(
    bit208c(state.isLoadingInvite),
    opt208c(state.message),
    gilEncFeedback208c(state.feedback),
    encStr208c(state.groupInvitePayload),
    state.inviteExpiresAt.toString(),
    state.inviteMaxUses.toString(),
    state.inviteUsedCount.toString(),
    state.inviteRemainingUses.toString(),
    pendingRetry?.let { bit208c(it.first) + ":" + it.second + ":" + it.third } ?: "~",
).joinToString("|")

private fun gilOracle208c(c: GilCase208c): String {
    val effects = mutableListOf<String>()
    var state = GroupDetailUiState()
    var pendingRetry: Triple<Boolean, Long, Int>? = null
    var sessCalls = 0
    fun snap(prefix: String) {
        effects += prefix + ":" + gilState208c(state, pendingRetry)
    }
    fun sessionActive(): Boolean {
        val idx = sessCalls++
        return idx < c.sessFlipAfter
    }
    fun expiredFeedback(): GroupMutationFeedback =
        GroupMutationFeedbackPolicy.fromThrowable(
            GroupMutationAction.INVITE,
            IllegalStateException(gilText208c(GroupDetailLoadMessage.SESSION_EXPIRED)),
        )
    fun gatewayCall(op: GilOp208c): Result<GroupInviteResponse> {
        if (c.gatewayToken.isBlank()) {
            return Result.failure(IllegalStateException("session_missing"))
        }
        effects += "CALL:invite:" + encStr208c(c.gatewayToken.trim()) + ":" +
            encStr208c(c.chatId) + ":" + bit208c(op.rotate) + ":" + op.expires + ":" +
            op.maxUses + ":" + op.mode + ":" + op.errKind
        return when (op.mode) {
            0 -> Result.success(
                GroupInviteResponse(
                    token = "tok",
                    payload = op.payload,
                    expiresAt = op.expiresAt,
                    maxUses = op.respMaxUses,
                    usedCount = op.usedCount,
                    remainingUses = op.remainingUses,
                )
            )
            1 -> Result.failure(gilErr208c(op.errKind))
            2 -> Result.failure(RuntimeException())
            3 -> throw gilErr208c(op.errKind)
            4 -> throw RuntimeException()
            else -> throw CancellationException("cancel-boom")
        }
    }
    fun loadGroupInvite(op: GilOp208c) {
        if (!c.enabled) return
        if (c.chatId.isBlank() || c.token.isBlank()) {
            state = state.copy(
                isLoadingInvite = false,
                message = gilText208c(GroupDetailLoadMessage.SESSION_EXPIRED),
                feedback = expiredFeedback(),
            )
            snap("ST")
            return
        }
        state = state.copy(isLoadingInvite = true, message = null, feedback = null)
        snap("ST")
        try {
            if (!sessionActive()) {
                state = state.copy(
                    isLoadingInvite = false,
                    message = gilText208c(GroupDetailLoadMessage.SESSION_EXPIRED),
                    feedback = expiredFeedback(),
                )
                snap("ST")
                return
            }
            val result = gatewayCall(op)
            result.fold(
                onSuccess = { res ->
                    if (!sessionActive()) {
                        state = state.copy(isLoadingInvite = false)
                        snap("ST")
                        return@fold
                    }
                    pendingRetry = null
                    effects += "RETRY:~"
                    val msg = if (op.rotate) {
                        gilText208c(GroupDetailLoadMessage.INVITE_REFRESHED)
                    } else {
                        null
                    }
                    state = state.copy(
                        isLoadingInvite = false,
                        groupInvitePayload = res.payload,
                        inviteExpiresAt = res.expiresAt,
                        inviteMaxUses = res.maxUses,
                        inviteUsedCount = res.usedCount,
                        inviteRemainingUses = res.remainingUses,
                        message = msg,
                        feedback = if (msg != null) {
                            GroupMutationFeedbackPolicy.success(GroupMutationAction.INVITE, msg)
                        } else {
                            null
                        },
                    )
                    snap("ST")
                },
                onFailure = { error ->
                    val fb = GroupMutationFeedbackPolicy.fromThrowable(
                        GroupMutationAction.INVITE, error,
                    )
                    pendingRetry = Triple(op.rotate, op.expires, op.maxUses)
                    effects += "RETRY:" + bit208c(op.rotate) + ":" + op.expires + ":" + op.maxUses
                    state = state.copy(
                        isLoadingInvite = false,
                        message = fb.detail ?: gilText208c(GroupDetailLoadMessage.INVITE_FAILED),
                        feedback = fb.copy(canRetry = true),
                    )
                    snap("ST")
                },
            )
        } catch (e: CancellationException) {
            state = state.copy(isLoadingInvite = false)
            snap("ST")
        } catch (e: Exception) {
            effects += "THROW:" + e.javaClass.simpleName
        }
    }
    for (op in c.ops) {
        loadGroupInvite(op)
        snap("FIN")
    }
    return effects.joinToString(",")
}

private fun gilProd208c(c: GilCase208c): String {
    val effects = mutableListOf<String>()
    var state = GroupDetailUiState()
    var pendingRetry: Triple<Boolean, Long, Int>? = null
    var sessCalls = 0
    fun snap(prefix: String) {
        effects += prefix + ":" + gilState208c(state, pendingRetry)
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
    var curOp: GilOp208c? = null
    val fakeGateway = object : GroupNetworkGateway {
        override suspend fun getOrCreateGroupInvite(
            token: String,
            chatId: String,
            rotate: Boolean,
            expiresInSeconds: Long,
            maxUses: Int,
        ): Result<GroupInviteResponse> {
            val op = curOp!!
            effects += "CALL:invite:" + encStr208c(token) + ":" + encStr208c(chatId) + ":" +
                bit208c(rotate) + ":" + expiresInSeconds + ":" + maxUses + ":" +
                op.mode + ":" + op.errKind
            return when (op.mode) {
                0 -> Result.success(
                    GroupInviteResponse(
                        token = "tok",
                        payload = op.payload,
                        expiresAt = op.expiresAt,
                        maxUses = op.respMaxUses,
                        usedCount = op.usedCount,
                        remainingUses = op.remainingUses,
                    )
                )
                1 -> Result.failure(gilErr208c(op.errKind))
                2 -> Result.failure(RuntimeException())
                3 -> throw gilErr208c(op.errKind)
                4 -> throw RuntimeException()
                else -> throw CancellationException("cancel-boom")
            }
        }
        override suspend fun addGroupMembers(token: String, chatId: String, memberIds: List<String>): Result<Unit> =
            throw NotImplementedError("unused")
        override suspend fun removeGroupMember(token: String, chatId: String, memberId: String): Result<Unit> =
            throw NotImplementedError("unused")
        override suspend fun updateMemberRole(token: String, chatId: String, memberId: String, role: String): Result<Unit> =
            throw NotImplementedError("unused")
        override suspend fun transferGroupOwnership(token: String, chatId: String, memberId: String): Result<Unit> =
            throw NotImplementedError("unused")
        override suspend fun updateMemberTitle(token: String, chatId: String, memberId: String, title: String): Result<Unit> =
            throw NotImplementedError("unused")
        override suspend fun updateMemberMute(token: String, chatId: String, memberId: String, mutedUntil: Long): Result<Unit> =
            throw NotImplementedError("unused")
        override suspend fun muteAllMembers(token: String, chatId: String, mutedUntil: Long): Result<Unit> =
            throw NotImplementedError("unused")
        override suspend fun renameGroup(token: String, chatId: String, newName: String): Result<Unit> =
            throw NotImplementedError("unused")
        override suspend fun updateGroupAnnouncement(token: String, chatId: String, announcement: String): Result<Unit> =
            throw NotImplementedError("unused")
        override suspend fun uploadGroupAvatar(token: String, chatId: String, base64Data: String): Result<String> =
            throw NotImplementedError("unused")
        override suspend fun updateGroupNickname(token: String, chatId: String, nickname: String): Result<Unit> =
            throw NotImplementedError("unused")
        override suspend fun deleteChat(token: String, chatId: String): Result<Unit> =
            throw NotImplementedError("unused")
        override suspend fun getChats(token: String): Result<List<ChatDto>> =
            throw NotImplementedError("unused")
        override suspend fun getGroupMembers(token: String, chatId: String): Result<List<GroupMemberDto>> =
            throw NotImplementedError("unused")
        override suspend fun getAllSearchableUsers(token: String): Result<List<UserDto>> =
            throw NotImplementedError("unused")
        override suspend fun getGroupAudit(token: String, chatId: String, limit: Int, offset: Int): Result<List<GroupAuditLogDto>> =
            throw NotImplementedError("unused")
        override suspend fun listBots(token: String): Result<String> =
            throw NotImplementedError("unused")
        override suspend fun inviteBotToChat(token: String, chatId: String, botId: String): Result<String> =
            throw NotImplementedError("unused")
        override suspend fun getSenderKeyDistributionStatus(
            token: String,
            chatId: String,
            epoch: Long?,
            currentDeviceId: Int?,
        ): Result<SenderKeyDistributionStatusDto> = throw NotImplementedError("unused")
    }
    val groupInviteController = GroupInviteController(
        tokenProvider = { c.gatewayToken },
        gateway = fakeGateway,
    )
    val controller = GroupInviteLoadController(
        scope = scope,
        currentState = { state },
        updateState = { transform ->
            state = transform(state)
            snap("ST")
        },
        text = { msg -> gilText208c(msg) },
        sessionActive = {
            val idx = sessCalls++
            idx < c.sessFlipAfter
        },
        invitesEnabled = { c.enabled },
        groupInviteController = groupInviteController,
        chatId = { c.chatId },
        token = { c.token },
        ownerUserId = { c.owner },
        pendingRetrySet = { fn ->
            pendingRetry = if (fn == null) {
                null
            } else {
                Triple(curOp!!.rotate, curOp!!.expires, curOp!!.maxUses)
            }
            effects += "RETRY:" + (pendingRetry?.let {
                bit208c(it.first) + ":" + it.second + ":" + it.third
            } ?: "~")
        },
    )
    for (op in c.ops) {
        curOp = op
        try {
            controller.loadGroupInvite(op.rotate, op.expires, op.maxUses)
        } catch (e: Exception) {
            effects += "THROW:" + e.javaClass.simpleName
        }
        drain()
        snap("FIN")
    }
    return effects.joinToString(",")
}

private fun randomGilOp208c(r: Random): GilOp208c = GilOp208c(
    kind = 0,
    rotate = r.nextInt(2) == 0,
    expires = listOf(0L, 86400L, 604800L, -1L, 2592000L)[r.nextInt(5)],
    maxUses = listOf(0, 1, 100, 1000, -1)[r.nextInt(5)],
    mode = r.nextInt(6),
    errKind = r.nextInt(12),
    payload = listOf("pay", "", "x\ty", "ünïcödé")[r.nextInt(4)],
    expiresAt = listOf(0L, 1700000000L, -5L)[r.nextInt(3)],
    respMaxUses = listOf(0, 100, 1000)[r.nextInt(3)],
    usedCount = listOf(0, 3, 100)[r.nextInt(3)],
    remainingUses = listOf(0, 97, 100)[r.nextInt(3)],
)

private fun randomGilCase208c(r: Random): GilCase208c = GilCase208c(
    enabled = r.nextInt(5) != 0,
    chatId = listOf("g1", "", " ", "x\ty")[r.nextInt(4)],
    token = listOf("tok", "", "  ")[r.nextInt(3)],
    gatewayToken = listOf("tok", "", "  ")[r.nextInt(3)],
    owner = listOf("u1", "", " ", "me", "other")[r.nextInt(5)],
    sessFlipAfter = listOf(0, 1, 2, 3, 5, 999)[r.nextInt(6)],
    ops = List(r.nextInt(0, 9)) { randomGilOp208c(r) },
)

private fun boundaryGilCase208c(r: Random): GilCase208c {
    val base = GilCase208c()
    return when (r.nextInt(24)) {
        0 -> base.copy(ops = listOf(GilOp208c()))
        1 -> base.copy(ops = listOf(GilOp208c(rotate = true)))
        2 -> base.copy(ops = listOf(GilOp208c(mode = 1, errKind = 0)))
        3 -> base.copy(ops = listOf(GilOp208c(mode = 1, errKind = 2)))
        4 -> base.copy(ops = listOf(GilOp208c(mode = 1, errKind = 3)))
        5 -> base.copy(ops = listOf(GilOp208c(mode = 1, errKind = 4)))
        6 -> base.copy(ops = listOf(GilOp208c(mode = 1, errKind = 5)))
        7 -> base.copy(ops = listOf(GilOp208c(mode = 1, errKind = 6)))
        8 -> base.copy(ops = listOf(GilOp208c(mode = 1, errKind = 7)))
        9 -> base.copy(ops = listOf(GilOp208c(mode = 1, errKind = 8)))
        10 -> base.copy(ops = listOf(GilOp208c(mode = 1, errKind = 9)))
        11 -> base.copy(ops = listOf(GilOp208c(mode = 1, errKind = 10)))
        12 -> base.copy(ops = listOf(GilOp208c(mode = 1, errKind = 11)))
        13 -> base.copy(ops = listOf(GilOp208c(mode = 2)))
        14 -> base.copy(ops = listOf(GilOp208c(mode = 3, errKind = 4)))
        15 -> base.copy(ops = listOf(GilOp208c(mode = 4)))
        16 -> base.copy(ops = listOf(GilOp208c(mode = 5)))
        17 -> base.copy(ops = listOf(GilOp208c()), gatewayToken = "")
        18 -> base.copy(ops = listOf(GilOp208c()), gatewayToken = "  ")
        19 -> base.copy(ops = listOf(GilOp208c()), token = "")
        20 -> base.copy(ops = listOf(GilOp208c()), chatId = "")
        21 -> base.copy(ops = listOf(GilOp208c()), enabled = false)
        22 -> base.copy(ops = listOf(GilOp208c()), sessFlipAfter = 0)
        else -> base.copy(ops = listOf(GilOp208c(rotate = true)), sessFlipAfter = 1)
    }
}

// ---------- tests ----------

class GroupInviteLoadControllerFuzzTest {

    @Test
    fun randomCases() {
        val r = Random(208305)
        repeat(300) {
            val c = randomGilCase208c(r)
            assertEquals(gilOracle208c(c), gilProd208c(c))
        }
    }

    @Test
    fun boundaryCases() {
        val r = Random(208306)
        repeat(200) {
            val c = boundaryGilCase208c(r)
            assertEquals(gilOracle208c(c), gilProd208c(c))
        }
    }

    @Test
    fun pinnedCases() {
        val cases = listOf(
            GilCase208c(ops = listOf(GilOp208c())),
            GilCase208c(ops = listOf(GilOp208c(rotate = true))),
            GilCase208c(ops = listOf(GilOp208c(mode = 1, errKind = 4))),
            GilCase208c(ops = listOf(GilOp208c(mode = 1, errKind = 7))),
            GilCase208c(ops = listOf(GilOp208c(mode = 2))),
            GilCase208c(ops = listOf(GilOp208c(mode = 3, errKind = 0))),
            GilCase208c(ops = listOf(GilOp208c(mode = 5))),
            GilCase208c(ops = listOf(GilOp208c()), gatewayToken = ""),
            GilCase208c(ops = listOf(GilOp208c()), token = ""),
            GilCase208c(ops = listOf(GilOp208c()), enabled = false),
            GilCase208c(ops = listOf(GilOp208c()), sessFlipAfter = 1),
            GilCase208c(ops = emptyList()),
        )
        for (c in cases) {
            assertEquals(gilOracle208c(c), gilProd208c(c))
        }
    }
}
