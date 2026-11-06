package com.maodouchat.core.model

/**
 * Pure gates for whether a polled signaling offer is still actionable.
 * Server TTL is 120s; client uses the same 120s window so cold-start poll cannot
 * re-ring ancient offers. DEFAULT_MAX_AGE_MS mirrors IncomingCallCoordinator.STALE_MS.
 */
object SignalingOfferFreshnessPolicy {

    /** Freshness window; same value as IncomingCallCoordinator.STALE_MS. */
    const val DEFAULT_MAX_AGE_MS = 120_000L

    /**
     * @param timestampMillis server store time; non-positive means "unknown" → accept
     *   (legacy rows / clock skew edge: prefer hang-up path over silent drop of live offers)
     * @param nowMillis current wall clock
     * @param maxAgeMs freshness window (default matches coordinator STALE)
     */
    fun isOfferFresh(
        timestampMillis: Long,
        nowMillis: Long,
        maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
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
        maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
    ): Boolean {
        if (!type.equals("offer", ignoreCase = true)) return false
        if (callId.isNotBlank() && callId in terminatedCallIds) return false
        return isOfferFresh(timestampMillis, nowMillis, maxAgeMs)
    }
}
