package com.maodouchat.domain.conversation

import com.maodouchat.core.model.ConversationLocalCleanupFailure
import com.maodouchat.core.model.ConversationLocalCleanupMode
import com.maodouchat.core.model.ConversationLocalCleanupReport
import com.maodouchat.core.model.ConversationLocalCleanupSession
import com.maodouchat.core.model.ConversationLocalCleanupStep
import com.maodouchat.core.model.ConversationLocalStateBackend
import com.maodouchat.core.model.ConversationLocalStateCoordinator
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

private fun bit206c(b: Boolean): String = if (b) "1" else "0"

// op kind: 0 clearLocalChatHistory，1 clearChatDraft，2 cleanupLocalChat。
private data class ClnOp206c(
    val kind: Int,
    val chatId: String = "c1",
    val sessOwner: String = "me",
    val sessGen: Long = 1L,
)

private data class ClnCase206c(
    val owner: String = "me",
    val gen: Long = 1L,
    // currentSession() 前 flipAfter 次返回期望会话，其后返回异主会话；999 = 永不翻转。
    val flipAfter: Int = 999,
    val failSteps: List<String> = emptyList(),
    // 0 抛 RuntimeException，1 抛 CancellationException（step 内照样记为失败）。
    val failKind: Int = 0,
    val messageIds: List<String> = emptyList(),
    // 0 成功、1 抛 RuntimeException、2 抛 CancellationException。
    val draftMode: Int = 0,
    val ops: List<ClnOp206c> = emptyList(),
)

private val CLN_OTHER_SESSION206c = ConversationLocalCleanupSession("__other__", -999999L)

private fun clnCurrentSession206c(c: ClnCase206c, sessCalls: IntArray): ConversationLocalCleanupSession {
    val idx = sessCalls[0]++
    return if (idx < c.flipAfter) ConversationLocalCleanupSession(c.owner, c.gen) else CLN_OTHER_SESSION206c
}

private fun clnThrowFor206c(c: ClnCase206c): Nothing {
    if (c.failKind == 1) throw CancellationException("cancel-boom")
    throw RuntimeException("boom")
}

