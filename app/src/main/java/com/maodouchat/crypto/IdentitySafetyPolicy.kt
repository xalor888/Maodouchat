package com.maodouchat.crypto

/**
 * Multi-device identity aggregation for chat safety UI (M2-9 / §6 extract).
 * Pure decisions only — no Android / network.
 */
object IdentitySafetyPolicy {

    /**
     * Worst-wins across devices: CHANGED > UNKNOWN > TRUSTED > VERIFIED.
     * Empty list is UNKNOWN (no session yet).
     */
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

    /** Prefer device #1 when present; otherwise first listed device. */
    fun primaryDevice(
        states: List<DeviceSafetyState>
    ): DeviceSafetyState? =
        states.firstOrNull { it.deviceId == 1 } ?: states.firstOrNull()

    fun canVerifyAny(states: List<DeviceSafetyState>): Boolean =
        states.any { state ->
            !state.safetyCode.isNullOrBlank() &&
                state.trustState != IdentityTrustState.VERIFIED
        }

    /** Banner / dialog should stay prominent until user re-verifies. */
    fun isStickyWarning(trust: IdentityTrustState): Boolean =
        SafetyCodePolicy.isStickyIdentityWarning(trust)

    fun warningKind(trust: IdentityTrustState): WarningKind = when (trust) {
        IdentityTrustState.CHANGED -> WarningKind.CHANGED
        IdentityTrustState.UNKNOWN -> WarningKind.UNKNOWN
        IdentityTrustState.TRUSTED -> WarningKind.TRUSTED
        IdentityTrustState.VERIFIED -> WarningKind.NONE
    }

}
