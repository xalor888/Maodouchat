package com.maodouchat.domain.contacts

import com.maodouchat.core.model.GroupInvitationDto
import com.maodouchat.core.model.GroupInviteAcceptResponse
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
private fun encStr207c(s: String): String {
    val out = StringBuilder()
    for (ch in s) out.append("%04x".format(ch.code))
    return out.toString()
}

private fun bit207c(b: Boolean): String = if (b) "1" else "0"

private fun opt207c(s: String?): String = s?.let { encStr207c(it) } ?: "~"

private data class InvInvite207c(
    val id: String,
    val chatId: String,
    val chatName: String,
    val inviterName: String,
    val memberCount: Int,
    val createdAt: Long,
)

// op kind：0 load，1 accept，2 decline。
// mutMode / loadMode：0 成功、1 失败 Result 带消息、2 失败 Result 空消息、
// 3 抛 RuntimeException 带消息、4 抛 RuntimeException 空消息、5 抛 CancellationException。
// loadMode 同时控制成功后嵌套重载的那一次 loader 调用（接受与拒绝都重载）。
private data class InvOp207c(
    val kind: Int,
    val inviteId: String = "i1",
    val mutMode: Int = 0,
    val loadMode: Int = 0,
    val loadPage: List<InvInvite207c> = emptyList(),
)

private data class InvCase207c(
    val hasSession: Boolean = true,
    val owner: String = "u1",
    // sessionMayContinue 在第 flipAfter 次调用起返回 false（调用计数跨 op 累计）。
    val sessFlipAfter: Int = 999,
    // busy 门控恒真时 accept/decline 直接返回。
    val busy: Boolean = false,
    val ops: List<InvOp207c> = emptyList(),
)

private fun invText207c(msg: ContactsMessage): String = when (msg) {
    ContactsMessage.CONTACTS_GROUP_UNNAMED -> "unnamed-group"
    ContactsMessage.CONTACTS_GROUP_INVITE_ACCEPTED -> "invite-accepted"
    ContactsMessage.CONTACTS_GROUP_INVITE_DECLINED -> "invite-declined"
    ContactsMessage.OPERATION_FAILED -> "operation-failed"
    else -> "txt-" + msg.name
}

private fun invEncItem207c(item: GroupInviteItem): String = listOf(
    encStr207c(item.id),
    encStr207c(item.chatId),
    encStr207c(item.chatName),
    encStr207c(item.inviterName),
    item.memberCount.toString(),
    item.createdAt.toString(),
).joinToString(":")

private fun invState207c(state: ContactsUiState): String = listOf(
    bit207c(state.isGroupInviteBusy),
    opt207c(state.errorMessage),
    opt207c(state.infoMessage),
    state.groupInvites.joinToString(";") { invEncItem207c(it) },
).joinToString("|")

