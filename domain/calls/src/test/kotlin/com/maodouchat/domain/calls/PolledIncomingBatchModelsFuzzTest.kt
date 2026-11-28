package com.maodouchat.domain.calls

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class PolledIncomingBatchModelsFuzzTest {

    private val random = Random(0xCA11_2026L)

    private fun randomString(): String {
        val pool = ('a'..'z') + ('A'..'Z') + ('0'..'9') + listOf(' ', '_', '-', '.', 'é', '中', '文')
        return (0 until random.nextInt(0, 12)).map { pool.random(random) }.joinToString("")
    }

    private fun randomLong(): Long =
        when (random.nextInt(5)) {
            0 -> 0L
            1 -> Long.MAX_VALUE
            2 -> Long.MIN_VALUE
            3 -> random.nextLong(-1000, 1000)
            else -> random.nextLong()
        }

    private fun randomSignalMessage(): SignalMessage =
        SignalMessage(
            id = randomString(),
            fromUserId = randomString(),
            type = randomString(),
            payload = randomString(),
            timestamp = randomLong(),
            callId = randomString(),
            groupId = randomString(),
            groupMemberIds = List(random.nextInt(0, 4)) { randomString() },
            groupInvite = random.nextBoolean(),
            epoch = randomLong(),
            sequence = randomLong(),
            idempotencyKey = randomString(),
        )

    private fun randomMissedRecordParams(): MissedRecordParams =
        MissedRecordParams(
            signalingCallId = randomString(),
            fromUserId = randomString(),
            callerName = randomString(),
            isVideo = random.nextBoolean(),
            isGroup = random.nextBoolean(),
        )

    private fun randomTerminalDecision(): TerminalDecision =
        TerminalDecision(
            callId = randomString(),
            missedRecord = if (random.nextBoolean()) null else randomMissedRecordParams(),
        )

    private fun randomBatchDecision(): PolledIncomingBatchDecision =
        PolledIncomingBatchDecision(
            terminals = List(random.nextInt(0, 4)) { randomTerminalDecision() },
            cancelPreferCallId = random.nextBoolean(),
            primary = if (random.nextBoolean()) null else randomSignalMessage(),
            shouldNavigate = random.nextBoolean(),
            busyReplies = List(random.nextInt(0, 4)) { randomSignalMessage() },
        )

    @Test
    fun `missed record params copy and equality over random fields`() {
        repeat(200) {
            val params = randomMissedRecordParams()
            assertEquals(params, params.copy())
            assertEquals(params.hashCode(), params.copy().hashCode())
            assertNotEquals(params, params.copy(signalingCallId = params.signalingCallId + "x"))
            assertNotEquals(params, params.copy(fromUserId = params.fromUserId + "x"))
            assertNotEquals(params, params.copy(callerName = params.callerName + "x"))
            assertNotEquals(params, params.copy(isVideo = !params.isVideo))
            assertNotEquals(params, params.copy(isGroup = !params.isGroup))
        }
    }

    @Test
    fun `terminal decision copy and equality over random fields`() {
        repeat(200) {
            val decision = randomTerminalDecision()
            assertEquals(decision, decision.copy())
            assertEquals(decision.hashCode(), decision.copy().hashCode())
            assertNotEquals(decision, decision.copy(callId = decision.callId + "x"))
            val otherRecord = (decision.missedRecord ?: randomMissedRecordParams())
                .copy(callerName = (decision.missedRecord?.callerName ?: "") + "x")
            assertNotEquals(decision, decision.copy(missedRecord = otherRecord))
            assertEquals(decision, decision.copy(missedRecord = decision.missedRecord))
        }
    }

    @Test
    fun `batch decision copy and equality over random fields`() {
        repeat(200) {
            val decision = randomBatchDecision()
            assertEquals(decision, decision.copy())
            assertEquals(decision.hashCode(), decision.copy().hashCode())
            assertNotEquals(decision, decision.copy(terminals = decision.terminals + randomTerminalDecision()))
            assertNotEquals(decision, decision.copy(cancelPreferCallId = !decision.cancelPreferCallId))
            assertNotEquals(decision, decision.copy(shouldNavigate = !decision.shouldNavigate))
            assertNotEquals(decision, decision.copy(busyReplies = decision.busyReplies + randomSignalMessage()))
            val otherPrimary = (decision.primary ?: randomSignalMessage())
                .copy(id = (decision.primary?.id ?: "") + "x")
            assertNotEquals(decision, decision.copy(primary = otherPrimary))
            assertEquals(decision, decision.copy(primary = decision.primary))
        }
    }
}
