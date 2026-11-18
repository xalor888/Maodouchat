package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatDetailMediaModelsTest {

    @Test
    fun mediaCenterUiStateDefaultsToLoadingEmpty() {
        val state = MediaCenterUiState()
        assertTrue(state.isLoading)
        assertEquals(emptyList(), state.items)
        assertEquals(null, state.isChatLocked)
        assertEquals("", state.chatName)
        assertFalse(state.isSecretChat)
    }

    @Test
    fun starredMessagesUiStateDefaultsToLoadingEmpty() {
        val state = StarredMessagesUiState()
        assertTrue(state.isLoading)
        assertEquals(emptyList(), state.messages)
        assertEquals("", state.currentUserId)
        assertFalse(state.globalScope)
    }
}
