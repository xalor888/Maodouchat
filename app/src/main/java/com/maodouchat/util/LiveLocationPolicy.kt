package com.maodouchat.util

import com.maodouchat.data.model.LocationPayload
import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageType

/**
 * Client-side live location policy (E2EE LOCATION payloads).
 * Server only stores ciphertext; session continuity is via [LocationPayload.sessionId].
 */
object LiveLocationPolicy {
    const val MIN_UPDATE_INTERVAL_MS = 12_000L
    const val DEFAULT_DURATION_MS = 15L * 60_000L
    val DURATION_OPTIONS_MS = listOf(
        15L * 60_000L,
        60L * 60_000L,
        8L * 60L * 60_000L
    )

    fun isLive(payload: LocationPayload?, now: Long = System.currentTimeMillis()): Boolean {
        if (payload == null || !payload.live) return false
        val until = payload.liveUntil ?: return true
        return until > now
    }

    fun isLiveMessage(message: Message, now: Long = System.currentTimeMillis()): Boolean {
        if (message.type != MessageType.LOCATION) return false
        return isLive(message.parsedLocation(), now)
    }

    fun remainingMs(payload: LocationPayload, now: Long = System.currentTimeMillis()): Long {
        val until = payload.liveUntil ?: return 0L
        return (until - now).coerceAtLeast(0L)
    }

    fun formatRemaining(ms: Long): String {
        val totalSec = (ms / 1000L).coerceAtLeast(0L)
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        return when {
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "${m}m"
            else -> "${totalSec}s"
        }
    }

    fun remainingFromUntil(until: Long?, now: Long = System.currentTimeMillis()): Long {
        if (until == null) return 0L
        return (until - now).coerceAtLeast(0L)
    }
}
