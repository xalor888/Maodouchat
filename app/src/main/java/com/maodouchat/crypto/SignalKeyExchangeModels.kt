package com.maodouchat.crypto

enum class SignalExchangeFailure {
    SIGNED_PREKEY_MISSING,
    HTTP,
    EMPTY_RESPONSE,
    TIMEOUT,
    NETWORK,
    INVALID_RESPONSE,
    UNEXPECTED
}

class SignalExchangeException(
    val failure: SignalExchangeFailure,
    val statusCode: Int? = null,
    val serverMessage: String? = null,
    /** Stable machine-readable code from the API (for example DEVICE_ID_CONFLICT). */
    val serverCode: String? = null,
    cause: Throwable? = null
) : Exception(serverMessage, cause)
