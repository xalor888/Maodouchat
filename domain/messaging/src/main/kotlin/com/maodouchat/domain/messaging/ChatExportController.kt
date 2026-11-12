package com.maodouchat.domain.messaging

import com.maodouchat.core.model.ChatDetailUiState
import com.maodouchat.core.model.JsonFormat
import com.maodouchat.core.model.Message
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// 聊天导出控制器。从 ChatDetailViewModel 抽出；导出只读本地已解密明文快照，
// 密聊一律拒绝导出（防明文泄漏），建立版本协议、流式写入与任务取消支持。
// Context/CurrentSession/BackgroundSessionGate/ChatExport/R 文案走端口，其余逻辑逐字平移。
class ChatExportController(
    private val getRecentMessages: suspend (chatId: String, limit: Int) -> List<Message>,
    private val uiState: MutableStateFlow<ChatDetailUiState>,
    private val textProvider: (ChatExportText, Array<out Any>) -> String,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val exportEnabled: () -> Boolean,
    private val currentUserId: () -> String?,
    private val sessionToken: () -> String?,
    private val sessionMayContinue: (String) -> Boolean,
    private val secretChatCapabilities: (String) -> ConversationPrivacyCapabilities?,
    private val maxMessages: Int,
    private val formatVersion: Int,
    private val writeExportFile: (
        fileName: String,
        chatName: String,
        ownerId: String,
        messages: Sequence<Message>,
        resolveSenderName: (String) -> String,
        isCancelled: () -> Boolean,
    ) -> File?,
    private val shareExportFile: (File, String) -> Boolean,
) {
    private fun text(key: ChatExportText, vararg args: Any): String = textProvider(key, args)

    private var exportJob: Job? = null

    /** 取消进行中的聊天记录导出任务。 */
    fun cancelExport() {
        exportJob?.cancel()
        exportJob = null
    }

    /** 导出本会话聊天记录（本地已解密消息 → 流式文本 → 系统分享）。上限 maxMessages 条。 */
    fun exportChatHistory() {
        cancelExport()
        exportJob = scope.launch {
            if (!exportEnabled()) {
                uiState.update { it.copy(infoMessage = text(ChatExportText.EXPORT_DISABLED)) }
                return@launch
            }
            val chat = uiState.value.chat ?: return@launch
            val caps = secretChatCapabilities(chat.id)
                ?: ConversationPrivacyCapabilities(
                    isSecretChat = chat.isSecret || uiState.value.isSecretChat == true,
                    isLocked = false
                )
            if (!ConversationPrivacyPolicy.allows(caps, PrivacyAction.EXPORT)) {
                uiState.update { it.copy(infoMessage = text(ChatExportText.SECRET_CHAT_EXPORT_BLOCKED)) }
                return@launch
            }
            if (sessionToken().isNullOrBlank()) {
                uiState.update { it.copy(groupEncryptionWarning = text(ChatExportText.SESSION_EXPIRED)) }
                return@launch
            }
            // 用带 LIMIT 的 getRecentMessages 从源头限量，避免全量加载后再 takeLast 的 OOM
            val recent = try {
                getRecentMessages(chat.id, maxMessages)
                    .asReversed()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                emptyList()
            }
            if (recent.isEmpty()) {
                uiState.update { it.copy(infoMessage = text(ChatExportText.EXPORT_EMPTY)) }
                return@launch
            }
            val ownerId = currentUserId() ?: ""
            val participants = chat.participants.associateBy { it.id }
            val chatName = if (chat.isGroup) {
                chat.groupName?.takeIf { it.isNotBlank() } ?: text(ChatExportText.GROUP_NAME)
            } else {
                participants.values.firstOrNull { it.id != ownerId }?.name?.takeIf { it.isNotBlank() }
                    ?: text(ChatExportText.GROUP_NAME)
            }
            val file = withContext(ioDispatcher) {
                writeExportFile(
                    chat.id,
                    chatName,
                    ownerId,
                    recent.asSequence(),
                    { id -> participants[id]?.name?.takeIf { it.isNotBlank() } ?: id },
                    { !isActive }
                )
            }
            if (file != null && shareExportFile(file, text(ChatExportText.EXPORT_SHARE_TITLE))) {
                uiState.update { it.copy(infoMessage = text(ChatExportText.EXPORT_DONE)) }
            } else {
                uiState.update { it.copy(infoMessage = text(ChatExportText.EXPORT_FAILED)) }
            }
        }
    }

    /** 导出当前聊天的全部消息为 JSON 字符串（本地可读明文 content 快照，非服务端密文）。 */
    fun exportChatAsJson(): String {
        // 会话态（是谁 / 有没有令牌）读会话层；导出本身不发请求，不需要凭据本身。
        val exportOwnerUserId = currentUserId() ?: "me"
        val token = sessionToken() ?: ""
        if (
            token.isBlank() ||
            exportOwnerUserId.isBlank() ||
            exportOwnerUserId == "me" ||
            !sessionMayContinue(exportOwnerUserId)
        ) {
            return "{}"
        }
        val state = uiState.value
        val chat = state.chat ?: return "{}"
        val caps = secretChatCapabilities(chat.id)
            ?: ConversationPrivacyCapabilities(
                isSecretChat = chat.isSecret || state.isSecretChat == true,
                isLocked = false
            )
        if (!ConversationPrivacyPolicy.allows(caps, PrivacyAction.EXPORT)) {
            return "{}"
        }
        // 导出只含当前已加载的分页窗口（pageLimit=200）；显式标记 partial，避免误以为备份完整。
        val partial = state.hasMoreOlderMessages
        val data = mapOf(
            "formatVersion" to formatVersion,
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
