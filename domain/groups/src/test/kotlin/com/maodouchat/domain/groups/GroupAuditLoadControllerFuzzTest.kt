package com.maodouchat.domain.groups

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
private fun encStr206b(s: String): String {
    val out = StringBuilder()
    for (ch in s) out.append("%04x".format(ch.code))
    return out.toString()
}

private fun bit206b(b: Boolean): String = if (b) "1" else "0"

private fun opt206b(s: String?): String = s?.let { encStr206b(it) } ?: "~"

private data class AudLog206b(
    val id: String,
    val actorId: String,
    val actorName: String,
    val action: String,
    val targetUserId: String?,
    val targetUserName: String?,
    val createdAt: Long,
)

// gw: 0 成功、1 失败 Result、2 抛 RuntimeException、3 抛 CancellationException、
// 4 抛 200 字 RuntimeException（message.take(120) 边界）。
private data class AudOp206b(
    val gw: Int,
    val page: List<AudLog206b> = emptyList(),
)

private data class AudCase206b(
    val initLogs: List<AudLog206b> = emptyList(),
    val initLoading: Boolean = false,
    val initHasMore: Boolean = true,
    val initMessage: String? = null,
    val initOffset: Int = 0,
    val owner: String = "me",
    val ownerFlip: Boolean = false,
    // 0 恒真、1 恒假、2 首次真其后假。
    val sessMode: Int = 0,
    // 0 有令牌、1 空白令牌、2 空令牌。
    val tokenMode: Int = 0,
    val chatId: String = "g1",
    val ops: List<AudOp206b> = emptyList(),
)

private fun audToDto206b(a: AudLog206b): GroupAuditLogDto = GroupAuditLogDto(
    id = a.id,
    actorId = a.actorId,
    actorName = a.actorName,
    action = a.action,
    targetUserId = a.targetUserId,
    targetUserName = a.targetUserName,
    createdAt = a.createdAt,
)

private fun encAudLog206b(a: GroupAuditLogDto): String = listOf(
    encStr206b(a.id),
    encStr206b(a.actorId),
    encStr206b(a.actorName),
    encStr206b(a.action),
    opt206b(a.targetUserId),
    opt206b(a.targetUserName),
    a.createdAt.toString(),
).joinToString(":")

private fun encAudState206b(
    logs: List<GroupAuditLogDto>,
    isLoading: Boolean,
    hasMore: Boolean,
    message: String?,
    offset: Int,
): String = listOf(
    logs.joinToString(",") { encAudLog206b(it) },
    bit206b(isLoading),
    bit206b(hasMore),
    opt206b(message),
    offset.toString(),
).joinToString("|")

private fun audInitialState206b(c: AudCase206b): GroupDetailUiState = GroupDetailUiState(
    auditLogs = c.initLogs.map { audToDto206b(it) },
    isLoadingMoreAudit = c.initLoading,
    hasMoreAudit = c.initHasMore,
    message = c.initMessage,
)

private fun audOracle206b(c: AudCase206b): String {
    val effects = mutableListOf<String>()
    var logs: List<GroupAuditLogDto> = c.initLogs.map { audToDto206b(it) }
    var isLoading = c.initLoading
    var hasMore = c.initHasMore
    var message = c.initMessage
    var offset = c.initOffset
    var ownerCalls = 0
    var sessCalls = 0
    fun ownerId(): String {
        val idx = ownerCalls++
        return if (idx == 0) c.owner else if (c.ownerFlip) "other" else c.owner
    }
    fun sessActive(@Suppress("UNUSED_PARAMETER") owner: String): Boolean {
        val idx = sessCalls++
        return when (c.sessMode) {
            0 -> true
            1 -> false
            else -> idx == 0
        }
    }
    fun isCurrentOwner(expected: String): Boolean =
        expected.isNotBlank() && ownerId() == expected
    fun snap(prefix: String) {
        effects += prefix + ":" + encAudState206b(logs, isLoading, hasMore, message, offset)
    }
    for (op in c.ops) {
        if (isLoading || !hasMore) {
            snap("FIN")
            continue
        }
        val auditOwner = ownerId()
        if (auditOwner.isBlank()) {
            snap("FIN")
            continue
        }
        isLoading = true
        snap("ST")
        try {
            if (!sessActive(auditOwner)) {
                isLoading = false
                snap("ST")
            } else {
                val off = offset
                val page: List<GroupAuditLogDto> = when {
                    c.tokenMode != 0 -> emptyList()
                    op.gw == 0 -> {
                        effects += "CALL:gw:0"
                        op.page.map { audToDto206b(it) }
                    }
                    op.gw == 1 -> {
                        effects += "CALL:gw:1"
                        emptyList()
                    }
                    op.gw == 3 -> {
                        effects += "CALL:gw:3"
                        throw CancellationException("cancel-boom")
                    }
                    op.gw == 4 -> {
                        effects += "CALL:gw:4"
                        throw RuntimeException("x".repeat(200))
                    }
                    else -> {
                        effects += "CALL:gw:2"
                        throw RuntimeException("boom")
                    }
                }
                offset = off + page.size
                if (!sessActive(auditOwner)) {
                    // 直接返回：loading 保持 true，offset 已更新。
                } else {
                    if (page.isEmpty()) {
                        isLoading = false
                        hasMore = false
                    } else {
                        logs = (logs + page).distinctBy { it.id }
                        isLoading = false
                        hasMore = page.size >= 100
                    }
                    snap("ST")
                }
            }
        } catch (e: CancellationException) {
            if (isCurrentOwner(auditOwner)) {
                isLoading = false
                snap("ST")
            }
            // CancellationException 不进 handler、不留 THROW。
        } catch (e: Exception) {
            if (isCurrentOwner(auditOwner)) {
                isLoading = false
                message = e.message?.take(120)
                snap("ST")
            }
        }
        snap("FIN")
    }
    return effects.joinToString(",")
}

