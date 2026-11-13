package com.maodouchat.domain.messaging

import com.maodouchat.core.model.ChatDetailUiState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatDetailGateDerivedTest {

    @Test
    fun `lock pending only while isChatLocked is unknown`() {
        assertTrue(resolveChatDetailGateDerived(ChatDetailUiState()).chatLockPending)
        assertFalse(resolveChatDetailGateDerived(ChatDetailUiState(isChatLocked = false)).chatLockPending)
        assertFalse(resolveChatDetailGateDerived(ChatDetailUiState(isChatLocked = true)).chatLockPending)
    }

    @Test
    fun `blocking when locked and not unlocked`() {
        val gate = resolveChatDetailGateDerived(ChatDetailUiState(isChatLocked = true))
        assertTrue(gate.chatLockBlocking)
    }

    @Test
    fun `not blocking when unlocked`() {
        val gate = resolveChatDetailGateDerived(
            ChatDetailUiState(isChatLocked = true, isChatUnlocked = true)
        )
        assertFalse(gate.chatLockBlocking)
    }

    @Test
    fun `not blocking when not locked`() {
        val gate = resolveChatDetailGateDerived(ChatDetailUiState(isChatLocked = false))
        assertFalse(gate.chatLockBlocking)
    }

    @Test
    fun `disappearing active only for non-group with seconds`() {
        assertTrue(
            resolveChatDetailGateDerived(
                ChatDetailUiState(chatIsGroup = false, disappearingMessageSeconds = 60)
            ).disappearingActive
        )
        assertFalse(
            resolveChatDetailGateDerived(
                ChatDetailUiState(chatIsGroup = true, disappearingMessageSeconds = 60)
            ).disappearingActive
        )
        assertFalse(
            resolveChatDetailGateDerived(
                ChatDetailUiState(chatIsGroup = false, disappearingMessageSeconds = 0)
            ).disappearingActive
        )
    }

    @Test
    fun `secret active only when isSecretChat is true`() {
        assertTrue(resolveChatDetailGateDerived(ChatDetailUiState(isSecretChat = true)).secretActive)
        assertFalse(resolveChatDetailGateDerived(ChatDetailUiState(isSecretChat = false)).secretActive)
        assertFalse(resolveChatDetailGateDerived(ChatDetailUiState(isSecretChat = null)).secretActive)
    }
}
