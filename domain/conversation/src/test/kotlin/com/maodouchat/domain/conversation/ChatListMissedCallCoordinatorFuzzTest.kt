package com.maodouchat.domain.conversation

import com.maodouchat.core.model.Chat
import com.maodouchat.core.model.MissedCall
import com.maodouchat.core.model.User
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
private fun encStr204c(s: String): String {
    val out = StringBuilder()
    for (ch in s) out.append("%04x".format(ch.code))
    return out.toString()
}

private fun bit204c(b: Boolean): String = if (b) "1" else "0"

// op kind: 0 markRead，1 clear，2 find，3 remove。
// markThrow/clearThrow/deleteThrow：0 成功 1 抛错；cancelMode：0 全成功 1 首个抛错 2 全抛错；
// centerMode：0 全成功 1 首个抛错 2 第二个抛错（不足两个则不抛）。
private data class MissOp204c(
    val kind: Int,
    val userId: String = "",
    val callId: String = "",
    val portThrow: Int = 0,
    val cancelMode: Int = 0,
    val centerMode: Int = 0,
)

private data class MissCall204c(
    val id: String,
    val callerId: String = "u9",
    val callerName: String = "Caller",
    val callType: String = "VOICE",
    val receivedAt: Long = 1000L,
    val isRead: Boolean = false,
)

private data class MissChat204c(
    val id: String,
    val isGroup: Boolean = false,
    val participantIds: List<String> = emptyList(),
)

private data class MissCase204c(
    val owner: String = "me",
    val sessionActive: Boolean = true,
    val missed: List<MissCall204c> = emptyList(),
    val chats: List<MissChat204c> = emptyList(),
    val ops: List<MissOp204c> = emptyList(),
)

private fun encMissCall204c(m: MissCall204c): String = listOf(
    encStr204c(m.id),
    encStr204c(m.callerId),
    encStr204c(m.callerName),
    encStr204c(m.callType),
    m.receivedAt.toString(),
    bit204c(m.isRead),
).joinToString(":")

private fun encMissed204c(list: List<MissCall204c>): String =
    list.joinToString(",") { encMissCall204c(it) }

private fun missOracle204c(c: MissCase204c): String {
    val effects = mutableListOf<String>()
    var missed = c.missed
    fun snap(prefix: String) {
        effects += prefix + ":" + encMissed204c(missed)
    }
    fun dismiss(snapshot: List<MissCall204c>, portThrow: Int, cancelMode: Int, centerMode: Int) {
        for ((idx, call) in snapshot.withIndex()) {
            effects += "CALL:cancel:" + encStr204c(call.id)
            if (cancelMode == 2 || (cancelMode == 1 && idx == 0)) {
                // 生产逐条 catch 吞掉，继续下一条
            }
        }
        try {
            for ((idx, call) in snapshot.withIndex()) {
                effects += "CALL:center:" + encStr204c("missed_" + call.id)
                if (centerMode == 1 && idx == 0) throw RuntimeException("boom")
                if (centerMode == 2 && idx == 1) throw RuntimeException("boom")
            }
        } catch (_: Exception) {
            effects += "LOG:missed-call center cleanup failed"
        }
    }
    for (op in c.ops) {
        when (op.kind) {
            0, 1 -> {
                if (c.owner.isBlank() || !c.sessionActive) {
                    // fall through to FIN
                } else if (!c.sessionActive) {
                    // fall through to FIN
                } else {
                    val snapshot = missed
                    if (op.kind == 0) {
                        effects += "CALL:markAllRead"
                        if (op.portThrow == 1) effects += "LOG:markMissedCallsRead failed"
                    } else {
                        effects += "CALL:clearAll"
                        if (op.portThrow == 1) effects += "LOG:clearMissedCalls failed"
                    }
                    if (c.sessionActive) {
                        dismiss(snapshot, op.portThrow, op.cancelMode, op.centerMode)
                    }
                }
            }
            2 -> {
                val found = if (op.userId.isBlank()) {
                    null
                } else {
                    c.chats.firstOrNull { chat ->
                        !chat.isGroup && chat.participantIds.any { it == op.userId }
                    }?.id
                }
                effects += "FIND:" + (found?.let { encStr204c(it) } ?: "~")
            }
            3 -> {
                if (op.callId.isBlank()) {
                    // fall through to FIN
                } else {
                    missed = missed.filterNot { it.id == op.callId }
                    if (c.owner.isBlank()) {
                        // fall through to FIN
                    } else if (!c.sessionActive) {
                        // fall through to FIN
                    } else {
                        effects += "CALL:deleteCall:" + encStr204c(op.callId)
                        effects += "CALL:cancel:" + encStr204c(op.callId)
                    }
                }
            }
            else -> throw IllegalArgumentException("unknown op ${op.kind}")
        }
        snap("FIN")
    }
    return effects.joinToString(",")
}

