package com.maodouchat.domain.calls

import com.maodouchat.core.model.CallIceServer
import com.maodouchat.core.model.CallSessionGate
import com.maodouchat.core.model.CallState
import com.maodouchat.core.model.CallUiState
import com.maodouchat.core.model.NetworkQuality
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CallSessionTimersControllerTest {

    private class Fixture {
        val gate = CallSessionGate()
        val session = gate.begin()
        var state = CallUiState(callState = CallState.CONNECTED)
        var stateUpdates = 0
        var nowMs = 100_000L
        var stats: CallConnectionStats? = null
        var token = "tok-1"
        var fetchResult: Result<List<CallIceServer>> =
            Result.success(listOf(CallIceServer(listOf("turn:turn.example.com:3478"))))
        val fetchedTokens = mutableListOf<String>()
        val refreshed = mutableListOf<List<CallIceServer>>()
        var answeredLogs = 0

        fun controller(scope: CoroutineScope) = CallSessionTimersController(
            scope = scope,
            currentState = { state },
            updateState = { transform ->
                stateUpdates++
                state = transform(state)
            },
            getConnectionStats = { stats },
            refreshIceServers = { servers -> refreshed += servers },
            fetchIceServers = { liveToken ->
                fetchedTokens += liveToken
                fetchResult
            },
            elapsedRealtimeMs = { nowMs },
            activeCallSession = { session },
            callSessionGate = gate,
            token = { token },
            onLogAnswered = { answeredLogs++ },
        )
    }

    @Test
    fun durationTimerLogsAnsweredOnceAndFormatsElapsed() = runTest {
        val f = Fixture()
        val c = f.controller(this)
        c.startDurationTimer()
        runCurrent()
        assertEquals(1, f.answeredLogs)
        assertEquals("00:00", f.state.duration)
        f.nowMs += 65_000L
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals("01:05", f.state.duration)
        c.cancelTimers()
        runCurrent()
    }

    @Test
    fun durationTimerStopsWhenCallLeavesConnected() = runTest {
        val f = Fixture()
        val c = f.controller(this)
        c.startDurationTimer()
        runCurrent()
        val updates = f.stateUpdates
        f.state = f.state.copy(callState = CallState.IDLE)
        f.nowMs += 10_000L
        advanceTimeBy(10_000L)
        runCurrent()
        assertEquals(updates, f.stateUpdates)
    }

    @Test
    fun networkStatsPollingMapsStatsAndSkipsUnchanged() = runTest {
        val f = Fixture()
        f.stats = CallConnectionStats(rttMs = 100L, packetLossPercent = 0.5)
        val c = f.controller(this)
        c.startNetworkStatsPolling()
        runCurrent()
        assertEquals(NetworkQuality.GOOD, f.state.networkStats)
        val updates = f.stateUpdates
        advanceTimeBy(4_000L)
        runCurrent()
        assertEquals(updates, f.stateUpdates)
        f.stats = CallConnectionStats(rttMs = 900L, packetLossPercent = 20.0)
        advanceTimeBy(4_000L)
        runCurrent()
        assertEquals(NetworkQuality.POOR, f.state.networkStats)
        c.cancelTimers()
        runCurrent()
    }

    @Test
    fun networkStatsPollingLeavesUnknownWhenNoStats() = runTest {
        val f = Fixture()
        val c = f.controller(this)
        c.startNetworkStatsPolling()
        runCurrent()
        assertEquals(NetworkQuality.UNKNOWN, f.state.networkStats)
        assertEquals(0, f.stateUpdates)
        c.cancelTimers()
        runCurrent()
    }

    @Test
    fun iceRefreshFetchesAndAppliesFreshServers() = runTest {
        val f = Fixture()
        val c = f.controller(this)
        c.startDurationTimer()
        runCurrent()
        advanceTimeBy(30L * 60L * 1_000L)
        runCurrent()
        assertEquals(listOf("tok-1"), f.fetchedTokens)
        assertEquals(1, f.refreshed.size)
        assertEquals(false, f.state.iceStunOnly)
        c.cancelTimers()
        runCurrent()
    }

    @Test
    fun iceRefreshSkipsWhenTokenBlankAndRetriesOnFailure() = runTest {
        val f = Fixture()
        f.token = ""
        val c = f.controller(this)
        c.startDurationTimer()
        runCurrent()
        advanceTimeBy(30L * 60L * 1_000L)
        runCurrent()
        assertTrue(f.fetchedTokens.isEmpty())
        assertTrue(f.refreshed.isEmpty())
        f.token = "tok-2"
        f.fetchResult = Result.failure(RuntimeException("no network"))
        advanceTimeBy(30L * 60L * 1_000L)
        runCurrent()
        assertEquals(listOf("tok-2"), f.fetchedTokens)
        assertTrue(f.refreshed.isEmpty())
        f.fetchResult = Result.success(CallIceServer.defaultStun())
        advanceTimeBy(30L * 60L * 1_000L)
        runCurrent()
        assertEquals(1, f.refreshed.size)
        assertEquals(true, f.state.iceStunOnly)
        c.cancelTimers()
        runCurrent()
    }

    @Test
    fun cancelTimersStopsAllLoops() = runTest {
        val f = Fixture()
        f.stats = CallConnectionStats(rttMs = 100L, packetLossPercent = 0.5)
        val c = f.controller(this)
        c.startDurationTimer()
        c.startNetworkStatsPolling()
        runCurrent()
        c.cancelTimers()
        f.nowMs += 60_000L
        f.stats = CallConnectionStats(rttMs = 900L, packetLossPercent = 20.0)
        advanceTimeBy(31L * 60L * 1_000L)
        runCurrent()
        assertEquals("00:00", f.state.duration)
        assertEquals(NetworkQuality.GOOD, f.state.networkStats)
        assertTrue(f.fetchedTokens.isEmpty())
    }

    @Test
    fun loopsStopWhenSessionIsInvalidated() = runTest {
        val f = Fixture()
        val c = f.controller(this)
        c.startDurationTimer()
        runCurrent()
        f.gate.invalidate(f.session)
        f.nowMs += 60_000L
        advanceTimeBy(31L * 60L * 1_000L)
        runCurrent()
        assertEquals("00:00", f.state.duration)
        assertTrue(f.fetchedTokens.isEmpty())
    }
}