// ConversationLocalStateCoordinator.cleanup 的逐分支镜像：step 顺序、
// mode 门禁、session 翻转后跳过余步、失败收集（含 CancellationException）。
private fun clnCleanupOracle206c(
    c: ClnCase206c,
    mode: ConversationLocalCleanupMode,
    chatId: String,
    expected: ConversationLocalCleanupSession,
    effects: MutableList<String>,
    sessCalls: IntArray,
): ConversationLocalCleanupReport {
    val failures = mutableListOf<ConversationLocalCleanupFailure>()
    var sessionChanged = false
    fun step(name: ConversationLocalCleanupStep, operation: () -> Unit) {
        if (sessionChanged) return
        if (clnCurrentSession206c(c, sessCalls) != expected) {
            sessionChanged = true
            return
        }
        try {
            operation()
        } catch (error: Exception) {
            failures += ConversationLocalCleanupFailure(name, error)
        }
    }
    fun backendCall(step: ConversationLocalCleanupStep) {
        effects += "CALL:" + step.name
        if (step.name in c.failSteps) clnThrowFor206c(c)
    }
    var cachedMessageIds = emptyList<String>()
    step(ConversationLocalCleanupStep.CAPTURE_MESSAGE_IDS) {
        backendCall(ConversationLocalCleanupStep.CAPTURE_MESSAGE_IDS)
        cachedMessageIds = c.messageIds
    }
    step(ConversationLocalCleanupStep.TOMBSTONE_MESSAGES) {
        backendCall(ConversationLocalCleanupStep.TOMBSTONE_MESSAGES)
    }
    step(ConversationLocalCleanupStep.CANCEL_ATTACHMENTS) {
        backendCall(ConversationLocalCleanupStep.CANCEL_ATTACHMENTS)
    }
    step(ConversationLocalCleanupStep.CANCEL_SCHEDULED_MESSAGES) {
        backendCall(ConversationLocalCleanupStep.CANCEL_SCHEDULED_MESSAGES)
    }
    if (mode == ConversationLocalCleanupMode.DELETE_CONVERSATION) {
        step(ConversationLocalCleanupStep.CANCEL_REMINDERS) {
            backendCall(ConversationLocalCleanupStep.CANCEL_REMINDERS)
        }
        step(ConversationLocalCleanupStep.DELETE_AI_TASKS) {
            backendCall(ConversationLocalCleanupStep.DELETE_AI_TASKS)
        }
        step(ConversationLocalCleanupStep.DELETE_AI_OPERATIONS) {
            backendCall(ConversationLocalCleanupStep.DELETE_AI_OPERATIONS)
        }
        step(ConversationLocalCleanupStep.DELETE_AI_SUMMARIES) {
            backendCall(ConversationLocalCleanupStep.DELETE_AI_SUMMARIES)
        }
        step(ConversationLocalCleanupStep.DELETE_DRAFT) {
            backendCall(ConversationLocalCleanupStep.DELETE_DRAFT)
        }
    }
    if (mode != ConversationLocalCleanupMode.CLEAR_HISTORY) {
        step(ConversationLocalCleanupStep.DELETE_LOCK) {
            backendCall(ConversationLocalCleanupStep.DELETE_LOCK)
        }
        step(ConversationLocalCleanupStep.CLEAR_LOCK_SESSION) {
            backendCall(ConversationLocalCleanupStep.CLEAR_LOCK_SESSION)
        }
    }
    if (mode == ConversationLocalCleanupMode.DELETE_CONVERSATION) {
        step(ConversationLocalCleanupStep.DELETE_SECRET_TTL) {
            backendCall(ConversationLocalCleanupStep.DELETE_SECRET_TTL)
        }
        step(ConversationLocalCleanupStep.DEACTIVATE_SECRET_SESSION) {
            backendCall(ConversationLocalCleanupStep.DEACTIVATE_SECRET_SESSION)
        }
        step(ConversationLocalCleanupStep.DELETE_SENDER_KEY_RETRY) {
            backendCall(ConversationLocalCleanupStep.DELETE_SENDER_KEY_RETRY)
        }
        step(ConversationLocalCleanupStep.INVALIDATE_SENDER_KEY) {
            backendCall(ConversationLocalCleanupStep.INVALIDATE_SENDER_KEY)
        }
    }
    step(ConversationLocalCleanupStep.CLEAR_MESSAGING_V2_STATE) {
        effects += "CALL:CLEAR_MESSAGING_V2_STATE:" +
            bit206c(mode == ConversationLocalCleanupMode.DELETE_CONVERSATION)
        if ("CLEAR_MESSAGING_V2_STATE" in c.failSteps) clnThrowFor206c(c)
    }
    step(ConversationLocalCleanupStep.CLEAR_ATTACHMENT_WIRE) {
        backendCall(ConversationLocalCleanupStep.CLEAR_ATTACHMENT_WIRE)
    }
    cachedMessageIds.forEach {
        step(ConversationLocalCleanupStep.DELETE_MEDIA) {
            backendCall(ConversationLocalCleanupStep.DELETE_MEDIA)
        }
    }
    step(ConversationLocalCleanupStep.DELETE_SEARCH_INDEX) {
        backendCall(ConversationLocalCleanupStep.DELETE_SEARCH_INDEX)
    }
    if (mode == ConversationLocalCleanupMode.DELETE_CONVERSATION) {
        step(ConversationLocalCleanupStep.CLEAR_SYNC_CURSORS) {
            backendCall(ConversationLocalCleanupStep.CLEAR_SYNC_CURSORS)
        }
    }
    step(ConversationLocalCleanupStep.REMOVE_NOTIFICATION_ITEMS) {
        backendCall(ConversationLocalCleanupStep.REMOVE_NOTIFICATION_ITEMS)
    }
    step(ConversationLocalCleanupStep.CANCEL_MESSAGE_NOTIFICATION) {
        backendCall(ConversationLocalCleanupStep.CANCEL_MESSAGE_NOTIFICATION)
    }
    if (mode == ConversationLocalCleanupMode.DELETE_CONVERSATION) {
        step(ConversationLocalCleanupStep.CANCEL_AI_REMINDERS) {
            backendCall(ConversationLocalCleanupStep.CANCEL_AI_REMINDERS)
        }
    }
    step(ConversationLocalCleanupStep.DELETE_MESSAGES) {
        backendCall(ConversationLocalCleanupStep.DELETE_MESSAGES)
    }
    if (mode == ConversationLocalCleanupMode.DELETE_CONVERSATION) {
        step(ConversationLocalCleanupStep.DELETE_CONVERSATION) {
            backendCall(ConversationLocalCleanupStep.DELETE_CONVERSATION)
        }
    } else {
        step(ConversationLocalCleanupStep.RESET_CONVERSATION_PREVIEW) {
            backendCall(ConversationLocalCleanupStep.RESET_CONVERSATION_PREVIEW)
        }
    }
    return ConversationLocalCleanupReport(
        sessionChanged = sessionChanged,
        failures = failures.toList(),
    )
}

