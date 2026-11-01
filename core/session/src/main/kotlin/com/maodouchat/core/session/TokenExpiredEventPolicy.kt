package com.maodouchat.core.session

object TokenExpiredEventPolicy {
    fun shouldHandle(
        eventOwnerUserId: String,
        eventSessionGeneration: Long,
        currentOwnerUserId: String?,
        currentSessionGeneration: Long,
    ): Boolean = eventOwnerUserId.isNotBlank() &&
        eventOwnerUserId == currentOwnerUserId &&
        eventSessionGeneration == currentSessionGeneration
}
