package com.maodouchat.domain.conversation

import com.maodouchat.core.model.Chat
import com.maodouchat.core.model.MissedCall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 未接来电列表动作（ChatList 瘦身：mark/clear/remove + 通知中心清理）。
 *
 * ViewModel 只转发 UI intent；Room / tray / center 清理经注入端口执行。
 * 未接列表与会话列表经端口读写，`ChatListUiState` 仍在 app 侧；会话门禁走端口。
 */
class ChatListMissedCallCoordinator(
    private val scope: CoroutineScope,
    private val getMissedCalls: () -> List<MissedCall>,
    private val getChats: () -> List<Chat>,
    private val updateMissedCalls: ((List<MissedCall>) -> List<MissedCall>) -> Unit,
    private val ownerUserId: () -> String,
    private val markAllRead: suspend () -> Unit,
    private val clearAll: suspend () -> Unit,
    private val deleteCall: suspend (callId: String) -> Unit,
    private val cancelMissedCallNotification: (callId: String) -> Unit,
    private val removeCenterItem: suspend (id: String) -> Unit,
    private val sessionActive: (String) -> Boolean,
    private val logWarning: (String, Throwable?) -> Unit,
) {
    fun markMissedCallsRead() {
        val markOwnerUserId = ownerUserId()
        if (
            markOwnerUserId.isBlank() ||
            !sessionActive(markOwnerUserId)
        ) {
            return
        }
        scope.launch {
            if (!sessionActive(markOwnerUserId)
            ) {
                return@launch
            }
            val snapshot = getMissedCalls()
            try {
                markAllRead()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                logWarning("markMissedCallsRead failed", error)
            }
            if (!sessionActive(markOwnerUserId)
            ) {
                return@launch
            }
            // Drop tray + center rows so mark-read on the list card matches privacy UX.
            dismissMissedCallNotifications(snapshot)
        }
    }

    fun clearMissedCalls() {
        val clearOwnerUserId = ownerUserId()
        if (
            clearOwnerUserId.isBlank() ||
            !sessionActive(clearOwnerUserId)
        ) {
            return
        }
        scope.launch {
            if (!sessionActive(clearOwnerUserId)
            ) {
                return@launch
            }
            val snapshot = getMissedCalls()
            try {
                clearAll()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                logWarning("clearMissedCalls failed", error)
            }
            if (!sessionActive(clearOwnerUserId)
            ) {
                return@launch
            }
            dismissMissedCallNotifications(snapshot)
        }
    }

    /**
     * Resolve local 1:1 chat id for a missed-call peer (if a conversation already exists).
     */
    fun findDirectChatIdForUser(userId: String): String? {
        if (userId.isBlank()) return null
        return getChats().firstOrNull { chat ->
            !chat.isGroup && chat.participants.any { it.id == userId }
        }?.id
    }

    /**
     * 1.289：从会话列表移除单条通话记录。
     * 同步删除 Room missed_calls（保持角标一致）+ 更新本地 state（弹窗即时消失）。
     * CallLogStore 已由调用方删除。
     */
    fun removeMissedCallLocally(callId: String) {
        if (callId.isBlank()) return
        val owner = ownerUserId()
        updateMissedCalls { it.filterNot { call -> call.id == callId } }
        if (owner.isBlank()) return
        scope.launch {
            if (!sessionActive(owner)
            ) {
                return@launch
            }
            try {
                deleteCall(callId)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
            }
            // 若该条已聚合在系统托盘，同步移除阴影
            runCatching { cancelMissedCallNotification(callId) }
        }
    }

    private suspend fun dismissMissedCallNotifications(snapshot: List<MissedCall>) {
        for (call in snapshot) {
            try {
                cancelMissedCallNotification(call.id)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
            }
        }
        try {
            for (call in snapshot) {
                removeCenterItem("missed_${call.id}")
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logWarning("missed-call center cleanup failed", error)
        }
    }

}
