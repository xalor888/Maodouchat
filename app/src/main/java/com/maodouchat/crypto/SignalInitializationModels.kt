package com.maodouchat.crypto

internal data class SignalInitializationState(
    val accountId: String? = null,
    val localCryptoReady: Boolean = false,
    val publicationReady: Boolean = false,
)

internal enum class SignalInitializationAction(val rebuildsLocalStore: Boolean) {
    REUSE(rebuildsLocalStore = false),
    UPLOAD_ONLY(rebuildsLocalStore = false),
    FULL_INITIALIZATION(rebuildsLocalStore = true),
}
