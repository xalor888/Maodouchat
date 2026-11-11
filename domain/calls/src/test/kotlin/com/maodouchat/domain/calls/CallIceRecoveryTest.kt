package com.maodouchat.domain.calls

import com.maodouchat.core.model.CallSessionGate
import com.maodouchat.core.model.CallState
import com.maodouchat.core.model.CallUiState
import com.maodouchat.core.session.CallSessionMachine
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CallIceRecoveryTest {

    private class Fixture {
        val gate = CallSessionGate()
        val session = gate.begin()
        val machine = CallSessionMachine()
        var state = CallUiState(callState = CallState.CONNECTED)
        var timersStarted = 0
        var restarts = 0
        val giveUps = mutableListOf<String>()

        fun recovery(scope: kotlinx.coroutines.CoroutineScope) = CallIceRecovery(
            scope = scope,
            endingCall = { false },
            callSessionMachine = machine,
            activeDomainSession = { session },
            startTimers = { timersStarted++ },
            activeCallSession = { session },
            callSessionGate = gate,
            updateState = { transform -> state = transform(state) },
            currentState = { state },
            onIceGiveUp = { giveUps += it },
            text = { message, _ -> message.name },
            restartIce = { restarts++ },
        )
    }

    @Test
    fun recoveredMarksConnectedAndStartsTimers() = runTest {
        val f = Fixture()
        f.state = CallUiState(callState = CallState.CONNECTED, networkReconnecting = true, errorMessage = "x", isInitializing = true)
        f.recovery(this).onIceConnectionChange(true)
        advanceUntilIdle()
        assertEquals(CallState.CONNECTED, f.state.callState)
        assertFalse(f.state.networkReconnecting)
        assertNull(f.state.errorMessage)
        assertFalse(f.state.isInitializing)
        assertEquals(1, f.timersStarted)
    }

    @Test
    fun disconnectedWhenNotConnectedIsNoop() = runTest {
        val f = Fixture()
        f.state = CallUiState(callState = CallState.IDLE)
        f.recovery(this).onIceConnectionChange(false)
        advanceUntilIdle()
        assertFalse(f.state.networkReconnecting)
        assertTrue(f.giveUps.isEmpty())
    }

    @Test
    fun disconnectedStartsGraceThenGivesUp() = runTest {
        val f = Fixture()
        val r = f.recovery(this)
        r.onIceConnectionChange(false)
        assertTrue(f.state.networkReconnecting)
        // advanceTimeBy 只跑到期时间严格早于目标点的任务；恰好到期的 delay 需再泵一次。
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(listOf("NETWORK_DISCONNECTED"), f.giveUps)
    }

    @Test
    fun failedRestartsTwiceThenGivesUp() = runTest {
        val f = Fixture()
        val r = f.recovery(this)
        r.onIceConnectionFailed()
        assertEquals(1, f.restarts)
        assertTrue(f.state.networkReconnecting)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(2, f.restarts)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(2, f.restarts)
        assertEquals(listOf("NETWORK_DISCONNECTED"), f.giveUps)
    }

    @Test
    fun failedWhenIdleIsNoop() = runTest {
        val f = Fixture()
        f.state = CallUiState(callState = CallState.IDLE)
        f.recovery(this).onIceConnectionFailed()
        advanceUntilIdle()
        assertEquals(0, f.restarts)
        assertTrue(f.giveUps.isEmpty())
    }

    @Test
    fun cancelStopsGraceJob() = runTest {
        val f = Fixture()
        val r = f.recovery(this)
        r.onIceConnectionChange(false)
        r.cancel()
        advanceTimeBy(10_000)
        assertTrue(f.giveUps.isEmpty())
    }
}
