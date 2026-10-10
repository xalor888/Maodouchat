package com.maodouchat.server.plugins

import com.maodouchat.server.repository.*
import io.ktor.server.routing.Route
import kotlinx.serialization.json.Json

/** Bot webhook 信息与投票测验/骰子（自 BotCoreRouting.kt 拆分，B12）。 */
internal fun Route.configureBotPollQuizRoutes(
    userRepo: UserRepository,
    serviceMessageRepo: ServiceMessageRepository,
    conversationParticipantRepo: ConversationParticipantRepository,
    botSendRateLimiter: BoundedRateLimiter,
    json: Json,
    messagingV2Repository: com.maodouchat.server.messaging.v2.MessagingV2Repository,
) {

    configureBotPollQuizInfoRoutes(
        botSendRateLimiter = botSendRateLimiter,
    )

    configureBotPollQuizSendRoutes(
        userRepo = userRepo,
        serviceMessageRepo = serviceMessageRepo,
        conversationParticipantRepo = conversationParticipantRepo,
        botSendRateLimiter = botSendRateLimiter,
        json = json,
        messagingV2Repository = messagingV2Repository,
    )
}
