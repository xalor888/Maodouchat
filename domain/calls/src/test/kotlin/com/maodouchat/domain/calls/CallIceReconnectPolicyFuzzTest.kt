package com.maodouchat.domain.calls

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class CallIceReconnectPolicyFuzzTest {

    private val random = Random(2026112242L)

    private val statePool = listOf(
        "DISCONNECTED", "CONNECTED", "COMPLETED", "FAILED",
        "CHECKING", "NEW", "CLOSED", "DISCONNECTING",
        "disconnected", "connected", "completed", "failed",
        "Failed", "Disconnected", "DISCONNECTED ", " FAILED", "",
        " ", "FAIL", "DISCONNECT", "CONNECT", "COMPLETE",
    )

    private val attemptPool = listOf(-5, -1, 0, 1, 2, 3, 5, 99, Int.MAX_VALUE, Int.MIN_VALUE)

    // 与生产侧逐行同构的 oracle：大小写不敏感匹配；FAILED 在最大重连次数内
    // 尝试 restart，达到上限才结束；其余状态一律忽略。
    private fun oracleAction(iceState: String, restartAttempts: Int): IceReconnectAction =
        when (iceState.uppercase()) {
            "DISCONNECTED" -> IceReconnectAction.START_GRACE
            "CONNECTED", "COMPLETED" -> IceReconnectAction.CANCEL_GRACE
            "FAILED" -> if (restartAttempts < ICE_MAX_RESTART_ATTEMPTS) {
                IceReconnectAction.RESTART_ICE
            } else {
                IceReconnectAction.END_NOW
            }
            else -> IceReconnectAction.IGNORE
        }

    @Test
    fun `iceReconnectAction matches the oracle for random states and attempts`() {
        repeat(300) {
            val state = statePool.random(random)
            val attempts = attemptPool.random(random)
            assertEquals(
                oracleAction(state, attempts),
                CallReliabilityPolicy.iceReconnectAction(state, attempts),
                "state=$state attempts=$attempts",
            )
        }
    }

    @Test
    fun `iceReconnectAction matches the oracle for random letter states`() {
        val alphabet = ('a'..'z') + ('A'..'Z')
        repeat(200) {
            val state = (0 until random.nextInt(0, 14)).map { alphabet.random(random) }.joinToString("")
            val attempts = attemptPool.random(random)
            assertEquals(
                oracleAction(state, attempts),
                CallReliabilityPolicy.iceReconnectAction(state, attempts),
                "state=$state attempts=$attempts",
            )
        }
    }

    @Test
    fun `FAILED restarts below the max and ends at or above it`() {
        for (attempts in -10..5) {
            val expected = if (attempts < ICE_MAX_RESTART_ATTEMPTS) {
                IceReconnectAction.RESTART_ICE
            } else {
                IceReconnectAction.END_NOW
            }
            assertEquals(
                expected,
                CallReliabilityPolicy.iceReconnectAction("FAILED", attempts),
                "attempts=$attempts",
            )
        }
        assertEquals(
            IceReconnectAction.END_NOW,
            CallReliabilityPolicy.iceReconnectAction("FAILED", Int.MAX_VALUE),
        )
    }

    @Test
    fun `grace actions ignore restart attempts`() {
        for (state in listOf("DISCONNECTED", "CONNECTED", "COMPLETED", "disconnected", "Connected")) {
            for (attempts in listOf(-1, 0, 2, 99)) {
                assertEquals(
                    oracleAction(state, attempts),
                    CallReliabilityPolicy.iceReconnectAction(state, attempts),
                    "state=$state attempts=$attempts",
                )
            }
        }
    }

    @Test
    fun `near-miss states are ignored`() {
        val ignored = listOf(
            "DISCONNECT", "DISCONNECTEDX", "XDISCONNECTED", "CONNECT", "COMPLETE",
            "FAIL", "FAILED1", "FAILED ", " FAILED", "", " ", "CHECKING", "NEW",
        )
        for (state in ignored) {
            assertEquals(
                IceReconnectAction.IGNORE,
                CallReliabilityPolicy.iceReconnectAction(state, 0),
                "state=$state",
            )
        }
    }

    @Test
    fun `reconnect constants are pinned`() {
        assertEquals(10_000L, ICE_RECONNECT_GRACE_MS)
        assertEquals(2, ICE_MAX_RESTART_ATTEMPTS)
        assertEquals(5_000L, ICE_RESTART_INTERVAL_MS)
    }
}
