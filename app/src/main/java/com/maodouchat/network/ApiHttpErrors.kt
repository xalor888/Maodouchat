package com.maodouchat.network

/** HTTP 错误语义：错误体解析、异常构造、会话变更哨兵（自 ApiService 抽出，零行为改动）。 */
internal object ApiHttpErrors {

    data class HttpResult(val code: Int, val isSuccessful: Boolean, val body: String)

    fun parseErrorResponse(body: String): ErrorResponse? = try {
        ApiService.json.decodeFromString(ErrorResponse.serializer(), body)
    } catch (e: Exception) {
        android.util.Log.w("ApiService", "parseError: non-JSON error body", e)
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

    fun apiExceptionFromHttp(
        statusCode: Int,
        body: String,
        retryAfterHeader: String? = null,
        url: String? = null
    ): ApiException {
        val error = parseErrorResponse(body)
        // 9.305：服务端错误必须带端点上下文——实测群分发 500 只有笼统「服务器内部错误」，
        // 无法定位是哪个接口炸的；4xx/5xx 一律记录（路径去 query 防敏感参数泄漏）
        if (statusCode >= 400) {
            android.util.Log.w("ApiService", "HTTP $statusCode ${url?.substringBefore('?') ?: "?"} code=${error?.code} msg=${error?.error} body=${body.take(200)}")
        }
        return ApiException(
            kind = ApiFailureKind.HTTP,
            statusCode = statusCode,
            serverMessage = error?.error,
            serverCode = error?.code,
            retryAfterSeconds = parseRetryAfterSeconds(body, retryAfterHeader)
        )
    }

    fun sessionChangedResult(): HttpResult = HttpResult(
        code = 409,
        isSuccessful = false,
        body = "{\"error\":\"session_changed\",\"code\":\"SESSION_CHANGED\"}"
    )

    fun sessionChangedException(): ApiException = apiExceptionFromHttp(
        sessionChangedResult().code,
        sessionChangedResult().body
    )
}
