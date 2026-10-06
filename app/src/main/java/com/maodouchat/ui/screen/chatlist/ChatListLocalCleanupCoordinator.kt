package com.maodouchat.ui.screen.chatlist

import android.util.Log
import com.maodouchat.conversation.ConversationLocalCleanupMode
import com.maodouchat.conversation.ConversationLocalCleanupSession
import com.maodouchat.conversation.ConversationLocalStateCoordinator
import com.maodouchat.conversation.conversationLocalCleanupSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 本地数据清理（ChatList 瘦身：自 ChatListViewModel 抽出）。 */
internal class ChatListLocalCleanupCoordinator(
    private val scope: CoroutineScope,
    private val ownerUserId: () -> String,
    private val localStateCoordinator: ConversationLocalStateCoordinator,
    private val deleteDraftForChat: suspend (ownerUserId: String, chatId: String) -> Unit,
    private val reloadChats: () -> Unit,
) {
    /** 清空指定会话的本地明文（保留会话/PIN/草稿/同步游标）。不清游标，避免重拉密文 Duplicate。 */
    fun clearLocalChatHistory(chatId: String) {
        if (chatId.isBlank()) return
        val owner = ownerUserId()
        if (owner.isBlank()) return
        val cleanupSession = conversationLocalCleanupSession(owner)
        scope.launch(Dispatchers.IO) {
            val report = withContext(NonCancellable) {
                localStateCoordinator.cleanup(
                    chatId = chatId,
                    expectedSession = cleanupSession,
                    mode = ConversationLocalCleanupMode.CLEAR_HISTORY,
                )
            }
            report.failures.forEach { failure ->
                Log.w(
                    "ChatListViewModel",
                    "local conversation cleanup failed at ${failure.step} for $chatId",
                    failure.error,
                )
            }
            if (!report.completed) return@launch
            reloadChats()
        }
    }

    /** 供 load/mutation 协调器复用的本地会话清理（自 ViewModel 逐字搬移）。 */
    suspend fun cleanupLocalChat(
        chatId: String,
        cleanupSession: ConversationLocalCleanupSession,
    ) {
        val report = localStateCoordinator.cleanup(
            chatId = chatId,
            expectedSession = cleanupSession,
            mode = ConversationLocalCleanupMode.DELETE_CONVERSATION,
        )
        report.failures.forEach { failure ->
            Log.w(
                "ChatListViewModel",
                "conversation deletion cleanup failed at ${failure.step} for $chatId",
                failure.error,
            )
        }
    }

    /** 1.142：会话列表长按菜单「清除草稿」（本地，不打开会话）。 */
    fun clearChatDraft(chatId: String) {
        if (chatId.isBlank()) return
        val owner = ownerUserId()
        if (owner.isBlank()) return
        scope.launch {
            try {
                deleteDraftForChat(owner, chatId)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
            }
        }
    }
}
