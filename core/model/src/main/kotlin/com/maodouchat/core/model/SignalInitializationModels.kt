package com.maodouchat.core.model

data class SignalInitializationState(
    val accountId: String? = null,
    val localCryptoReady: Boolean = false,
    val publicationReady: Boolean = false,
)

