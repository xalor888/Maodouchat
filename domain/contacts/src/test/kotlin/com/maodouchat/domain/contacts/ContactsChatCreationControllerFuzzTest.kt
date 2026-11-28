package com.maodouchat.domain.contacts

import com.maodouchat.core.model.Chat
import com.maodouchat.core.model.ConversationCreationPort
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
private fun encStr207b(s: String): String {
    val out = StringBuilder()
    for (ch in s) out.append("%04x".format(ch.code))
    return out.toString()
}

private fun bit207b(b: Boolean): String = if (b) "1" else "0"

private fun opt207b(s: String?): String = s?.let { encStr207b(it) } ?: "~"

// op kind：0 direct，1 secret，2 group，3 channel。
// mode：0 成功、1 失败 Result 带消息、2 失败 Result 空消息、
// 3 抛 RuntimeException 带消息、4 抛 RuntimeException 空消息、5 抛 CancellationException。
private data class CreOp207b(
    val kind: Int,
    val peerId: String = "u1",
    val name: String = "g",
    val memberIds: List<String> = emptyList(),
    val mode: Int = 0,
    val chatId: String = "c1",
)

private data class CreCase207b(
    // busy 门控恒真时所有创建调用直接返回。
    val busy: Boolean = false,
    val secretEnabled: Boolean = true,
    val ops: List<CreOp207b> = emptyList(),
)

private fun creText207b(msg: ContactsMessage): String = when (msg) {
    ContactsMessage.SECRET_CHAT_FEATURE_DISABLED -> "secret-disabled"
    ContactsMessage.CONTACTS_ENTER_GROUP_NAME -> "enter-group-name"
    ContactsMessage.CHAT_CHANNEL_NAME_HINT -> "channel-name-hint"
    ContactsMessage.CONTACTS_CREATE_CHAT_FAILED -> "create-failed"
    else -> "txt-" + msg.name
}

private fun creState207b(state: ContactsUiState): String = listOf(
    bit207b(state.isCreatingChat),
    opt207b(state.createdChatId),
    opt207b(state.errorMessage),
).joinToString("|")

private fun creOracle207b(c: CreCase207b): String {
    val effects = mutableListOf<String>()
    var state = ContactsUiState()
    fun snap(prefix: String) {
        effects += prefix + ":" + creState207b(state)
    }
    fun port(kind: Int, peerId: String, name: String, memberIds: List<String>, op: CreOp207b): Result<Chat> {
        effects += "CALL:" + when (kind) {
            0 -> "direct:" + encStr207b(peerId)
            1 -> "secret:" + encStr207b(peerId)
            2 -> "group:" + encStr207b(name) + ":" + memberIds.joinToString(";") { encStr207b(it) }
            else -> "channel:" + encStr207b(name) + ":" + memberIds.joinToString(";") { encStr207b(it) }
        }
        return when (op.mode) {
            0 -> Result.success(Chat(id = op.chatId))
            1 -> Result.failure(RuntimeException("boom"))
            2 -> Result.failure(RuntimeException())
            3 -> throw RuntimeException("boom")
            4 -> throw RuntimeException()
            else -> throw CancellationException("cancel-boom")
        }
    }
    fun launchCreation(block: () -> Result<Chat>) {
        if (c.busy) return
        state = state.copy(isCreatingChat = true, errorMessage = null)
        snap("ST")
        try {
            val result = block()
            result.fold(
                onSuccess = { chat ->
                    state = state.copy(isCreatingChat = false, createdChatId = chat.id)
                    snap("ST")
                },
                onFailure = { error ->
                    state = state.copy(
                        isCreatingChat = false,
                        errorMessage = error.message
                            ?: creText207b(ContactsMessage.CONTACTS_CREATE_CHAT_FAILED),
                    )
                    snap("ST")
                },
            )
        } catch (e: CancellationException) {
            state = state.copy(isCreatingChat = false)
            snap("ST")
            // 重新抛出后 handler 不收 CancellationException，不留 THROW。
        } catch (e: Exception) {
            state = state.copy(
                isCreatingChat = false,
                errorMessage = e.message
                    ?: creText207b(ContactsMessage.CONTACTS_CREATE_CHAT_FAILED),
            )
            snap("ST")
        }
    }
    for (op in c.ops) {
        when (op.kind) {
            0 -> launchCreation { port(0, op.peerId, "", emptyList(), op) }
            1 -> {
                if (!c.secretEnabled) {
                    state = state.copy(
                        errorMessage = creText207b(ContactsMessage.SECRET_CHAT_FEATURE_DISABLED)
                    )
                    snap("ST")
                } else {
                    launchCreation { port(1, op.peerId, "", emptyList(), op) }
                }
            }
            2 -> {
                val name = op.name.trim()
                if (name.isBlank()) {
                    state = state.copy(
                        errorMessage = creText207b(ContactsMessage.CONTACTS_ENTER_GROUP_NAME)
                    )
                    snap("ST")
                } else {
                    launchCreation { port(2, "", name, op.memberIds, op) }
                }
            }
            else -> {
                val name = op.name.trim()
                if (name.isBlank()) {
                    state = state.copy(
                        errorMessage = creText207b(ContactsMessage.CHAT_CHANNEL_NAME_HINT)
                    )
                    snap("ST")
                } else {
                    launchCreation { port(3, "", name, op.memberIds, op) }
                }
            }
        }
        snap("FIN")
    }
    return effects.joinToString(",")
}

