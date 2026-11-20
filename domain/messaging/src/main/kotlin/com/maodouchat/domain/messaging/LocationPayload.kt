package com.maodouchat.domain.messaging

import kotlinx.serialization.Serializable

@Serializable
data class LocationPayload(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float? = null,
    val label: String = "",
    val capturedAt: Long = System.currentTimeMillis(),
    /** Telegram-style live location session (client-driven updates). */
    val live: Boolean = false,
    val liveUntil: Long? = null,
    val sessionId: String? = null
)
