package com.maodouchat.server.plugins

import com.maodouchat.server.model.WsMessage
import com.maodouchat.server.repository.ConversationParticipantRepository
import com.maodouchat.server.repository.UserRepository
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * B08：打字指示子域。成员校验 + 双向拉黑过滤后向目标成员 fanout，避免 typing 侧信道
 * 泄露给拉黑关系。
 */
internal object TypingService {
    suspend fun fanout(
        senderId: String,
        payload: TypingPayload,
        json: Json,
        userRepo: UserRepository,
        participantRepository: ConversationParticipantRepository,
    ) {
        if (!participantRepository.isParticipant(payload.chatId, senderId)) return
        val participants = participantRepository.participantIds(payload.chatId)
        val blockedIds = userRepo.blockedEitherWayIdsInTx(senderId, participants)
        val typing = json.encodeToString(
            WsMessage("USER_TYPING", json.encodeToString(TypingPayload(senderId, payload.chatId, payload.isTyping)))
        )
        participants.filter { it != senderId && it !in blockedIds }.forEach { LocalRealtimeBus.publish(it, typing) }
    }
}