private fun invOracle207c(c: InvCase207c): String {
    val effects = mutableListOf<String>()
    var state = ContactsUiState()
    var sessCalls = 0
    fun snap(prefix: String) {
        effects += prefix + ":" + invState207c(state)
    }
    fun mayContinue(): Boolean {
        val idx = sessCalls++
        return idx < c.sessFlipAfter
    }
    fun toItem(dto: GroupInvitationDto): GroupInviteItem = GroupInviteItem(
        id = dto.id,
        chatId = dto.chatId,
        chatName = dto.chatName.ifBlank { invText207c(ContactsMessage.CONTACTS_GROUP_UNNAMED) },
        inviterName = dto.inviterName,
        memberCount = dto.memberCount,
        createdAt = dto.createdAt,
    )
    fun loader(mode: Int, page: List<InvInvite207c>): Result<List<GroupInvitationDto>> {
        effects += "CALL:loader:" + mode
        return when (mode) {
            0 -> Result.success(page.map {
                GroupInvitationDto(
                    id = it.id, chatId = it.chatId, userId = "", inviterName = it.inviterName,
                    chatName = it.chatName, memberCount = it.memberCount, createdAt = it.createdAt,
                )
            })
            1 -> Result.failure(RuntimeException("boom"))
            2 -> Result.failure(RuntimeException())
            3 -> throw RuntimeException("boom")
            4 -> throw RuntimeException()
            else -> throw CancellationException("cancel-boom")
        }
    }
    fun loadInvites(mode: Int, page: List<InvInvite207c>) {
        if (!c.hasSession || c.owner.isBlank()) return
        try {
            if (!mayContinue()) return
            val result = loader(mode, page)
            if (!mayContinue()) return
            val invites = result.getOrNull() ?: return
            state = state.copy(groupInvites = invites.map { toItem(it) })
            snap("ST")
        } catch (e: CancellationException) {
            // 未捕获的 CancellationException 不进 handler，不留 THROW。
        } catch (e: Exception) {
            // 未捕获的异常进 handler，留 THROW。
            effects += "THROW:" + e.javaClass.simpleName
        }
    }
    fun mutPort(accept: Boolean, inviteId: String, mode: Int): Result<GroupInviteAcceptResponse> {
        effects += "CALL:" + (if (accept) "accept:" else "decline:") + encStr207c(inviteId)
        return when (mode) {
            0 -> Result.success(GroupInviteAcceptResponse())
            1 -> Result.failure(RuntimeException("boom"))
            2 -> Result.failure(RuntimeException())
            3 -> throw RuntimeException("boom")
            4 -> throw RuntimeException()
            else -> throw CancellationException("cancel-boom")
        }
    }
    fun mutate(inviteId: String, accept: Boolean, op: InvOp207c) {
        if (!c.hasSession || c.owner.isBlank() || c.busy || state.isGroupInviteBusy) return
        if (!mayContinue()) return
        state = state.copy(isGroupInviteBusy = true, errorMessage = null, infoMessage = null)
        snap("ST")
        try {
            val result = mutPort(accept, inviteId, op.mutMode)
            if (!mayContinue()) return
            result.fold(
                onSuccess = {
                    state = state.copy(
                        isGroupInviteBusy = false,
                        infoMessage = invText207c(
                            if (accept) ContactsMessage.CONTACTS_GROUP_INVITE_ACCEPTED
                            else ContactsMessage.CONTACTS_GROUP_INVITE_DECLINED
                        ),
                        groupInvites = state.groupInvites.filterNot { it.id == inviteId },
                    )
                    snap("ST")
                    if (accept) effects += "CALLBACK:accepted"
                    loadInvites(op.loadMode, op.loadPage)
                },
                onFailure = { error ->
                    state = state.copy(
                        isGroupInviteBusy = false,
                        errorMessage = error.message
                            ?: invText207c(ContactsMessage.OPERATION_FAILED),
                    )
                    snap("ST")
                },
            )
        } catch (e: CancellationException) {
            state = state.copy(isGroupInviteBusy = false)
            snap("ST")
            // 重新抛出后 handler 不收 CancellationException，不留 THROW。
        } catch (e: Exception) {
            state = state.copy(
                isGroupInviteBusy = false,
                errorMessage = e.message ?: invText207c(ContactsMessage.OPERATION_FAILED),
            )
            snap("ST")
        }
    }
    for (op in c.ops) {
        when (op.kind) {
            0 -> loadInvites(op.loadMode, op.loadPage)
            1 -> mutate(op.inviteId, true, op)
            else -> mutate(op.inviteId, false, op)
        }
        snap("FIN")
    }
    return effects.joinToString(",")
}

