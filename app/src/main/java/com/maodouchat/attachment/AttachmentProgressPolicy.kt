package com.maodouchat.attachment

/**
 * UI progress mapping for attachment transfers (§6 pure extract).
 * Avoids flooding Compose recomposition with sub-percent noise.
 */
object AttachmentProgressPolicy {
    /** Minimum absolute progress delta to publish before completion. */
    const val MIN_PUBLISH_DELTA = 0.01f

    /**
     * Map byte progress into a sub-range of the overall bar.
     * @return null when total invalid or change too small (and not finished).
     */
    fun mapSegmentProgress(
        completed: Long,
        total: Long,
        start: Float,
        end: Float,
        previousPublished: Float
    ): Float? {
        if (total <= 0L) return null
        val startClamped = start.coerceIn(0f, 1f)
        val endClamped = end.coerceIn(startClamped, 1f)
        val ratio = (completed.toDouble() / total.toDouble()).coerceIn(0.0, 1.0)
        // Compare the delta in Double: in Float, 0.51f - 0.5f is just under
        // 0.01f, so a true one-percent jump was wrongly suppressed.
        val progressValue = (startClamped.toDouble() + ratio * (endClamped - startClamped)).coerceIn(0.0, 1.0)
        val progress = progressValue.toFloat()
        val prev = if (previousPublished < 0f) -1f else previousPublished.coerceIn(0f, 1f)
        if (prev >= 0f && kotlin.math.abs(prev.toDouble() - progressValue) < MIN_PUBLISH_DELTA.toDouble() && progress < 1f) {
            return null
        }
        return progress
    }
}
