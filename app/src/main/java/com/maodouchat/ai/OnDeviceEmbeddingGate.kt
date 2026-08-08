package com.maodouchat.ai

/**
 * Hard gate for on-device embedding / local vector index work (W4-04).
 * Implementation PRs must keep [isImplementationAllowed] false until the eval doc is filled.
 */
object OnDeviceEmbeddingGate {
    /** Max APK size growth (bytes) allowed for embedding weights + native runtime on arm64. */
    const val MAX_APK_DELTA_BYTES = 3L * 1024L * 1024L

    /** Release APK ceiling after merge (bytes). Aligns with docs/size-baseline.md. */
    const val MAX_RELEASE_APK_BYTES = 30L * 1024L * 1024L

    /** Cold full reindex budget for 1000 text messages (ms). */
    const val MAX_COLD_INDEX_MS_PER_1K = 5_000L

    /** Per-message incremental index budget off main thread (ms). */
    const val MAX_INCREMENTAL_INDEX_MS = 20L

    /**
     * Ship/feature flag. Remains false until docs/on-device-embedding-eval-gate.md
     * has measured pass rows for size, power, and account purge.
     */
    const val isImplementationAllowed: Boolean = false

    data class SizeAssessment(
        val allowed: Boolean,
        val reasons: List<String>
    )

    fun assessApkBudget(currentApkBytes: Long, embeddingDeltaBytes: Long): SizeAssessment {
        val reasons = mutableListOf<String>()
        if (embeddingDeltaBytes > MAX_APK_DELTA_BYTES) {
            reasons += "embedding_delta_exceeds_${MAX_APK_DELTA_BYTES}"
        }
        if (currentApkBytes + embeddingDeltaBytes > MAX_RELEASE_APK_BYTES) {
            reasons += "release_apk_would_exceed_${MAX_RELEASE_APK_BYTES}"
        }
        if (!isImplementationAllowed) {
            reasons += "implementation_flag_false"
        }
        return SizeAssessment(allowed = reasons.isEmpty(), reasons = reasons)
    }

    fun requiresOwnerScopedKeys(): Boolean = true

    fun mustPurgeOnAccountChange(): Boolean = true
}
