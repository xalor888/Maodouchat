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
}
