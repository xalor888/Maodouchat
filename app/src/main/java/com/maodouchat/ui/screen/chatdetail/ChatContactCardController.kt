package com.maodouchat.ui.screen.chatdetail

import android.app.Application
import com.maodouchat.R
import com.maodouchat.util.RuntimeFlags

// 名片发送：功能开关 / 密聊拦截 / 内容拼装，然后调发送流水线。
// 从 ChatDetailViewModel 纯搬移；调用方（作曲区扩展）仍走 VM 的同签名委托。
internal class ChatContactCardController(
    private val getApplication: () -> Application,
    private val textProvider: (Int, Array<out Any>) -> String,
    private val currentState: () -> ChatDetailUiState,
    private val updateState: ((ChatDetailUiState) -> ChatDetailUiState) -> Unit,
    private val sendMessage: (String) -> Unit,
) {
    private fun text(id: Int, vararg args: Any): String = textProvider(id, args)

    internal fun sendContactCard(targetUserId: String, displayName: String) {
        if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.CONTACT_CARD)) {
            updateState { it.copy(groupEncryptionWarning = text(R.string.contact_card_disabled)) }
            return
        }
        if (currentState().isSecretChat == true) {
            updateState { it.copy(groupEncryptionWarning = text(R.string.contact_card_secret_blocked)) }
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
