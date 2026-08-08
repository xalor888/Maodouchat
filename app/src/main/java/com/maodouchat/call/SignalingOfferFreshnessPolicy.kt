package com.maodouchat.call

/**
 * Pure gates for whether a polled signaling offer is still actionable.
 * Server TTL is [SignalingRepository.SIGNALING_TTL_MS] (120s); client uses the same window
 * as [IncomingCallCoordinator.STALE_MS] so cold-start poll cannot re-ring ancient offers.
 */
object SignalingOfferFreshnessPolicy {

    /**
     * @param timestampMillis server store time; non-positive means "unknown" → accept
     *   (legacy rows / clock skew edge: prefer hang-up path over silent drop of live offers)
     * @param nowMillis current wall clock
     * @param maxAgeMs freshness window (default matches coordinator STALE)
     */
    fun isOfferFresh(
        timestampMillis: Long,
        nowMillis: Long,
        maxAgeMs: Long = IncomingCallCoordinator.STALE_MS,
    ): Boolean {
        if (timestampMillis <= 0L) return true
        if (maxAgeMs <= 0L) return false
        val age = nowMillis - timestampMillis
        // Future timestamps (clock skew): treat as fresh.
        if (age < 0L) return true
        return age <= maxAgeMs
    }

    fun shouldKeepOffer(
        type: String,
        callId: String,
        terminatedCallIds: Set<String>,
        timestampMillis: Long,
        nowMillis: Long,
        maxAgeMs: Long = IncomingCallCoordinator.STALE_MS,
    ): Boolean {
        if (!type.equals("offer", ignoreCase = true)) return false
        if (callId.isNotBlank() && callId in terminatedCallIds) return false
        return isOfferFresh(timestampMillis, nowMillis, maxAgeMs)
    }
}
