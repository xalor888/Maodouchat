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

internal object SignalInitializationPolicy {
    fun action(
        state: SignalInitializationState,
        requestedUserId: String?,
    ): SignalInitializationAction = when {
        state.accountId == requestedUserId && state.localCryptoReady && state.publicationReady ->
            SignalInitializationAction.REUSE
        state.accountId == requestedUserId && state.localCryptoReady ->
            SignalInitializationAction.UPLOAD_ONLY
        else -> SignalInitializationAction.FULL_INITIALIZATION
    }

    fun selectAccount(
        state: SignalInitializationState,
        requestedUserId: String?,
    ): SignalInitializationState = if (state.accountId == requestedUserId) {
        state.copy(localCryptoReady = false, publicationReady = false)
    } else {
        SignalInitializationState(accountId = requestedUserId)
    }

    fun localStoreReady(state: SignalInitializationState): SignalInitializationState =
        state.copy(localCryptoReady = true)

    fun publicationSucceeded(state: SignalInitializationState): SignalInitializationState =
        state.copy(publicationReady = state.localCryptoReady && state.accountId != null)

    fun publicationFailed(
        state: SignalInitializationState,
        invalidateLocalCrypto: Boolean = false,
    ): SignalInitializationState = state.copy(
        localCryptoReady = state.localCryptoReady && !invalidateLocalCrypto,
        publicationReady = false,
    )

    fun cleared(): SignalInitializationState = SignalInitializationState()

    fun canReuse(currentUserId: String?, initializationSucceeded: Boolean, requestedUserId: String?): Boolean =
        action(
            SignalInitializationState(
                accountId = currentUserId,
                localCryptoReady = true,
                publicationReady = initializationSucceeded,
            ),
            requestedUserId,
        ) == SignalInitializationAction.REUSE && !requestedUserId.isNullOrBlank()

    fun canUseLocalCrypto(
        currentUserId: String?,
        localCryptoReady: Boolean,
        requestedUserId: String?,
    ): Boolean =
        localCryptoReady &&
            !requestedUserId.isNullOrBlank() &&
            currentUserId == requestedUserId
}