private fun missProd204c(c: MissCase204c): String {
    val effects = mutableListOf<String>()
    var missed = c.missed.map {
        MissedCall(it.id, it.callerId, it.callerName, it.callType, it.receivedAt, it.isRead)
    }
    val chats = c.chats.map {
        Chat(
            id = it.id,
            participants = it.participantIds.map { id -> User(id, id) },
            lastMessageTime = 0L,
            isGroup = it.isGroup,
        )
    }
    fun snap(prefix: String) {
        effects += prefix + ":" + encMissed204c(
            missed.map {
                MissCall204c(it.id, it.callerId, it.callerName, it.callType, it.receivedAt, it.isRead)
            },
        )
    }
    var curOp = MissOp204c(0)
    var cancelCallIdx = 0
    var centerCallIdx = 0
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
    fun cancelFake(callId: String, idx: Int) {
        effects += "CALL:cancel:" + encStr204c(callId)
        if (curOp.cancelMode == 2 || (curOp.cancelMode == 1 && idx == 0)) {
            throw RuntimeException("boom")
        }
    }
    val coordinator = ChatListMissedCallCoordinator(
        scope = scope,
        getMissedCalls = { missed },
        getChats = { chats },
        updateMissedCalls = { transform -> missed = transform(missed) },
        ownerUserId = { c.owner },
        markAllRead = {
            effects += "CALL:markAllRead"
            if (curOp.portThrow == 1) throw RuntimeException("boom")
        },
        clearAll = {
            effects += "CALL:clearAll"
            if (curOp.portThrow == 1) throw RuntimeException("boom")
        },
        deleteCall = { callId ->
            effects += "CALL:deleteCall:" + encStr204c(callId)
            if (curOp.portThrow == 1) throw RuntimeException("boom")
        },
        cancelMissedCallNotification = { callId ->
            // 生产在 dismiss 里逐条 try，remove 里 runCatching；idx 用调用计数近似：
            // dismiss 的 cancel 循环按快照顺序逐条调用，remove 只调一次。
            cancelFake(callId, cancelCallIdx++)
        },
        removeCenterItem = { id ->
            effects += "CALL:center:" + encStr204c(id)
            if (curOp.centerMode == 1 && centerCallIdx == 0) throw RuntimeException("boom")
            if (curOp.centerMode == 2 && centerCallIdx == 1) throw RuntimeException("boom")
            centerCallIdx++
        },
        sessionActive = { c.sessionActive },
        logWarning = { message, _ -> effects += "LOG:" + message },
    )
    for (op in c.ops) {
        curOp = op
        cancelCallIdx = 0
        centerCallIdx = 0
        try {
            when (op.kind) {
                0 -> coordinator.markMissedCallsRead()
                1 -> coordinator.clearMissedCalls()
                2 -> {
                    val found = coordinator.findDirectChatIdForUser(op.userId)
                    effects += "FIND:" + (found?.let { encStr204c(it) } ?: "~")
                }
                3 -> coordinator.removeMissedCallLocally(op.callId)
                else -> throw IllegalArgumentException("unknown op ${op.kind}")
            }
        } catch (e: Exception) {
            effects += "THROW:" + e.javaClass.simpleName
        }
        drain()
        snap("FIN")
    }
    return effects.joinToString(",")
}