private fun audProd206b(c: AudCase206b): String {
    val effects = mutableListOf<String>()
    var state = audInitialState206b(c)
    var offset = c.initOffset
    var ownerCalls = 0
    var sessCalls = 0
    var curGw = 0
    var curPage: List<AudLog206b> = emptyList()
    fun snap(prefix: String) {
        effects += prefix + ":" + encAudState206b(
            state.auditLogs, state.isLoadingMoreAudit, state.hasMoreAudit, state.message, offset,
        )
    }
    val handler = CoroutineExceptionHandler { _, e ->
        effects += "THROW:" + e.javaClass.simpleName
    }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined + handler)
    fun drain() {
        runBlocking {
            for (child in scope.coroutineContext[Job]?.children ?: emptySequence()) {
                runCatching { child.join() }
            }
        }
    }
    val gateway = object : GroupNetworkGateway {
        override suspend fun addGroupMembers(tok: String, chatId: String, memberIds: List<String>): Result<Unit> =
            Result.failure(UnsupportedOperationException("unused"))
        override suspend fun removeGroupMember(tok: String, chatId: String, memberId: String): Result<Unit> =
            Result.failure(UnsupportedOperationException("unused"))
        override suspend fun updateMemberRole(tok: String, chatId: String, memberId: String, role: String): Result<Unit> =
            Result.failure(UnsupportedOperationException("unused"))
        override suspend fun transferGroupOwnership(tok: String, chatId: String, memberId: String): Result<Unit> =
            Result.failure(UnsupportedOperationException("unused"))
        override suspend fun updateMemberTitle(tok: String, chatId: String, memberId: String, title: String): Result<Unit> =
            Result.failure(UnsupportedOperationException("unused"))
        override suspend fun updateMemberMute(tok: String, chatId: String, memberId: String, mutedUntil: Long): Result<Unit> =
            Result.failure(UnsupportedOperationException("unused"))
        override suspend fun muteAllMembers(tok: String, chatId: String, mutedUntil: Long): Result<Unit> =
            Result.failure(UnsupportedOperationException("unused"))
        override suspend fun renameGroup(tok: String, chatId: String, newName: String): Result<Unit> =
            Result.failure(UnsupportedOperationException("unused"))
        override suspend fun updateGroupAnnouncement(tok: String, chatId: String, announcement: String): Result<Unit> =
            Result.failure(UnsupportedOperationException("unused"))
        override suspend fun uploadGroupAvatar(tok: String, chatId: String, base64Data: String): Result<String> =
            Result.failure(UnsupportedOperationException("unused"))
        override suspend fun updateGroupNickname(tok: String, chatId: String, nickname: String): Result<Unit> =
            Result.failure(UnsupportedOperationException("unused"))
        override suspend fun deleteChat(tok: String, chatId: String): Result<Unit> =
            Result.failure(UnsupportedOperationException("unused"))
        override suspend fun getChats(tok: String): Result<List<ChatDto>> =
            Result.failure(UnsupportedOperationException("unused"))
        override suspend fun getGroupMembers(tok: String, chatId: String): Result<List<GroupMemberDto>> =
            Result.failure(UnsupportedOperationException("unused"))
        override suspend fun getAllSearchableUsers(tok: String): Result<List<UserDto>> =
            Result.failure(UnsupportedOperationException("unused"))
        override suspend fun getOrCreateGroupInvite(tok: String, chatId: String, rotate: Boolean, expiresInSeconds: Long, maxUses: Int): Result<GroupInviteResponse> =
            Result.failure(UnsupportedOperationException("unused"))
        override suspend fun getGroupAudit(tok: String, chatId: String, limit: Int, offset: Int): Result<List<GroupAuditLogDto>> {
            effects += "CALL:gw:" + curGw
            return when (curGw) {
                0 -> Result.success(curPage.map { audToDto206b(it) })
                1 -> Result.failure(RuntimeException("boom"))
                3 -> throw CancellationException("cancel-boom")
                4 -> throw RuntimeException("x".repeat(200))
                else -> throw RuntimeException("boom")
            }
        }
        override suspend fun listBots(tok: String): Result<String> =
            Result.failure(UnsupportedOperationException("unused"))
        override suspend fun inviteBotToChat(tok: String, chatId: String, botId: String): Result<String> =
            Result.failure(UnsupportedOperationException("unused"))
        override suspend fun getSenderKeyDistributionStatus(tok: String, chatId: String, epoch: Long?, currentDeviceId: Int?): Result<SenderKeyDistributionStatusDto> =
            Result.failure(UnsupportedOperationException("unused"))
    }
    val auditController = GroupAuditController(
        tokenProvider = {
            when (c.tokenMode) {
                0 -> "tok"
                1 -> "  "
                else -> ""
            }
        },
        gateway = gateway,
        ioDispatcher = Dispatchers.Unconfined,
    )
    val controller = GroupAuditLoadController(
        scope = scope,
        currentState = { state },
        updateState = { transform ->
            state = transform(state)
            snap("ST")
        },
        chatId = { c.chatId },
        ownerUserId = {
            val idx = ownerCalls++
            if (idx == 0) c.owner else if (c.ownerFlip) "other" else c.owner
        },
        sessionActive = { _: String ->
            val idx = sessCalls++
            when (c.sessMode) {
                0 -> true
                1 -> false
                else -> idx == 0
            }
        },
        groupAuditController = auditController,
        auditOffsetGet = { offset },
        auditOffsetSet = { offset = it },
    )
    for (op in c.ops) {
        curGw = op.gw
        curPage = op.page
        try {
            controller.loadMoreAudit()
        } catch (e: Exception) {
            effects += "THROW:" + e.javaClass.simpleName
        }
        drain()
        snap("FIN")
    }
    return effects.joinToString(",")
}

