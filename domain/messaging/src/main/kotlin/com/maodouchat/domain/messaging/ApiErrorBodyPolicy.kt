package com.maodouchat.domain.messaging

import kotlinx.serialization.json.Json

private val apiErrorJson = Json { ignoreUnknownKeys = true }

// HTTP 错误体解析：错误 JSON 解码 + Retry-After（body 优先、header 兜底、上限一天）。
object ApiErrorBodyPolicy {

    fun parseErrorResponse(body: String): ErrorResponse? = try {
        apiErrorJson.decodeFromString(ErrorResponse.serializer(), body)
    } catch (_: Exception) {
        null
    }

    fun parseError(body: String): String? {
        return parseErrorResponse(body)?.error?.takeIf { it.isNotBlank() }
    }

    fun parseRetryAfterSeconds(body: String, headerValue: String? = null): Long? {
        val fromBody = parseErrorResponse(body)?.retryAfterSeconds?.takeIf { it > 0L }
        if (fromBody != null) return fromBody.coerceAtMost(86_400L)
        val raw = headerValue?.trim().orEmpty()
        if (raw.isEmpty()) return null
        raw.toLongOrNull()?.takeIf { it > 0L }?.let { return it.coerceAtMost(86_400L) }
        // HTTP-date Retry-After is rare for our APIs; ignore parse failures.
        return null
    }
}
