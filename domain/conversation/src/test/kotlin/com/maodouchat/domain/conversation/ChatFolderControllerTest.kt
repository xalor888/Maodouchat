package com.maodouchat.domain.conversation

import com.maodouchat.core.model.Chat
import com.maodouchat.core.model.ChatFolder
import com.maodouchat.core.model.ChatFolderDto
import com.maodouchat.core.model.ChatFoldersSyncResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class ChatFolderControllerTest {

    private val dispatcher = StandardTestDispatcher()

    /** 替代 app 侧 ChatListUiState 与偏好设置/会话/网络的最小状态。 */
    private class State {
        var folders: List<ChatFolder> = emptyList()
        var selectedFolderId: String? = null
        var secretChatIds: Set<String> = emptySet()
        var chats: List<Chat> = emptyList()
        var unreadPriorityEnabled: Boolean = true
        var localFolders: List<ChatFolder> = emptyList()
        var localUnreadPriority: Boolean = false
        var ownerId: String = "owner-1"
        var hasSession: Boolean = true
        var sessionMayContinue: Boolean = true
        var foldersEnabled: Boolean = true
        var remoteResult: Result<ChatFoldersSyncResponse> =
            Result.success(ChatFoldersSyncResponse())
        var fetchCalls: Int = 0
        var pushed: List<ChatFolderDto>? = null
        var pushCalls: Int = 0
    }

    private fun CoroutineScope.buildController(state: State) = ChatFolderController(
        scope = this,
        getFolders = { state.folders },
        updateFolders = { state.folders = it },
        getSelectedFolderId = { state.selectedFolderId },
        updateSelectedFolderId = { state.selectedFolderId = it },
        getSecretChatIds = { state.secretChatIds },
        getChats = { state.chats },
        updateUnreadPriorityEnabled = { state.unreadPriorityEnabled = it },
        getLocalFolders = { state.localFolders },
        setLocalFolders = { state.localFolders = it },
        isUnreadPriorityEnabled = { state.localUnreadPriority },
        persistUnreadPriorityEnabled = { state.localUnreadPriority = it },
        foldersEnabled = { state.foldersEnabled },
        ownerUserId = { state.ownerId },
        hasSession = { state.hasSession },
        sessionMayContinue = { state.sessionMayContinue },
        fetchRemoteFolders = { state.fetchCalls++; state.remoteResult },
        pushRemoteFolders = { folders ->
            state.pushCalls++
            state.pushed = folders
            Result.success(ChatFoldersSyncResponse())
        },
    )

    private fun folder(id: String, name: String = id, order: Int = 0, chats: List<String> = emptyList()) =
        ChatFolder(id = id, name = name, chatIds = chats, sortOrder = order)

    private fun remote(vararg dtos: ChatFolderDto) =
        Result.success(ChatFoldersSyncResponse(folders = dtos.toList()))

    @Test
    fun `loadFolders applies local folders then adopts remote folders sorted`() = runTest(dispatcher) {
        val state = State().apply {
            localFolders = listOf(folder("f2", order = 2), folder("f1", order = 1))
            remoteResult = remote(
                ChatFolderDto(id = "r2", name = "R2", sortOrder = 2, chatIds = listOf("c2")),
                ChatFolderDto(id = "r1", name = "R1", sortOrder = 1, chatIds = listOf("c1")),
            )
        }
        val c = buildController(state)
        c.loadFolders()
        advanceUntilIdle()
        assertEquals(listOf("r1", "r2"), state.folders.map { it.id })
        assertEquals(listOf("r1", "r2"), state.localFolders.map { it.id })
        assertEquals(1, state.fetchCalls)
        assertNull(state.pushed)
    }

    @Test
    fun `loadFolders pushes local folders when remote is empty`() = runTest(dispatcher) {
        val state = State().apply {
            localFolders = listOf(folder("f1"))
            remoteResult = remote()
        }
        val c = buildController(state)
        c.loadFolders()
        advanceUntilIdle()
        assertEquals(listOf("f1"), state.folders.map { it.id })
        assertEquals(listOf("f1"), state.pushed?.map { it.id })
        assertEquals(1, state.pushCalls)
    }

    @Test
    fun `loadFolders does nothing when both sides are empty`() = runTest(dispatcher) {
        val state = State()
        val c = buildController(state)
        c.loadFolders()
        advanceUntilIdle()
        assertTrue(state.folders.isEmpty())
        assertNull(state.pushed)
        assertEquals(1, state.fetchCalls)
    }

    @Test
    fun `loadFolders skips sync without session`() = runTest(dispatcher) {
        val state = State().apply {
            localFolders = listOf(folder("f1"))
            hasSession = false
        }
        val c = buildController(state)
        c.loadFolders()
        advanceUntilIdle()
        assertEquals(listOf("f1"), state.folders.map { it.id })
        assertEquals(0, state.fetchCalls)
    }

    @Test
    fun `loadFolders skips sync when session gate blocks`() = runTest(dispatcher) {
        val state = State().apply {
            localFolders = listOf(folder("f1"))
            sessionMayContinue = false
        }
        val c = buildController(state)
        c.loadFolders()
        advanceUntilIdle()
        assertEquals(listOf("f1"), state.folders.map { it.id })
        assertEquals(0, state.fetchCalls)
    }

    @Test
    fun `loadFolders keeps local folders when fetch fails`() = runTest(dispatcher) {
        val state = State().apply {
            localFolders = listOf(folder("f1"))
            remoteResult = Result.failure(RuntimeException("offline"))
        }
        val c = buildController(state)
        c.loadFolders()
        advanceUntilIdle()
        assertEquals(listOf("f1"), state.folders.map { it.id })
        assertNull(state.pushed)
    }

    @Test
    fun `loadFolders clears selected folder that no longer exists`() = runTest(dispatcher) {
        val state = State().apply {
            selectedFolderId = "gone"
            remoteResult = remote(ChatFolderDto(id = "f1", name = "F1", sortOrder = 0))
        }
        val c = buildController(state)
        c.loadFolders()
        advanceUntilIdle()
        assertNull(state.selectedFolderId)
    }

    @Test
    fun `loadFolders keeps selected folder that still exists`() = runTest(dispatcher) {
        val state = State().apply {
            selectedFolderId = "f1"
            remoteResult = remote(ChatFolderDto(id = "f1", name = "F1", sortOrder = 0))
        }
        val c = buildController(state)
        c.loadFolders()
        advanceUntilIdle()
        assertEquals("f1", state.selectedFolderId)
    }

    @Test
    fun `createFolder returns false when flag is off`() = runTest(dispatcher) {
        val state = State().apply { foldersEnabled = false }
        val c = buildController(state)
        assertFalse(c.createFolder("Work"))
        assertTrue(state.folders.isEmpty())
        assertEquals(0, state.pushCalls)
    }

    @Test
    fun `createFolder persists locally and pushes to cloud`() = runTest(dispatcher) {
        val state = State()
        val c = buildController(state)
        assertTrue(c.createFolder("Work"))
        advanceUntilIdle()
        assertEquals(listOf("Work"), state.folders.map { it.name })
        assertEquals(listOf("Work"), state.localFolders.map { it.name })
        assertEquals(listOf("Work"), state.pushed?.map { it.name })
    }

    @Test
    fun `persist strips secret chat ids from folders`() = runTest(dispatcher) {
        val state = State().apply {
            folders = listOf(folder("f1", chats = listOf("s1", "c1")))
            secretChatIds = setOf("s1")
        }
        val c = buildController(state)
        assertTrue(c.createFolder("Work"))
        advanceUntilIdle()
        assertEquals(listOf("c1"), state.folders.first { it.id == "f1" }.chatIds)
    }

    @Test
    fun `renameFolder rejects blank name and renames valid name`() = runTest(dispatcher) {
        val state = State().apply { folders = listOf(folder("f1", "Old")) }
        val c = buildController(state)
        assertFalse(c.renameFolder("f1", "   "))
        assertEquals("Old", state.folders.single().name)
        assertTrue(c.renameFolder("f1", "New"))
        advanceUntilIdle()
        assertEquals("New", state.folders.single().name)
    }

    @Test
    fun `deleteFolder removes and pushes`() = runTest(dispatcher) {
        val state = State().apply { folders = listOf(folder("f1"), folder("f2")) }
        val c = buildController(state)
        c.deleteFolder("f1")
        advanceUntilIdle()
        assertEquals(listOf("f2"), state.folders.map { it.id })
        assertEquals(1, state.pushCalls)
    }

    @Test
    fun `moveFolder swaps sort order with neighbour`() = runTest(dispatcher) {
        val state = State().apply {
            folders = listOf(folder("f1", order = 0), folder("f2", order = 1))
        }
        val c = buildController(state)
        assertTrue(c.moveFolder("f1", 1))
        assertEquals(listOf("f2", "f1"), state.folders.map { it.id })
        assertFalse(c.moveFolder("f1", 1))
        advanceUntilIdle()
    }

    @Test
    fun `reorderFolder moves folder to target index`() = runTest(dispatcher) {
        val state = State().apply {
            folders = listOf(folder("f1", order = 0), folder("f2", order = 1), folder("f3", order = 2))
        }
        val c = buildController(state)
        assertTrue(c.reorderFolder("f1", 2))
        assertEquals(listOf("f2", "f3", "f1"), state.folders.map { it.id })
        advanceUntilIdle()
    }

    @Test
    fun `moveChatToFolder adds chat and skips secret chats`() = runTest(dispatcher) {
        val state = State().apply {
            folders = listOf(folder("f1"), folder("f2", chats = listOf("c1")))
            chats = listOf(Chat(id = "c1"), Chat(id = "c2", chatType = "SECRET"))
        }
        val c = buildController(state)
        c.moveChatToFolder("c1", "f1")
        assertEquals(listOf("c1"), state.folders.first { it.id == "f1" }.chatIds)
        assertTrue(state.folders.first { it.id == "f2" }.chatIds.isEmpty())
        c.moveChatToFolder("c2", "f1")
        assertEquals(listOf("c1"), state.folders.first { it.id == "f1" }.chatIds)
        c.moveChatToFolder("  ", "f1")
        assertEquals(listOf("c1"), state.folders.first { it.id == "f1" }.chatIds)
        advanceUntilIdle()
    }

    @Test
    fun `selectFolder blanks to null and keeps valid id`() = runTest(dispatcher) {
        val state = State()
        val c = buildController(state)
        c.selectFolder("f1")
        assertEquals("f1", state.selectedFolderId)
        c.selectFolder("   ")
        assertNull(state.selectedFolderId)
    }

    @Test
    fun `unread priority loads refreshes and sets`() = runTest(dispatcher) {
        val state = State().apply { localUnreadPriority = true }
        val c = buildController(state)
        c.loadUnreadPriority()
        assertTrue(state.unreadPriorityEnabled)
        state.localUnreadPriority = false
        c.refreshUnreadPriorityPreference()
        assertFalse(state.unreadPriorityEnabled)
        c.setUnreadPriorityEnabled(true)
        assertTrue(state.unreadPriorityEnabled)
        assertTrue(state.localUnreadPriority)
    }
}
