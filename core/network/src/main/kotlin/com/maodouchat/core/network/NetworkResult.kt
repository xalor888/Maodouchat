package com.maodouchat.core.network

sealed interface NetworkResult<out T> {
    data class Success<T>(val value: T) : NetworkResult<T>

    data class Failure(
        val kind: Kind,
        val message: String? = null,
        val statusCode: Int? = null,
        val retryAfterMillis: Long? = null,
    ) : NetworkResult<Nothing>

    enum class Kind {
        AUTHENTICATION,
        AUTHORIZATION,
        VALIDATION,
        CONFLICT,
        NOT_FOUND,
        RATE_LIMITED,
        TRANSIENT,
        PERMANENT,
        CANCELLED,
    }
}

inline fun <T> NetworkResult<T>.getOrThrow(): T = when (this) {
    is NetworkResult.Success -> value
    is NetworkResult.Failure -> throw NetworkFailureException(this)
}

class NetworkFailureException(val failure: NetworkResult.Failure) :
    IllegalStateException(failure.message ?: failure.kind.name)