private val AUD_IDS206b = listOf("", " ", "a1", "a2", "x\ty", "ünïcödé")

private fun randomAudLog206b(r: Random): AudLog206b = AudLog206b(
    id = AUD_IDS206b[r.nextInt(AUD_IDS206b.size)],
    actorId = listOf("u1", "u2", "", "me")[r.nextInt(4)],
    actorName = listOf("Ann", "", "x\ty")[r.nextInt(3)],
    action = listOf("ADD", "REMOVE", "MUTE", "", "ROLE")[r.nextInt(5)],
    targetUserId = if (r.nextInt(3) == 0) AUD_IDS206b[r.nextInt(AUD_IDS206b.size)] else null,
    targetUserName = if (r.nextInt(3) == 0) "T" else null,
    createdAt = listOf(0L, 1L, 1_700_000_000_000L, -5L)[r.nextInt(4)],
)

private fun randomAudOp206b(r: Random): AudOp206b = AudOp206b(
    gw = r.nextInt(5),
    page = List(r.nextInt(0, 4)) { randomAudLog206b(r) },
)

private fun randomAudCase206b(r: Random): AudCase206b = AudCase206b(
    initLogs = List(r.nextInt(0, 3)) { randomAudLog206b(r) },
    initLoading = r.nextInt(5) == 0,
    initHasMore = r.nextInt(5) != 0,
    initMessage = if (r.nextInt(4) == 0) "old-msg" else null,
    initOffset = listOf(0, 1, 99, 100, 250)[r.nextInt(5)],
    owner = listOf("me", "", " ", "u1")[r.nextInt(4)],
    ownerFlip = r.nextInt(4) == 0,
    sessMode = listOf(0, 0, 0, 1, 2)[r.nextInt(5)],
    tokenMode = listOf(0, 0, 0, 1, 2)[r.nextInt(5)],
    chatId = listOf("g1", "", " ", "x\ty")[r.nextInt(4)],
    ops = List(r.nextInt(0, 9)) { randomAudOp206b(r) },
)

