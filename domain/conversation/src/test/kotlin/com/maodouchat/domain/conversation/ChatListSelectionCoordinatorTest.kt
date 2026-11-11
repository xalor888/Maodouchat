package com.maodouchat.domain.conversation

import com.maodouchat.core.model.ChatListSelection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `ChatListSelectionCoordinator` 随迁入 `:domain:conversation` 时新增的契约测试。 */
class ChatListSelectionCoordinatorTest {

    private class Harness {
        var selection = ChatListSelection()
        val pinned = mutableListOf<String>()
        val deleted = mutableListOf<String>()
        val coordinator = ChatListSelectionCoordinator(
            getSelection = { selection },
            updateSelection = { selection = it },
            togglePinned = { pinned += it },
            deleteChat = { deleted += it },
        )
    }

    @Test
    fun enterSelectionModeOpensModeAndKeepsSelection() {
        val h = Harness()
        h.selection = ChatListSelection(selectionMode = false, selectedChatIds = setOf("a"))
        h.coordinator.enterSelectionMode()
        assertTrue(h.selection.selectionMode)
        assertEquals(setOf("a"), h.selection.selectedChatIds)
    }

    @Test
    fun exitSelectionModeClearsModeAndSelection() {
        val h = Harness()
        h.selection = ChatListSelection(selectionMode = true, selectedChatIds = setOf("a", "b"))
        h.coordinator.exitSelectionMode()
        assertFalse(h.selection.selectionMode)
        assertTrue(h.selection.selectedChatIds.isEmpty())
    }

    @Test
    fun toggleSelectChatAddsAndRemovesIds() {
        val h = Harness()
        h.coordinator.enterSelectionMode()
        h.coordinator.toggleSelectChat("a")
        h.coordinator.toggleSelectChat("b")
        assertEquals(setOf("a", "b"), h.selection.selectedChatIds)
        h.coordinator.toggleSelectChat("a")
        assertEquals(setOf("b"), h.selection.selectedChatIds)
        assertTrue(h.selection.selectionMode)
    }

    @Test
    fun toggleLastSelectedChatExitsMode() {
        val h = Harness()
        h.coordinator.enterSelectionMode()
        h.coordinator.toggleSelectChat("a")
        h.coordinator.toggleSelectChat("a")
        assertFalse(h.selection.selectionMode)
        assertTrue(h.selection.selectedChatIds.isEmpty())
    }

    @Test
    fun batchTogglePinSelectedPinsEachSelected() {
        val h = Harness()
        h.selection = ChatListSelection(selectionMode = true, selectedChatIds = setOf("a", "b"))
        h.coordinator.batchTogglePinSelected()
        assertEquals(listOf("a", "b"), h.pinned)
        assertEquals(setOf("a", "b"), h.selection.selectedChatIds)
    }

    @Test
    fun batchDeleteSelectedDeletesEachAndExitsMode() {
        val h = Harness()
        h.selection = ChatListSelection(selectionMode = true, selectedChatIds = setOf("a", "b"))
        h.coordinator.batchDeleteSelected()
        assertEquals(listOf("a", "b"), h.deleted)
        assertFalse(h.selection.selectionMode)
        assertTrue(h.selection.selectedChatIds.isEmpty())
    }

    @Test
    fun batchOpsWithEmptySelectionAreNoop() {
        val h = Harness()
        h.coordinator.batchTogglePinSelected()
        h.coordinator.batchDeleteSelected()
        assertTrue(h.pinned.isEmpty())
        assertTrue(h.deleted.isEmpty())
        assertFalse(h.selection.selectionMode)
    }
}
