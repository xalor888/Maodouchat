package com.maodouchat.chatlist

import com.maodouchat.util.ChatFolder
import com.maodouchat.util.ChatFolderPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    fun `renameFolder renames only target and rejects duplicates blanks and unknown ids`() {
        val renamed = ChatFolderPolicy.renameFolder(folders("A", "B", "C"), "f1", "  Renamed  ")
        assertEquals(listOf("A", "Renamed", "C"), renamed?.map { it.name })
        assertEquals(listOf(0, 1, 2), renamed?.map { it.sortOrder })
        assertNull(ChatFolderPolicy.renameFolder(folders("A", "B"), "f1", "a"))
        assertNull(ChatFolderPolicy.renameFolder(folders("A", "B"), "f1", "   "))
        assertNull(ChatFolderPolicy.renameFolder(folders("A", "B"), "missing", "New"))
        val sameName = ChatFolderPolicy.renameFolder(folders("A", "B"), "f1", "B")
        assertEquals(listOf("A", "B"), sameName?.map { it.name })
    }

    @Test
    fun `deleteFolder removes only target without renumbering survivors`() {
        val kept = ChatFolderPolicy.deleteFolder(folders("A", "B", "C"), "f1")
        assertEquals(listOf("A", "C"), kept.map { it.name })
        assertEquals(listOf(0, 2), kept.map { it.sortOrder })
        assertEquals(folders("A", "B"), ChatFolderPolicy.deleteFolder(folders("A", "B"), "missing"))
    }

    @Test
    fun `setChatInFolder adds once removes only from target and ignores blanks`() {
        val start = listOf(
            ChatFolder(id = "f0", name = "A", chatIds = listOf("c1"), sortOrder = 0),
            ChatFolder(id = "f1", name = "B", chatIds = listOf("c1", "c2"), sortOrder = 1),
        )
        val added = ChatFolderPolicy.setChatInFolder(start, "f0", "c3", included = true)
        assertEquals(listOf("c1", "c3"), added[0].chatIds)
        assertEquals(listOf("c1", "c2"), added[1].chatIds)
        val dup = ChatFolderPolicy.setChatInFolder(start, "f0", "c1", included = true)
        assertEquals(listOf("c1"), dup[0].chatIds)
        val removed = ChatFolderPolicy.setChatInFolder(start, "f1", "c2", included = false)
        assertEquals(listOf("c1"), removed[0].chatIds)
        assertEquals(listOf("c1"), removed[1].chatIds)
        assertEquals(start, ChatFolderPolicy.setChatInFolder(start, "f0", "  ", included = true))
        assertEquals(start, ChatFolderPolicy.setChatInFolder(start, "missing", "c9", included = true))
    }

    @Test
    fun `folderOfChat finds first owner and filterChatIds keeps caller order`() {
        val start = listOf(
            ChatFolder(id = "f0", name = "A", chatIds = listOf("c1"), sortOrder = 0),
            ChatFolder(id = "f1", name = "B", chatIds = listOf("c1", "c2"), sortOrder = 1),
        )
        assertEquals("f0", ChatFolderPolicy.folderOfChat(start, "c1")?.id)
        assertEquals("f1", ChatFolderPolicy.folderOfChat(start, "c2")?.id)
        assertNull(ChatFolderPolicy.folderOfChat(start, "missing"))
        assertEquals(
            listOf("c1", "c2", "c3"),
            ChatFolderPolicy.filterChatIds(null, listOf("c1", "c2", "c3"))
        )
        assertEquals(
            listOf("c2", "c1"),
            ChatFolderPolicy.filterChatIds(start[1], listOf("c2", "c1", "c3"))
        )
    }

    @Test
    fun `system filters unread flags capacity and folder unread sum`() {
        assertTrue(ChatFolderPolicy.isSystemFilter(ChatFolderPolicy.SYSTEM_GROUPS_ID))
        assertTrue(ChatFolderPolicy.isSystemFilter(ChatFolderPolicy.SYSTEM_DIRECT_ID))
        assertTrue(ChatFolderPolicy.isSystemFilter(ChatFolderPolicy.SYSTEM_UNREAD_ID))
        assertTrue(ChatFolderPolicy.isSystemFilter(ChatFolderPolicy.SYSTEM_SECRET_ID))
        assertTrue(ChatFolderPolicy.isSystemFilter(ChatFolderPolicy.SYSTEM_LOCKED_ID))
        assertFalse(ChatFolderPolicy.isSystemFilter(ChatFolderPolicy.ALL_ID))
        assertFalse(ChatFolderPolicy.isSystemFilter(null))
        assertFalse(ChatFolderPolicy.isSystemFilter("folder_x"))
        assertFalse(ChatFolderPolicy.isUnreadChat(0, markedUnread = false))
        assertTrue(ChatFolderPolicy.isUnreadChat(0, markedUnread = true))
        assertTrue(ChatFolderPolicy.isUnreadChat(3, markedUnread = false))
        assertFalse(ChatFolderPolicy.isUnreadChat(-2, markedUnread = false))
        assertTrue(ChatFolderPolicy.canCreateMore(ChatFolderPolicy.MAX_FOLDERS - 1))
        assertFalse(ChatFolderPolicy.canCreateMore(ChatFolderPolicy.MAX_FOLDERS))
        val folder = ChatFolder(id = "f0", name = "A", chatIds = listOf("c1", "c2", "c3"), sortOrder = 0)
        assertEquals(2, ChatFolderPolicy.unreadInFolder(folder, mapOf("c1" to 2, "c3" to -4)))
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

    @Test
    fun `createFolder next order follows max order not count`() {
        val existing = listOf(
            ChatFolder(id = "f0", name = "A", sortOrder = 0),
            ChatFolder(id = "f1", name = "B", sortOrder = 2),
        )
        val created = ChatFolderPolicy.createFolder(existing, "C")
        assertEquals(listOf("A", "B", "C"), created?.map { it.name })
        assertEquals(listOf(0, 2, 3), created?.map { it.sortOrder })
    }

    @Test
    fun `createFolder rejects normalized case duplicate`() {
        assertNull(ChatFolderPolicy.createFolder(folders("Work"), "  WORK  "))
    }

    @Test
    fun `moveFolder delta two swaps with distant folder`() {
        val result = ChatFolderPolicy.moveFolder(folders("A", "B", "C"), "f0", delta = 2)
        assertEquals(listOf("C", "B", "A"), result?.map { it.name })
        assertEquals(listOf(0, 1, 2), result?.map { it.sortOrder })
    }

    @Test
    fun `moveFolder delta zero returns same order`() {
        val result = ChatFolderPolicy.moveFolder(folders("A", "B", "C"), "f1", delta = 0)
        assertEquals(listOf("A", "B", "C"), result?.map { it.name })
        assertEquals(listOf(0, 1, 2), result?.map { it.sortOrder })
    }

    @Test
    fun `reorderFolder negative target clamps to front`() {
        val reordered = ChatFolderPolicy.reorderFolder(folders("A", "B", "C"), "f2", -5)
        assertEquals(listOf("C", "A", "B"), reordered?.map { it.name })
        assertEquals(listOf(0, 1, 2), reordered?.map { it.sortOrder })
    }
}
