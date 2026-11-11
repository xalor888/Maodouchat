package com.maodouchat.domain.messaging

import com.maodouchat.core.model.ChatDetailUiState
import com.maodouchat.core.model.Message
import com.maodouchat.core.model.MessageType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout

class ChatAiOperationsControllerTest {

    private fun message(chatId: String = "c1") = Message(
        id = "m1",
        chatId = chatId,
        senderId = "owner-1",
        content = "hi",
        type = MessageType.TEXT,
    )

    private fun record(
        id: String = "op1",
        lastErrorCode: String? = null,
    ) = AiOperationRecord(
        id = id,
        type = "TRANSCRIBE_VOICE",
        state = "RUNNING",
        attempts = 2,
        lastErrorCode = lastErrorCode,
    )

    private class Harness(
        var ownerId: String = "owner-1",
        var sessionActive: Boolean = true,
        var sessionAnswers: ArrayDeque<Boolean>? = null,
    ) {
        val scopeJob = Job()
        val scope = CoroutineScope(UnconfinedTestDispatcher() + scopeJob)
        val operationsFlow = MutableStateFlow<List<AiOperationRecord>>(emptyList())
        var state = ChatDetailUiState()
        val recovered = mutableListOf<Pair<String, String>>()
        var pruneTerminalCalls = 0
        val prunedSummaries = mutableListOf<Long>()
        val prunedTasks = mutableListOf<Long>()
        val committed = mutableListOf<Pair<String?, Message>>()
        var commitResult = true
        val retryJobs = mutableMapOf<String, Job>()
        val retryAt = mutableMapOf<String, Long>()

        fun controller() = ChatAiOperationsController(
            scope = scope,
            updateState = { transform -> state = transform(state) },
            activeChatId = { "c1" },
            currentOwnerUserId = { ownerId },
            sessionMayContinue = { sessionAnswers?.removeFirstOrNull() ?: sessionActive },
            observeOperations = { _, _ -> operationsFlow },
            recoverInterrupted = { owner, chat -> recovered += owner to chat; 1 },
            pruneTerminalOperations = { pruneTerminalCalls++; 1 },
            pruneSummariesOlderThan = { prunedSummaries += it },
            pruneCompletedTasksOlderThan = { prunedTasks += it },
            commitMessageResult = { operationId, msg -> committed += operationId to msg; commitResult },
            aiAutoRetryJobs = retryJobs,
            aiAutoRetryAt = retryAt,
        )

        suspend fun waitForRecovery() {
            withTimeout(5_000) {
                while (prunedTasks.isEmpty()) delay(10)
            }
        }
    }

    @Test
    fun `observe maps operations to ui state`() = runTest {
        val harness = Harness()
        harness.controller().observeAiOperations()
        harness.operationsFlow.value = listOf(record())
        val shown = harness.state.aiOperations
        assertEquals(1, shown.size)
        assertEquals("op1", shown.single().id)
        assertEquals("TRANSCRIBE_VOICE", shown.single().type)
        assertEquals("RUNNING", shown.single().state)
        assertEquals(2, shown.single().attempts)
        assertEquals(null, shown.single().lastErrorCode)
        assertEquals(null, shown.single().nextRetryAtMs)
        assertEquals(null, shown.single().retryAfterSeconds)
    }

    @Test
    fun `observe fills retry countdown and next retry time`() = runTest {
        val harness = Harness()
        harness.retryAt["op1"] = 12_345L
        harness.controller().observeAiOperations()
        harness.operationsFlow.value = listOf(record(lastErrorCode = "RATE_LIMITED"))
        val shown = harness.state.aiOperations.single()
        assertEquals(12_345L, shown.nextRetryAtMs)
        assertEquals(30L, shown.retryAfterSeconds)
    }

    @Test
    fun `observe drops emissions after owner switch`() = runTest {
        val harness = Harness()
        harness.controller().observeAiOperations()
        harness.operationsFlow.value = listOf(record("op1"))
        assertEquals(listOf("op1"), harness.state.aiOperations.map { it.id })
        harness.ownerId = "owner-2"
        harness.operationsFlow.value = listOf(record("op2"))
        assertEquals(listOf("op1"), harness.state.aiOperations.map { it.id })
    }

    @Test
    fun `observe returns early for blank owner`() = runTest {
        val harness = Harness(ownerId = "")
        harness.controller().observeAiOperations()
        harness.operationsFlow.value = listOf(record())
        assertTrue(harness.state.aiOperations.isEmpty())
        assertTrue(harness.recovered.isEmpty())
    }

    @Test
    fun `observe recovers interrupted operations and prunes old caches`() = runTest {
        val harness = Harness()
        val before = System.currentTimeMillis()
        harness.controller().observeAiOperations()
        harness.waitForRecovery()
        assertEquals(listOf("owner-1" to "c1"), harness.recovered)
        assertEquals(1, harness.pruneTerminalCalls)
        assertEquals(1, harness.prunedSummaries.size)
        assertEquals(1, harness.prunedTasks.size)
        val cutoff = before - 90L * 24L * 60L * 60L * 1_000L
        assertTrue(harness.prunedSummaries.single() in cutoff - 60_000L..cutoff + 60_000L)
        assertTrue(harness.prunedTasks.single() in cutoff - 60_000L..cutoff + 60_000L)
    }

    @Test
    fun `commit succeeds and clears auto retry bookkeeping`() = runTest {
        val harness = Harness()
        val job = Job()
        harness.retryJobs["op1"] = job
        harness.retryAt["op1"] = 999L
        val msg = message()
        val ok = harness.controller().commitAiMessageResult("op1", msg, "owner-1", "c1")
        assertTrue(ok)
        assertEquals(listOf<Pair<String?, Message>>("op1" to msg), harness.committed)
        assertTrue(harness.retryJobs.isEmpty())
        assertTrue(harness.retryAt.isEmpty())
        assertTrue(job.isCancelled)
    }

    @Test
    fun `commit throws when message belongs to another chat`() = runTest {
        val harness = Harness()
        assertFailsWith<CancellationException> {
            harness.controller().commitAiMessageResult("op1", message("c2"), "owner-1", "c1")
        }
        assertTrue(harness.committed.isEmpty())
    }

    @Test
    fun `commit returns false when store rejects`() = runTest {
        val harness = Harness()
        harness.commitResult = false
        val job = Job()
        harness.retryJobs["op1"] = job
        val ok = harness.controller().commitAiMessageResult("op1", message(), "owner-1", "c1")
        assertFalse(ok)
        assertTrue(harness.retryJobs.containsKey("op1"))
        assertFalse(job.isCancelled)
    }

    @Test
    fun `commit returns false when session check fails after store write`() = runTest {
        val harness = Harness(sessionAnswers = ArrayDeque(listOf(true, false)))
        val ok = harness.controller().commitAiMessageResult("op1", message(), "owner-1", "c1")
        assertFalse(ok)
        assertEquals(1, harness.committed.size)
    }

    @Test
    fun `commit with null operation id still succeeds`() = runTest {
        val harness = Harness()
        val msg = message()
        val ok = harness.controller().commitAiMessageResult(null, msg, "owner-1", "c1")
        assertTrue(ok)
        assertEquals(listOf<Pair<String?, Message>>(null to msg), harness.committed)
    }
}
