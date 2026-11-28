package com.maodouchat.domain.contacts

import com.maodouchat.core.model.User
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
private fun encStr208a(s: String): String {
    val out = StringBuilder()
    for (ch in s) out.append("%04x".format(ch.code))
    return out.toString()
}

private fun bit208a(b: Boolean): String = if (b) "1" else "0"

private fun opt208a(s: String?): String = s?.let { encStr208a(it) } ?: "~"

private data class FrnUser208a(
    val id: String,
    val name: String,
)

private data class FrnItem208a(
    val id: String,
    val userId: String,
    val userName: String,
    val message: String,
    val createdAt: Long,
    val outgoing: Boolean,
)

// op kind：0 load，1 accept，2 reject，3 cancel，4 removeFriend，5 blockUser，
// 6 sendFriendRequest，7 acceptAll，8 rejectAll，9 setNickname。
// mode：0 成功、1 失败 Result 带消息、2 失败 Result 空消息、
// 3 抛 RuntimeException 带消息、4 抛 RuntimeException 空消息、5 抛 CancellationException。
// loadMode / loadPage / loadPageOut 控制单步与批量成功后嵌套重载的那一次 load 调用。
private data class FrnOp208a(
    val kind: Int,
    val requestId: String = "r1",
    val user: FrnUser208a = FrnUser208a("u1", "Ann"),
    val message: String = "",
    val nickname: String = "",
    val mode: Int = 0,
    val loadMode: Int = 0,
    val loadPage: List<FrnItem208a> = emptyList(),
    val loadPageOut: List<FrnItem208a> = emptyList(),
    val batchModes: List<Int> = emptyList(),
)

private data class FrnCase208a(
    val busy: Boolean = false,
    val enabled: Boolean = true,
    val batchIds: List<String> = listOf("r1", "r2"),
    val ops: List<FrnOp208a> = emptyList(),
)

private fun frnText208a(msg: ContactsMessage, args: Array<out Any>): String = when (msg) {
    ContactsMessage.CONTACTS_FRIEND_ACCEPTED -> "friend-accepted"
    ContactsMessage.CONTACTS_FRIEND_REJECTED -> "friend-rejected"
    ContactsMessage.CONTACTS_FRIEND_REMOVED -> "friend-removed"
    ContactsMessage.CONTACTS_FRIEND_BLOCKED -> "friend-blocked:" + args[0]
    ContactsMessage.CONTACTS_FRIEND_REQUEST_SENT -> "friend-request-sent"
    ContactsMessage.CONTACTS_FRIEND_REQUEST_FAILED -> "friend-request-failed"
    ContactsMessage.CONTACTS_FRIEND_ACCEPTED_ALL -> "friend-accepted-all"
    ContactsMessage.CONTACTS_FRIEND_REJECTED_ALL -> "friend-rejected-all"
    ContactsMessage.CONTACTS_FRIEND_BATCH_PARTIAL -> "batch-partial:" + args[0] + "/" + args[1]
    ContactsMessage.CONTACTS_NICKNAME_SAVED -> "nickname-saved"
    ContactsMessage.FRIEND_REQUESTS_DISABLED -> "friend-requests-disabled"
    ContactsMessage.OPERATION_FAILED -> "operation-failed"
    else -> "txt-" + msg.name
}

private fun frnEncItem208a(item: FriendRequestItem): String = listOf(
    encStr208a(item.id),
    encStr208a(item.user.id),
    encStr208a(item.user.name),
    encStr208a(item.message),
    item.createdAt.toString(),
    if (item.outgoing) "1" else "0",
).joinToString(":")

private fun frnState208a(state: ContactsUiState): String = listOf(
    bit208a(state.isFriendActionBusy),
    opt208a(state.errorMessage),
    opt208a(state.infoMessage),
    state.incomingRequests.joinToString(";") { frnEncItem208a(it) },
    state.outgoingRequests.joinToString(";") { frnEncItem208a(it) },
).joinToString("|")

