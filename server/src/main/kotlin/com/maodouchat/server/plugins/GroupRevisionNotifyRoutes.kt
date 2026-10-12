package com.maodouchat.server.plugins

import com.maodouchat.server.model.GroupRevisionChangedPayload
import com.maodouchat.server.model.WsMessage
import com.maodouchat.server.repository.ConversationParticipantRepository
import com.maodouchat.server.repository.ConversationQueryRepository
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 群变更通知簇：群成员/结构变更后的实时广播（从 Routing.kt 按域拆出）。 */
internal suspend fun notifyGroupRevisionChanged(
    queryRepository: ConversationQueryRepository,
    participantRepository: ConversationParticipantRepository,
    json: Json,
    chatId: String,
    reason: String,
    actorId: String,
    targetUserId: String? = null,
    recipientIds: List<String>? = null
) {
    val chat = queryRepository.getById(chatId) ?: return
    if (!chat.isGroup) return
    notifyGroupRevisionChangedWithData(
        json = json,
        chatId = chatId,
        reason = reason,
        actorId = actorId,
        targetUserId = targetUserId,
        memberRevision = chat.memberRevision,
        recipientIds = recipientIds ?: participantRepository.participantIds(chatId)
    )
}

/** 批量快照已就绪时的广播入口（避免逐群 getChatById + getParticipantIds）。 */
internal suspend fun notifyGroupRevisionChangedWithData(
    json: Json,
    chatId: String,
    reason: String,
    actorId: String,
    targetUserId: String? = null,
    memberRevision: Long,
    recipientIds: List<String>
) {
    val payload = GroupRevisionChangedPayload(
        chatId = chatId,
        memberRevision = memberRevision,
        reason = reason,
        actorId = actorId,
        targetUserId = targetUserId
    )
    val message = json.encodeToString(
        WsMessage.serializer(),
        WsMessage("GROUP_REVISION_CHANGED", json.encodeToString(GroupRevisionChangedPayload.serializer(), payload))
    )
    val recipients = recipientIds.distinct()
    recipients.forEach { LocalRealtimeBus.publish(it, message) }
}
