package com.maodouchat.attachment

data class Summary(
    val active: Int = 0,
    val queued: Int = 0,
    val failed: Int = 0,
    val completed: Int = 0,
    val totalBytes: Long = 0L,
    val uploadedBytes: Long = 0L
) {
    val hasFailures: Boolean get() = failed > 0
    val hasActivity: Boolean get() = active > 0 || queued > 0
}