private fun frnItem208a(v: FrnItem208a): FriendRequestItem = FriendRequestItem(
    id = v.id,
    user = User(id = v.userId, name = v.userName),
    message = v.message,
    createdAt = v.createdAt,
    outgoing = v.outgoing,
)

private fun frnOracle208a(c: FrnCase208a): String {
    val effects = mutableListOf<String>()
    var state = ContactsUiState()
    fun snap(prefix: String) {
        effects += prefix + ":" + frnState208a(state)
    }
    fun loadRequests(op: FrnOp208a): Result<FriendRequestsSnapshot> {
        effects += "CALL:load:" + op.loadMode
        return when (op.loadMode) {
            0 -> Result.success(
                FriendRequestsSnapshot(
                    incoming = op.loadPage.map { frnItem208a(it) },
                    outgoing = op.loadPageOut.map { frnItem208a(it) },
                )
            )
            1 -> Result.failure(RuntimeException("boom"))
            2 -> Result.failure(RuntimeException())
            3 -> throw RuntimeException("boom")
            4 -> throw RuntimeException()
            else -> throw CancellationException("cancel-boom")
        }
    }
    fun loadFriendRequests(op: FrnOp208a) {
        try {
            val result = loadRequests(op)
            result.onSuccess { snapshot ->
                state = state.copy(
                    incomingRequests = snapshot.incoming,
                    outgoingRequests = snapshot.outgoing,
                )
                snap("ST")
            }
        } catch (e: CancellationException) {
            // 未捕获的 CancellationException 不进 handler，不留 THROW。
        } catch (e: Exception) {
            effects += "THROW:" + e.javaClass.simpleName
        }
    }
    fun singleResult(label: String, mode: Int): Result<Unit> {
        if (label.isNotEmpty()) effects += "CALL:" + label
        return when (mode) {
            0 -> Result.success(Unit)
            1 -> Result.failure(RuntimeException("boom"))
            2 -> Result.failure(RuntimeException())
            3 -> throw RuntimeException("boom")
            4 -> throw RuntimeException()
            else -> throw CancellationException("cancel-boom")
        }
    }
    fun batchResult(ids: List<String>, modes: List<Int>): Map<String, Result<Unit>> {
        effects += "CALL:batch:" + ids.joinToString(";") { encStr208a(it) } +
            ":" + modes.joinToString(";")
        return ids.mapIndexed { index, id ->
            val mode = modes.getOrElse(index) { 0 }
            id to when (mode) {
                0 -> Result.success(Unit)
                1 -> Result.failure(RuntimeException("boom"))
                2 -> Result.failure(RuntimeException())
                3 -> throw RuntimeException("boom")
                4 -> throw RuntimeException()
                else -> throw CancellationException("cancel-boom")
            }
        }.toMap()
    }
    fun launchFriendAction(
        op: FrnOp208a,
        successMessage: String? = null,
        fallbackErrorMessage: String? = null,
        refreshRequests: Boolean = false,
        refreshContacts: Boolean = false,
        action: () -> Result<*>,
    ) {
        if (c.busy || state.isFriendActionBusy) return
        state = state.copy(isFriendActionBusy = true, errorMessage = null, infoMessage = null)
        snap("ST")
        try {
            action().fold(
                onSuccess = {
                    state = state.copy(isFriendActionBusy = false, infoMessage = successMessage)
                    snap("ST")
                    // 嵌套 loadFriendRequests 是 scope.launch 的异步子协程，
                    // 同步的 reloadContacts 回调先跑，重载效果随后才到。
                    if (refreshContacts) effects += "CALLBACK:reload-contacts"
                    if (refreshRequests) loadFriendRequests(op)
                },
                onFailure = { error ->
                    state = state.copy(
                        isFriendActionBusy = false,
                        errorMessage = error.message ?: fallbackErrorMessage
                            ?: frnText208a(ContactsMessage.OPERATION_FAILED, emptyArray()),
                    )
                    snap("ST")
                },
            )
        } catch (e: CancellationException) {
            state = state.copy(isFriendActionBusy = false)
            snap("ST")
        } catch (e: Exception) {
            state = state.copy(
                isFriendActionBusy = false,
                errorMessage = e.message ?: fallbackErrorMessage
                    ?: frnText208a(ContactsMessage.OPERATION_FAILED, emptyArray()),
            )
            snap("ST")
        }
    }
    fun launchFriendBatchAction(
        op: FrnOp208a,
        allSucceededMessage: String,
        refreshContacts: Boolean = false,
        action: (ids: List<String>) -> Map<String, Result<*>>,
    ) {
        val ids = c.batchIds
        if (ids.isEmpty() || c.busy || state.isFriendActionBusy) return
        state = state.copy(isFriendActionBusy = true, errorMessage = null, infoMessage = null)
        snap("ST")
        try {
            val results = action(ids)
            val successCount = results.values.count { it.isSuccess }
            val failedCount = results.values.count { it.isFailure }
            state = state.copy(
                isFriendActionBusy = false,
                infoMessage = if (failedCount == 0) {
                    allSucceededMessage
                } else {
                    frnText208a(
                        ContactsMessage.CONTACTS_FRIEND_BATCH_PARTIAL,
                        arrayOf(successCount, failedCount),
                    )
                },
            )
            snap("ST")
            // 与 launchFriendAction 同理：同步回调先于异步重载。
            if (refreshContacts) effects += "CALLBACK:reload-contacts"
            loadFriendRequests(op)
        } catch (e: CancellationException) {
            state = state.copy(isFriendActionBusy = false)
            snap("ST")
        } catch (e: Exception) {
            state = state.copy(
                isFriendActionBusy = false,
                errorMessage = e.message ?: frnText208a(ContactsMessage.OPERATION_FAILED, emptyArray()),
            )
            snap("ST")
        }
    }
    for (op in c.ops) {
        when (op.kind) {
            0 -> loadFriendRequests(op)
            1 -> launchFriendAction(
                op,
                successMessage = frnText208a(ContactsMessage.CONTACTS_FRIEND_ACCEPTED, emptyArray()),
                refreshRequests = true,
                refreshContacts = true,
            ) { singleResult("accept:" + encStr208a(op.requestId), op.mode) }
            2 -> launchFriendAction(
                op,
                successMessage = frnText208a(ContactsMessage.CONTACTS_FRIEND_REJECTED, emptyArray()),
                refreshRequests = true,
            ) { singleResult("reject:" + encStr208a(op.requestId), op.mode) }
            3 -> launchFriendAction(
                op,
                refreshRequests = true,
            ) { singleResult("cancel:" + encStr208a(op.requestId), op.mode) }
            4 -> launchFriendAction(
                op,
                successMessage = frnText208a(ContactsMessage.CONTACTS_FRIEND_REMOVED, emptyArray()),
            ) { singleResult("remove:" + encStr208a(op.user.id), op.mode) }
            5 -> launchFriendAction(
                op,
                successMessage = frnText208a(
                    ContactsMessage.CONTACTS_FRIEND_BLOCKED,
                    arrayOf(User(op.user.id, op.user.name).displayName),
                ),
            ) { singleResult("block:" + encStr208a(op.user.id), op.mode) }
            6 -> {
                if (!c.enabled) {
                    state = state.copy(
                        errorMessage = frnText208a(ContactsMessage.FRIEND_REQUESTS_DISABLED, emptyArray()),
                    )
                    snap("ST")
                } else {
                    launchFriendAction(
                        op,
                        successMessage = frnText208a(ContactsMessage.CONTACTS_FRIEND_REQUEST_SENT, emptyArray()),
                        fallbackErrorMessage = frnText208a(ContactsMessage.CONTACTS_FRIEND_REQUEST_FAILED, emptyArray()),
                        refreshRequests = true,
                    ) {
                        effects += "CALL:send:" + encStr208a(op.user.id) + ":" +
                            encStr208a(op.message.take(300))
                        singleResult("", op.mode).let { it }
                    }
                }
            }
            7 -> launchFriendBatchAction(
                op,
                allSucceededMessage = frnText208a(ContactsMessage.CONTACTS_FRIEND_ACCEPTED_ALL, emptyArray()),
                refreshContacts = true,
            ) { ids -> batchResult(ids, op.batchModes) }
            8 -> launchFriendBatchAction(
                op,
                allSucceededMessage = frnText208a(ContactsMessage.CONTACTS_FRIEND_REJECTED_ALL, emptyArray()),
            ) { ids -> batchResult(ids, op.batchModes) }
            else -> {
                // setContactNickname：无 try/catch，抛错直接进 handler。
                try {
                    val result = run {
                        effects += "CALL:nick:" + encStr208a(op.user.id) + ":" +
                            encStr208a(op.nickname)
                        singleResult("", op.mode)
                    }
                    result.fold(
                        onSuccess = {
                            state = state.copy(
                                infoMessage = frnText208a(ContactsMessage.CONTACTS_NICKNAME_SAVED, emptyArray()),
                            )
                            snap("ST")
                        },
                        onFailure = { error ->
                            state = state.copy(
                                errorMessage = error.message
                                    ?: frnText208a(ContactsMessage.OPERATION_FAILED, emptyArray()),
                            )
                            snap("ST")
                        },
                    )
                } catch (e: CancellationException) {
                    // 未捕获的 CancellationException 不进 handler，不留 THROW。
                } catch (e: Exception) {
                    effects += "THROW:" + e.javaClass.simpleName
                }
            }
        }
        snap("FIN")
    }
    return effects.joinToString(",")
}

