package com.maodouchat.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatFolderPolicyTest {
    @Test
    fun `createFolder rejects blank and duplicates`() {
        val first = ChatFolderPolicy.createFolder(emptyList(), "工作", id = "f1")!!
        assertEquals(1, first.size)
        assertNull(ChatFolderPolicy.createFolder(first, " 工作 "))
        assertNull(ChatFolderPolicy.createFolder(first, "   "))
    }

    @Test
    fun `moveChatToFolder is single membership`() {
        val folders = listOf(
            ChatFolder("a", "A", listOf("c1", "c2"), 0),
            ChatFolder("b", "B", listOf("c3"), 1)
        )
        val moved = ChatFolderPolicy.moveChatToFolder(folders, "c1", "b")
        assertEquals(listOf("c2"), moved.first { it.id == "a" }.chatIds)
        assertEquals(listOf("c3", "c1"), moved.first { it.id == "b" }.chatIds)
        val cleared = ChatFolderPolicy.moveChatToFolder(moved, "c1", null)
        assertTrue(cleared.none { "c1" in it.chatIds })
    }

    @Test
    fun `unreadInFolder sums`() {
        val folder = ChatFolder("a", "A", listOf("c1", "c2", "c3"))
        assertEquals(
            5,
            ChatFolderPolicy.unreadInFolder(folder, mapOf("c1" to 2, "c2" to 3, "c9" to 9))
        )
    }

    @Test
    fun `rename and delete`() {
        val base = ChatFolderPolicy.createFolder(emptyList(), "家人", id = "f1")!!
        val renamed = ChatFolderPolicy.renameFolder(base, "f1", "家庭")!!
        assertEquals("家庭", renamed.single().name)
        assertEquals(emptyList<ChatFolder>(), ChatFolderPolicy.deleteFolder(renamed, "f1"))
    }

    @Test
    fun `system unread filter identity`() {
        assertTrue(ChatFolderPolicy.isSystemFilter(ChatFolderPolicy.SYSTEM_UNREAD_ID))
        assertTrue(ChatFolderPolicy.isUnreadChat(3, false))
        assertTrue(ChatFolderPolicy.isUnreadChat(0, true))
        assertTrue(!ChatFolderPolicy.isUnreadChat(0, false))
    }

    @Test
    fun `system secret filter is built-in`() {
        assertTrue(ChatFolderPolicy.isSystemFilter(ChatFolderPolicy.SYSTEM_SECRET_ID))
        assertTrue(!ChatFolderPolicy.isSystemFilter("folder_user"))
    }

    @Test
    fun `system locked filter is built-in`() {
        assertTrue(ChatFolderPolicy.isSystemFilter(ChatFolderPolicy.SYSTEM_LOCKED_ID))
    }
}
