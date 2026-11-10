package com.maodouchat.domain.contacts

import com.maodouchat.core.model.ContactsLoadResult
import com.maodouchat.core.model.ContactsSession
import com.maodouchat.core.model.User
import kotlinx.coroutines.flow.Flow

interface ContactsRepository {
    fun currentSession(): ContactsSession?
    fun isCurrent(session: ContactsSession): Boolean
    fun observeFriends(session: ContactsSession): Flow<List<User>>
    suspend fun loadFriends(session: ContactsSession): ContactsLoadResult
    suspend fun search(session: ContactsSession, query: String): ContactsLoadResult
}

class ContactsController(private val repository: ContactsRepository) {
    fun currentSession(): ContactsSession? = repository.currentSession()

    fun isCurrent(session: ContactsSession): Boolean = repository.isCurrent(session)

    fun observeFriends(session: ContactsSession): Flow<List<User>> =
        repository.observeFriends(session)

    suspend fun loadFriends(session: ContactsSession): ContactsLoadResult =
        repository.loadFriends(session).takeIf { repository.isCurrent(session) }
            ?: ContactsLoadResult(emptyList(), sessionMissing = true)

    suspend fun search(session: ContactsSession, query: String): ContactsLoadResult =
        repository.search(session, query.trim()).takeIf { repository.isCurrent(session) }
            ?: ContactsLoadResult(emptyList(), sessionMissing = true)
}
