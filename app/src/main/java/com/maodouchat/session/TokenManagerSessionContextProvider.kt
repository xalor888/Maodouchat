package com.maodouchat.session

import com.maodouchat.network.TokenManager
import kotlinx.coroutines.flow.StateFlow

/** Adapts encrypted auth storage to the account-generation boundary. */
class TokenManagerSessionContextProvider(
    private val tokenManager: TokenManager,
) : SessionContextProvider {
    override val contexts: StateFlow<SessionContext?>
        get() = tokenManager.sessionContexts

    override fun isAccessTokenCurrent(context: SessionContext, accessToken: String): Boolean =
        tokenManager.owns(context) &&
            accessToken.isNotBlank() &&
            tokenManager.getToken() == accessToken
}
