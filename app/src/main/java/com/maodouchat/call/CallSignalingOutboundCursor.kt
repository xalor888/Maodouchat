package com.maodouchat.call

import com.maodouchat.webrtc.CallReliabilityPolicy
import java.util.concurrent.atomic.AtomicLong

/**
 * P03：本端通话信令游标。每次新通话 [begin] 抬升 epoch；每条出站信令递增 sequence，
 * 并生成可复用的 idempotencyKey（REST/WS 重试同一键）。
 */
class CallSignalingOutboundCursor {
    @Volatile
    private var epoch: Long = 0L
    private val sequence = AtomicLong(0L)

    data class Ticket(
        val epoch: Long,
        val sequence: Long,
        val idempotencyKey: String,
    )

    fun begin(epochSeed: Long = System.currentTimeMillis()) {
        epoch = epochSeed.coerceAtLeast(1L)
        sequence.set(0L)
    }

    fun clear() {
        epoch = 0L
        sequence.set(0L)
    }

    fun currentEpoch(): Long = epoch

    fun next(callId: String, type: String): Ticket {
        val activeEpoch = epoch.coerceAtLeast(1L)
        if (epoch == 0L) epoch = activeEpoch
        val seq = sequence.incrementAndGet()
        val normalized = CallReliabilityPolicy.normalizeSignalingType(type)
        return Ticket(
            epoch = activeEpoch,
            sequence = seq,
            idempotencyKey = "$callId|$activeEpoch|$seq|$normalized",
        )
    }
}
