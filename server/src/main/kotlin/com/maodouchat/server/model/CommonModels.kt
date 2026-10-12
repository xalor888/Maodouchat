package com.maodouchat.server.model

import kotlinx.serialization.Serializable

// 通用 DTO：错误体与健康检查。


@Serializable
data class ErrorResponse(
    val error: String,
    val code: String? = null,
    val retryAfterSeconds: Long? = null,
    val messageId: String? = null
)


@Serializable
data class HealthStatusResponse(
    val status: String,
    val service: String = "maodouchat-server",
    val checks: Map<String, String> = emptyMap(),
    val timestamp: Long = System.currentTimeMillis()
)
