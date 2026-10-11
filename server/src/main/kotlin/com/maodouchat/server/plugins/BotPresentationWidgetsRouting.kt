package com.maodouchat.server.plugins

import com.maodouchat.server.repository.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

/** Bot 展示组件（自 BotPresentationRouting.kt 拆分，B12）。 */
internal fun Route.configureBotPresentationWidgetsRoutes(
    userRepository: UserRepository,
    participantRepository: ConversationParticipantRepository,
    serviceMessageRepository: ServiceMessageRepository,
    botRateLimiter: BoundedRateLimiter,
    json: Json,
) {
    configureBotPresentationWidgetsProbeRoutes(
        participantRepository = participantRepository,
        botRateLimiter = botRateLimiter,
    )
    configureBotPresentationWidgetsStatusRoutes(
        userRepository = userRepository,
        participantRepository = participantRepository,
        serviceMessageRepository = serviceMessageRepository,
        botRateLimiter = botRateLimiter,
        json = json,
    )
    configureBotPresentationWidgetsRichRoutes(
        userRepository = userRepository,
        participantRepository = participantRepository,
        serviceMessageRepository = serviceMessageRepository,
        botRateLimiter = botRateLimiter,
        json = json,
    )
}
