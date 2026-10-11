package com.maodouchat.server.plugins

import com.maodouchat.server.repository.*
import io.ktor.server.routing.Route
import kotlinx.serialization.json.*

/** Bot 群资料管理（写/查询拆为独立簇，本函数只做委托）。 */
internal fun Route.configureBotChatAdminRoutes(
    groupProfileRepo: GroupProfileRepository,
    conversationParticipantRepo: ConversationParticipantRepository,
    conversationQueryRepo: ConversationQueryRepository,
    botSendRateLimiter: BoundedRateLimiter,
    json: Json,
) {
    configureBotChatAdminWriteRoutes(
        groupProfileRepo = groupProfileRepo,
        conversationParticipantRepo = conversationParticipantRepo,
        conversationQueryRepo = conversationQueryRepo,
        botSendRateLimiter = botSendRateLimiter,
        json = json,
    )
    configureBotChatAdminQueryRoutes(
        conversationParticipantRepo = conversationParticipantRepo,
        botSendRateLimiter = botSendRateLimiter,
    )
}
