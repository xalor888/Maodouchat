package com.maodouchat.ui.screen.chatdetail

import android.util.Log
import com.maodouchat.R
import com.maodouchat.chatdetail.ChatDetailAccess
import com.maodouchat.data.model.Chat
import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageType
import com.maodouchat.data.model.User
import com.maodouchat.data.repository.ChatListPreviewPolicy

// 消息展示与搜索索引：列表预览文案、解密后回写预览、本地全文索引
// 从 ChatDetailViewModel 纯搬移，日志 tag 保持 ChatDetailViewModel 不变。
internal class ChatMessagePreviewController(
    private val chatProvider: () -> Chat?,
    private val contactProvider: () -> User,
    private val currentUserId: () -> String,
    private val activeChatId: () -> String,
    private val textProvider: (Int, Array<out Any>) -> String,
) {
    private fun text(id: Int, vararg args: Any): String = textProvider(id, args)

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
            templates = NudgeDisplayPolicy.Templates(
                youNudged = { target -> text(R.string.chat_nudge_you_nudged, target) },
                theyNudgedYou = { sender -> text(R.string.chat_nudge_they_nudged_you, sender) },
                theyNudgedTarget = { sender, target ->
                    text(R.string.chat_nudge_they_nudged_target, sender, target)
                }
            )
        )
    }

    private fun listPreviewTextForMessage(message: Message): String {
        if (message.type == MessageType.TEXT || message.type == MessageType.MARKDOWN) {
            val body = message.parsedContent()
            return if (body.isBlank() || ChatListPreviewPolicy.looksLikeWireEnvelope(body)) {
                text(R.string.message_preview_encrypted)
            } else {
                body.take(200)
            }
        }
        val preview = ChatListPreviewPolicy.fromLatestMessage(
            latest = message,
            mediaLabel = { type ->
                when (type) {
                    MessageType.IMAGE -> text(R.string.message_preview_image)
                    MessageType.GIF -> text(R.string.message_preview_gif)
                    MessageType.STICKER -> text(R.string.message_preview_sticker)
                    MessageType.LOCATION -> text(R.string.message_preview_location)
                    MessageType.VOICE -> text(R.string.message_preview_voice)
                    MessageType.VIDEO -> text(R.string.message_preview_video)
                    MessageType.FILE -> text(R.string.message_preview_file)
                    else -> text(R.string.message_preview_encrypted)
                }
            },
            encryptedPlaceholder = text(R.string.message_preview_encrypted),
            revokedPlaceholder = text(R.string.chat_message_revoked_placeholder),
            nudgeText = { detailNudgePreview(it) }
        )
        return preview.text.ifBlank { text(R.string.message_preview_encrypted) }
    }

    internal fun emitListPreviewForDecrypted(message: Message) {
        if (message.type == MessageType.SK_DIST) return
        val chatId = message.chatId.ifBlank { activeChatId() }
        if (chatId.isBlank()) return
        ChatDetailAccess.emitMessageSent(
            chatId,
            listPreviewTextForMessage(message),
            message.type.name,
            playSendSound = false,
        )
    }

    internal suspend fun indexSearchableMessage(message: Message) {
        // 密聊消息不落搜索索引（与 ImageOcrAutoIndexer 一致）：即使本地 SQLCipher 已加密，
        // 密聊明文不应进入可搜索缓存，避免密聊内容在全局搜索中可被检索。
        val caps = com.maodouchat.security.SecretChatCapabilities.forChat(message.chatId)
        if (!com.maodouchat.domain.messaging.ConversationPrivacyPolicy.allows(caps, com.maodouchat.domain.messaging.PrivacyAction.SEARCH)) {
            return
        }
        try {
            com.maodouchat.chatdetail.ChatDetailDataAccess.messageSearchRepository()
                .indexMessage(message)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w("ChatDetailViewModel", "indexSearchableMessage failed", error)
        }
    }
}
