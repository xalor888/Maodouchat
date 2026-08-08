package com.maodouchat.ui

internal data class OwnerSessionSnapshot(
    val ownerUserId: String,
    val sessionGeneration: Long,
)

internal object OwnerSessionPolicy {
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
