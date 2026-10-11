package com.maodouchat.server.plugins

import com.maodouchat.server.repository.*
import io.ktor.server.routing.Route
import kotlinx.serialization.json.*

/** Bot 安全提示路由：按域拆为邀请、内容安全、私密保护 3 簇（零行为改动）。 */
internal fun Route.configureBotPresentationHintSafetyRoutes(
    userRepository: UserRepository,
    participantRepository: ConversationParticipantRepository,
    serviceMessageRepository: ServiceMessageRepository,
    botRateLimiter: BoundedRateLimiter,
    json: Json,
) {
    configureBotPresentationHintSafetyInviteRoutes(
        userRepository = userRepository,
        participantRepository = participantRepository,
        serviceMessageRepository = serviceMessageRepository,
        botRateLimiter = botRateLimiter,
        json = json,
    )

    configureBotPresentationHintSafetyContentRoutes(
        userRepository = userRepository,
        participantRepository = participantRepository,
        serviceMessageRepository = serviceMessageRepository,
        botRateLimiter = botRateLimiter,
        json = json,
    )

    configureBotPresentationHintSafetySecretRoutes(
        userRepository = userRepository,
        participantRepository = participantRepository,
        serviceMessageRepository = serviceMessageRepository,
        botRateLimiter = botRateLimiter,
        json = json,
    )
}
