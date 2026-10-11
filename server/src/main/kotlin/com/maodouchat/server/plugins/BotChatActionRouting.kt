package com.maodouchat.server.plugins

import com.maodouchat.server.model.*
import com.maodouchat.server.repository.*
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/** Bot 聊天动作与置顶（sendChatAction / pinChatMessage / getChatPins）。 */
internal fun Route.configureBotChatActionRoutes(
    userRepo: UserRepository,
    pinnedMessageRepo: PinnedMessageRepository,
    conversationParticipantRepo: ConversationParticipantRepository,
    conversationQueryRepo: ConversationQueryRepository,
    botSendRateLimiter: BoundedRateLimiter,
    json: Json,
) {
    configureBotChatActionWriteRoutes(userRepo, pinnedMessageRepo, conversationParticipantRepo, conversationQueryRepo, botSendRateLimiter, json)
    configureBotChatActionQueryRoutes(conversationParticipantRepo, pinnedMessageRepo, botSendRateLimiter)
}