private val MISS_IDS204c = listOf(
    "", " ", "c1", "c2", "c3", "u1", "u2", "me", "x\ty", "ünïcödé",
)

private fun randomMissCall204c(r: Random, id: String): MissCall204c =
    MissCall204c(
        id = id,
        callerId = MISS_IDS204c[r.nextInt(MISS_IDS204c.size)],
        callerName = listOf("", "Caller", "x\ty")[r.nextInt(3)],
        callType = listOf("VOICE", "VIDEO", "")[r.nextInt(3)],
        receivedAt = listOf(0L, 1000L, -5L)[r.nextInt(3)],
        isRead = r.nextBoolean(),
    )

private fun randomMissOp204c(r: Random): MissOp204c {
    val id = MISS_IDS204c[r.nextInt(MISS_IDS204c.size)]
    return when (r.nextInt(4)) {
        0 -> MissOp204c(
            0,
            portThrow = r.nextInt(2),
            cancelMode = r.nextInt(3),
            centerMode = r.nextInt(3),
        )
        1 -> MissOp204c(
            1,
            portThrow = r.nextInt(2),
            cancelMode = r.nextInt(3),
            centerMode = r.nextInt(3),
        )
        2 -> MissOp204c(2, userId = id)
        else -> MissOp204c(
            3,
            callId = id,
            portThrow = r.nextInt(2),
            cancelMode = r.nextInt(2),
        )
    }
}

private fun randomMissCase204c(r: Random): MissCase204c {
    val callIds = MISS_IDS204c.shuffled(r).take(r.nextInt(0, 5))
    val chatIds = MISS_IDS204c.shuffled(r).take(r.nextInt(0, 4))
    return MissCase204c(
        owner = listOf("", " ", "me", "u1")[r.nextInt(4)],
        sessionActive = r.nextBoolean(),
        missed = callIds.map { randomMissCall204c(r, it) },
        chats = chatIds.map {
            MissChat204c(
                id = it,
                isGroup = r.nextInt(4) == 0,
                participantIds = MISS_IDS204c.shuffled(r).take(r.nextInt(0, 4)),
            )
        },
        ops = List(r.nextInt(0, 9)) { randomMissOp204c(r) },
    )
}

