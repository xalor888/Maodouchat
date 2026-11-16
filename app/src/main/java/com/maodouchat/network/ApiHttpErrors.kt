package com.maodouchat.network

import com.maodouchat.core.model.ApiException
import com.maodouchat.core.model.ApiFailureKind
import com.maodouchat.core.model.HttpResult
import com.maodouchat.domain.messaging.ApiErrorBodyPolicy

/** HTTP 错误语义：异常构造、会话变更哨兵（错误体解析在 ApiErrorBodyPolicy）。 */
internal object ApiHttpErrors {

    fun apiExceptionFromHttp(
        statusCode: Int,
        body: String,
        retryAfterHeader: String? = null,
        url: String? = null
    ): ApiException {
        val error = ApiErrorBodyPolicy.parseErrorResponse(body)
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
            retryAfterSeconds = ApiErrorBodyPolicy.parseRetryAfterSeconds(body, retryAfterHeader)
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
