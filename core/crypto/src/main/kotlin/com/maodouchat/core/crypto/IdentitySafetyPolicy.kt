package com.maodouchat.core.crypto

import com.maodouchat.core.model.WarningKind

object IdentitySafetyPolicy {

    // 最坏者胜：CHANGED > UNKNOWN > TRUSTED > VERIFIED；空列表视为 UNKNOWN。
    fun aggregateTrust(
        states: List<DeviceSafetyState>
    ): IdentityTrustState {
        if (states.isEmpty()) return IdentityTrustState.UNKNOWN
        if (states.any { it.trustState == IdentityTrustState.CHANGED }) {
            return IdentityTrustState.CHANGED
        }
        if (states.any { it.trustState == IdentityTrustState.UNKNOWN }) {
            return IdentityTrustState.UNKNOWN
        }
        if (states.all { it.trustState == IdentityTrustState.VERIFIED }) {
            return IdentityTrustState.VERIFIED
        }
        return IdentityTrustState.TRUSTED
    }

    fun primaryDevice(
        states: List<DeviceSafetyState>
    ): DeviceSafetyState? =
        states.firstOrNull { it.deviceId == 1 } ?: states.firstOrNull()

    fun canVerifyAny(states: List<DeviceSafetyState>): Boolean =
        states.any { state ->
            !state.safetyCode.isNullOrBlank() &&
                state.trustState != IdentityTrustState.VERIFIED
        }

    fun isStickyWarning(trust: IdentityTrustState): Boolean =
        SafetyCodePolicy.isStickyIdentityWarning(trust)

    fun warningKind(trust: IdentityTrustState): WarningKind = when (trust) {
        IdentityTrustState.CHANGED -> WarningKind.CHANGED
        IdentityTrustState.UNKNOWN -> WarningKind.UNKNOWN
        IdentityTrustState.TRUSTED -> WarningKind.TRUSTED
        IdentityTrustState.VERIFIED -> WarningKind.NONE
    }

}
