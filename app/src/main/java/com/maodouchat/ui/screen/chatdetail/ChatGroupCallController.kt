package com.maodouchat.ui.screen.chatdetail

import android.app.Application
import com.maodouchat.R
import com.maodouchat.util.RuntimeFlags
import com.maodouchat.webrtc.CallType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

// 群通话入口：从 ChatDetailViewModel 纯搬移；VM 只留同签名委托。
internal class ChatGroupCallController(
    private val uiState: MutableStateFlow<ChatDetailUiState>,
    private val application: Application,
    private val currentUserId: () -> String,
    private val text: (Int, Array<out Any>) -> String,
) {
    /** 群通话入口：把当前群除自己外的成员挨个邀请 */
    fun startGroupCallFromChat(
        callType: CallType,
        selectedMemberIds: Set<String>? = null
    ) {
        val chat = uiState.value.chat ?: return
        if (!chat.isGroup) return
        if (!RuntimeFlags.isEnabled(application, RuntimeFlags.CALLS)) {
            uiState.update { it.copy(errorMessage = text(R.string.calls_disabled, arrayOf())) }
            return
        }
        val fineOk = when (callType) {
            CallType.VIDEO -> RuntimeFlags.isEnabled(application, RuntimeFlags.VIDEO_CALL)
            else -> RuntimeFlags.isEnabled(application, RuntimeFlags.VOICE_CALL)
        }
        if (!fineOk) {
            uiState.update {
                it.copy(
                    errorMessage = text(
                        if (callType == CallType.VIDEO) R.string.video_call_disabled
                        else R.string.voice_call_disabled,
                        arrayOf()
                    )
                )
            }
            return
        }
        val memberIds = chat.participants.map { it.id }.filter {
            it != currentUserId() && (selectedMemberIds == null || it in selectedMemberIds)
        }
        if (memberIds.isEmpty()) {
            uiState.update { it.copy(groupEncryptionWarning = text(R.string.chat_group_call_empty, arrayOf())) }
            return
        }
        // 委派给 CallViewModel 处理（CallViewModel 需通过 NavGraph 注入；这里用单例 fallback）
        com.maodouchat.call.CallOrchestrator.requestGroupCall(chat.id, memberIds, callType)
    }
}
