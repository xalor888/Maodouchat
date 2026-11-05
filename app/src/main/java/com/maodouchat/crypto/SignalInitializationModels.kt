package com.maodouchat.crypto

internal data class SignalInitializationState(
    val accountId: String? = null,
    val localCryptoReady: Boolean = false,
    val publicationReady: Boolean = false,
)

