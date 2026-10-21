package com.maodouchat.contacts.usecase

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class DefaultContactMutationUseCaseTest {

    @Test
    fun setNickname_success() = runBlocking {
        var savedNickname = ""
        val useCase = DefaultContactMutationUseCase(
            sessionProvider = { "owner-1" to "token-1" },
            setNicknameLocal = { _, nick -> savedNickname = nick }
        )

        val result = useCase.setNickname("friend-1", "Best Friend")
        assertTrue(result.isSuccess)
        assertEquals("Best Friend", savedNickname)
    }

    @Test
    fun blockUser_blocksSelfTarget() = runBlocking {
        val useCase = DefaultContactMutationUseCase(
            sessionProvider = { "owner-1" to "token-1" },
            setNicknameLocal = { _, _ -> }
        )

        val result = useCase.blockUser("owner-1")
        assertTrue("Cannot block self", result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun removeFriend_successTriggersCallback() = runBlocking {
        val removedCallbackCalled = AtomicBoolean(false)
        val useCase = DefaultContactMutationUseCase(
            sessionProvider = { "owner-1" to "token-1" },
            setNicknameLocal = { _, _ -> },
            removeFriendApi = { _, id ->
                assertEquals("friend-2", id)
                Result.success(Unit)
            },
            onFriendRemoved = { owner, friend ->
                assertEquals("owner-1", owner)
                assertEquals("friend-2", friend)
                removedCallbackCalled.set(true)
            }
        )

        val result = useCase.removeFriend("friend-2")
        assertTrue(result.isSuccess)
        assertTrue(removedCallbackCalled.get())
    }

    @Test
    fun removeFriend_abortsOnAccountSwitch() = runBlocking {
        var currentAccount = "owner-1" to "token-1"
        val removedCallbackCalled = AtomicBoolean(false)
        val useCase = DefaultContactMutationUseCase(
            sessionProvider = { currentAccount },
            setNicknameLocal = { _, _ -> },
            removeFriendApi = { _, _ ->
                currentAccount = "owner-2" to "token-2"
                Result.success(Unit)
            },
            onFriendRemoved = { _, _ ->
                removedCallbackCalled.set(true)
            }
        )

        val result = useCase.removeFriend("friend-2")
        assertTrue(result.isFailure)
        assertFalse(removedCallbackCalled.get())
    }

    @Test
    fun setNickname_failsOnBlankIdOrMissingSession() = runBlocking {
        val useCase = DefaultContactMutationUseCase(
            sessionProvider = { "owner-1" to "token-1" },
            setNicknameLocal = { _, _ -> }
        )
        val blankResult = useCase.setNickname("   ", "x")
        assertTrue(blankResult.isFailure)
        assertTrue(blankResult.exceptionOrNull()?.message?.contains("User ID cannot be blank") == true)

        val noSessionCase = DefaultContactMutationUseCase(
            sessionProvider = { null to "token-1" },
            setNicknameLocal = { _, _ -> }
        )
        val noSessionResult = noSessionCase.setNickname("friend-1", "x")
        assertTrue(noSessionResult.isFailure)
        assertTrue(noSessionResult.exceptionOrNull()?.message?.contains("Session expired") == true)
    }

    @Test
    fun setNickname_trimsNicknameBeforeSaving() = runBlocking {
        var savedNickname = ""
        val useCase = DefaultContactMutationUseCase(
            sessionProvider = { "owner-1" to "token-1" },
            setNicknameLocal = { _, nick -> savedNickname = nick }
        )

        val result = useCase.setNickname("friend-1", "  Best Friend  ")
        assertTrue(result.isSuccess)
        assertEquals("Best Friend", savedNickname)
    }

    @Test
    fun blockUser_apiFailureSkipsBlockedCallback() = runBlocking {
        val blockedCallbackCalled = AtomicBoolean(false)
        val useCase = DefaultContactMutationUseCase(
            sessionProvider = { "owner-1" to "token-1" },
            setNicknameLocal = { _, _ -> },
            blockUserApi = { _, _ -> Result.failure(RuntimeException("403")) },
            onUserBlocked = { _, _ -> blockedCallbackCalled.set(true) }
        )

        val result = useCase.blockUser("spammer-1")
        assertTrue(result.isFailure)
        assertEquals("403", result.exceptionOrNull()?.message)
        assertFalse(blockedCallbackCalled.get())
    }

    @Test
    fun blockUser_accountSwitchAborts() = runBlocking {
        var currentAccount = "owner-1" to "token-1"
        val blockedCallbackCalled = AtomicBoolean(false)
        val useCase = DefaultContactMutationUseCase(
            sessionProvider = { currentAccount },
            setNicknameLocal = { _, _ -> },
            blockUserApi = { _, _ ->
                currentAccount = "owner-2" to "token-2"
                Result.success(Unit)
            },
            onUserBlocked = { _, _ -> blockedCallbackCalled.set(true) }
        )

        val result = useCase.blockUser("spammer-1")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("Account switched during block user") == true)
        assertFalse(blockedCallbackCalled.get())
    }

    @Test
    fun unblockUser_successReturnsApiResult() = runBlocking {
        val useCase = DefaultContactMutationUseCase(
            sessionProvider = { "owner-1" to "token-1" },
            setNicknameLocal = { _, _ -> },
            unblockUserApi = { token, id ->
                assertEquals("token-1", token)
                assertEquals("spammer-1", id)
                Result.success(Unit)
            }
        )

        val result = useCase.unblockUser("spammer-1")
        assertTrue(result.isSuccess)
    }

    @Test
    fun setNickname_cancellationPropagates() = runBlocking {
        val useCase = DefaultContactMutationUseCase(
            sessionProvider = { "owner-1" to "token-1" },
            setNicknameLocal = { _, _ -> throw CancellationException("gone") }
        )

        var thrown: Throwable? = null
        try {
            useCase.setNickname("friend-1", "x")
        } catch (e: CancellationException) {
            thrown = e
        }
        assertEquals("gone", (thrown as? CancellationException)?.message)
    }
}
