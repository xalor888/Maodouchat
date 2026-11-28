package com.maodouchat.domain.calls

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class CallSignalingOutboundCursorFuzzTest {

    private val random = Random(2026_11_22L)

    // 与生产侧逐行同构的模型游标：begin 把 seed 抬到 ≥1 并清零序列；
    // 未 begin 时首个 next 把 epoch 抬到 1；key 为 call|epoch|seq|归一化类型。
    private class CursorModel {
        var epoch: Long = 0L
        var sequence: Long = 0L

        fun begin(seed: Long) {
            epoch = maxOf(seed, 1L)
            sequence = 0L
        }

        fun clear() {
            epoch = 0L
            sequence = 0L
        }

        fun next(callId: String, type: String): Ticket {
            val activeEpoch = maxOf(epoch, 1L)
            if (epoch == 0L) epoch = activeEpoch
            sequence += 1L
            return Ticket(
                epoch = activeEpoch,
                sequence = sequence,
                idempotencyKey = "$callId|$activeEpoch|$sequence|${type.trim().lowercase()}",
            )
        }
    }

    private val seedPool = listOf(
        0L, 1L, -1L, -100L, 42L, Long.MAX_VALUE, Long.MIN_VALUE,
    )

    private val typePool = listOf(
        "OFFER", "offer", " ICE-CANDIDATE ", "answer", "", "  ", "Hang-Up",
    )

    private fun randomSeed(): Long =
        if (random.nextInt(2) == 0) seedPool[random.nextInt(seedPool.size)]
        else random.nextLong()

    private fun randomType(): String =
        if (random.nextInt(2) == 0) typePool[random.nextInt(typePool.size)]
        else buildString {
            repeat(random.nextInt(8)) { append("abOFFER- ".random(random)) }
        }

    private fun randomCallId(): String =
        listOf("call-a", "", " ", "c1", "call-${random.nextInt(100)}")[random.nextInt(5)]

    @Test
    fun `random operation sequences match the model cursor`() {
        repeat(30) {
            val cursor = CallSignalingOutboundCursor()
            val model = CursorModel()
            repeat(20) {
                when (random.nextInt(4)) {
                    0 -> {
                        val seed = randomSeed()
                        cursor.begin(seed)
                        model.begin(seed)
                        assertEquals(model.epoch, cursor.currentEpoch(), "seed=$seed")
                    }
                    1 -> {
                        cursor.clear()
                        model.clear()
                        assertEquals(0L, cursor.currentEpoch())
                    }
                    else -> {
                        val callId = randomCallId()
                        val type = randomType()
                        assertEquals(
                            model.next(callId, type),
                            cursor.next(callId, type),
                            "callId=$callId type=$type",
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `begin coerces non-positive seeds to epoch one`() {
        val cases = listOf(
            0L to 1L,
            -1L to 1L,
            -100L to 1L,
            Long.MIN_VALUE to 1L,
            1L to 1L,
            42L to 42L,
            Long.MAX_VALUE to Long.MAX_VALUE,
        )
        for ((seed, expectedEpoch) in cases) {
            val cursor = CallSignalingOutboundCursor()
            cursor.begin(seed)
            assertEquals(expectedEpoch, cursor.currentEpoch(), "seed=$seed")
            val ticket = cursor.next("call-a", "OFFER")
            assertEquals(expectedEpoch, ticket.epoch, "seed=$seed")
            assertEquals(1L, ticket.sequence, "seed=$seed")
            assertEquals("call-a|$expectedEpoch|1|offer", ticket.idempotencyKey, "seed=$seed")
        }
    }

    @Test
    fun `next without begin starts at epoch one`() {
        val cursor = CallSignalingOutboundCursor()
        assertEquals(0L, cursor.currentEpoch())
        val first = cursor.next("call-a", "OFFER")
        assertEquals(Ticket(1L, 1L, "call-a|1|1|offer"), first)
        assertEquals(1L, cursor.currentEpoch())
        val second = cursor.next("call-a", "ice-candidate")
        assertEquals(Ticket(1L, 2L, "call-a|1|2|ice-candidate"), second)
    }

    @Test
    fun `clear resets epoch and sequence`() {
        val cursor = CallSignalingOutboundCursor()
        cursor.begin(42L)
        cursor.next("call-a", "OFFER")
        cursor.next("call-a", "OFFER")
        cursor.clear()
        assertEquals(0L, cursor.currentEpoch())
        val first = cursor.next("call-a", "OFFER")
        assertEquals(Ticket(1L, 1L, "call-a|1|1|offer"), first)
    }

    @Test
    fun `sequence increments by one per next call within an epoch`() {
        val cursor = CallSignalingOutboundCursor()
        cursor.begin(7L)
        repeat(200) { index ->
            val ticket = cursor.next("call-a", "offer")
            assertEquals(7L, ticket.epoch, "index=$index")
            assertEquals(index + 1L, ticket.sequence, "index=$index")
            assertEquals("call-a|7|${index + 1L}|offer", ticket.idempotencyKey, "index=$index")
        }
    }

    @Test
    fun `signaling type is trimmed and lowercased in the key`() {
        val cursor = CallSignalingOutboundCursor()
        cursor.begin(3L)
        val cases = listOf(
            " OFFER " to "offer",
            "Ice-Candidate" to "ice-candidate",
            "HANG-UP" to "hang-up",
            "" to "",
            "  " to "",
        )
        for ((type, normalized) in cases) {
            val ticket = cursor.next("c", type)
            assertEquals("c|3|${ticket.sequence}|$normalized", ticket.idempotencyKey, "type=$type")
        }
    }
}
