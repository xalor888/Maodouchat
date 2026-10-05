package com.maodouchat.ui.screen.chatdetail

import android.app.Application
import com.maodouchat.data.model.MessageType
import com.maodouchat.util.CaptureAlertPolicy
import com.maodouchat.util.RuntimeFlags

// 截屏/录屏检测的本地提示 + 对端 E2EE 告警（仅密聊或阅后即焚的 1:1 会话才告警，8s 节流）：
// 逻辑从 ChatDetailViewModel 纯搬移。
internal class ChatCaptureAlertController(
    private val getApplication: () -> Application,
    private val activeChatId: () -> String,
    private val currentState: () -> ChatDetailUiState,
    private val updateState: ((ChatDetailUiState) -> ChatDetailUiState) -> Unit,
    private val sendInlineContent: (String, MessageType, String) -> String?,
    private val getLastPeerNotifyAt: () -> Long,
    private val setLastPeerNotifyAt: (Long) -> Unit,
) {
    fun notifyLocalCaptureDetected(message: String) {
        val msg = message.trim()
        if (msg.isBlank()) return
        val state = currentState()
        val disappearOn = (state.chat?.disappearingMessageSeconds ?: 0) > 0
        val shouldWarnPeer = (state.isSecretChat == true) || disappearOn
        updateState {
            it.copy(
                groupEncryptionWarning = msg,
                secretChatInfoMessage = if (it.isSecretChat == true) msg else it.secretChatInfoMessage
            )
        }
        // Best-effort peer notice over E2EE (1:1 only). Debounced inside helper.
        if (shouldWarnPeer && state.chat?.isGroup != true) {
            sendCaptureAlertToPeer()
        }
    }

    private fun sendCaptureAlertToPeer() {
        if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.CAPTURE_ALERT)) return
        val now = System.currentTimeMillis()
        if (now - getLastPeerNotifyAt() < 8_000L) return
        setLastPeerNotifyAt(now)
        if (activeChatId().isBlank()) return
        if (currentState().chat?.isGroup == true) return
        val label = com.maodouchat.session.CurrentSession.snapshot().userId?.take(8) ?: "me"
        val content = CaptureAlertPolicy.format(label, "screenshot")
        // Reuse sticker/nudge-like inline send pipeline (E2EE TEXT envelope).
        sendInlineContent(content, MessageType.TEXT, content)
    }
}
