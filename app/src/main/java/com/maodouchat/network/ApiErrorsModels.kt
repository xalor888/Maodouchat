package com.maodouchat.network

enum class ApiFailureKind {
    HTTP,
    TIMEOUT,
    NETWORK,
    INVALID_RESPONSE,
    UNEXPECTED
}

class ApiException(
    val kind: ApiFailureKind,
    val statusCode: Int? = null,
    val serverMessage: String? = null,
    val serverCode: String? = null,
    /**
     * `true` means the request may already have reached the server. Callers that can incur a
     * charge or another non-idempotent side effect must require an explicit user retry.
     */
    val requestMayHaveReachedServer: Boolean = true,
    /** Server-provided Retry-After / body retryAfterSeconds when present. */
    val retryAfterSeconds: Long? = null,
    cause: Throwable? = null
) : Exception(serverMessage, cause)
