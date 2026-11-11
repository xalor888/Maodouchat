package com.maodouchat.domain.conversation

import com.maodouchat.core.model.ChatDetailUiState

// 名片发送：功能开关 / 密聊拦截 / 内容拼装，然后调发送流水线。
// 从 ChatDetailViewModel 纯搬移；调用方（作曲区扩展）仍走 VM 的同签名委托。
class ChatContactCardController(
    private val contactCardEnabled: () -> Boolean,
    private val text: (ChatContactCardMessage) -> String,
    private val currentState: () -> ChatDetailUiState,
    private val updateState: ((ChatDetailUiState) -> ChatDetailUiState) -> Unit,
    private val sendMessage: (String) -> Unit,
) {
    fun sendContactCard(targetUserId: String, displayName: String) {
        if (!contactCardEnabled()) {
            updateState { it.copy(groupEncryptionWarning = text(ChatContactCardMessage.CONTACT_CARD_DISABLED)) }
            return
        }
        if (currentState().isSecretChat == true) {
            updateState { it.copy(groupEncryptionWarning = text(ChatContactCardMessage.CONTACT_CARD_SECRET_BLOCKED)) }
            return
        }
        if (targetUserId.isBlank()) return
        val safeName = displayName.trim().take(80).ifBlank { "contact" }
        val cardContent = buildString {
            append("👤 ")
            append(safeName)
            append("\n[contactUser:")
            append(targetUserId)
            append("]")
        }
        // 8.49 修复：改走 forceText——写入 inputText 再 sendMessage() 会把用户未发送的
        // 持久化草稿一并清除（sendMessage 清空输入框并 clearDraft），造成数据丢失
        sendMessage(cardContent)
    }
}
