package com.maodouchat.server.plugins

import com.maodouchat.server.model.MessageResponse
import com.maodouchat.server.model.WsMessage
import com.maodouchat.server.repository.ConversationParticipantRepository
import com.maodouchat.server.repository.ServiceMessageRepository
import com.maodouchat.server.repository.UserRepository
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Publishes durable bot plaintext and wakes recipients selected by route block policy. */
internal suspend fun publishBotServiceMessage(
    userRepository: UserRepository,
    participantRepository: ConversationParticipantRepository,
    serviceMessageRepository: ServiceMessageRepository,
    json: Json,
    botId: String,
    chatId: String,
    messageId: String,
    content: String,
    timestamp: Long,
    type: String,
    excludedRecipientIds: Set<String> = emptySet(),
): MessageResponse? {
    val participantIds = participantRepository.participantIds(chatId)
    val blockedIds = try {
        userRepository.blockedEitherWayIdsInTx(botId, participantIds)
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (_: Exception) {
        emptySet()
    }
    val recipients = participantIds.filterNotTo(linkedSetOf()) {
        it in blockedIds || it in excludedRecipientIds
    }
    val result = serviceMessageRepository.publish(
        id = messageId,
        chatId = chatId,
        botUserId = botId,
        content = content,
        timestamp = timestamp,
        type = type,
        recipientUserIds = recipients,
    )
    val published = result as? ServiceMessageRepository.PublishResult.Published ?: return null
    val wakeup = json.encodeToString(WsMessage("INBOX_AVAILABLE_V2", "{}"))
    published.mailbox.recipientUserIds.forEach { sendToUser(it, wakeup) }
    return published.message
}