private fun creProd207b(c: CreCase207b): String {
    val effects = mutableListOf<String>()
    var state = ContactsUiState()
    fun snap(prefix: String) {
        effects += prefix + ":" + creState207b(state)
    }
    var curOp: CreOp207b? = null
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
    fun call(kind: Int, peerId: String, name: String, memberIds: List<String>): Result<Chat> {
        val op = curOp!!
        effects += "CALL:" + when (kind) {
            0 -> "direct:" + encStr207b(peerId)
            1 -> "secret:" + encStr207b(peerId)
            2 -> "group:" + encStr207b(name) + ":" + memberIds.joinToString(";") { encStr207b(it) }
            else -> "channel:" + encStr207b(name) + ":" + memberIds.joinToString(";") { encStr207b(it) }
        }
        return when (op.mode) {
            0 -> Result.success(Chat(id = op.chatId))
            1 -> Result.failure(RuntimeException("boom"))
            2 -> Result.failure(RuntimeException())
            3 -> throw RuntimeException("boom")
            4 -> throw RuntimeException()
            else -> throw CancellationException("cancel-boom")
        }
    }
    val port = object : ConversationCreationPort {
        override suspend fun createDirectChat(peerUserId: String): Result<Chat> =
            call(0, peerUserId, "", emptyList())
        override suspend fun createSecretChat(peerUserId: String): Result<Chat> =
            call(1, peerUserId, "", emptyList())
        override suspend fun createGroupChat(name: String, memberUserIds: List<String>): Result<Chat> =
            call(2, "", name, memberUserIds)
        override suspend fun createChannelChat(name: String, memberUserIds: List<String>): Result<Chat> =
            call(3, "", name, memberUserIds)
    }
    val controller = ContactsChatCreationController(
        scope = scope,
        updateState = { transform ->
            state = transform(state)
            snap("ST")
        },
        isCreatingChat = { c.busy || state.isCreatingChat },
        text = { msg, _ -> creText207b(msg) },
        conversationCreationPort = port,
        secretChatEnabled = { c.secretEnabled },
    )
    for (op in c.ops) {
        curOp = op
        try {
            when (op.kind) {
                0 -> controller.createDirectChat(User(op.peerId, op.peerId))
                1 -> controller.startSecretChat(User(op.peerId, op.peerId))
                2 -> controller.createGroupChat(op.name, op.memberIds.map { User(it, it) })
                else -> controller.createChannelChat(op.name, op.memberIds.map { User(it, it) })
            }
        } catch (e: Exception) {
            effects += "THROW:" + e.javaClass.simpleName
        }
        drain()
        snap("FIN")
    }
    return effects.joinToString(",")
}

private val CRE_IDS207b = listOf("", " ", "u1", "u2", "me", "x\ty", "ünïcödé")

private val CRE_NAMES207b = listOf("", " ", "  g  ", "g", "x\ty", "ünïcödé", "a".repeat(40))

private fun randomCreOp207b(r: Random): CreOp207b {
    val kind = r.nextInt(4)
    return CreOp207b(
        kind = kind,
        peerId = CRE_IDS207b[r.nextInt(CRE_IDS207b.size)],
        name = CRE_NAMES207b[r.nextInt(CRE_NAMES207b.size)],
        memberIds = List(r.nextInt(0, 4)) { CRE_IDS207b[r.nextInt(CRE_IDS207b.size)] },
        mode = r.nextInt(6),
        chatId = listOf("c1", "c2", "", "x\ty")[r.nextInt(4)],
    )
}

