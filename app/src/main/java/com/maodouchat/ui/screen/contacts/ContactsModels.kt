package com.maodouchat.ui.screen.contacts

import com.maodouchat.core.model.User

data class ContactsSession(val ownerUserId: String)

data class ContactsLoadResult(
    val users: List<User>,
    val failure: Throwable? = null,
    val sessionMissing: Boolean = false,
)
