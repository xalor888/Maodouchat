package com.maodouchat.domain.calls

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CallSignalingReliabilityPolicyFuzzTest {

    private val random = Random(2026112243L)

    private val idPool = listOf("", " ", "call-1", "call-2", "Call-1", "CALL-1", "call-1 ", " call-1", "c")

    private val criticalPool = listOf(
        "offer", "answer", "reject", "busy", "hang-up",
        "OFFER", "ANSWER", "REJECT", "BUSY", "HANG-UP",
        "Offer", "Hang-Up", "  offer  ", " offer", "busy ",
        "hang_up", "hangup", "hang up", "offers", "answer2",
        "candidate", "ice", "bye", "", " ", "hang-up-",
    )

    private val criticalSet = setOf("offer", "answer", "reject", "busy", "hang-up")

    // 挂断动作必须精确命中当前会话 id：不 trim、不忽略大小写，空串只等于空串。
    private fun oracleAcceptHangUp(activeCallId: String, requestedCallId: String): Boolean =
        activeCallId == requestedCallId

    // 关键信令类型先 trim + lowercase 再查表。
    private fun oracleIsCritical(type: String): Boolean =
        CallReliabilityPolicy.normalizeSignalingType(type) in criticalSet

    @Test
    fun `shouldAcceptHangUpAction matches exact equality for random ids`() {
        repeat(300) {
            val active = idPool.random(random)
            val requested = idPool.random(random)
            assertEquals(
                oracleAcceptHangUp(active, requested),
                CallReliabilityPolicy.shouldAcceptHangUpAction(active, requested),
                "active=$active requested=$requested",
            )
        }
    }

    @Test
    fun `shouldAcceptHangUpAction pins blank equals blank only`() {
        assertTrue(CallReliabilityPolicy.shouldAcceptHangUpAction("", ""))
        assertFalse(CallReliabilityPolicy.shouldAcceptHangUpAction("", "call-1"))
        assertFalse(CallReliabilityPolicy.shouldAcceptHangUpAction("call-1", ""))
        assertTrue(CallReliabilityPolicy.shouldAcceptHangUpAction("call-1", "call-1"))
        assertFalse(CallReliabilityPolicy.shouldAcceptHangUpAction("Call-1", "call-1"))
        assertFalse(CallReliabilityPolicy.shouldAcceptHangUpAction("call-1", "call-1 "))
    }

    @Test
    fun `isCriticalSignalingType matches the oracle for random types`() {
        repeat(300) {
            val type = criticalPool.random(random)
            assertEquals(
                oracleIsCritical(type),
                CallReliabilityPolicy.isCriticalSignalingType(type),
                "type=$type",
            )
        }
    }

    @Test
    fun `isCriticalSignalingType pins the critical set`() {
        for (type in listOf("offer", "answer", "reject", "busy", "hang-up")) {
            assertTrue(CallReliabilityPolicy.isCriticalSignalingType(type), "type=$type")
            assertTrue(CallReliabilityPolicy.isCriticalSignalingType(type.uppercase()), "type=$type")
        }
        assertTrue(CallReliabilityPolicy.isCriticalSignalingType("  OFFER  "))
        assertTrue(CallReliabilityPolicy.isCriticalSignalingType("Hang-Up"))
        for (type in listOf("hang_up", "hangup", "hang up", "offers", "answer2", "candidate", "ice", "bye", "", " ")) {
            assertFalse(CallReliabilityPolicy.isCriticalSignalingType(type), "type=$type")
        }
    }
}
