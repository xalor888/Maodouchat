package com.maodouchat.notification

object NotificationIntentPolicy {
    fun belongsToCurrentAccount(
        notificationOwnerUserId: String?,
        currentUserId: String?,
        sessionPurgeInProgress: Boolean,
    ): Boolean {
        if (sessionPurgeInProgress) return false
        if (notificationOwnerUserId.isNullOrBlank() || currentUserId.isNullOrBlank()) return false
        return notificationOwnerUserId == currentUserId
    }
}