private fun clnOracle206c(c: ClnCase206c): String {
    val effects = mutableListOf<String>()
    val sessCalls = intArrayOf(0)
    for (op in c.ops) {
        when (op.kind) {
            0 -> {
                if (!op.chatId.isBlank() && !c.owner.isBlank()) {
                    val expected = ConversationLocalCleanupSession(c.owner, c.gen)
                    val report = clnCleanupOracle206c(
                        c, ConversationLocalCleanupMode.CLEAR_HISTORY, op.chatId, expected, effects, sessCalls,
                    )
                    for (f in report.failures) {
                        effects += "WARN:local conversation cleanup failed at ${f.step} for ${op.chatId}|" +
                            f.error.javaClass.simpleName
                    }
                    if (report.completed) effects += "RELOAD"
                }
            }
            1 -> {
                if (!op.chatId.isBlank() && !c.owner.isBlank()) {
                    effects += "CALL:deleteDraft:" + c.draftMode
                }
            }
            else -> {
                val expected = ConversationLocalCleanupSession(op.sessOwner, op.sessGen)
                if (op.chatId.isBlank() || op.sessOwner.isBlank()) {
                    effects += "THROW:IllegalArgumentException"
                } else {
                    val report = clnCleanupOracle206c(
                        c, ConversationLocalCleanupMode.DELETE_CONVERSATION, op.chatId, expected, effects, sessCalls,
                    )
                    for (f in report.failures) {
                        effects += "WARN:conversation deletion cleanup failed at ${f.step} for ${op.chatId}|" +
                            f.error.javaClass.simpleName
                    }
                }
            }
        }
        effects += "FIN"
    }
    return effects.joinToString(",")
}

