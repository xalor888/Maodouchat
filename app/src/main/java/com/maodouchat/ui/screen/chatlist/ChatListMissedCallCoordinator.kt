package com.maodouchat.ui.screen.chatlist

import android.util.Log
import com.maodouchat.data.model.MissedCall
import com.maodouchat.network.TokenManager
import com.maodouchat.security.BackgroundSessionGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 未接来电列表动作（ChatList 瘦身：mark/clear/remove + 通知中心清理）。
 *
 * ViewModel 只转发 UI intent；Room / tray / center 清理经注入端口执行。
 */
internal class ChatListMissedCallCoordinator(
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<ChatListUiState>,
    private val tokenManager: TokenManager,
    private val ownerUserId: () -> String,
    private val markAllRead: suspend () -> Unit,
    private val clearAll: suspend () -> Unit,
    private val deleteCall: suspend (callId: String) -> Unit,
    private val cancelMissedCallNotification: (callId: String) -> Unit,
    private val removeCenterItem: suspend (id: String) -> Unit,
) {
    fun markMissedCallsRead() {
        val markOwnerUserId = ownerUserId()
        if (
            markOwnerUserId.isBlank() ||
            !BackgroundSessionGate.mayContinue(
                expectedUserId = markOwnerUserId,
                liveToken = tokenManager.getToken(),
                liveUserId = tokenManager.getUserId(),
            )
        ) {
            return
        }
        scope.launch {
            if (!BackgroundSessionGate.mayContinue(
                    expectedUserId = markOwnerUserId,
                    liveToken = tokenManager.getToken(),
                    liveUserId = tokenManager.getUserId(),
                )
            ) {
                return@launch
            }
            val snapshot = uiState.value.missedCalls
            try {
                markAllRead()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "markMissedCallsRead failed", error)
            }
            if (!BackgroundSessionGate.mayContinue(
                    expectedUserId = markOwnerUserId,
                    liveToken = tokenManager.getToken(),
                    liveUserId = tokenManager.getUserId(),
                )
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
            !BackgroundSessionGate.mayContinue(
                expectedUserId = clearOwnerUserId,
                liveToken = tokenManager.getToken(),
                liveUserId = tokenManager.getUserId(),
            )
        ) {
            return
        }
        scope.launch {
            if (!BackgroundSessionGate.mayContinue(
                    expectedUserId = clearOwnerUserId,
                    liveToken = tokenManager.getToken(),
                    liveUserId = tokenManager.getUserId(),
                )
            ) {
                return@launch
            }
            val snapshot = uiState.value.missedCalls
            try {
                clearAll()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "clearMissedCalls failed", error)
            }
            if (!BackgroundSessionGate.mayContinue(
                    expectedUserId = clearOwnerUserId,
                    liveToken = tokenManager.getToken(),
                    liveUserId = tokenManager.getUserId(),
                )
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
        return uiState.value.chats.firstOrNull { chat ->
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
        uiState.update { st ->
            st.copy(missedCalls = st.missedCalls.filterNot { it.id == callId })
        }
        if (owner.isBlank()) return
        scope.launch {
            if (!BackgroundSessionGate.mayContinue(
                    expectedUserId = owner,
                    liveToken = tokenManager.getToken(),
                    liveUserId = tokenManager.getUserId(),
                )
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
            Log.w(TAG, "missed-call center cleanup failed", error)
        }
    }

    companion object {
        private const val TAG = "ChatListMissedCall"
    }
}
