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
        states: List<SignalProtocol.DeviceSafetyState>
    ): SignalProtocol.IdentityTrustState {
        if (states.isEmpty()) return SignalProtocol.IdentityTrustState.UNKNOWN
        if (states.any { it.trustState == SignalProtocol.IdentityTrustState.CHANGED }) {
            return SignalProtocol.IdentityTrustState.CHANGED
        }
        if (states.any { it.trustState == SignalProtocol.IdentityTrustState.UNKNOWN }) {
            return SignalProtocol.IdentityTrustState.UNKNOWN
        }
        if (states.all { it.trustState == SignalProtocol.IdentityTrustState.VERIFIED }) {
            return SignalProtocol.IdentityTrustState.VERIFIED
        }
        return SignalProtocol.IdentityTrustState.TRUSTED
    }

    /** Prefer device #1 when present; otherwise first listed device. */
    fun primaryDevice(
        states: List<SignalProtocol.DeviceSafetyState>
    ): SignalProtocol.DeviceSafetyState? =
        states.firstOrNull { it.deviceId == 1 } ?: states.firstOrNull()

    fun canVerifyAny(states: List<SignalProtocol.DeviceSafetyState>): Boolean =
        states.any { state ->
            !state.safetyCode.isNullOrBlank() &&
                state.trustState != SignalProtocol.IdentityTrustState.VERIFIED
        }

    /** Banner / dialog should stay prominent until user re-verifies. */
    fun isStickyWarning(trust: SignalProtocol.IdentityTrustState): Boolean =
        SafetyCodePolicy.isStickyIdentityWarning(trust)

    fun warningKind(trust: SignalProtocol.IdentityTrustState): WarningKind = when (trust) {
        SignalProtocol.IdentityTrustState.CHANGED -> WarningKind.CHANGED
        SignalProtocol.IdentityTrustState.UNKNOWN -> WarningKind.UNKNOWN
        SignalProtocol.IdentityTrustState.TRUSTED -> WarningKind.TRUSTED
        SignalProtocol.IdentityTrustState.VERIFIED -> WarningKind.NONE
    }

    enum class WarningKind {
        CHANGED, UNKNOWN, TRUSTED, NONE
    }
}
