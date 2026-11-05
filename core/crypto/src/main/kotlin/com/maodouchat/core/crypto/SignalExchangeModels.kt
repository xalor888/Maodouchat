package com.maodouchat.core.crypto

enum class SignalExchangeFailure {
    SIGNED_PREKEY_MISSING,
    HTTP,
    EMPTY_RESPONSE,
    TIMEOUT,
    NETWORK,
    INVALID_RESPONSE,
    UNEXPECTED
}
