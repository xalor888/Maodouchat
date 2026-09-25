package com.maodouchat.ui.screen.chatlist

import android.util.Log
import com.maodouchat.ai.AiArchiveSuggestion
import com.maodouchat.data.local.entity.ArchiveSuggestionDismissalEntity
import com.maodouchat.data.model.Chat
import com.maodouchat.session.CurrentSession
import com.maodouchat.ui.OwnerSessionSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 智能归档建议（ChatList 瘦身：加载/忽略持久化/采纳）。
 *
 * Room dismissal 与 AiArchiveSuggestion 打分经注入端口；ViewModel 只转发 intent
 * 并提供 archiveChat 回调（复用 settings toggle）。
 */
internal class ChatListArchiveSuggestionCoordinator(
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<ChatListUiState>,
    private val ownerUserId: () -> String,
    private val ownerSession: (ownerUserId: String) -> OwnerSessionSnapshot,
    private val isOwnerSessionCurrent: (OwnerSessionSnapshot) -> Boolean,
    private val loadDismissedIds: suspend (ownerUserId: String) -> List<String>,
    private val addDismissal: suspend (ownerUserId: String, chatId: String, atMillis: Long) -> Unit,
    private val refreshSuggestions: suspend () -> List<AiArchiveSuggestion.Suggestion>,
    private val onArchiveChat: (Chat) -> Unit,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /** 8.48 修复：忽略集合按账号持久化；Room 表承载，init 时异步加载。 */
    private val dismissedArchiveSuggestions = mutableSetOf<String>()

    fun start() {
        scope.launch {
            val userId = CurrentSession.ownerUserId()
            if (userId.isBlank()) return@launch
            val ids = try {
                withContext(ioDispatcher) { loadDismissedIds(userId) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return@launch
            }
            if (ids.isEmpty()) return@launch
            dismissedArchiveSuggestions += ids
            // 加载完成前已展示的建议里可能混入已忽略项，重新过滤一次。
            uiState.update { state ->
                state.copy(
                    archiveSuggestions = state.archiveSuggestions.filter { s ->
                        s.chatId !in dismissedArchiveSuggestions
                    }
                )
            }
        }
    }

    /** 重算智能归档建议（纯本地 SQLCipher 打分，无服务端调用）。 */
    fun loadArchiveSuggestions() {
        val owner = ownerUserId()
        if (owner.isBlank()) return
        val session = ownerSession(owner)
        scope.launch {
            if (!isOwnerSessionCurrent(session)) return@launch
            val suggestions = try {
                refreshSuggestions()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "loadArchiveSuggestions failed", error)
                emptyList()
            }
            if (!isOwnerSessionCurrent(session)) return@launch
            uiState.update {
                it.copy(
                    archiveSuggestions = suggestions.filter { s ->
                        s.chatId !in dismissedArchiveSuggestions
                    }
                )
            }
        }
    }

    /** 忽略单条归档建议（持久化，进程重启后不重现）。 */
    fun dismissArchiveSuggestion(chatId: String) {
        if (chatId.isBlank()) return
        dismissedArchiveSuggestions += chatId
        persistDismissal(chatId)
        uiState.update {
            it.copy(archiveSuggestions = it.archiveSuggestions.filter { s -> s.chatId != chatId })
        }
    }

    /** 忽略全部归档建议（持久化）。 */
    fun dismissAllArchiveSuggestions() {
        val ids = uiState.value.archiveSuggestions.map { it.chatId }.filter { it.isNotBlank() }
        if (ids.isEmpty()) return
        dismissedArchiveSuggestions += ids
        persistDismissals(ids)
        uiState.update { it.copy(archiveSuggestions = emptyList()) }
    }

    /** 采纳智能归档建议：归档会话（复用 toggleArchived 服务端同步）并移除建议。 */
    fun archiveChatFromSuggestion(chat: Chat) {
        // 8.48 修复：建议卡片只在 init+3s 计算一次，本地 archived 可能已陈旧——
        // 若该会话已被（长按菜单/他端/服务端）归档，点「归档」不得反向取消归档，仅移除建议
        val fresh = uiState.value.chats.firstOrNull { it.id == chat.id }
        if (fresh != null && !fresh.archived) onArchiveChat(chat)
        dismissArchiveSuggestion(chat.id)
    }

    private fun dismissalOwnerId(): String = CurrentSession.ownerUserId()

    private fun persistDismissal(chatId: String) {
        val userId = dismissalOwnerId()
        if (userId.isBlank()) return
        scope.launch(ioDispatcher) {
            try {
                addDismissal(userId, chatId, System.currentTimeMillis())
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                Log.w(TAG, "persist archive dismissal failed for $chatId")
            }
        }
    }

    private fun persistDismissals(chatIds: List<String>) {
        val userId = dismissalOwnerId()
        if (userId.isBlank()) return
        scope.launch(ioDispatcher) {
            try {
                val now = System.currentTimeMillis()
                chatIds.forEach { chatId ->
                    addDismissal(userId, chatId, now)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                Log.w(TAG, "persist archive dismissals failed")
            }
        }
    }

    companion object {
        private const val TAG = "ChatListArchiveSuggest"

        fun dismissalEntity(
            ownerUserId: String,
            chatId: String,
            atMillis: Long,
        ): ArchiveSuggestionDismissalEntity =
            ArchiveSuggestionDismissalEntity(
                ownerUserId = ownerUserId,
                chatId = chatId,
                dismissedAtMillis = atMillis,
            )
    }
}