private fun boundaryMissCase204c(r: Random): MissCase204c {
    val m1 = MissCall204c("c1")
    val m2 = MissCall204c("c2", callerId = "u2")
    val base = MissCase204c(missed = listOf(m1, m2))
    val chats = listOf(
        MissChat204c("chat1", participantIds = listOf("u1", "u2")),
        MissChat204c("chat2", isGroup = true, participantIds = listOf("u1")),
        MissChat204c("chat3", participantIds = listOf("u3")),
    )
    val cases = listOf(
        base.copy(ops = listOf(MissOp204c(0))),
        base.copy(ops = listOf(MissOp204c(0, portThrow = 1))),
        base.copy(ops = listOf(MissOp204c(0, cancelMode = 1))),
        base.copy(ops = listOf(MissOp204c(0, cancelMode = 2))),
        base.copy(ops = listOf(MissOp204c(0, centerMode = 1))),
        base.copy(ops = listOf(MissOp204c(0, centerMode = 2))),
        base.copy(ops = listOf(MissOp204c(0, cancelMode = 2, centerMode = 2))),
        base.copy(ops = listOf(MissOp204c(0)), sessionActive = false),
        base.copy(ops = listOf(MissOp204c(0)), owner = ""),
        base.copy(ops = listOf(MissOp204c(0)), missed = emptyList()),
        base.copy(ops = listOf(MissOp204c(1))),
        base.copy(ops = listOf(MissOp204c(1, portThrow = 1))),
        base.copy(ops = listOf(MissOp204c(1, cancelMode = 1, centerMode = 1))),
        base.copy(ops = listOf(MissOp204c(1)), sessionActive = false),
        base.copy(ops = listOf(MissOp204c(1)), owner = " "),
        base.copy(chats = chats, ops = listOf(MissOp204c(2, userId = "u1"))),
        base.copy(chats = chats, ops = listOf(MissOp204c(2, userId = "u3"))),
        base.copy(chats = chats, ops = listOf(MissOp204c(2, userId = "ghost"))),
        base.copy(chats = chats, ops = listOf(MissOp204c(2, userId = ""))),
        base.copy(chats = chats, ops = listOf(MissOp204c(2, userId = " "))),
        base.copy(ops = listOf(MissOp204c(2, userId = "u1"))),
        base.copy(ops = listOf(MissOp204c(3, callId = "c1"))),
        base.copy(ops = listOf(MissOp204c(3, callId = "c1", portThrow = 1))),
        base.copy(ops = listOf(MissOp204c(3, callId = "c1", cancelMode = 1))),
        base.copy(ops = listOf(MissOp204c(3, callId = "c1", portThrow = 1, cancelMode = 1))),
        base.copy(ops = listOf(MissOp204c(3, callId = "ghost"))),
        base.copy(ops = listOf(MissOp204c(3, callId = ""))),
        base.copy(ops = listOf(MissOp204c(3, callId = "c1")), sessionActive = false),
        base.copy(ops = listOf(MissOp204c(3, callId = "c1")), owner = ""),
        base.copy(
            ops = listOf(
                MissOp204c(0, cancelMode = 1, centerMode = 2),
                MissOp204c(3, callId = "c1"),
                MissOp204c(2, userId = "u2"),
                MissOp204c(1, portThrow = 1),
            ),
            chats = chats,
        ),
        base.copy(ops = emptyList()),
    )
    return cases[r.nextInt(cases.size)]
}

// ---------- tests ----------

class ChatListMissedCallCoordinatorFuzzTest {

    @Test
    fun randomCases() {
        val r = Random(204301)
        repeat(300) {
            val c = randomMissCase204c(r)
            assertEquals(missOracle204c(c), missProd204c(c))
        }
    }

    @Test
    fun boundaryCases() {
        val r = Random(204302)
        repeat(200) {
            val c = boundaryMissCase204c(r)
            assertEquals(missOracle204c(c), missProd204c(c))
        }
    }

    @Test
    fun pinnedCases() {
        val m1 = MissCall204c("c1")
        val m2 = MissCall204c("c2", callerId = "u2")
        val chats = listOf(
            MissChat204c("chat1", participantIds = listOf("u1", "u2")),
            MissChat204c("chat2", isGroup = true, participantIds = listOf("u1")),
        )
        val cases = listOf(
            MissCase204c(missed = listOf(m1, m2), ops = listOf(MissOp204c(0))),
            MissCase204c(missed = listOf(m1, m2), ops = listOf(MissOp204c(0, portThrow = 1, cancelMode = 2, centerMode = 2))),
            MissCase204c(missed = listOf(m1, m2), ops = listOf(MissOp204c(1))),
            MissCase204c(missed = listOf(m1, m2), chats = chats, ops = listOf(MissOp204c(2, userId = "u1"))),
            MissCase204c(missed = listOf(m1, m2), chats = chats, ops = listOf(MissOp204c(2, userId = "u2"))),
            MissCase204c(missed = listOf(m1, m2), ops = listOf(MissOp204c(3, callId = "c1"))),
            MissCase204c(missed = listOf(m1, m2), ops = listOf(MissOp204c(3, callId = "c1", portThrow = 1, cancelMode = 1))),
            MissCase204c(
                missed = listOf(m1, m2),
                chats = chats,
                ops = listOf(
                    MissOp204c(0, cancelMode = 1),
                    MissOp204c(3, callId = "c2"),
                    MissOp204c(2, userId = "u2"),
                ),
            ),
            MissCase204c(missed = listOf(m1, m2), ops = emptyList()),
        )
        for (c in cases) assertEquals(missOracle204c(c), missProd204c(c))
    }
}
