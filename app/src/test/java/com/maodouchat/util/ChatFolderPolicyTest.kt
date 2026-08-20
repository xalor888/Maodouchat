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
    fun `moveFolder swaps adjacent and rejects out of range`() {
        val folders = listOf(
            ChatFolder("a", "A", emptyList(), 0),
            ChatFolder("b", "B", emptyList(), 1),
            ChatFolder("c", "C", emptyList(), 2)
        )
        val moved = ChatFolderPolicy.moveFolder(folders, "b", -1)!!
        assertEquals(listOf("b", "a", "c"), moved.sortedBy { it.sortOrder }.map { it.id })
        assertNull(ChatFolderPolicy.moveFolder(folders, "a", -1))
        assertNull(ChatFolderPolicy.moveFolder(folders, "c", 1))
        assertNull(ChatFolderPolicy.moveFolder(folders, "missing", 1))
    }

    @Test
    fun `reorderFolder inserts at target and renumbers`() {
        // 历史交换可能留下 sortOrder 间隔（0,5,9），重排后应连号
        val folders = listOf(
            ChatFolder("a", "A", emptyList(), 0),
            ChatFolder("b", "B", emptyList(), 5),
            ChatFolder("c", "C", emptyList(), 9)
        )
        val moved = ChatFolderPolicy.reorderFolder(folders, "c", 0)!!
        assertEquals(listOf("c", "a", "b"), moved.sortedBy { it.sortOrder }.map { it.id })
        assertEquals(listOf(0, 1, 2), moved.sortedBy { it.sortOrder }.map { it.sortOrder })
    }

    @Test
    fun `reorderFolder clamps target and keeps data intact`() {
        val folders = listOf(
            ChatFolder("a", "A", listOf("c1"), 0),
            ChatFolder("b", "B", listOf("c2"), 1)
        )
        val clamped = ChatFolderPolicy.reorderFolder(folders, "a", 99)!!
        assertEquals(listOf("b", "a"), clamped.sortedBy { it.sortOrder }.map { it.id })
        assertEquals(listOf("c1"), clamped.first { it.id == "a" }.chatIds)
        // 原位不变返回排序后的原列表
        val same = ChatFolderPolicy.reorderFolder(folders, "a", 0)!!
        assertEquals(listOf("a", "b"), same.sortedBy { it.sortOrder }.map { it.id })
        assertNull(ChatFolderPolicy.reorderFolder(folders, "missing", 0))
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
