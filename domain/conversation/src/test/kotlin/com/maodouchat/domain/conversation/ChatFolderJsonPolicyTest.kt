package com.maodouchat.domain.conversation

import com.maodouchat.core.model.ChatFolder
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatFolderJsonPolicyTest {

    private fun folder(id: String, name: String, sortOrder: Int, chatIds: List<String> = emptyList()) =
        ChatFolder(id = id, name = name, chatIds = chatIds, sortOrder = sortOrder)

    @Test
    fun roundTripPreservesFolders() {
        val folders = listOf(folder("f1", "工作", 1, listOf("c1", "c2")), folder("f2", "家人", 0))
        assertEquals(folders.sortedBy { it.sortOrder }, ChatFolderJsonPolicy.decodeFolders(ChatFolderJsonPolicy.encodeFolders(folders)))
    }

    @Test
    fun decodeInvalidJsonReturnsEmpty() {
        assertEquals(emptyList<ChatFolder>(), ChatFolderJsonPolicy.decodeFolders("not json"))
        assertEquals(emptyList<ChatFolder>(), ChatFolderJsonPolicy.decodeFolders(""))
    }

    @Test
    fun decodeSkipsEmptyIdOrName() {
        val raw = "[{\"id\":\"\",\"name\":\"x\",\"sortOrder\":0},{\"id\":\"f1\",\"name\":\"   \",\"sortOrder\":1},{\"id\":\"f2\",\"name\":\"ok\",\"sortOrder\":2}]"
        assertEquals(listOf(folder("f2", "ok", 2)), ChatFolderJsonPolicy.decodeFolders(raw))
    }

    @Test
    fun decodeDedupsChatIdsAndTrims() {
        val raw = "[{\"id\":\"f1\",\"name\":\"x\",\"sortOrder\":0,\"chatIds\":[\" c1 \",\"c1\",\"\",\"c2\"]}]"
        assertEquals(listOf(folder("f1", "x", 0, listOf("c1", "c2"))), ChatFolderJsonPolicy.decodeFolders(raw))
    }

    @Test
    fun decodeSortsBySortOrder() {
        val raw = "[{\"id\":\"b\",\"name\":\"b\",\"sortOrder\":2},{\"id\":\"a\",\"name\":\"a\",\"sortOrder\":0}]"
        assertEquals(listOf("a", "b"), ChatFolderJsonPolicy.decodeFolders(raw).map { it.id })
    }

    @Test
    fun decodeMissingSortOrderDefaultsToIndex() {
        val raw = "[{\"id\":\"a\",\"name\":\"a\"},{\"id\":\"b\",\"name\":\"b\"}]"
        assertEquals(listOf(0, 1), ChatFolderJsonPolicy.decodeFolders(raw).map { it.sortOrder })
    }
}