private fun invProd207c(c: InvCase207c): String {
    val effects = mutableListOf<String>()
    var state = ContactsUiState()
    var sessCalls = 0
    fun snap(prefix: String) {
        effects += prefix + ":" + invState207c(state)
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
    var curOp: InvOp207c? = null
    fun loaderCall(op: InvOp207c): Result<List<GroupInvitationDto>> {
        effects += "CALL:loader:" + op.loadMode
        return when (op.loadMode) {
            0 -> Result.success(op.loadPage.map {
                GroupInvitationDto(
                    id = it.id, chatId = it.chatId, userId = "", inviterName = it.inviterName,
                    chatName = it.chatName, memberCount = it.memberCount, createdAt = it.createdAt,
                )
            })
            1 -> Result.failure(RuntimeException("boom"))
            2 -> Result.failure(RuntimeException())
            3 -> throw RuntimeException("boom")
            4 -> throw RuntimeException()
            else -> throw CancellationException("cancel-boom")
        }
    }
    fun mutCall(accept: Boolean, inviteId: String, op: InvOp207c): Result<GroupInviteAcceptResponse> {
        effects += "CALL:" + (if (accept) "accept:" else "decline:") + encStr207c(inviteId)
        return when (op.mutMode) {
            0 -> Result.success(GroupInviteAcceptResponse())
            1 -> Result.failure(RuntimeException("boom"))
            2 -> Result.failure(RuntimeException())
            3 -> throw RuntimeException("boom")
            4 -> throw RuntimeException()
            else -> throw CancellationException("cancel-boom")
        }
    }
    val controller = ContactsGroupInviteController(
        scope = scope,
        updateState = { transform ->
            state = transform(state)
            snap("ST")
        },
        isGroupInviteBusy = { c.busy || state.isGroupInviteBusy },
        hasSession = { c.hasSession },
        sessionOwnerUserId = { c.owner },
        sessionMayContinue = {
            val idx = sessCalls++
            idx < c.sessFlipAfter
        },
        text = { msg, _ -> invText207c(msg) },
        groupInviteLoader = { loaderCall(curOp!!) },
        groupInviteAcceptor = { inviteId -> mutCall(true, inviteId, curOp!!) },
        groupInviteDecliner = { inviteId -> mutCall(false, inviteId, curOp!!) },
        onInviteAccepted = { effects += "CALLBACK:accepted" },
    )
    for (op in c.ops) {
        curOp = op
        try {
            when (op.kind) {
                0 -> controller.loadGroupInvites()
                1 -> controller.acceptGroupInvite(op.inviteId)
                else -> controller.declineGroupInvite(op.inviteId)
            }
        } catch (e: Exception) {
            effects += "THROW:" + e.javaClass.simpleName
        }
        drain()
        snap("FIN")
    }
    return effects.joinToString(",")
}

private val INV_IDS207c = listOf("", " ", "i1", "i2", "u1", "x\ty", "ünïcödé")

private val INV_CHATS207c = listOf("g1", "g2", "", "x\ty")

private val INV_NAMES207c = listOf("", "  ", "Group", "x\ty", "ünïcödé")

private fun randomInvInvite207c(r: Random): InvInvite207c = InvInvite207c(
    id = INV_IDS207c[r.nextInt(INV_IDS207c.size)],
    chatId = INV_CHATS207c[r.nextInt(INV_CHATS207c.size)],
    chatName = INV_NAMES207c[r.nextInt(INV_NAMES207c.size)],
    inviterName = listOf("Ann", "", "u1")[r.nextInt(3)],
    memberCount = listOf(0, 1, 5, 100)[r.nextInt(4)],
    createdAt = listOf(0L, 1L, 1_700_000_000_000L, -5L)[r.nextInt(4)],
)

private fun randomInvOp207c(r: Random): InvOp207c {
    val kind = listOf(0, 0, 1, 1, 2)[r.nextInt(5)]
    return InvOp207c(
        kind = kind,
        inviteId = INV_IDS207c[r.nextInt(INV_IDS207c.size)],
        mutMode = r.nextInt(6),
        loadMode = r.nextInt(6),
        loadPage = List(r.nextInt(0, 4)) { randomInvInvite207c(r) },
    )
}

private fun randomInvCase207c(r: Random): InvCase207c = InvCase207c(
    hasSession = r.nextInt(5) != 0,
    owner = listOf("u1", "", " ", "me", "other")[r.nextInt(5)],
    sessFlipAfter = listOf(0, 1, 2, 3, 5, 999)[r.nextInt(6)],
    busy = r.nextInt(5) == 0,
    ops = List(r.nextInt(0, 9)) { randomInvOp207c(r) },
)

private fun boundaryInvCase207c(r: Random): InvCase207c {
    val base = InvCase207c()
    val one = InvInvite207c("i1", "g1", "Group", "Ann", 5, 1000L)
    val two = InvInvite207c("i2", "g1", "Group2", "Bob", 3, 1001L)
    return when (r.nextInt(24)) {
        0 -> base.copy(ops = listOf(InvOp207c(0, loadPage = listOf(one))))
        1 -> base.copy(ops = listOf(InvOp207c(0, loadPage = emptyList())))
        2 -> base.copy(ops = listOf(InvOp207c(0, loadMode = 1)))
        3 -> base.copy(ops = listOf(InvOp207c(0, loadMode = 2)))
        4 -> base.copy(ops = listOf(InvOp207c(0, loadMode = 3)))
        5 -> base.copy(ops = listOf(InvOp207c(0, loadMode = 4)))
        6 -> base.copy(ops = listOf(InvOp207c(0, loadMode = 5)))
        7 -> base.copy(ops = listOf(InvOp207c(0, loadPage = listOf(one))), sessFlipAfter = 0)
        8 -> base.copy(ops = listOf(InvOp207c(0, loadPage = listOf(one))), sessFlipAfter = 1)
        9 -> base.copy(ops = listOf(InvOp207c(0, loadPage = listOf(one))), hasSession = false)
        10 -> base.copy(ops = listOf(InvOp207c(0, loadPage = listOf(one))), owner = "")
        11 -> base.copy(ops = listOf(InvOp207c(0, loadPage = listOf(one))), owner = " ")
        12 -> base.copy(ops = listOf(
            InvOp207c(0, loadPage = listOf(one, two)),
            InvOp207c(1, inviteId = "i1"),
        ))
        13 -> base.copy(ops = listOf(
            InvOp207c(0, loadPage = listOf(one)),
            InvOp207c(1, inviteId = "i1", loadMode = 0, loadPage = listOf(two)),
        ))
        14 -> base.copy(ops = listOf(InvOp207c(1, inviteId = "i1", mutMode = 1)))
        15 -> base.copy(ops = listOf(InvOp207c(1, inviteId = "i1", mutMode = 5)))
        16 -> base.copy(ops = listOf(InvOp207c(1, inviteId = "i1")), busy = true)
        17 -> base.copy(ops = listOf(
            InvOp207c(0, loadPage = listOf(one)),
            InvOp207c(2, inviteId = "i1"),
        ))
        18 -> base.copy(ops = listOf(InvOp207c(1, inviteId = "i1")), sessFlipAfter = 1)
        19 -> base.copy(ops = listOf(InvOp207c(1, inviteId = "i1")), sessFlipAfter = 0)
        20 -> base.copy(ops = listOf(
            InvOp207c(0, loadPage = listOf(one.copy(chatName = ""))),
        ))
        21 -> base.copy(ops = listOf(
            InvOp207c(0, loadPage = listOf(one, two)),
            InvOp207c(2, inviteId = "i2", mutMode = 2),
        ))
        22 -> base.copy(ops = listOf(InvOp207c(1, inviteId = "i1", mutMode = 3)))
        else -> base.copy(ops = emptyList())
    }
}

// ---------- tests ----------

class ContactsGroupInviteControllerFuzzTest {

    @Test
    fun randomCases() {
        val r = Random(207305)
        repeat(300) {
            val c = randomInvCase207c(r)
            assertEquals(invOracle207c(c), invProd207c(c))
        }
    }

    @Test
    fun boundaryCases() {
        val r = Random(207306)
        repeat(200) {
            val c = boundaryInvCase207c(r)
            assertEquals(invOracle207c(c), invProd207c(c))
        }
    }

    @Test
    fun pinnedCases() {
        val one = InvInvite207c("i1", "g1", "Group", "Ann", 5, 1000L)
        val two = InvInvite207c("i2", "g1", "Group2", "Bob", 3, 1001L)
        val cases = listOf(
            InvCase207c(ops = listOf(InvOp207c(0, loadPage = listOf(one)))),
            InvCase207c(ops = listOf(InvOp207c(0, loadMode = 3))),
            InvCase207c(ops = listOf(InvOp207c(0, loadMode = 5))),
            InvCase207c(ops = listOf(
                InvOp207c(0, loadPage = listOf(one, two)),
                InvOp207c(1, inviteId = "i1"),
            )),
            InvCase207c(ops = listOf(InvOp207c(1, inviteId = "i1", mutMode = 1))),
            InvCase207c(ops = listOf(
                InvOp207c(0, loadPage = listOf(one)),
                InvOp207c(2, inviteId = "i1"),
            )),
            InvCase207c(ops = listOf(InvOp207c(1, inviteId = "i1")), busy = true),
            InvCase207c(ops = listOf(
                InvOp207c(0, loadPage = listOf(one.copy(chatName = ""))),
            )),
            InvCase207c(ops = listOf(InvOp207c(0, loadPage = listOf(one))), sessFlipAfter = 0),
            InvCase207c(ops = listOf(InvOp207c(1, inviteId = "i1")), sessFlipAfter = 1),
            InvCase207c(ops = emptyList()),
        )
        for (c in cases) {
            assertEquals(invOracle207c(c), invProd207c(c))
        }
    }
}
