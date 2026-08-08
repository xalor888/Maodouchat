package com.maodouchat.call

/**
 * Logout/account-switch must end any active call under the *current* session epoch so the
 * live [CallViewModel] still accepts the hang-up, then bump the epoch so leftover
 * [CallActionBus] / deep-link / wake events cannot affect the next account.
 */
object CallSessionEpochPolicy {
    /**
     * @return true when [hangUpGeneration] matches [currentGeneration] at hang-up time
     *         (active VM should accept) and [postInvalidateGeneration] is strictly newer
     *         (next account must drop the same event if still buffered).
     */
    fun isHangUpThenInvalidateSafe(
        hangUpGeneration: Long,
        currentGenerationAtHangUp: Long,
        postInvalidateGeneration: Long,
    ): Boolean =
        hangUpGeneration == currentGenerationAtHangUp &&
            postInvalidateGeneration > hangUpGeneration
}