private fun frnProd208a(c: FrnCase208a): String {
    val effects = mutableListOf<String>()
    var state = ContactsUiState()
    fun snap(prefix: String) {
        effects += prefix + ":" + frnState208a(state)
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
    var curOp: FrnOp208a? = null
    fun loadResult(op: FrnOp208a): Result<FriendRequestsSnapshot> {
        effects += "CALL:load:" + op.loadMode
        return when (op.loadMode) {
            0 -> Result.success(
                FriendRequestsSnapshot(
                    incoming = op.loadPage.map { frnItem208a(it) },
                    outgoing = op.loadPageOut.map { frnItem208a(it) },
                )
            )
            1 -> Result.failure(RuntimeException("boom"))
            2 -> Result.failure(RuntimeException())
            3 -> throw RuntimeException("boom")
            4 -> throw RuntimeException()
            else -> throw CancellationException("cancel-boom")
        }
    }
    fun singleProd(label: String, mode: Int): Result<Unit> {
        if (label.isNotEmpty()) effects += "CALL:" + label
        return when (mode) {
            0 -> Result.success(Unit)
            1 -> Result.failure(RuntimeException("boom"))
            2 -> Result.failure(RuntimeException())
            3 -> throw RuntimeException("boom")
            4 -> throw RuntimeException()
            else -> throw CancellationException("cancel-boom")
        }
    }
    fun batchProd(ids: List<String>, modes: List<Int>): Map<String, Result<Unit>> {
        effects += "CALL:batch:" + ids.joinToString(";") { encStr208a(it) } +
            ":" + modes.joinToString(";")
        return ids.mapIndexed { index, id ->
            val mode = modes.getOrElse(index) { 0 }
            id to when (mode) {
                0 -> Result.success(Unit)
                1 -> Result.failure(RuntimeException("boom"))
                2 -> Result.failure(RuntimeException())
                3 -> throw RuntimeException("boom")
                4 -> throw RuntimeException()
                else -> throw CancellationException("cancel-boom")
            }
        }.toMap()
    }
    val friendRequestUseCase = object : FriendRequestUseCase {
        override suspend fun loadRequests(): Result<FriendRequestsSnapshot> =
            loadResult(curOp!!)
        override suspend fun sendFriendRequest(targetUserId: String, note: String): Result<FriendRequestItem> {
            effects += "CALL:send:" + encStr208a(targetUserId) + ":" + encStr208a(note)
            return when (curOp!!.mode) {
                0 -> Result.success(FriendRequestItem("r1", User(targetUserId, "Ann")))
                1 -> Result.failure(RuntimeException("boom"))
                2 -> Result.failure(RuntimeException())
                3 -> throw RuntimeException("boom")
                4 -> throw RuntimeException()
                else -> throw CancellationException("cancel-boom")
            }
        }
        override suspend fun acceptFriendRequest(requestId: String): Result<FriendRequestItem?> {
            effects += "CALL:accept:" + encStr208a(requestId)
            return when (curOp!!.mode) {
                0 -> Result.success(FriendRequestItem(requestId, User("u1", "Ann")))
                1 -> Result.failure(RuntimeException("boom"))
                2 -> Result.failure(RuntimeException())
                3 -> throw RuntimeException("boom")
                4 -> throw RuntimeException()
                else -> throw CancellationException("cancel-boom")
            }
        }
        override suspend fun rejectFriendRequest(requestId: String): Result<Unit> =
            singleProd("reject:" + encStr208a(requestId), curOp!!.mode)
        override suspend fun cancelFriendRequest(requestId: String): Result<Unit> =
            singleProd("cancel:" + encStr208a(requestId), curOp!!.mode)
        override suspend fun batchAcceptFriendRequests(requestIds: List<String>): Map<String, Result<FriendRequestItem?>> {
            val base = batchProd(requestIds, curOp!!.batchModes)
            return base.mapValues { (_, v) ->
                v.fold(
                    onSuccess = { Result.success(FriendRequestItem("r1", User("u1", "Ann"))) },
                    onFailure = { Result.failure<FriendRequestItem?>(it) },
                )
            }
        }
        override suspend fun batchRejectFriendRequests(requestIds: List<String>): Map<String, Result<Unit>> =
            batchProd(requestIds, curOp!!.batchModes)
    }
    val contactMutationUseCase = object : ContactMutationUseCase {
        override suspend fun setNickname(userId: String, nickname: String): Result<Unit> {
            effects += "CALL:nick:" + encStr208a(userId) + ":" + encStr208a(nickname)
            return singleProd("", curOp!!.mode)
        }
        override suspend fun removeFriend(userId: String): Result<Unit> =
            singleProd("remove:" + encStr208a(userId), curOp!!.mode)
        override suspend fun blockUser(userId: String): Result<Unit> =
            singleProd("block:" + encStr208a(userId), curOp!!.mode)
        override suspend fun unblockUser(userId: String): Result<Unit> = Result.success(Unit)
    }
    val controller = ContactsFriendActionController(
        scope = scope,
        updateState = { transform ->
            state = transform(state)
            snap("ST")
        },
        isFriendActionBusy = { c.busy || state.isFriendActionBusy },
        incomingRequestIds = { c.batchIds },
        text = { msg, args -> frnText208a(msg, args) },
        friendRequestUseCase = friendRequestUseCase,
        contactMutationUseCase = contactMutationUseCase,
        friendRequestsEnabled = { c.enabled },
        reloadContacts = { effects += "CALLBACK:reload-contacts" },
    )
    for (op in c.ops) {
        curOp = op
        try {
            when (op.kind) {
                0 -> controller.loadFriendRequests()
                1 -> controller.acceptFriendRequest(op.requestId)
                2 -> controller.rejectFriendRequest(op.requestId)
                3 -> controller.cancelFriendRequest(op.requestId)
                4 -> controller.removeFriend(User(op.user.id, op.user.name))
                5 -> controller.blockUser(User(op.user.id, op.user.name))
                6 -> controller.sendFriendRequest(User(op.user.id, op.user.name), op.message)
                7 -> controller.acceptAllFriendRequests()
                8 -> controller.rejectAllFriendRequests()
                else -> controller.setContactNickname(User(op.user.id, op.user.name), op.nickname)
            }
        } catch (e: Exception) {
            effects += "THROW:" + e.javaClass.simpleName
        }
        drain()
        snap("FIN")
    }
    return effects.joinToString(",")
}

private val FRN_IDS208a = listOf("", " ", "r1", "r2", "u1", "x\ty")

private val FRN_USERS208a = listOf(
    FrnUser208a("u1", "Ann"),
    FrnUser208a("u2", ""),
    FrnUser208a("u1", "x\ty"),
    FrnUser208a("", "Bob"),
)

private val FRN_MSGS208a = listOf("", "hi", "a".repeat(350), "x\ty", "ünïcödé")

private val FRN_NICKS208a = listOf("", "  Nick  ", "x", "ünïcödé")

private fun randomFrnItem208a(r: Random): FrnItem208a = FrnItem208a(
    id = FRN_IDS208a[r.nextInt(FRN_IDS208a.size)],
    userId = listOf("u1", "u2", "", "x\ty")[r.nextInt(4)],
    userName = listOf("Ann", "", "Bob")[r.nextInt(3)],
    message = FRN_MSGS208a[r.nextInt(FRN_MSGS208a.size)],
    createdAt = listOf(0L, 1L, 1_700_000_000_000L, -5L)[r.nextInt(4)],
    outgoing = r.nextInt(2) == 0,
)

private fun randomFrnOp208a(r: Random): FrnOp208a {
    val kind = listOf(0, 1, 1, 2, 2, 3, 4, 5, 6, 7, 8, 9)[r.nextInt(12)]
    return FrnOp208a(
        kind = kind,
        requestId = FRN_IDS208a[r.nextInt(FRN_IDS208a.size)],
        user = FRN_USERS208a[r.nextInt(FRN_USERS208a.size)],
        message = FRN_MSGS208a[r.nextInt(FRN_MSGS208a.size)],
        nickname = FRN_NICKS208a[r.nextInt(FRN_NICKS208a.size)],
        mode = r.nextInt(6),
        loadMode = r.nextInt(6),
        loadPage = List(r.nextInt(0, 4)) { randomFrnItem208a(r) },
        loadPageOut = List(r.nextInt(0, 3)) { randomFrnItem208a(r) },
        batchModes = List(r.nextInt(0, 4)) { r.nextInt(6) },
    )
}

private fun randomFrnCase208a(r: Random): FrnCase208a = FrnCase208a(
    busy = r.nextInt(5) == 0,
    enabled = r.nextInt(4) != 0,
    batchIds = List(r.nextInt(0, 4)) { FRN_IDS208a[r.nextInt(FRN_IDS208a.size)] },
    ops = List(r.nextInt(0, 9)) { randomFrnOp208a(r) },
)

private fun boundaryFrnCase208a(r: Random): FrnCase208a {
    val base = FrnCase208a()
    val one = FrnItem208a("r1", "u1", "Ann", "hi", 1000L, false)
    val two = FrnItem208a("r2", "u2", "Bob", "", 1001L, true)
    return when (r.nextInt(24)) {
        0 -> base.copy(ops = listOf(FrnOp208a(0, loadPage = listOf(one))))
        1 -> base.copy(ops = listOf(FrnOp208a(0, loadMode = 1)))
        2 -> base.copy(ops = listOf(FrnOp208a(0, loadMode = 2)))
        3 -> base.copy(ops = listOf(FrnOp208a(0, loadMode = 3)))
        4 -> base.copy(ops = listOf(FrnOp208a(0, loadMode = 5)))
        5 -> base.copy(ops = listOf(FrnOp208a(1, requestId = "r1")))
        6 -> base.copy(ops = listOf(FrnOp208a(1, requestId = "r1", mode = 1)))
        7 -> base.copy(ops = listOf(FrnOp208a(1, requestId = "r1", mode = 5)))
        8 -> base.copy(ops = listOf(FrnOp208a(2, requestId = "r1")))
        9 -> base.copy(ops = listOf(FrnOp208a(3, requestId = "r1")))
        10 -> base.copy(ops = listOf(FrnOp208a(4, user = FrnUser208a("u1", "Ann"))))
        11 -> base.copy(ops = listOf(FrnOp208a(5, user = FrnUser208a("u1", ""))))
        12 -> base.copy(ops = listOf(FrnOp208a(6, user = FrnUser208a("u1", "Ann"), message = "hi")), enabled = false)
        13 -> base.copy(ops = listOf(FrnOp208a(6, user = FrnUser208a("u1", "Ann"), message = "a".repeat(350))))
        14 -> base.copy(ops = listOf(FrnOp208a(6, user = FrnUser208a("u1", "Ann"), message = "hi", mode = 1)))
        15 -> base.copy(ops = listOf(FrnOp208a(6, user = FrnUser208a("u1", "Ann"), message = "hi", mode = 5)))
        16 -> base.copy(ops = listOf(FrnOp208a(7, batchModes = listOf(0, 0))))
        17 -> base.copy(ops = listOf(FrnOp208a(7, batchModes = listOf(0, 1))))
        18 -> base.copy(ops = listOf(FrnOp208a(7)), batchIds = emptyList())
        19 -> base.copy(ops = listOf(FrnOp208a(8, batchModes = listOf(1, 2))))
        20 -> base.copy(ops = listOf(FrnOp208a(9, user = FrnUser208a("u1", "Ann"), nickname = "  Nick  ")))
        21 -> base.copy(ops = listOf(FrnOp208a(9, user = FrnUser208a("u1", "Ann"), nickname = "x", mode = 2)))
        22 -> base.copy(ops = listOf(FrnOp208a(9, user = FrnUser208a("u1", "Ann"), nickname = "x", mode = 3)))
        else -> base.copy(ops = listOf(FrnOp208a(1, requestId = "r1")), busy = true)
    }
}

// ---------- tests ----------

class ContactsFriendActionControllerFuzzTest {

    @Test
    fun randomCases() {
        val r = Random(208105)
        repeat(300) {
            val c = randomFrnCase208a(r)
            assertEquals(frnOracle208a(c), frnProd208a(c))
        }
    }

    @Test
    fun boundaryCases() {
        val r = Random(208106)
        repeat(200) {
            val c = boundaryFrnCase208a(r)
            assertEquals(frnOracle208a(c), frnProd208a(c))
        }
    }

    @Test
    fun pinnedCases() {
        val one = FrnItem208a("r1", "u1", "Ann", "hi", 1000L, false)
        val cases = listOf(
            FrnCase208a(ops = listOf(FrnOp208a(0, loadPage = listOf(one)))),
            FrnCase208a(ops = listOf(FrnOp208a(0, loadMode = 3))),
            FrnCase208a(ops = listOf(FrnOp208a(0, loadMode = 5))),
            FrnCase208a(ops = listOf(FrnOp208a(1, requestId = "r1"))),
            FrnCase208a(ops = listOf(FrnOp208a(1, requestId = "r1", mode = 1))),
            FrnCase208a(ops = listOf(FrnOp208a(5, user = FrnUser208a("u1", "")))),
            FrnCase208a(ops = listOf(FrnOp208a(6, user = FrnUser208a("u1", "Ann"), message = "a".repeat(350))), enabled = false),
            FrnCase208a(ops = listOf(FrnOp208a(7, batchModes = listOf(0, 1)))),
            FrnCase208a(ops = listOf(FrnOp208a(7)), batchIds = emptyList()),
            FrnCase208a(ops = listOf(FrnOp208a(9, user = FrnUser208a("u1", "Ann"), nickname = "  Nick  "))),
            FrnCase208a(ops = listOf(FrnOp208a(1, requestId = "r1")), busy = true),
            FrnCase208a(ops = emptyList()),
        )
        for (c in cases) {
            assertEquals(frnOracle208a(c), frnProd208a(c))
        }
    }
}
