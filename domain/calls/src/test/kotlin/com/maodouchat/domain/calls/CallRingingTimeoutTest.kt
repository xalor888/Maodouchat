package com.maodouchat.domain.calls

import com.maodouchat.core.model.CallSessionGate
import com.maodouchat.core.model.CallState
import com.maodouchat.core.model.CallType
import com.maodouchat.core.model.CallUiState
import com.maodouchat.core.model.IncomingCallCoordinator
import com.maodouchat.core.model.PendingIncomingCall
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CallRingingTimeoutTest {

    private data class RecordedMissed(
        val signalingCallId: String,
        val fromUserId: String,
        val callerName: String,
        val isVideo: Boolean,
        val isGroup: Boolean,
    )

    private class Fixture {
        val gate = CallSessionGate()
        val session = gate.begin()
        var state = CallUiState(
            callState = CallState.RINGING,
            isIncoming = true,
            contactId = "u2",
            contactName = "Bob",
            callType = CallType.VIDEO,
        )
        val records = mutableListOf<RecordedMissed>()
        val warnings = mutableListOf<Pair<String, Throwable>>()
        val noAnswers = mutableListOf<String>()
        var recordFailure: Throwable? = null

        fun timeout(scope: CoroutineScope, timeoutMs: Long = 1_000L) = CallRingingTimeout(
            scope = scope,
            sessionGate = gate,
            activeCallSession = { session },
            currentState = { state },
            activeCallId = { "call-1" },
            timeoutMs = timeoutMs,
            text = { message -> "[${message.name}]" },
            recordMissedCall = { signalingCallId, fromUserId, callerName, isVideo, isGroup ->
                recordFailure?.let { throw it }
                records += RecordedMissed(signalingCallId, fromUserId, callerName, isVideo, isGroup)
            },
            logWarn = { message, error -> warnings += message to error },
            onNoAnswer = { noAnswers += it },
        )
    }

    @Test
    fun incomingRingingTimeoutRecordsMissedAndHangsUp() = runTest {
        val f = Fixture()
        IncomingCallCoordinator.setPending(
            PendingIncomingCall(contactId = "u2", contactName = "Bob", callType = CallType.VIDEO, offerSdp = ""),
        )
        f.timeout(this).start("u2")
        advanceTimeBy(1_000L)
        runCurrent()

        assertEquals(
            listOf(RecordedMissed("call-1", "u2", "Bob", true, false)),
            f.records,
        )
        assertEquals(listOf("[CALL_NO_ANSWER]"), f.noAnswers)
        assertNull(IncomingCallCoordinator.pending.value)
    }

    @Test
    fun outgoingCallingTimeoutHangsUpWithoutRecord() = runTest {
        val f = Fixture()
        f.state = f.state.copy(callState = CallState.CALLING, isIncoming = false)
        f.timeout(this).start("u2")
        advanceTimeBy(1_000L)
        runCurrent()

        assertTrue(f.records.isEmpty())
        assertEquals(listOf("[CALL_NO_ANSWER]"), f.noAnswers)
    }

    @Test
    fun connectedStateIsNoop() = runTest {
        val f = Fixture()
        f.state = f.state.copy(callState = CallState.CONNECTED)
        f.timeout(this).start("u2")
        advanceTimeBy(1_000L)
        runCurrent()

        assertTrue(f.records.isEmpty())
        assertTrue(f.noAnswers.isEmpty())
    }

    @Test
    fun staleSessionIsNoop() = runTest {
        val f = Fixture()
        f.timeout(this).start("u2")
        f.gate.begin()
        advanceTimeBy(1_000L)
        runCurrent()

        assertTrue(f.records.isEmpty())
        assertTrue(f.noAnswers.isEmpty())
    }

    @Test
    fun cancelBeforeTimeoutIsNoop() = runTest {
        val f = Fixture()
        val timeout = f.timeout(this)
        timeout.start("u2")
        timeout.cancel()
        advanceTimeBy(1_000L)
        runCurrent()

        assertTrue(f.records.isEmpty())
        assertTrue(f.noAnswers.isEmpty())
    }

    @Test
    fun restartCancelsPreviousTimer() = runTest {
        val f = Fixture()
        val timeout = f.timeout(this)
        timeout.start("u2")
        advanceTimeBy(600L)
        runCurrent()
        timeout.start("u2")
        advanceTimeBy(600L)
        runCurrent()
        assertTrue(f.noAnswers.isEmpty())
        advanceTimeBy(400L)
        runCurrent()

        assertEquals(1, f.records.size)
        assertEquals(listOf("[CALL_NO_ANSWER]"), f.noAnswers)
    }

    @Test
    fun recordFailureLogsAndStillHangsUp() = runTest {
        val f = Fixture()
        f.recordFailure = RuntimeException("boom")
        f.timeout(this).start("u2")
        advanceTimeBy(1_000L)
        runCurrent()

        assertTrue(f.records.isEmpty())
        assertEquals(1, f.warnings.size)
        assertEquals("missed-call record on ring timeout failed", f.warnings.single().first)
        assertEquals(listOf("[CALL_NO_ANSWER]"), f.noAnswers)
    }

    @Test
    fun blankContactFallsBackToStartArg() = runTest {
        val f = Fixture()
        f.state = f.state.copy(contactId = "")
        f.timeout(this).start("u9")
        advanceTimeBy(1_000L)
        runCurrent()

        assertEquals("u9", f.records.single().fromUserId)
    }
}
