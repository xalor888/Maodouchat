package com.maodouchat.ui.screen.chatlist

import android.content.Context
import com.maodouchat.data.repository.ChatFolderNetworkRepository
import com.maodouchat.network.ChatFolderDto
import com.maodouchat.security.BackgroundSessionGate
import com.maodouchat.util.ChatFolder
import com.maodouchat.util.ChatFolderPolicy
import com.maodouchat.util.ChatFolderPreferences
import com.maodouchat.util.RuntimeFlags
import com.maodouchat.util.UnreadPriorityPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 会话列表「文件夹 + 未读优先」控制器。
 * 把文件夹的本地/云端同步、排序、移动会话等逻辑从 ChatListViewModel 抽出；
 * 只依赖注入进来的 scope / token / uiState，不依赖 ViewModel 或 Application 单例。
 */
class ChatFolderController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<ChatListUiState>,
) {
    fun loadFolders() {
        val folders = ChatFolderPreferences.getFolders(context)
        uiState.update { it.copy(folders = folders) }
        syncFoldersFromCloud()
    }

    /** 拉取云端文件夹并与本地合并（云端更新时间更新时优先云端，否则推本地）。 */
    private fun syncFoldersFromCloud() {
        val ownerUserId = com.maodouchat.session.CurrentSession.ownerUserId()
        if (!com.maodouchat.session.CurrentSession.hasSession()) return
        scope.launch {
            if (!BackgroundSessionGate.mayContinue(
                expectedUserId = ownerUserId,
            )
            ) return@launch
            val remoteResult = ChatFolderNetworkRepository().folders()
            val remoteError = remoteResult.exceptionOrNull()
            if (remoteError is CancellationException) throw remoteError
            val remote = remoteResult.getOrNull() ?: return@launch
            if (!BackgroundSessionGate.mayContinue(
                expectedUserId = ownerUserId,
            )
            ) return@launch
            val local = ChatFolderPreferences.getFolders(context)
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
            ChatFolderPreferences.setFolders(context, mapped)
            uiState.update {
                val selectedStillExists = it.selectedFolderId == null ||
                    ChatFolderPolicy.isSystemFilter(it.selectedFolderId) ||
                    mapped.any { folder -> folder.id == it.selectedFolderId }
                it.copy(
                    folders = mapped,
                    selectedFolderId = if (selectedStillExists) it.selectedFolderId else null
                )
            }
        }
    }

    private fun pushFoldersToCloud(folders: List<ChatFolder>) {
        val ownerUserId = com.maodouchat.session.CurrentSession.ownerUserId()
        if (!com.maodouchat.session.CurrentSession.hasSession()) return
        scope.launch {
            if (!BackgroundSessionGate.mayContinue(
                expectedUserId = ownerUserId,
            )
            ) return@launch
            val payload = folders.map { folder ->
                ChatFolderDto(
                    id = folder.id,
                    name = folder.name,
                    sortOrder = folder.sortOrder,
                    chatIds = folder.chatIds
                )
            }
            val result = ChatFolderNetworkRepository().putFolders(folders = payload)
            val error = result.exceptionOrNull()
            if (error is CancellationException) throw error
        }
    }

    fun loadUnreadPriority() {
        refreshUnreadPriorityPreference()
    }

    /** 从设置页返回时同步开关（不外发）。 */
    fun refreshUnreadPriorityPreference() {
        val enabled = UnreadPriorityPreferences.isEnabled(context)
        uiState.update { it.copy(unreadPriorityEnabled = enabled) }
    }

    fun setUnreadPriorityEnabled(enabled: Boolean) {
        UnreadPriorityPreferences.setEnabled(context, enabled)
        uiState.update { it.copy(unreadPriorityEnabled = enabled) }
    }

    private fun persistFolders(folders: List<ChatFolder>) {
        val secretIds = uiState.value.secretChatIds +
            uiState.value.chats.filter { it.isSecret }.map { it.id }.toSet()
        val sanitized = folders.map { folder ->
            folder.copy(chatIds = folder.chatIds.filterNot { it in secretIds })
        }
        ChatFolderPreferences.setFolders(context, sanitized)
        uiState.update {
            val selectedStillExists = it.selectedFolderId == null ||
                ChatFolderPolicy.isSystemFilter(it.selectedFolderId) ||
                sanitized.any { folder -> folder.id == it.selectedFolderId }
            it.copy(
                folders = sanitized,
                selectedFolderId = if (selectedStillExists) it.selectedFolderId else null
            )
        }
        pushFoldersToCloud(sanitized)
    }

    fun selectFolder(folderId: String?) {
        uiState.update {
            val id = folderId?.takeIf { raw -> raw.isNotBlank() }
            // System filters and user folders both sticky for this session only
            it.copy(selectedFolderId = id)
        }
    }

    fun createFolder(name: String): Boolean {
        if (!RuntimeFlags.isEnabled(context, RuntimeFlags.CHAT_FOLDERS)) {
            return false
        }
        val next = ChatFolderPolicy.createFolder(uiState.value.folders, name)
            ?: return false
        persistFolders(next)
        return true
    }

    fun renameFolder(folderId: String, name: String): Boolean {
        val next = ChatFolderPolicy.renameFolder(uiState.value.folders, folderId, name)
            ?: return false
        persistFolders(next)
        return true
    }

    fun deleteFolder(folderId: String) {
        persistFolders(ChatFolderPolicy.deleteFolder(uiState.value.folders, folderId))
    }

    /** 文件夹上下移（交换 sortOrder，本地+云端同步）。 */
    fun moveFolder(folderId: String, delta: Int): Boolean {
        val next = ChatFolderPolicy.moveFolder(uiState.value.folders, folderId, delta)
            ?: return false
        persistFolders(next)
        return true
    }

    /** 拖拽排序——把文件夹移到目标位置（插入语义，云端同步）。 */
    fun reorderFolder(folderId: String, targetIndex: Int): Boolean {
        val next = ChatFolderPolicy.reorderFolder(uiState.value.folders, folderId, targetIndex)
            ?: return false
        persistFolders(next)
        return true
    }

    fun moveChatToFolder(chatId: String, folderId: String?) {
        if (chatId.isBlank()) return
        val moving = uiState.value.chats.firstOrNull { it.id == chatId }
        if (moving?.isSecret == true || chatId in uiState.value.secretChatIds) return
        persistFolders(
            ChatFolderPolicy.moveChatToFolder(
                existing = uiState.value.folders,
                chatId = chatId,
                targetFolderId = folderId
            )
        )
    }
}
