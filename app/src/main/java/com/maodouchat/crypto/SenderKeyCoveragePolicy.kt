package com.maodouchat.crypto

/** 判断本端是否需要重新分发群 sender key 的纯决策。 */
object SenderKeyCoveragePolicy {

    fun requiresDistribution(
        hasLocalDistribution: Boolean,
        requestedEpoch: Long,
        statusEpoch: Long,
        targetStatuses: List<String>
    ): Boolean = assess(
        hasLocalDistribution = hasLocalDistribution,
        requestedEpoch = requestedEpoch,
        statusEpoch = statusEpoch,
        targetStatuses = targetStatuses
    ).requiresDistribution

    /**
     * Explains coverage for UI / retry copy. Decision matches historical [requiresDistribution]:
     * - no local mint → distribute
     * - status epoch ≠ group member revision → distribute
     * - any non-SENT target → distribute
     * - empty target list after epoch match → complete (solo / no remote devices)
     */
    fun assess(
        hasLocalDistribution: Boolean,
        requestedEpoch: Long,
        statusEpoch: Long,
        targetStatuses: List<String>,
        /** When server reports total devices; null means "use target list size". */
        reportedTotal: Int? = null
    ): Assessment {
        if (!hasLocalDistribution) {
            return Assessment(requiresDistribution = true, reason = Reason.LOCAL_MISSING)
        }
        if (statusEpoch != requestedEpoch) {
            return Assessment(requiresDistribution = true, reason = Reason.EPOCH_MISMATCH)
        }
        val total = reportedTotal ?: targetStatuses.size
        if (total <= 0 && targetStatuses.isEmpty()) {
            // Historical contract: empty coverage list after epoch match is complete.
            // UI can still show "no record" when reportedTotal is explicitly 0 and epoch was 0.
            return if (reportedTotal == 0 && statusEpoch <= 0L) {
                Assessment(requiresDistribution = true, reason = Reason.NO_SERVER_RECORD)
            } else {
                Assessment(requiresDistribution = false, reason = Reason.COMPLETE)
            }
        }
        var failed = 0
        var pending = 0
        var unknown = 0
        var sent = 0
        targetStatuses.forEach { raw ->
            when {
                raw.equals(STATUS_SENT, ignoreCase = true) -> sent++
                raw.equals(STATUS_FAILED, ignoreCase = true) -> failed++
                raw.equals(STATUS_PENDING, ignoreCase = true) -> pending++
                else -> unknown++
            }
        }
        return when {
            failed > 0 -> Assessment(true, Reason.FAILED_TARGETS, failed, pending, unknown, sent)
            pending > 0 -> Assessment(true, Reason.PENDING_TARGETS, failed, pending, unknown, sent)
            unknown > 0 -> Assessment(true, Reason.UNKNOWN_TARGETS, failed, pending, unknown, sent)
            else -> Assessment(false, Reason.COMPLETE, failed, pending, unknown, sent)
        }
    }

    private const val STATUS_SENT = "SENT"
    private const val STATUS_FAILED = "FAILED"
    private const val STATUS_PENDING = "PENDING"
}
