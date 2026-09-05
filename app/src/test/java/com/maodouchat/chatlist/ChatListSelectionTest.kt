package com.maodouchat.chatlist

import com.maodouchat.data.model.Chat
import com.maodouchat.ui.screen.chatlist.ChatListSelection
import com.maodouchat.ui.screen.chatlist.ChatListSelectionEvent
import com.maodouchat.ui.screen.chatlist.UnreadBatchTargets
import com.maodouchat.ui.screen.chatlist.reduceSelection
import com.maodouchat.ui.screen.chatlist.selectUnreadBatchTargets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatListSelectionTest {

    @Test
    fun enterKeepsExistingSelection() {
        val state = ChatListSelection(selectionMode = false, selectedChatIds = setOf("a"))
        val next = reduceSelection(state, ChatListSelectionEvent.Enter)
        assertTrue(next.selectionMode)
        assertEquals(setOf("a"), next.selectedChatIds)
    }

    @Test
    fun exitClearsSelection() {
        val state = ChatListSelection(selectionMode = true, selectedChatIds = setOf("a", "b"))
        val next = reduceSelection(state, ChatListSelectionEvent.Exit)
        assertFalse(next.selectionMode)
        assertTrue(next.selectedChatIds.isEmpty())
    }

    @Test
    fun toggleAddsAndKeepsModeUntouched() {
        val state = ChatListSelection(selectionMode = true, selectedChatIds = setOf("a"))
        val next = reduceSelection(state, ChatListSelectionEvent.Toggle("b"))
        assertTrue(next.selectionMode)
        assertEquals(setOf("a", "b"), next.selectedChatIds)
    }

    @Test
    fun toggleRemoves() {
        val state = ChatListSelection(selectionMode = true, selectedChatIds = setOf("a", "b"))
        val next = reduceSelection(state, ChatListSelectionEvent.Toggle("a"))
        assertTrue(next.selectionMode)
        assertEquals(setOf("b"), next.selectedChatIds)
    }

    @Test
    fun lastDeselectExitsMode() {
        val state = ChatListSelection(selectionMode = true, selectedChatIds = setOf("a"))
        val next = reduceSelection(state, ChatListSelectionEvent.Toggle("a"))
        assertFalse(next.selectionMode)
        assertTrue(next.selectedChatIds.isEmpty())
    }

    @Test
    fun longPressSequenceEnterThenToggle() {
        // UI 长按路径：enter → toggle，与旧 ViewModel 行为一致。
        var state = ChatListSelection()
        state = reduceSelection(state, ChatListSelectionEvent.Enter)
        state = reduceSelection(state, ChatListSelectionEvent.Toggle("c1"))
        assertTrue(state.selectionMode)
        assertEquals(setOf("c1"), state.selectedChatIds)
    }

    @Test
    fun emptySelectionYieldsNoTargets() {
        val chats = listOf(Chat(id = "a", unreadCount = 3))
        assertEquals(UnreadBatchTargets(), selectUnreadBatchTargets(chats, emptySet()))
    }

    @Test
    fun onlyUnreadSelectedAreTargets() {
        val chats = listOf(
            Chat(id = "a", unreadCount = 3),
            Chat(id = "b", unreadCount = 0),
            Chat(id = "c", unreadCount = 0, markedUnread = true),
        )
        val targets = selectUnreadBatchTargets(chats, setOf("a", "b", "c", "ghost"))
        assertEquals(listOf("a", "c"), targets.ordinary.map { it.id })
        assertTrue(targets.secret.isEmpty())
    }

    @Test
    fun secretChatsSplitOut() {
        val chats = listOf(
            Chat(id = "s1", unreadCount = 1, chatType = "SECRET"),
            Chat(id = "d1", unreadCount = 1),
        )
        val targets = selectUnreadBatchTargets(chats, setOf("s1", "d1"))
        assertEquals(listOf("d1"), targets.ordinary.map { it.id })
        assertEquals(listOf("s1"), targets.secret.map { it.id })
    }
}
