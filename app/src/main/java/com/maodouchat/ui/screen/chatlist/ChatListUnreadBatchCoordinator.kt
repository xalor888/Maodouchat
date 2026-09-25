package com.maodouchat.ui.screen.chatlist

import com.maodouchat.data.model.Chat
import com.maodouchat.data.model.Message
import com.maodouchat.ui.OwnerSessionSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 会话列表批量已读（ChatList 瘦身：全部已读 / 多选已读）。
 *
 * 乐观 UI 归零 → Room 投影 → tray 清理 → 普通会话 durable v2 read watermark。
 * 密聊只清本地角标，不发已读回执。
 */
internal class ChatListUnreadBatchCoordinator(
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<ChatListUiState>,
    private val ownerUserId: () -> String,
    private val ownerSession: (ownerUserId: String) -> OwnerSessionSnapshot,
    private val isOwnerSessionCurrent: (OwnerSessionSnapshot) -> Boolean,
    private val withOwnerRoomWrite: suspend (OwnerSessionSnapshot, suspend () -> Unit) -> Boolean,
    private val getCachedChat: suspend (chatId: String) -> Chat?,
    private val cacheChats: suspend (List<Chat>) -> Unit,
    private val getLatestIncomingMessage: suspend (chatId: String, ownerUserId: String) -> Message?,
    private val enqueueReadReceipt: suspend (
        conversationId: String,
        throughMessageId: String,
        groupRevision: Long?,
    ) -> Unit,
    private val cancelMessageNotification: (chatId: String) -> Unit,
) {
    /**
     * 未读文件夹「全部已读」：本地原子清零，再为每个普通会话写入持久 v2 已读水位。
     * 乐观投影会落 Room，保证列表即时收敛且进程死亡不复活角标。
     */
    fun markAllUnreadChatsRead() {
        val chats = uiState.value.chats
        markReadBatch(
            chatIds = chats.map { it.id }.toSet(),
            excludeArchived = true,
        )
    }

    /** Batch local read projection plus one durable v2 read watermark per conversation. */
    fun batchMarkReadSelected() {
        val selected = uiState.value.selectedChatIds
        if (selected.isEmpty()) return
        markReadBatch(chatIds = selected, excludeArchived = false)
    }

    private fun markReadBatch(chatIds: Set<String>, excludeArchived: Boolean) {
        val owner = ownerUserId()
        if (owner.isBlank() || chatIds.isEmpty()) return
        val targets = selectUnreadBatchTargets(
            uiState.value.chats,
            chatIds,
            excludeArchived = excludeArchived,
        )
        val toRead = targets.ordinary + targets.secret
        if (toRead.isEmpty()) return
        val ordinary = targets.ordinary
        val session = ownerSession(owner)
        val allReadIds = toRead.map { it.id }.toSet()
        uiState.update { state ->
            state.copy(chats = zeroChatsUnread(state.chats, allReadIds))
        }
        scope.launch {
            toRead.forEach { chat ->
                if (!isOwnerSessionCurrent(session)) return@launch
                // 这里读令牌**只是**为了判断「还在不在登录态」（读到的值从未被使用），
                // 所以用会话层的判断而不是取凭据（G332）。
                if (!com.maodouchat.session.CurrentSession.hasSession()) return@launch
                try {
                    withOwnerRoomWrite(session) {
                        val cached = getCachedChat(chat.id)
                        val zeroed = cached?.copy(unreadCount = 0, markedUnread = false)
                            ?: chat.copy(unreadCount = 0, markedUnread = false)
                        if (cached == null || cached.unreadCount != 0 || cached.markedUnread) {
                            cacheChats(listOf(zeroed))
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // 本地已读缓存失败不阻塞后续清理
                }
                // 已读后清理该会话的 tray 通知（与聊天页进入后的行为一致）
                runCatching { cancelMessageNotification(chat.id) }
            }
            if (ordinary.isNotEmpty() && isOwnerSessionCurrent(session)) {
                ordinary.forEach { chat ->
                    val boundary = getLatestIncomingMessage(chat.id, owner) ?: return@forEach
                    enqueueReadReceipt(
                        chat.id,
                        boundary.id,
                        chat.memberRevision.takeIf { chat.isGroup },
                    )
                }
            }
        }
    }
}
