package com.maodouchat.crypto

import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageType
import com.maodouchat.data.repository.ChatListPreviewPolicy
import com.maodouchat.data.repository.ChatRepository
import com.maodouchat.data.repository.MessageRepository

/**
 * 未打开的群：先 ingest SK_DIST（含 1:1 包装），再重试尾部文本解密。
 * ChatList 实时 WS 与 BacklogSync 共用，避免离线补拉后列表永远是密文占位。
 */
object InactiveChatSenderKeyIngest {

    suspend fun ingest(
        signal: SignalProtocol,
        chatRepo: ChatRepository,
        messageRepo: MessageRepository,
        message: Message,
        token: String,
    ) {
        val content = message.content
        if (content.isBlank() || token.isBlank()) return
        if (SessionCipherOccupancy.shouldSkipSessionCipher(message.chatId, message.senderId)) return
        val epoch = chatRepo.getChatById(message.chatId)
            ?.takeIf { it.isGroup && it.memberRevision > 0L }
            ?.memberRevision
        val distPlaintext = when {
            signal.isSenderKeyDistributionEnvelope(content) -> content
            signal.isEncryptedEnvelope(content) -> {
                if (SessionCipherOccupancy.shouldSkipSessionCipher(message.chatId, message.senderId)) return
                when (val distResult = signal.decryptContentEnvelope(message.senderId, content)) {
                    is SignalProtocol.DecryptResult.Success -> distResult.plaintext
                    SignalProtocol.DecryptResult.NoSession -> {
                        val repaired = signal.ensureSessions(token, message.senderId).isSuccess
                        if (!repaired) {
                            null
                        } else {
                            signal.clearDecryptRetryStateForSender(message.senderId)
                            when (val retry = signal.decryptContentEnvelope(message.senderId, content)) {
                                is SignalProtocol.DecryptResult.Success -> retry.plaintext
                                else -> null
                            }
                        }
                    }
                    else -> null
                }
            }
            else -> null
        } ?: return
        val outcome = signal.processSenderKeyDistributionEnvelope(
            message.senderId,
            distPlaintext,
            expectedGroupId = message.chatId,
            currentEpoch = epoch
        )
        if (outcome != SenderKeyDistOutcome.Failed) {
            messageRepo.insertMessage(message.copy(content = distPlaintext, type = MessageType.SK_DIST))
        }
    }

    suspend fun retryDecryptTail(
        signal: SignalProtocol,
        messageRepo: MessageRepository,
        chatId: String,
        isGroup: Boolean,
        onRecovered: suspend () -> Unit,
    ) {
        if (chatId.isBlank() || SessionCipherOccupancy.isChatOccupied(chatId)) return
        val recent = messageRepo.getRecentMessages(chatId, limit = 24)
        var recoveredAny = false
        for (msg in recent) {
            if (msg.type !in setOf(MessageType.TEXT, MessageType.MARKDOWN, MessageType.STICKER, MessageType.LOCATION)) {
                continue
            }
            if (!ChatListPreviewPolicy.isUnreadableListHead(msg)) continue
            if (SessionCipherOccupancy.isChatOccupied(chatId)) return
            val plain = decryptInline(signal, msg, isGroup) ?: continue
            messageRepo.insertMessage(msg.copy(content = plain))
            recoveredAny = true
        }
        if (recoveredAny) onRecovered()
    }

    private fun decryptInline(signal: SignalProtocol, message: Message, isGroup: Boolean): String? {
        val content = message.content
        if (content.isBlank() || SessionCipherOccupancy.shouldSkipSessionCipher(message.chatId, message.senderId)) return null
        if (!signal.isEncryptedEnvelope(content) &&
            !signal.isSenderKeyEnvelope(content) &&
            !ChatListPreviewPolicy.looksLikeWireEnvelope(content)
        ) {
            return content
        }
        return try {
            val result = if (isGroup || signal.isSenderKeyEnvelope(content)) {
                signal.decryptGroupContentEnvelope(
                    message.senderId,
                    content,
                    expectedGroupId = message.chatId
                )
            } else {
                signal.decryptContentEnvelope(message.senderId, content)
            }
            when (result) {
                is SignalProtocol.DecryptResult.Success -> result.plaintext.takeIf { it.isNotBlank() }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }
}
