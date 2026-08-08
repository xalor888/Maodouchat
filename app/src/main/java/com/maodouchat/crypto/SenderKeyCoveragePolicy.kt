package com.maodouchat.crypto

/** Pure decision policy for whether this sender must fan out its group Sender Key again. */
object SenderKeyCoveragePolicy {
    enum class Reason {
        LOCAL_MISSING,
        EPOCH_MISMATCH,
        FAILED_TARGETS,
        PENDING_TARGETS,
        UNKNOWN_TARGETS,
        /** Server status missing/empty while local key expects fan-out (epoch may still match). */
        NO_SERVER_RECORD,
        COMPLETE
    }

    data class Assessment(
        val requiresDistribution: Boolean,
        val reason: Reason,
        val failedCount: Int = 0,
        val pendingCount: Int = 0,
        val unknownCount: Int = 0,
        val sentCount: Int = 0
    )

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