private fun boundaryAudCase206b(r: Random): AudCase206b {
    val base = AudCase206b()
    val one = AudLog206b("a1", "u1", "Ann", "ADD", null, null, 1000L)
    val page100 = List(100) { i -> AudLog206b("a$i", "u1", "Ann", "ADD", null, null, 1000L + i) }
    val page101 = List(101) { i -> AudLog206b("a$i", "u1", "Ann", "ADD", null, null, 1000L + i) }
    return when (r.nextInt(24)) {
        0 -> base.copy(ops = listOf(AudOp206b(0, listOf(one))))
        1 -> base.copy(ops = listOf(AudOp206b(1, listOf(one))))
        2 -> base.copy(ops = listOf(AudOp206b(2, listOf(one))))
        3 -> base.copy(ops = listOf(AudOp206b(3, listOf(one))))
        4 -> base.copy(ops = listOf(AudOp206b(4, listOf(one))))
        5 -> base.copy(ops = listOf(AudOp206b(0, listOf(one))), tokenMode = 1)
        6 -> base.copy(ops = listOf(AudOp206b(0, listOf(one))), tokenMode = 2)
        7 -> base.copy(ops = listOf(AudOp206b(0, listOf(one))), sessMode = 1)
        8 -> base.copy(ops = listOf(AudOp206b(0, listOf(one))), sessMode = 2)
        9 -> base.copy(ops = listOf(AudOp206b(0, listOf(one))), owner = "")
        10 -> base.copy(ops = listOf(AudOp206b(0, listOf(one))), owner = " ")
        11 -> base.copy(ops = listOf(AudOp206b(0, listOf(one))), initLoading = true)
        12 -> base.copy(ops = listOf(AudOp206b(0, listOf(one))), initHasMore = false)
        13 -> base.copy(ops = listOf(AudOp206b(0, emptyList())))
        14 -> base.copy(ops = listOf(AudOp206b(0, page100)))
        15 -> base.copy(ops = listOf(AudOp206b(0, page101)))
        16 -> base.copy(ops = listOf(AudOp206b(0, listOf(one, one.copy(actorName = "Bob")))), initLogs = listOf(one))
        17 -> base.copy(ops = listOf(AudOp206b(2, listOf(one))), ownerFlip = true)
        18 -> base.copy(ops = listOf(AudOp206b(3, listOf(one))), ownerFlip = true)
        19 -> base.copy(ops = listOf(AudOp206b(4, listOf(one))), ownerFlip = true)
        20 -> base.copy(ops = listOf(AudOp206b(0, listOf(one)), AudOp206b(0, listOf(one.copy(id = "a2")))), initOffset = 100)
        21 -> base.copy(ops = listOf(AudOp206b(0, listOf(one))), initOffset = 250, initMessage = "old-msg")
        22 -> base.copy(ops = listOf(AudOp206b(2, listOf(one))), sessMode = 2)
        else -> base.copy(ops = emptyList())
    }
}

// ---------- tests ----------

class GroupAuditLoadControllerFuzzTest {

    @Test
    fun randomCases() {
        val r = Random(206303)
        repeat(300) {
            val c = randomAudCase206b(r)
            assertEquals(audOracle206b(c), audProd206b(c))
        }
    }

    @Test
    fun boundaryCases() {
        val r = Random(206304)
        repeat(200) {
            val c = boundaryAudCase206b(r)
            assertEquals(audOracle206b(c), audProd206b(c))
        }
    }

    @Test
    fun pinnedCases() {
        val one = AudLog206b("a1", "u1", "Ann", "ADD", null, null, 1000L)
        val page100 = List(100) { i -> AudLog206b("a$i", "u1", "Ann", "ADD", null, null, 1000L + i) }
        val cases = listOf(
            AudCase206b(ops = listOf(AudOp206b(0, listOf(one)))),
            AudCase206b(ops = listOf(AudOp206b(1, listOf(one)))),
            AudCase206b(ops = listOf(AudOp206b(2, listOf(one)))),
            AudCase206b(ops = listOf(AudOp206b(3, listOf(one)))),
            AudCase206b(ops = listOf(AudOp206b(4, listOf(one)))),
            AudCase206b(ops = listOf(AudOp206b(0, listOf(one))), tokenMode = 1),
            AudCase206b(ops = listOf(AudOp206b(0, listOf(one))), sessMode = 2),
            AudCase206b(ops = listOf(AudOp206b(0, listOf(one))), owner = ""),
            AudCase206b(ops = listOf(AudOp206b(0, page100))),
            AudCase206b(ops = listOf(AudOp206b(0, listOf(one, one.copy(actorName = "Bob")))), initLogs = listOf(one)),
            AudCase206b(ops = emptyList()),
        )
        for (c in cases) {
            assertEquals(audOracle206b(c), audProd206b(c))
        }
    }
}
