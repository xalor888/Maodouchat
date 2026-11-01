package com.maodouchat.core.session

data class OwnerSessionSnapshot(
    val ownerUserId: String,
    val sessionGeneration: Long,
)

object OwnerSessionPolicy {
    fun isCurrent(
        snapshot: OwnerSessionSnapshot,
        liveUserId: String?,
        liveToken: String?,
        liveSessionGeneration: Long,
        purgeInProgress: Boolean,
    ): Boolean =
        !purgeInProgress &&
            snapshot.ownerUserId.isNotBlank() &&
            snapshot.ownerUserId != "me" &&
            liveUserId == snapshot.ownerUserId &&
            !liveToken.isNullOrBlank() &&
            liveSessionGeneration == snapshot.sessionGeneration
}
