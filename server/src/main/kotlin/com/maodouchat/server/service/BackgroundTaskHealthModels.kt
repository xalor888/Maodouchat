package com.maodouchat.server.service

data class TaskStatus(
    val name: String,
    val lastSuccessAt: Long? = null,
    val lastErrorAt: Long? = null,
    val lastError: String? = null,
    val consecutiveFailures: Int = 0,
) {
    fun healthy(): Boolean = consecutiveFailures < BackgroundTaskHealth.MAX_CONSECUTIVE_FAILURES
    fun known(): Boolean = lastSuccessAt != null || lastErrorAt != null
}
