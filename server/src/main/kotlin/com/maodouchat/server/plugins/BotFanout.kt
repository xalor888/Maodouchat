package com.maodouchat.server.plugins

import com.maodouchat.server.model.*
import com.maodouchat.server.repository.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.http.content.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// bot hint 空白折叠：每次调用都在重新编译，提到文件级复用。
private val whitespaceRunRegex = Regex("\\s+")

internal fun sanitizeBotHint(raw: String?): String = (raw ?: "")
    .map { if (it.isISOControl() || it == '\u007F') ' ' else it }
    .joinToString("")
    .replace(whitespaceRunRegex, " ")
    .trim()
    .take(120)

/** 9.136：改为 internal 供 SecretSurfaceRouting 的 8 个 hint 端点复用（9.131 仅覆盖 Routing.kt 内部家族）。 */
internal suspend fun fanoutBotMessage(
    userRepo: UserRepository,
    participantRepository: ConversationParticipantRepository,
    json: Json,
    botId: String,
    chatId: String,
    botMessage: MessageResponse,
    excludedRecipientIds: Set<String> = emptySet(),
    messagingV2Repository: com.maodouchat.server.messaging.v2.MessagingV2Repository,
) {
    val fanoutPids = participantRepository.participantIds(chatId)
    val botBlockedIds = try {
        userRepo.blockedEitherWayIdsInTx(botId, fanoutPids)
    } catch (_: Exception) {
        emptySet()
    }
    val result = messagingV2Repository.enqueueServiceMessage(
        message = botMessage,
        recipientUserIds = fanoutPids.filterNotTo(linkedSetOf()) {
            it in botBlockedIds || it in excludedRecipientIds
        },
    )
    val wakeup = json.encodeToString(WsMessage("INBOX_AVAILABLE_V2", "{}"))
    result.recipientUserIds.forEach { pid ->
        LocalRealtimeBus.publish(pid, wakeup)
    }
}

internal suspend fun fanoutBotEvent(
    userRepo: UserRepository,
    participantRepository: ConversationParticipantRepository,
    json: Json,
    botId: String,
    chatId: String,
    event: com.maodouchat.server.messaging.v2.ServiceMessagingV2Event,
    excludedRecipientIds: Set<String> = emptySet(),
    messagingV2Repository: com.maodouchat.server.messaging.v2.MessagingV2Repository,
) {
    val participantIds = participantRepository.participantIds(chatId)
    val blockedIds = try {
        userRepo.blockedEitherWayIdsInTx(botId, participantIds)
    } catch (_: Exception) {
        emptySet()
    }
    val now = System.currentTimeMillis()
    val result = messagingV2Repository.enqueueServiceEvent(
        id = "bot_event_" + UUID.randomUUID().toString().replace("-", ""),
        conversationId = chatId,
        senderUserId = botId,
        clientTimestamp = now,
        event = event,
        recipientUserIds = participantIds.filterNotTo(linkedSetOf()) {
            it in blockedIds || it in excludedRecipientIds
        },
    )
    val wakeup = json.encodeToString(WsMessage("INBOX_AVAILABLE_V2", "{}"))
    result.recipientUserIds.forEach { recipientId ->
        LocalRealtimeBus.publish(recipientId, wakeup)
    }
}

internal suspend fun fanoutSystemDelete(
    participantRepository: ConversationParticipantRepository,
    json: Json,
    chatId: String,
    messageId: String,
    messagingV2Repository: com.maodouchat.server.messaging.v2.MessagingV2Repository,
) {
    val participantIds = participantRepository.participantIds(chatId).toSet()
    val now = System.currentTimeMillis()
    val result = messagingV2Repository.enqueueServiceEvent(
        id = "system_event_" + UUID.randomUUID().toString().replace("-", ""),
        conversationId = chatId,
        senderUserId = "system",
        clientTimestamp = now,
        event = com.maodouchat.server.messaging.v2.ServiceMessagingV2Event(
            action = "DELETE",
            targetMessageId = messageId,
        ),
        recipientUserIds = participantIds,
    )
    val wakeup = json.encodeToString(WsMessage("INBOX_AVAILABLE_V2", "{}"))
    result.recipientUserIds.forEach { recipientId ->
        LocalRealtimeBus.publish(recipientId, wakeup)
    }
}
