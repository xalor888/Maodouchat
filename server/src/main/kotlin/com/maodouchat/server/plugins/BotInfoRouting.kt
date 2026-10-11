package com.maodouchat.server.plugins

import com.maodouchat.server.repository.ConversationParticipantRepository
import io.ktor.server.routing.Route

/** Bot API 身份与信息探针（getMe / webhookInfo / chats）。 */
internal fun Route.configureBotInfoRoutes(
    botSendRateLimiter: BoundedRateLimiter,
    conversationParticipantRepo: ConversationParticipantRepository,
) {
    configureBotInfoWebhookRoutes(botSendRateLimiter)
    configureBotInfoProfileRoutes(botSendRateLimiter)
    configureBotInfoChatsRoutes(botSendRateLimiter, conversationParticipantRepo)
}
