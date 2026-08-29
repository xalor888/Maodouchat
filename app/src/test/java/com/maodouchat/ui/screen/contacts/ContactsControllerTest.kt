package com.maodouchat.ui.screen.contacts

import com.maodouchat.data.model.User
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContactsControllerTest {
    @Test
    fun staleResultIsDiscardedAfterRepositoryReturns() = runTest {
        val session = ContactsSession("owner")
        var current = true
        val repository = object : ContactsRepository {
            override fun currentSession() = session
            override fun isCurrent(session: ContactsSession) = current
            override suspend fun loadFriends(session: ContactsSession): ContactsLoadResult {
                current = false
                return ContactsLoadResult(listOf(User(id = "u1", name = "User")))
            }
            override suspend fun search(session: ContactsSession, query: String) = ContactsLoadResult(emptyList())
        }

        val result = ContactsController(repository).loadFriends(session)

        assertTrue(result.sessionMissing)
        assertEquals(emptyList(), result.users)
    }

    @Test
    fun searchTrimsQueryAtBoundary() = runTest {
        var received = ""
        val session = ContactsSession("owner")
        val repository = object : ContactsRepository {
            override fun currentSession() = session
            override fun isCurrent(session: ContactsSession) = true
            override suspend fun loadFriends(session: ContactsSession) = ContactsLoadResult(emptyList())
            override suspend fun search(session: ContactsSession, query: String): ContactsLoadResult {
                received = query
                return ContactsLoadResult(emptyList())
            }
        }

        ContactsController(repository).search(session, "  Alice  ")

        assertEquals("Alice", received)
    }
}
