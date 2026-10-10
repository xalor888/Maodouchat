package com.maodouchat.server.plugins

import com.maodouchat.server.repository.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*
/** Bot 状态/进度/提醒展示（自 BotPresentationRouting.kt 拆分，B12）。 */
internal fun Route.configureBotPresentationStatusRoutes(
    userRepository: UserRepository,
    participantRepository: ConversationParticipantRepository,
    serviceMessageRepository: ServiceMessageRepository,
    botRateLimiter: BoundedRateLimiter,
    json: Json,
) {
    configureBotPresentationStatusQueryRoutes(botRateLimiter = botRateLimiter)
    configureBotPresentationStatusPushRoutes(
        userRepository = userRepository,
        participantRepository = participantRepository,
        serviceMessageRepository = serviceMessageRepository,
        botRateLimiter = botRateLimiter,
        json = json,
    )
}
