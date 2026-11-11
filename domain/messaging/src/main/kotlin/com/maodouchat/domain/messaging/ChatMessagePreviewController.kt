package com.maodouchat.domain.messaging

import com.maodouchat.core.model.Chat
import com.maodouchat.core.model.ChatListPreviewPolicy
import com.maodouchat.core.model.Message
import com.maodouchat.core.model.MessageType
import com.maodouchat.core.model.NudgeDisplayPolicy
import com.maodouchat.core.model.Templates
import com.maodouchat.core.model.User
import kotlinx.coroutines.CancellationException

// 消息展示与搜索索引：列表预览文案、解密后回写预览、本地全文索引。
// 从 ChatDetailViewModel 纯搬移，日志 tag 保持 ChatDetailViewModel 不变。
class ChatMessagePreviewController(
    private val chatProvider: () -> Chat?,
    private val contactProvider: () -> User,
    private val currentUserId: () -> String,
    private val activeChatId: () -> String,
    private val textProvider: (ChatMessagePreviewText, Array<out Any>) -> String,
    private val emitListPreview: (chatId: String, preview: String, typeName: String, playSendSound: Boolean) -> Unit,
    private val allowsSearchIndex: (String) -> Boolean,
    private val indexMessage: suspend (Message) -> Unit,
    private val logWarning: (String, Throwable?) -> Unit,
) {
    private fun text(kind: ChatMessagePreviewText, vararg args: Any): String = textProvider(kind, args)

    private fun detailNudgePreview(message: Message): String {
        val chat = chatProvider()
        val senderName = chat?.participants?.firstOrNull { it.id == message.senderId }?.name
            ?.takeIf { it.isNotBlank() }
            ?: contactProvider().name.takeIf { it.isNotBlank() }
            ?: message.senderId
        return NudgeDisplayPolicy.displayText(
            isOwnMessage = message.senderId == currentUserId(),
            storedContent = message.content,
            senderDisplayName = senderName,
            isDirectChat = chat?.isGroup != true,
            templates = Templates(
                youNudged = { target -> text(ChatMessagePreviewText.NUDGE_YOU_NUDGED, target) },
                theyNudgedYou = { sender -> text(ChatMessagePreviewText.NUDGE_THEY_NUDGED_YOU, sender) },
                theyNudgedTarget = { sender, target ->
                    text(ChatMessagePreviewText.NUDGE_THEY_NUDGED_TARGET, sender, target)
                }
            )
        )
    }

    private fun listPreviewTextForMessage(message: Message): String {
        if (message.type == MessageType.TEXT || message.type == MessageType.MARKDOWN) {
            val body = message.parsedContent()
            return if (body.isBlank() || ChatListPreviewPolicy.looksLikeWireEnvelope(body)) {
                text(ChatMessagePreviewText.PREVIEW_ENCRYPTED)
            } else {
                body.take(200)
            }
        }
        val preview = ChatListPreviewPolicy.fromLatestMessage(
            latest = message,
            mediaLabel = { type ->
                when (type) {
                    MessageType.IMAGE -> text(ChatMessagePreviewText.PREVIEW_IMAGE)
                    MessageType.GIF -> text(ChatMessagePreviewText.PREVIEW_GIF)
                    MessageType.STICKER -> text(ChatMessagePreviewText.PREVIEW_STICKER)
                    MessageType.LOCATION -> text(ChatMessagePreviewText.PREVIEW_LOCATION)
                    MessageType.VOICE -> text(ChatMessagePreviewText.PREVIEW_VOICE)
                    MessageType.VIDEO -> text(ChatMessagePreviewText.PREVIEW_VIDEO)
                    MessageType.FILE -> text(ChatMessagePreviewText.PREVIEW_FILE)
                    else -> text(ChatMessagePreviewText.PREVIEW_ENCRYPTED)
                }
            },
            encryptedPlaceholder = text(ChatMessagePreviewText.PREVIEW_ENCRYPTED),
            revokedPlaceholder = text(ChatMessagePreviewText.MESSAGE_REVOKED_PLACEHOLDER),
            nudgeText = { detailNudgePreview(it) }
        )
        return preview.text.ifBlank { text(ChatMessagePreviewText.PREVIEW_ENCRYPTED) }
    }

    fun emitListPreviewForDecrypted(message: Message) {
        if (message.type == MessageType.SK_DIST) return
        val chatId = message.chatId.ifBlank { activeChatId() }
        if (chatId.isBlank()) return
        emitListPreview(
            chatId,
            listPreviewTextForMessage(message),
            message.type.name,
            false,
        )
    }

    suspend fun indexSearchableMessage(message: Message) {
        // 密聊消息不落搜索索引（与 ImageOcrAutoIndexer 一致）：即使本地 SQLCipher 已加密，
        // 密聊明文不应进入可搜索缓存，避免密聊内容在全局搜索中可被检索。
        if (!allowsSearchIndex(message.chatId)) {
            return
        }
        try {
            indexMessage(message)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logWarning("indexSearchableMessage failed", error)
        }
    }
}
