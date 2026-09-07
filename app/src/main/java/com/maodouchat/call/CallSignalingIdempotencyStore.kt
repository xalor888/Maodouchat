package com.maodouchat.call

import com.maodouchat.webrtc.CallReliabilityPolicy

/**
 * P03：通话信令幂等记忆（有界）。优先用服务端/客户端 idempotencyKey；
 * 空 key 时回退到 callId|from|type|payload（完整 payload，避免 hash 碰撞丢 ICE）。
 */
class CallSignalingIdempotencyStore(
    private val maxSize: Int = 128,
) {
    private val keys = LinkedHashSet<String>()

    fun remember(
        callId: String,
        fromUserId: String,
        type: String,
        payload: String,
        idempotencyKey: String = "",
    ): Boolean {
        val key = if (idempotencyKey.isNotBlank()) {
            "id|$idempotencyKey"
        } else {
            val normalized = CallReliabilityPolicy.normalizeSignalingType(type)
            "$callId|$fromUserId|$normalized|$payload"
        }
        if (!keys.add(key)) return false
        while (keys.size > maxSize) {
            val oldest = keys.firstOrNull() ?: break
            keys.remove(oldest)
        }
        return true
    }

    fun clear() {
        keys.clear()
    }
}
