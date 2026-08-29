package com.maodouchat.session

import kotlinx.coroutines.flow.StateFlow

/** Stable ownership handle for work that must not cross an authentication lifecycle. */
data class SessionContext(
    val ownerUserId: String,
    val generation: Long,
)

interface SessionContextProvider {
    val contexts: StateFlow<SessionContext?>

    fun current(): SessionContext? = contexts.value

    fun isCurrent(context: SessionContext): Boolean = current() == context

    fun isAccessTokenCurrent(context: SessionContext, accessToken: String): Boolean
}