private class ClnBackend206c(
    private val c: ClnCase206c,
    private val effects: MutableList<String>,
) : ConversationLocalStateBackend {
    private fun call(step: ConversationLocalCleanupStep) {
        effects += "CALL:" + step.name
        if (step.name in c.failSteps) clnThrowFor206c(c)
    }

    override suspend fun messageIds(chatId: String): List<String> {
        call(ConversationLocalCleanupStep.CAPTURE_MESSAGE_IDS)
        return c.messageIds
    }

    override suspend fun tombstoneMessages(ownerUserId: String, chatId: String) {
        call(ConversationLocalCleanupStep.TOMBSTONE_MESSAGES)
    }

    override suspend fun cancelAttachments(chatId: String) {
        call(ConversationLocalCleanupStep.CANCEL_ATTACHMENTS)
    }

    override suspend fun cancelScheduledMessages(ownerUserId: String, chatId: String) {
        call(ConversationLocalCleanupStep.CANCEL_SCHEDULED_MESSAGES)
    }

    override suspend fun cancelReminders(ownerUserId: String, chatId: String) {
        call(ConversationLocalCleanupStep.CANCEL_REMINDERS)
    }

    override suspend fun deleteAiTasks(chatId: String) {
        call(ConversationLocalCleanupStep.DELETE_AI_TASKS)
    }

    override suspend fun deleteAiOperations(ownerUserId: String, chatId: String) {
        call(ConversationLocalCleanupStep.DELETE_AI_OPERATIONS)
    }

    override suspend fun deleteAiSummaries(chatId: String) {
        call(ConversationLocalCleanupStep.DELETE_AI_SUMMARIES)
    }

    override suspend fun deleteDraft(ownerUserId: String, chatId: String) {
        call(ConversationLocalCleanupStep.DELETE_DRAFT)
    }

    override suspend fun deleteLock(chatId: String) {
        call(ConversationLocalCleanupStep.DELETE_LOCK)
    }

    override suspend fun clearLockSession(chatId: String) {
        call(ConversationLocalCleanupStep.CLEAR_LOCK_SESSION)
    }

    override suspend fun deleteSecretTtl(chatId: String) {
        call(ConversationLocalCleanupStep.DELETE_SECRET_TTL)
    }

    override suspend fun deactivateSecretSession(chatId: String) {
        call(ConversationLocalCleanupStep.DEACTIVATE_SECRET_SESSION)
    }

    override suspend fun deleteSenderKeyRetry(ownerUserId: String, chatId: String) {
        call(ConversationLocalCleanupStep.DELETE_SENDER_KEY_RETRY)
    }

    override suspend fun invalidateSenderKey(chatId: String) {
        call(ConversationLocalCleanupStep.INVALIDATE_SENDER_KEY)
    }

    override suspend fun clearMessagingV2State(
        session: ConversationLocalCleanupSession,
        chatId: String,
        serverParticipantStateDeleted: Boolean,
    ) {
        effects += "CALL:CLEAR_MESSAGING_V2_STATE:" + bit206c(serverParticipantStateDeleted)
        if ("CLEAR_MESSAGING_V2_STATE" in c.failSteps) clnThrowFor206c(c)
    }

    override suspend fun clearAttachmentWire(ownerUserId: String, chatId: String) {
        call(ConversationLocalCleanupStep.CLEAR_ATTACHMENT_WIRE)
    }

    override suspend fun deleteMedia(messageId: String) {
        call(ConversationLocalCleanupStep.DELETE_MEDIA)
    }

    override suspend fun deleteSearchIndex(chatId: String) {
        call(ConversationLocalCleanupStep.DELETE_SEARCH_INDEX)
    }

    override suspend fun clearSyncCursors(chatId: String) {
        call(ConversationLocalCleanupStep.CLEAR_SYNC_CURSORS)
    }

    override suspend fun removeNotificationItems(chatId: String) {
        call(ConversationLocalCleanupStep.REMOVE_NOTIFICATION_ITEMS)
    }

    override suspend fun cancelMessageNotification(chatId: String) {
        call(ConversationLocalCleanupStep.CANCEL_MESSAGE_NOTIFICATION)
    }

    override suspend fun cancelAiReminders(chatId: String) {
        call(ConversationLocalCleanupStep.CANCEL_AI_REMINDERS)
    }

    override suspend fun deleteMessages(chatId: String) {
        call(ConversationLocalCleanupStep.DELETE_MESSAGES)
    }

    override suspend fun resetConversationPreview(chatId: String) {
        call(ConversationLocalCleanupStep.RESET_CONVERSATION_PREVIEW)
    }

    override suspend fun deleteConversation(chatId: String) {
        call(ConversationLocalCleanupStep.DELETE_CONVERSATION)
    }
}

