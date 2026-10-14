package com.maodouchat.network

/**
 * 登录/注册等面向用户的失败文案：HTTP 优先用服务端 message；
 * 网络/超时/无效响应没有 serverMessage（Exception.message 为 null），必须落到非空兜底。
 */
internal fun Throwable.toUserFacingMessage(
    networkMessage: String,
    timeoutMessage: String,
    invalidResponseMessage: String,
    fallbackMessage: String,
): String {
    if (this is ApiException) {
        val server = serverMessage?.trim().orEmpty()
        if (server.isNotEmpty()) return server
        return when (kind) {
            ApiFailureKind.TIMEOUT -> timeoutMessage
            ApiFailureKind.NETWORK -> networkMessage
            ApiFailureKind.INVALID_RESPONSE -> invalidResponseMessage
            ApiFailureKind.HTTP, ApiFailureKind.UNEXPECTED -> fallbackMessage
        }
    }
    return message?.trim()?.takeIf { it.isNotEmpty() } ?: fallbackMessage
}

internal fun apiExceptionForIOException(error: java.io.IOException): ApiException {
    val connectionWasNeverEstablished = generateSequence<Throwable>(error) { it.cause }
        .any {
            it is java.net.UnknownHostException ||
                it is java.net.ConnectException ||
                it is java.net.NoRouteToHostException
        }
    return ApiException(
        kind = if (error is java.net.SocketTimeoutException) ApiFailureKind.TIMEOUT else ApiFailureKind.NETWORK,
        requestMayHaveReachedServer = !connectionWasNeverEstablished,
        cause = error
    )
}

internal object TokenExpiredEventPolicy {
    fun shouldHandle(
        eventOwnerUserId: String,
        eventSessionGeneration: Long,
        currentOwnerUserId: String?,
        currentSessionGeneration: Long,
    ): Boolean = eventOwnerUserId.isNotBlank() &&
        eventOwnerUserId == currentOwnerUserId &&
        eventSessionGeneration == currentSessionGeneration
}
