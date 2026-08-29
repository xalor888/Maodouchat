package com.maodouchat.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-local account generation authority. Generation changes are synchronous so callers can
 * invalidate owned work before starting suspendable logout or account-switch cleanup.
 */
class MutableSessionContextProvider(initialOwnerUserId: String? = null) {
    private val lock = Any()
    private var generation = 0L
    private val mutableContexts = MutableStateFlow(
        initialOwnerUserId?.takeIf(String::isNotBlank)?.let { SessionContext(it, generation) }
    )

    val contexts: StateFlow<SessionContext?> = mutableContexts.asStateFlow()

    fun activate(ownerUserId: String, forceNewGeneration: Boolean = false): SessionContext {
        require(ownerUserId.isNotBlank()) { "ownerUserId must not be blank" }
        return synchronized(lock) {
            val current = mutableContexts.value
            if (!forceNewGeneration && current?.ownerUserId == ownerUserId) return@synchronized current
            generation += 1L
            SessionContext(ownerUserId, generation).also { mutableContexts.value = it }
        }
    }

    fun invalidate(expectedOwnerUserId: String? = null): SessionContext? = synchronized(lock) {
        val current = mutableContexts.value ?: return@synchronized null
        if (expectedOwnerUserId != null && current.ownerUserId != expectedOwnerUserId) {
            return@synchronized null
        }
        generation += 1L
        mutableContexts.value = null
        current
    }
}
