package com.maodouchat.util

object NotificationPrivacyPolicy {
    /** App lock is an explicit privacy choice, so system notifications stay generic while enabled. */
    fun hideSensitiveDetails(appLockEnabled: Boolean, previewEnabled: Boolean): Boolean =
        appLockEnabled || !previewEnabled
}
