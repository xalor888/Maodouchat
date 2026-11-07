package com.maodouchat.core.model

data class ContactsSession(val ownerUserId: String)

data class ContactsLoadResult(
    val users: List<User>,
    val failure: Throwable? = null,
    val sessionMissing: Boolean = false,
)