private fun clnProd206c(c: ClnCase206c): String {
    val effects = mutableListOf<String>()
    val sessCalls = intArrayOf(0)
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
    val stateCoordinator = ConversationLocalStateCoordinator(
        currentSession = { clnCurrentSession206c(c, sessCalls) },
        backend = ClnBackend206c(c, effects),
    )
    val controller = ChatListLocalCleanupCoordinator(
        scope = scope,
        ownerUserId = { c.owner },
        localStateCoordinator = stateCoordinator,
        deleteDraftForChat = { _, _ ->
            effects += "CALL:deleteDraft:" + c.draftMode
            when (c.draftMode) {
                1 -> throw RuntimeException("boom")
                2 -> throw CancellationException("cancel-boom")
                else -> Unit
            }
        },
        reloadChats = { effects += "RELOAD" },
        cleanupSessionFor = { owner -> ConversationLocalCleanupSession(owner, c.gen) },
        logWarning = { message, error ->
            effects += "WARN:" + message + "|" + (error?.javaClass?.simpleName ?: "null")
        },
        ioDispatcher = Dispatchers.Unconfined,
    )
    for (op in c.ops) {
        try {
            when (op.kind) {
                0 -> controller.clearLocalChatHistory(op.chatId)
                1 -> controller.clearChatDraft(op.chatId)
                else -> runBlocking {
                    controller.cleanupLocalChat(
                        op.chatId,
                        ConversationLocalCleanupSession(op.sessOwner, op.sessGen),
                    )
                }
            }
        } catch (e: Exception) {
            effects += "THROW:" + e.javaClass.simpleName
        }
        drain()
        effects += "FIN"
    }
    return effects.joinToString(",")
}

private val CLN_STEP_NAMES206c = ConversationLocalCleanupStep.entries.map { it.name }

private val CLN_CHAT_IDS206c = listOf("c1", "", " ", "x\ty", "ünïcödé")

private fun randomClnOp206c(r: Random): ClnOp206c {
    val kind = r.nextInt(3)
    return ClnOp206c(
        kind = kind,
        chatId = CLN_CHAT_IDS206c[r.nextInt(CLN_CHAT_IDS206c.size)],
        sessOwner = if (kind == 2) listOf("me", "other", "", " " )[r.nextInt(4)] else "me",
        sessGen = if (kind == 2) listOf(1L, 2L, -1L)[r.nextInt(3)] else 1L,
    )
}

private fun randomClnCase206c(r: Random): ClnCase206c {
    val failCount = r.nextInt(0, 4)
    val failSteps = (0 until failCount).map {
        CLN_STEP_NAMES206c[r.nextInt(CLN_STEP_NAMES206c.size)]
    }.distinct()
    return ClnCase206c(
        owner = listOf("me", "", " ", "u1")[r.nextInt(4)],
        gen = listOf(1L, 2L, -1L)[r.nextInt(3)],
        flipAfter = listOf(0, 1, 2, 3, 5, 10, 999)[r.nextInt(7)],
        failSteps = failSteps,
        failKind = r.nextInt(2),
        messageIds = List(r.nextInt(0, 4)) { "m" + r.nextInt(0, 3) },
        draftMode = r.nextInt(3),
        ops = List(r.nextInt(0, 9)) { randomClnOp206c(r) },
    )
}

