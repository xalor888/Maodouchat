package com.maodouchat.security.totp

sealed interface TotpState {
    object Uninitialized : TotpState
    object Loading : TotpState
    object Disabled : TotpState

    data class SetupReady(
        val secret: String,
        val otpauthUrl: String? = null,
        val recoveryCodes: List<String> = emptyList(),
        val isConfirming: Boolean = false,
        val error: String? = null
    ) : TotpState

    data class Enabled(
        val recoveryCodes: List<String> = emptyList(),
        val isWorking: Boolean = false,
        val error: String? = null
    ) : TotpState

    data class Error(
        val message: String,
        val previousState: TotpState? = null
    ) : TotpState
}
