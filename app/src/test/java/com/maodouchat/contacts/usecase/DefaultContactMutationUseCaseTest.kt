package com.maodouchat.contacts.usecase

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
}
