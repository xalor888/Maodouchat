package com.maodouchat.domain.conversation

import com.maodouchat.core.model.CallOrchestrator
import com.maodouchat.core.model.CallType
import com.maodouchat.core.model.ChatDetailUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

// 群通话入口：从 ChatDetailViewModel 纯搬移；VM 只留同签名委托。
class ChatGroupCallController(
    private val uiState: MutableStateFlow<ChatDetailUiState>,
    private val currentUserId: () -> String,
    private val callsEnabled: () -> Boolean,
    private val videoCallEnabled: () -> Boolean,
    private val voiceCallEnabled: () -> Boolean,
    private val text: (ChatGroupCallMessage) -> String,
) {
    /** 群通话入口：把当前群除自己外的成员挨个邀请 */
    fun startGroupCallFromChat(
        callType: CallType,
        selectedMemberIds: Set<String>? = null
    ) {
        val chat = uiState.value.chat ?: return
        if (!chat.isGroup) return
        if (!callsEnabled()) {
            uiState.update { it.copy(errorMessage = text(ChatGroupCallMessage.CALLS_DISABLED)) }
            return
        }
        val fineOk = when (callType) {
            CallType.VIDEO -> videoCallEnabled()
            else -> voiceCallEnabled()
        }
        if (!fineOk) {
            uiState.update {
                it.copy(
                    errorMessage = text(
                        if (callType == CallType.VIDEO) ChatGroupCallMessage.VIDEO_CALL_DISABLED
                        else ChatGroupCallMessage.VOICE_CALL_DISABLED
                    )
                )
            }
            return
        }
        val memberIds = chat.participants.map { it.id }.filter {
            it != currentUserId() && (selectedMemberIds == null || it in selectedMemberIds)
        }
        if (memberIds.isEmpty()) {
            uiState.update { it.copy(groupEncryptionWarning = text(ChatGroupCallMessage.GROUP_CALL_EMPTY)) }
            return
        }
        // 委派给 CallViewModel 处理（CallViewModel 需通过 NavGraph 注入；这里用单例 fallback）
        CallOrchestrator.requestGroupCall(chat.id, memberIds, callType)
    }
}
