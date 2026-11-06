package com.maodouchat.network

internal fun isNonRecoverableWebSocketNetworkError(error: Throwable): Boolean =
    generateSequence(error) { it.cause }.any { it is javax.net.ssl.SSLException }
