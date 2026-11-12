package com.maodouchat.domain.calls

import com.maodouchat.core.model.CallState
import com.maodouchat.core.model.CallUiState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CallLifecycleControllerTest {

    private class Fixture(val generation: Long) {
        var state = CallUiState(callState = CallState.CONNECTED)
        var callId = "call-1"
        var progress = MutableStateFlow(0)
        var timersCancelled = 0
        var ingressStopped = 0
        var ringingCancelled = 0
        var iceCancelled = 0
        var foregroundStopped = 0
        val endCalls = mutableListOf<Boolean>()

        fun controller(scope: kotlinx.coroutines.CoroutineScope) = CallLifecycleController(
            scope = scope,
            currentState = { state },
            updateState = { transform -> state = transform(state) },
            activeCallId = { callId },
            cancelTimers = { timersCancelled++ },
            stopSignalingIngress = { ingressStopped++ },
            cancelRingingTimeout = { ringingCancelled++ },
            cancelIceRecovery = { iceCancelled++ },
            stopForegroundService = { foregroundStopped++ },
            endCall = { notifyPeer -> endCalls += notifyPeer },
            nativeDownloadProgress = progress,
            currentSessionGeneration = { generation },
        )
    }

    // onStart 启动的两个 collect 永不结束：须用 backgroundScope，runTest 结束时自动取消；
    // 若传 runTest 自身的 TestScope，收尾会报 UncompletedCoroutinesError。
    @Test
    fun progressUpdatesState() = runTest {
        val f = Fixture(1001L)
        val c = f.controller(backgroundScope)
        c.onStart()
        runCurrent()
        f.progress.value = 42
        runCurrent()
        assertEquals(42, f.state.nativeDownloadProgress)
    }

    @Test
    fun matchingHangUpEndsCallWithNotifyPeer() = runTest {
        val f = Fixture(1002L)
        val c = f.controller(backgroundScope)
        c.onStart()
        runCurrent()
        assertTrue(CallActionBus.requestHangUp("call-1", notifyPeer = true, sessionGeneration = 1002L))
        runCurrent()
        assertEquals(listOf(true), f.endCalls)
    }

    @Test
    fun staleGenerationHangUpIsDropped() = runTest {
        val f = Fixture(1003L)
        val c = f.controller(backgroundScope)
        c.onStart()
        runCurrent()
        assertTrue(CallActionBus.requestHangUp("call-1", notifyPeer = true, sessionGeneration = 9999L))
        runCurrent()
        assertTrue(f.endCalls.isEmpty())
    }

    @Test
    fun wrongCallIdHangUpIsDropped() = runTest {
        val f = Fixture(1004L)
        val c = f.controller(backgroundScope)
        c.onStart()
        runCurrent()
        assertTrue(CallActionBus.requestHangUp("call-other", notifyPeer = true, sessionGeneration = 1004L))
        runCurrent()
        assertTrue(f.endCalls.isEmpty())
    }

    @Test
    fun idleStateDropsHangUp() = runTest {
        val f = Fixture(1005L)
        f.state = f.state.copy(callState = CallState.IDLE)
        val c = f.controller(backgroundScope)
        c.onStart()
        runCurrent()
        assertTrue(CallActionBus.requestHangUp("call-1", notifyPeer = true, sessionGeneration = 1005L))
        runCurrent()
        assertTrue(f.endCalls.isEmpty())
    }

    @Test
    fun onClearedNotifiesPeerWhenConnectedAndStopsEverything() = runTest {
        val f = Fixture(1006L)
        val c = f.controller(this)
        c.onCleared()
        assertEquals(listOf(true), f.endCalls)
        assertEquals(1, f.timersCancelled)
        assertEquals(1, f.ingressStopped)
        assertEquals(1, f.ringingCancelled)
        assertEquals(1, f.iceCancelled)
        assertEquals(1, f.foregroundStopped)
    }

    @Test
    fun onClearedStaysSilentWhenIdle() = runTest {
        val f = Fixture(1007L)
        f.state = f.state.copy(callState = CallState.IDLE)
        val c = f.controller(this)
        c.onCleared()
        assertEquals(listOf(false), f.endCalls)
        assertEquals(1, f.foregroundStopped)
    }
}
