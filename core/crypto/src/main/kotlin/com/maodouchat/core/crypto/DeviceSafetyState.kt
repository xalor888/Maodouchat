package com.maodouchat.core.crypto

data class DeviceSafetyState(
    val deviceId: Int,
    val identityKey: String,
    val identityFingerprint: String,
    val trustState: IdentityTrustState,
    val safetyCode: String?,
    val isCurrent: Boolean = false
)
