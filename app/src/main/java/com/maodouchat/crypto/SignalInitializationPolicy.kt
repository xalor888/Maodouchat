package com.maodouchat.crypto

internal object SignalInitializationPolicy {
    fun canReuse(currentUserId: String?, initializationSucceeded: Boolean, requestedUserId: String?): Boolean =
        initializationSucceeded &&
            !requestedUserId.isNullOrBlank() &&
            currentUserId == requestedUserId
}
