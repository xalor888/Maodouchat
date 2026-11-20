package com.maodouchat.domain.messaging

import com.maodouchat.core.model.ApiException
import com.maodouchat.core.model.ApiFailureKind
import com.maodouchat.core.model.HttpResult

/** HTTP 错误语义：异常构造、会话变更哨兵（错误体解析在 ApiErrorBodyPolicy）。 */
object ApiHttpErrors {

    fun apiExceptionFromHttp(
        statusCode: Int,
        body: String,
        retryAfterHeader: String? = null,
        url: String? = null
    ): ApiException {
        val error = ApiErrorBodyPolicy.parseErrorResponse(body)
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
