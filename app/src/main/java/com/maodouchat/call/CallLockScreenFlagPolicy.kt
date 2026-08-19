package com.maodouchat.call

/** Single source of truth for whether MainActivity may remain visible over keyguard. */
object CallLockScreenFlagPolicy {
    fun shouldEnable(
        activeCallId: String?,
        hasPendingIncomingCall: Boolean,
    ): Boolean = !activeCallId.isNullOrBlank() || hasPendingIncomingCall
}
