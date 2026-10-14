package com.maodouchat.network

internal fun resolveUserVisibility(
    currentIsOnline: Boolean,
    currentStatus: String,
    currentLastSeen: Long,
    eventIsOnline: Boolean,
    eventLastSeen: Long,
    onlineRevoked: Boolean,
    statusRevoked: Boolean
): ResolvedUserVisibility = ResolvedUserVisibility(
    isOnline = when {
        onlineRevoked -> false
        statusRevoked -> currentIsOnline
        else -> eventIsOnline
    },
    status = if (statusRevoked) "" else currentStatus,
    lastSeen = when {
        onlineRevoked -> 0L
        statusRevoked -> currentLastSeen
        else -> eventLastSeen
    }
)

internal fun isNonRecoverableWebSocketNetworkError(error: Throwable): Boolean =
    generateSequence(error) { it.cause }.any { it is javax.net.ssl.SSLException }