private fun boundaryClnCase206c(r: Random): ClnCase206c {
    val base = ClnCase206c()
    return when (r.nextInt(24)) {
        0 -> base.copy(ops = listOf(ClnOp206c(0, "c1")))
        1 -> base.copy(ops = listOf(ClnOp206c(0, "c1")), messageIds = listOf("m1", "m2", "m3"))
        2 -> base.copy(ops = listOf(ClnOp206c(0, "c1")), failSteps = listOf("CAPTURE_MESSAGE_IDS"), messageIds = listOf("m1", "m2"))
        3 -> base.copy(ops = listOf(ClnOp206c(0, "c1")), failSteps = listOf("DELETE_MESSAGES"))
        4 -> base.copy(ops = listOf(ClnOp206c(0, "c1")), failSteps = CLN_STEP_NAMES206c)
        5 -> base.copy(ops = listOf(ClnOp206c(0, "c1")), failSteps = listOf("CLEAR_MESSAGING_V2_STATE"))
        6 -> base.copy(ops = listOf(ClnOp206c(0, "c1")), failSteps = listOf("DELETE_SEARCH_INDEX"), failKind = 1)
        7 -> base.copy(ops = listOf(ClnOp206c(0, "c1")), flipAfter = 0)
        8 -> base.copy(ops = listOf(ClnOp206c(0, "c1")), flipAfter = 1)
        9 -> base.copy(ops = listOf(ClnOp206c(0, "c1")), flipAfter = 2)
        10 -> base.copy(ops = listOf(ClnOp206c(0, "")), flipAfter = 999)
        11 -> base.copy(ops = listOf(ClnOp206c(0, "c1")), owner = "")
        12 -> base.copy(ops = listOf(ClnOp206c(1, "c1")), draftMode = 0)
        13 -> base.copy(ops = listOf(ClnOp206c(1, "c1")), draftMode = 1)
        14 -> base.copy(ops = listOf(ClnOp206c(1, "c1")), draftMode = 2)
        15 -> base.copy(ops = listOf(ClnOp206c(1, "")), draftMode = 1)
        16 -> base.copy(ops = listOf(ClnOp206c(2, "c1", "me", 1L)))
        17 -> base.copy(ops = listOf(ClnOp206c(2, "c1", "me", 1L)), messageIds = listOf("m1"))
        18 -> base.copy(ops = listOf(ClnOp206c(2, "c1", "other", 1L)))
        19 -> base.copy(ops = listOf(ClnOp206c(2, "", "me", 1L)))
        20 -> base.copy(ops = listOf(ClnOp206c(2, "c1", "", 1L)))
        21 -> base.copy(ops = listOf(ClnOp206c(2, "c1", "me", 1L)), failSteps = listOf("DELETE_CONVERSATION"))
        22 -> base.copy(ops = listOf(ClnOp206c(0, "c1"), ClnOp206c(2, "c1", "me", 1L), ClnOp206c(1, "c1")))
        else -> base.copy(ops = emptyList())
    }
}

// ---------- tests ----------

class ChatListLocalCleanupCoordinatorFuzzTest {

    @Test
    fun randomCases() {
        val r = Random(206305)
        repeat(300) {
            val c = randomClnCase206c(r)
            assertEquals(clnOracle206c(c), clnProd206c(c))
        }
    }

    @Test
    fun boundaryCases() {
        val r = Random(206306)
        repeat(200) {
            val c = boundaryClnCase206c(r)
            assertEquals(clnOracle206c(c), clnProd206c(c))
        }
    }

    @Test
    fun pinnedCases() {
        val cases = listOf(
            ClnCase206c(ops = listOf(ClnOp206c(0, "c1"))),
            ClnCase206c(ops = listOf(ClnOp206c(0, "c1")), messageIds = listOf("m1", "m2", "m3")),
            ClnCase206c(ops = listOf(ClnOp206c(0, "c1")), failSteps = listOf("CAPTURE_MESSAGE_IDS"), messageIds = listOf("m1", "m2")),
            ClnCase206c(ops = listOf(ClnOp206c(0, "c1")), failSteps = listOf("DELETE_MESSAGES")),
            ClnCase206c(ops = listOf(ClnOp206c(0, "c1")), flipAfter = 0),
            ClnCase206c(ops = listOf(ClnOp206c(0, "c1")), flipAfter = 1),
            ClnCase206c(ops = listOf(ClnOp206c(1, "c1")), draftMode = 1),
            ClnCase206c(ops = listOf(ClnOp206c(1, "c1")), draftMode = 2),
            ClnCase206c(ops = listOf(ClnOp206c(2, "c1", "me", 1L))),
            ClnCase206c(ops = listOf(ClnOp206c(2, "c1", "other", 1L))),
            ClnCase206c(ops = emptyList()),
        )
        for (c in cases) {
            assertEquals(clnOracle206c(c), clnProd206c(c))
        }
    }
}
