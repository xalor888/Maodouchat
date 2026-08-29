package com.maodouchat.ui.screen.contacts

import android.app.Application
import com.maodouchat.MaodouchatApp
import com.maodouchat.data.model.User
import com.maodouchat.data.repository.FriendCacheStore
import com.maodouchat.data.repository.UserRepository
import com.maodouchat.network.ApiService
import com.maodouchat.network.TokenManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.firstOrNull

data class ContactsSession(val ownerUserId: String)

data class ContactsLoadResult(
    val users: List<User>,
    val failure: Throwable? = null,
    val sessionMissing: Boolean = false,
)

interface ContactsRepository {
    fun currentSession(): ContactsSession?
    fun isCurrent(session: ContactsSession): Boolean
    suspend fun loadFriends(session: ContactsSession): ContactsLoadResult
    suspend fun search(session: ContactsSession, query: String): ContactsLoadResult
}

class ContactsController(private val repository: ContactsRepository) {
    fun currentSession(): ContactsSession? = repository.currentSession()

    fun isCurrent(session: ContactsSession): Boolean = repository.isCurrent(session)

    suspend fun loadFriends(session: ContactsSession): ContactsLoadResult =
        repository.loadFriends(session).takeIf { repository.isCurrent(session) }
            ?: ContactsLoadResult(emptyList(), sessionMissing = true)

    suspend fun search(session: ContactsSession, query: String): ContactsLoadResult =
        repository.search(session, query.trim()).takeIf { repository.isCurrent(session) }
            ?: ContactsLoadResult(emptyList(), sessionMissing = true)
}

internal class AndroidContactsRepository(application: Application) : ContactsRepository {
    private val app = application as MaodouchatApp
    private val tokenManager = TokenManager.getInstance(application)
    private val userRepository = UserRepository(app.database.userDao())

    override fun currentSession(): ContactsSession? {
        val ownerUserId = tokenManager.getUserId().orEmpty()
        return ownerUserId.takeIf(String::isNotBlank)?.let(::ContactsSession)
    }

    override fun isCurrent(session: ContactsSession): Boolean =
        tokenManager.getUserId().orEmpty() == session.ownerUserId

    private fun isAuthenticated(session: ContactsSession): Boolean =
        com.maodouchat.security.BackgroundSessionGate.mayContinue(
            expectedUserId = session.ownerUserId,
            liveToken = tokenManager.getToken(),
            liveUserId = tokenManager.getUserId(),
        )

    override suspend fun loadFriends(session: ContactsSession): ContactsLoadResult {
        val token = tokenManager.getToken().orEmpty()
        if (token.isBlank() || !isAuthenticated(session)) {
            return cachedFriends(session, failure = null, sessionMissing = true)
        }
        return try {
            ApiService.getFriends(token).fold(
                onSuccess = { dtos ->
                    if (!isCurrent(session)) return ContactsLoadResult(emptyList(), sessionMissing = true)
                    val users = dtos
                        .filter { it.id != session.ownerUserId }
                        .distinctBy { it.id }
                        .map { it.toContactUser() }
                    userRepository.insertUsers(users)
                    FriendCacheStore.replaceAll(app, dtos.map { it.id }.toSet())
                    val merged = users.map { user ->
                        val nickname = userRepository.getUserById(user.id)?.nickname
                        if (nickname.isNullOrBlank()) user else user.copy(nickname = nickname)
                    }.sortedBy { it.displayName.lowercase() }
                    ContactsLoadResult(merged)
                },
                onFailure = { cachedFriends(session, it, sessionMissing = false) },
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            cachedFriends(session, error, sessionMissing = false)
        }
    }

    override suspend fun search(session: ContactsSession, query: String): ContactsLoadResult {
        if (query.isBlank() || !isAuthenticated(session)) {
            return ContactsLoadResult(emptyList(), sessionMissing = !isAuthenticated(session))
        }
        val token = tokenManager.getToken().orEmpty()
        if (token.isBlank()) return ContactsLoadResult(emptyList(), sessionMissing = true)
        return try {
            ApiService.searchUsers(token, query).fold(
                onSuccess = { dtos ->
                    if (!isCurrent(session)) return ContactsLoadResult(emptyList(), sessionMissing = true)
                    val users = dtos.map { it.toContactUser() }
                    val merged = users.map { user ->
                        val nickname = userRepository.getUserById(user.id)?.nickname
                        if (nickname.isNullOrBlank()) user else user.copy(nickname = nickname)
                    }
                    if (users.isNotEmpty()) userRepository.insertUsers(users)
                    ContactsLoadResult(merged)
                },
                onFailure = { error -> ContactsLoadResult(userRepository.searchUsers(query), error) },
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            ContactsLoadResult(userRepository.searchUsers(query), error)
        }
    }

    private suspend fun cachedFriends(
        session: ContactsSession,
        failure: Throwable?,
        sessionMissing: Boolean,
    ): ContactsLoadResult {
        val friendIds = FriendCacheStore.getFriendIds(app)
        val users = userRepository.getAllUsers().firstOrNull().orEmpty()
            .filter { it.id != session.ownerUserId && it.id in friendIds }
            .sortedBy { it.displayName.lowercase() }
        return ContactsLoadResult(users, failure, sessionMissing)
    }
}

private fun com.maodouchat.network.UserDto.toContactUser(): User = User(
    id = id,
    name = name,
    avatar = avatar,
    email = email,
    isOnline = isOnline,
    status = status,
    lastSeen = lastSeen,
)
