package com.maodouchat.ui.screen.chatdetail

import android.content.Context
import com.maodouchat.R
import com.maodouchat.data.repository.LocalMessageStore
import com.maodouchat.network.TokenManager
import com.maodouchat.security.BackgroundSessionGate
import com.maodouchat.util.ChatExport
import com.maodouchat.util.JsonFormat
import com.maodouchat.util.RuntimeFlags
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 聊天导出控制器。从 ChatDetailViewModel 抽出；导出只读本地已解密明文快照，
 * 密聊一律拒绝导出（防明文泄漏），分页窗口用 partial 标记。
 */
class ChatExportController(
    private val messageRepo: LocalMessageStore,
    private val tokenManager: TokenManager,
    private val uiState: MutableStateFlow<ChatDetailUiState>,
    private val textProvider: (Int, Array<out Any>) -> String,
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private fun text(id: Int, vararg args: Any): String = textProvider(id, args)
    private val currentUserId: String get() = tokenManager.getUserId() ?: "me"
    private val token: String get() = tokenManager.getToken() ?: ""

    /** 导出本会话聊天记录（本地已解密消息 → 文本 → 系统分享）。上限 ChatExport.MAX_MESSAGES 条。 */
    fun exportChatHistory() {
        scope.launch {
            if (!RuntimeFlags.isEnabled(context, RuntimeFlags.CHAT_EXPORT)) {
                uiState.update { it.copy(infoMessage = text(R.string.chat_export_disabled)) }
                return@launch
            }
            val chat = uiState.value.chat ?: return@launch
            if (chat.isSecret || uiState.value.isSecretChat == true) {
                uiState.update { it.copy(infoMessage = text(R.string.secret_chat_export_blocked)) }
                return@launch
            }
            if (tokenManager.getToken().isNullOrBlank()) {
                uiState.update { it.copy(groupEncryptionWarning = text(R.string.error_session_expired)) }
                return@launch
            }
            // 用带 LIMIT 的 getRecentMessages 从源头限量，避免全量加载后再 takeLast 的 OOM
            val recent = try {
                messageRepo.getRecentMessages(chat.id, ChatExport.MAX_MESSAGES)
                    .asReversed()
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (_: Exception) {
                emptyList()
            }
            if (recent.isEmpty()) {
                uiState.update { it.copy(infoMessage = text(R.string.chat_export_empty)) }
                return@launch
            }
            val ownerId = tokenManager.getUserId() ?: ""
            val participants = chat.participants.associateBy { it.id }
            val chatName = if (chat.isGroup) {
                chat.groupName?.takeIf { it.isNotBlank() } ?: text(R.string.chat_group)
            } else {
                participants.values.firstOrNull { it.id != ownerId }?.name?.takeIf { it.isNotBlank() }
                    ?: text(R.string.chat_group)
            }
            val exportText = ChatExport.buildText(
                chatName = chatName,
                ownerId = ownerId,
                resolveSenderName = { id -> participants[id]?.name?.takeIf { it.isNotBlank() } ?: id },
                messages = recent
            )
            val file = withContext(kotlinx.coroutines.Dispatchers.IO) {
                ChatExport.write(context, chat.id, exportText)
            }
            if (file != null && ChatExport.share(context, file, text(R.string.chat_export_share_title))) {
                uiState.update { it.copy(infoMessage = text(R.string.chat_export_done)) }
            } else {
                uiState.update { it.copy(infoMessage = text(R.string.chat_export_failed)) }
            }
        }
    }

    /** 导出当前聊天的全部消息为 JSON 字符串（本地可读明文 content 快照，非服务端密文）。 */
    fun exportChatAsJson(): String {
        val exportOwnerUserId = currentUserId
        if (
            token.isBlank() ||
            exportOwnerUserId.isBlank() ||
            exportOwnerUserId == "me" ||
            !BackgroundSessionGate.mayContinue(
                expectedUserId = exportOwnerUserId,
                liveToken = tokenManager.getToken(),
                liveUserId = tokenManager.getUserId(),
            )
        ) {
            return "{}"
        }
        val state = uiState.value
        // Secret chats: refuse plaintext export snapshot (defense-in-depth for any caller).
        if (state.isSecretChat == true) {
            return "{}"
        }
        val chat = state.chat ?: return "{}"
        // 导出只含当前已加载的分页窗口（pageLimit=200）；显式标记 partial，避免误以为备份完整。
        val partial = state.hasMoreOlderMessages
        val data = mapOf(
            "chatId" to chat.id,
            "isGroup" to chat.isGroup,
            "groupName" to chat.groupName,
            "exportedAt" to System.currentTimeMillis(),
            "messageCount" to state.messages.size,
            "partial" to partial,
            "messages" to state.messages.map {
                mapOf(
                    "id" to it.id,
                    "sender" to it.senderId,
                    "type" to it.type.name,
                    "timestamp" to it.timestamp,
                    "status" to it.status.name,
                    "content" to it.content
                )
            }
        )
        return JsonFormat.encode(data)
    }

    fun clearExportInfo() {
        uiState.update { it.copy(exportInfoMessage = null) }
    }
}
