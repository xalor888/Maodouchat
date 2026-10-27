package com.maodouchat.chatlist

import com.maodouchat.util.ChatFolder
import com.maodouchat.util.ChatFolderPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChatFolderPolicyTest {

    private fun folders(vararg names: String): List<ChatFolder> =
        names.mapIndexed { index, name ->
            ChatFolder(id = "f$index", name = name, sortOrder = index)
        }

    @Test
    fun `move up swaps sort order with previous folder`() {
        val result = ChatFolderPolicy.moveFolder(folders("A", "B", "C"), "f1", delta = -1)
        assertEquals(listOf("B", "A", "C"), result?.map { it.name })
    }

    @Test
    fun `move down swaps sort order with next folder`() {
        val result = ChatFolderPolicy.moveFolder(folders("A", "B", "C"), "f1", delta = 1)
        assertEquals(listOf("A", "C", "B"), result?.map { it.name })
    }

    @Test
    fun `move up at top returns null`() {
        assertNull(ChatFolderPolicy.moveFolder(folders("A", "B"), "f0", delta = -1))
    }

    @Test
    fun `move down at bottom returns null`() {
        assertNull(ChatFolderPolicy.moveFolder(folders("A", "B"), "f1", delta = 1))
    }

    @Test
    fun `move unknown folder returns null`() {
        assertNull(ChatFolderPolicy.moveFolder(folders("A"), "missing", delta = 1))
    }

    @Test
    fun `move keeps sort order values contiguous`() {
        val result = ChatFolderPolicy.moveFolder(folders("A", "B", "C"), "f2", delta = -1)
        assertEquals(listOf(0, 1, 2), result?.map { it.sortOrder })
    }

    @Test
    fun `normalizeName trims collapses whitespace and caps length`() {
        assertEquals("a b c", ChatFolderPolicy.normalizeName("  a   b\tc  "))
        assertEquals(48, ChatFolderPolicy.normalizeName("x".repeat(60)).length)
        assertEquals("", ChatFolderPolicy.normalizeName(null))
    }

    @Test
    fun `createFolder rejects duplicate case-insensitive and blank names`() {
        assertNull(ChatFolderPolicy.createFolder(folders("Work"), "work"))
        assertNull(ChatFolderPolicy.createFolder(folders("Work"), "   "))
        val created = ChatFolderPolicy.createFolder(emptyList(), "  New  Folder ")
        assertEquals(listOf("New Folder"), created?.map { it.name })
        assertEquals(listOf(0), created?.map { it.sortOrder })
    }

    @Test
    fun `createFolder refuses at max folders`() {
        val full = folders(*Array(ChatFolderPolicy.MAX_FOLDERS) { "F$it" })
        assertNull(ChatFolderPolicy.createFolder(full, "Extra"))
    }

    @Test
    fun `reorderFolder drags across positions and renumbers contiguously`() {
        val reordered = ChatFolderPolicy.reorderFolder(folders("A", "B", "C", "D"), "f0", 3)
        assertEquals(listOf("B", "C", "D", "A"), reordered?.map { it.name })
        assertEquals(listOf(0, 1, 2, 3), reordered?.map { it.sortOrder })
        val same = ChatFolderPolicy.reorderFolder(folders("A", "B"), "f0", 0)
        assertEquals(listOf("A", "B"), same?.map { it.name })
        val clamped = ChatFolderPolicy.reorderFolder(folders("A", "B", "C"), "f0", 99)
        assertEquals(listOf("B", "C", "A"), clamped?.map { it.name })
        assertNull(ChatFolderPolicy.reorderFolder(folders("A"), "missing", 0))
    }

    @Test
    fun `moveChatToFolder removes from all folders and adds only to target`() {
        val start = listOf(
            ChatFolder(id = "f0", name = "A", chatIds = listOf("c1"), sortOrder = 0),
            ChatFolder(id = "f1", name = "B", chatIds = listOf("c1", "c2"), sortOrder = 1),
        )
        val moved = ChatFolderPolicy.moveChatToFolder(start, "c1", "f1")
        assertEquals(emptyList<String>(), moved[0].chatIds)
        assertEquals(listOf("c2", "c1"), moved[1].chatIds)
        val removed = ChatFolderPolicy.moveChatToFolder(start, "c1", null)
        assertEquals(listOf("c2"), removed[1].chatIds)
        assertEquals(start, ChatFolderPolicy.moveChatToFolder(start, "  ", "f1"))
    }
}
