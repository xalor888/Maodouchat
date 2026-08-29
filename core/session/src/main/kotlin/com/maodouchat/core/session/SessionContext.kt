package com.maodouchat.core.session

import com.maodouchat.core.model.AccountId

/**
 * Immutable account identity captured at command submission time.
 * Long-running work must compare this generation before persisting results.
 */
data class SessionContext(
    val accountId: AccountId,
    val generation: Long,
    val accessToken: String,
) {
    init {
        require(generation >= 0L) { "generation must not be negative" }
        require(accessToken.isNotBlank()) { "accessToken must not be blank" }
    }
}

interface SessionContextProvider {
    fun current(): SessionContext?
    fun isCurrent(context: SessionContext): Boolean
}
