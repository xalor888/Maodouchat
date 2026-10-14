package com.maodouchat.crypto

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