private fun randomCreCase207b(r: Random): CreCase207b = CreCase207b(
    busy = r.nextInt(5) == 0,
    secretEnabled = r.nextInt(4) != 0,
    ops = List(r.nextInt(0, 9)) { randomCreOp207b(r) },
)

private fun boundaryCreCase207b(r: Random): CreCase207b {
    val base = CreCase207b()
    return when (r.nextInt(24)) {
        0 -> base.copy(ops = listOf(CreOp207b(0)))
        1 -> base.copy(ops = listOf(CreOp207b(0, mode = 1)))
        2 -> base.copy(ops = listOf(CreOp207b(0, mode = 2)))
        3 -> base.copy(ops = listOf(CreOp207b(0, mode = 3)))
        4 -> base.copy(ops = listOf(CreOp207b(0, mode = 4)))
        5 -> base.copy(ops = listOf(CreOp207b(0, mode = 5)))
        6 -> base.copy(ops = listOf(CreOp207b(1)), secretEnabled = false)
        7 -> base.copy(ops = listOf(CreOp207b(1, mode = 1)))
        8 -> base.copy(ops = listOf(CreOp207b(2, name = "")))
        9 -> base.copy(ops = listOf(CreOp207b(2, name = "   ")))
        10 -> base.copy(ops = listOf(CreOp207b(2, name = "  g  ", memberIds = listOf("u1", "u2"))))
        11 -> base.copy(ops = listOf(CreOp207b(2, name = "g", mode = 1)))
        12 -> base.copy(ops = listOf(CreOp207b(2, name = "g", mode = 5)))
        13 -> base.copy(ops = listOf(CreOp207b(3, name = "")))
        14 -> base.copy(ops = listOf(CreOp207b(3, name = "ch", memberIds = listOf("u1"))))
        15 -> base.copy(ops = listOf(CreOp207b(3, name = "ch", mode = 2)))
        16 -> base.copy(ops = listOf(CreOp207b(0)), busy = true)
        17 -> base.copy(ops = listOf(CreOp207b(2, name = "")), busy = true)
        18 -> base.copy(ops = listOf(CreOp207b(0, mode = 1), CreOp207b(0, mode = 0)))
        19 -> base.copy(ops = listOf(CreOp207b(0, peerId = "")))
        20 -> base.copy(ops = listOf(CreOp207b(0, peerId = "  ")))
        21 -> base.copy(ops = listOf(CreOp207b(0, chatId = "")))
        22 -> base.copy(ops = listOf(CreOp207b(1, mode = 5)))
        else -> base.copy(ops = emptyList())
    }
}

// ---------- tests ----------

class ContactsChatCreationControllerFuzzTest {

    @Test
    fun randomCases() {
        val r = Random(207303)
        repeat(300) {
            val c = randomCreCase207b(r)
            assertEquals(creOracle207b(c), creProd207b(c))
        }
    }

    @Test
    fun boundaryCases() {
        val r = Random(207304)
        repeat(200) {
            val c = boundaryCreCase207b(r)
            assertEquals(creOracle207b(c), creProd207b(c))
        }
    }

    @Test
    fun pinnedCases() {
        val cases = listOf(
            CreCase207b(ops = listOf(CreOp207b(0))),
            CreCase207b(ops = listOf(CreOp207b(0, mode = 1))),
            CreCase207b(ops = listOf(CreOp207b(0, mode = 2))),
            CreCase207b(ops = listOf(CreOp207b(0, mode = 3))),
            CreCase207b(ops = listOf(CreOp207b(0, mode = 5))),
            CreCase207b(ops = listOf(CreOp207b(1)), secretEnabled = false),
            CreCase207b(ops = listOf(CreOp207b(2, name = ""))),
            CreCase207b(ops = listOf(CreOp207b(2, name = "  g  ", memberIds = listOf("u1", "u2")))),
            CreCase207b(ops = listOf(CreOp207b(3, name = "ch", memberIds = listOf("u1")))),
            CreCase207b(ops = listOf(CreOp207b(0)), busy = true),
            CreCase207b(ops = emptyList()),
        )
        for (c in cases) {
            assertEquals(creOracle207b(c), creProd207b(c))
        }
    }
}
