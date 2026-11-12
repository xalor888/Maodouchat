package com.maodouchat.domain.conversation

import com.maodouchat.core.model.Chat
import com.maodouchat.core.model.ChatFolder
import com.maodouchat.core.model.ChatFolderDto
import com.maodouchat.core.model.ChatFolderPolicy
import com.maodouchat.core.model.ChatFoldersSyncResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 会话列表「文件夹 + 未读优先」控制器。
 * 把文件夹的本地/云端同步、排序、移动会话等逻辑从 ChatListViewModel 抽出；
 * 只依赖注入进来的 scope 与端口，不依赖 ViewModel 或 Application 单例。
 */
class ChatFolderController(
    private val scope: CoroutineScope,
    private val getFolders: () -> List<ChatFolder>,
    private val updateFolders: (List<ChatFolder>) -> Unit,
    private val getSelectedFolderId: () -> String?,
    private val updateSelectedFolderId: (String?) -> Unit,
    private val getSecretChatIds: () -> Set<String>,
    private val getChats: () -> List<Chat>,
    private val updateUnreadPriorityEnabled: (Boolean) -> Unit,
    private val getLocalFolders: () -> List<ChatFolder>,
    private val setLocalFolders: (List<ChatFolder>) -> Unit,
    private val isUnreadPriorityEnabled: () -> Boolean,
    private val persistUnreadPriorityEnabled: (Boolean) -> Unit,
    private val foldersEnabled: () -> Boolean,
    private val ownerUserId: () -> String,
    private val hasSession: () -> Boolean,
    private val sessionMayContinue: (String) -> Boolean,
    private val fetchRemoteFolders: suspend () -> Result<ChatFoldersSyncResponse>,
    private val pushRemoteFolders: suspend (List<ChatFolderDto>) -> Result<ChatFoldersSyncResponse>,
) {
    fun loadFolders() {
        val folders = getLocalFolders()
        updateFolders(folders)
        syncFoldersFromCloud()
    }

    /** 拉取云端文件夹并与本地合并（云端更新时间更新时优先云端，否则推本地）。 */
    private fun syncFoldersFromCloud() {
        val ownerId = ownerUserId()
        if (!hasSession()) return
        scope.launch {
            if (!sessionMayContinue(ownerId)) return@launch
            val remoteResult = fetchRemoteFolders()
            val remoteError = remoteResult.exceptionOrNull()
            if (remoteError is CancellationException) throw remoteError
            val remote = remoteResult.getOrNull() ?: return@launch
            if (!sessionMayContinue(ownerId)) return@launch
            val local = getLocalFolders()
            if (remote.folders.isEmpty() && local.isNotEmpty()) {
                pushFoldersToCloud(local)
                return@launch
            }
            if (remote.folders.isEmpty()) return@launch
            val mapped = remote.folders.map { dto ->
                ChatFolder(
                    id = dto.id,
                    name = dto.name,
                    chatIds = dto.chatIds,
                    sortOrder = dto.sortOrder
                )
            }.sortedBy { it.sortOrder }
            setLocalFolders(mapped)
            updateFolders(mapped)
            val selectedId = getSelectedFolderId()
            val selectedStillExists = selectedId == null ||
                ChatFolderPolicy.isSystemFilter(selectedId) ||
                mapped.any { folder -> folder.id == selectedId }
            updateSelectedFolderId(if (selectedStillExists) selectedId else null)
        }
    }

    private fun pushFoldersToCloud(folders: List<ChatFolder>) {
        val ownerId = ownerUserId()
        if (!hasSession()) return
        scope.launch {
            if (!sessionMayContinue(ownerId)) return@launch
            val payload = folders.map { folder ->
                ChatFolderDto(
                    id = folder.id,
                    name = folder.name,
                    sortOrder = folder.sortOrder,
                    chatIds = folder.chatIds
                )
            }
            val result = pushRemoteFolders(payload)
            val error = result.exceptionOrNull()
            if (error is CancellationException) throw error
        }
    }

    fun loadUnreadPriority() {
        refreshUnreadPriorityPreference()
    }

    /** 从设置页返回时同步开关（不外发）。 */
    fun refreshUnreadPriorityPreference() {
        val enabled = isUnreadPriorityEnabled()
        updateUnreadPriorityEnabled(enabled)
    }

    fun setUnreadPriorityEnabled(enabled: Boolean) {
        persistUnreadPriorityEnabled(enabled)
        updateUnreadPriorityEnabled(enabled)
    }

    private fun persistFolders(folders: List<ChatFolder>) {
        val secretIds = getSecretChatIds() +
            getChats().filter { it.isSecret }.map { it.id }.toSet()
        val sanitized = folders.map { folder ->
            folder.copy(chatIds = folder.chatIds.filterNot { it in secretIds })
        }
        setLocalFolders(sanitized)
        updateFolders(sanitized)
        val selectedId = getSelectedFolderId()
        val selectedStillExists = selectedId == null ||
            ChatFolderPolicy.isSystemFilter(selectedId) ||
            sanitized.any { folder -> folder.id == selectedId }
        updateSelectedFolderId(if (selectedStillExists) selectedId else null)
        pushFoldersToCloud(sanitized)
    }

    fun selectFolder(folderId: String?) {
        val id = folderId?.takeIf { raw -> raw.isNotBlank() }
        // System filters and user folders both sticky for this session only
        updateSelectedFolderId(id)
    }

    fun createFolder(name: String): Boolean {
        if (!foldersEnabled()) {
            return false
        }
        val next = ChatFolderPolicy.createFolder(getFolders(), name)
            ?: return false
        persistFolders(next)
        return true
    }

    fun renameFolder(folderId: String, name: String): Boolean {
        val next = ChatFolderPolicy.renameFolder(getFolders(), folderId, name)
            ?: return false
        persistFolders(next)
        return true
    }

    fun deleteFolder(folderId: String) {
        persistFolders(ChatFolderPolicy.deleteFolder(getFolders(), folderId))
    }

    /** 文件夹上下移（交换 sortOrder，本地+云端同步）。 */
    fun moveFolder(folderId: String, delta: Int): Boolean {
        val next = ChatFolderPolicy.moveFolder(getFolders(), folderId, delta)
            ?: return false
        persistFolders(next)
        return true
    }

    /** 拖拽排序——把文件夹移到目标位置（插入语义，云端同步）。 */
    fun reorderFolder(folderId: String, targetIndex: Int): Boolean {
        val next = ChatFolderPolicy.reorderFolder(getFolders(), folderId, targetIndex)
            ?: return false
        persistFolders(next)
        return true
    }

    fun moveChatToFolder(chatId: String, folderId: String?) {
        if (chatId.isBlank()) return
        val moving = getChats().firstOrNull { it.id == chatId }
        if (moving?.isSecret == true || chatId in getSecretChatIds()) return
        persistFolders(
            ChatFolderPolicy.moveChatToFolder(
                existing = getFolders(),
                chatId = chatId,
                targetFolderId = folderId
            )
        )
    }
}
