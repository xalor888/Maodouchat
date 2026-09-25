package com.maodouchat.ui.screen.chatdetail

import android.content.Context
import com.maodouchat.R
import com.maodouchat.data.repository.LocalMessageStore
import com.maodouchat.security.BackgroundSessionGate
import com.maodouchat.util.ChatExport
import com.maodouchat.util.JsonFormat
import com.maodouchat.util.RuntimeFlags
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 聊天导出控制器。从 ChatDetailViewModel 抽出；导出只读本地已解密明文快照，
 * 密聊一律拒绝导出（防明文泄漏），建立版本协议（FORMAT_VERSION）、流式写入与任务取消支持。
 */
class ChatExportController(
    private val messageRepo: LocalMessageStore,
    private val uiState: MutableStateFlow<ChatDetailUiState>,
    private val textProvider: (Int, Array<out Any>) -> String,
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private fun text(id: Int, vararg args: Any): String = textProvider(id, args)
    // 会话态（是谁 / 有没有令牌）读会话层；导出本身不发请求，不需要凭据本身。
    private val currentUserId: String get() = com.maodouchat.session.CurrentSession.snapshot().userId ?: "me"
    private val token: String get() = com.maodouchat.session.CurrentSession.snapshot().token ?: ""

    private var exportJob: Job? = null

    /** 取消进行中的聊天记录导出任务。 */
    fun cancelExport() {
        exportJob?.cancel()
        exportJob = null
    }

    /** 导出本会话聊天记录（本地已解密消息 → 流式文本 → 系统分享）。上限 ChatExport.MAX_MESSAGES 条。 */
    fun exportChatHistory() {
        cancelExport()
        exportJob = scope.launch {
            if (!RuntimeFlags.isEnabled(context, RuntimeFlags.CHAT_EXPORT)) {
                uiState.update { it.copy(infoMessage = text(R.string.chat_export_disabled)) }
                return@launch
            }
            val chat = uiState.value.chat ?: return@launch
            val caps = (context.applicationContext as? com.maodouchat.MaodouchatApp)
                ?.secretConversationController
                ?.capabilities(chat.id)
                ?: com.maodouchat.domain.messaging.ConversationPrivacyCapabilities(
                    isSecretChat = chat.isSecret || uiState.value.isSecretChat == true,
                    isLocked = false
                )
            if (!com.maodouchat.domain.messaging.ConversationPrivacyPolicy.allows(caps, com.maodouchat.domain.messaging.PrivacyAction.EXPORT)) {
                uiState.update { it.copy(infoMessage = text(R.string.secret_chat_export_blocked)) }
                return@launch
            }
            if (com.maodouchat.session.CurrentSession.snapshot().token.isNullOrBlank()) {
                uiState.update { it.copy(groupEncryptionWarning = text(R.string.error_session_expired)) }
                return@launch
            }
            // 用带 LIMIT 的 getRecentMessages 从源头限量，避免全量加载后再 takeLast 的 OOM
            val recent = try {
                messageRepo.getRecentMessages(chat.id, ChatExport.MAX_MESSAGES)
                    .asReversed()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                emptyList()
            }
            if (recent.isEmpty()) {
                uiState.update { it.copy(infoMessage = text(R.string.chat_export_empty)) }
                return@launch
            }
            val ownerId = com.maodouchat.session.CurrentSession.snapshot().userId ?: ""
            val participants = chat.participants.associateBy { it.id }
            val chatName = if (chat.isGroup) {
                chat.groupName?.takeIf { it.isNotBlank() } ?: text(R.string.chat_group)
            } else {
                participants.values.firstOrNull { it.id != ownerId }?.name?.takeIf { it.isNotBlank() }
                    ?: text(R.string.chat_group)
            }
            val file = withContext(kotlinx.coroutines.Dispatchers.IO) {
                ChatExport.writeStream(
                    context = context,
                    fileName = chat.id,
                    chatName = chatName,
                    ownerId = ownerId,
                    messages = recent.asSequence(),
                    resolveSenderName = { id -> participants[id]?.name?.takeIf { it.isNotBlank() } ?: id },
                    isCancelled = { !isActive }
                )
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
            )
        ) {
            return "{}"
        }
        val state = uiState.value
        val chat = state.chat ?: return "{}"
        val caps = (context.applicationContext as? com.maodouchat.MaodouchatApp)
            ?.secretConversationController
            ?.capabilities(chat.id)
            ?: com.maodouchat.domain.messaging.ConversationPrivacyCapabilities(
                isSecretChat = chat.isSecret || state.isSecretChat == true,
                isLocked = false
            )
        if (!com.maodouchat.domain.messaging.ConversationPrivacyPolicy.allows(caps, com.maodouchat.domain.messaging.PrivacyAction.EXPORT)) {
            return "{}"
        }
        // 导出只含当前已加载的分页窗口（pageLimit=200）；显式标记 partial，避免误以为备份完整。
        val partial = state.hasMoreOlderMessages
        val data = mapOf(
            "formatVersion" to ChatExport.FORMAT_VERSION,
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
