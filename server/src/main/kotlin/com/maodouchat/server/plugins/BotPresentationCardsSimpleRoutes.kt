package com.maodouchat.server.plugins

import com.maodouchat.server.repository.*
import io.ktor.server.routing.Route
import kotlinx.serialization.json.*

/** Bot 轻卡片：echo 探针、toast、键值、通知（本轮按域拆为 2 簇，零行为改动）。 */
internal fun Route.configureBotPresentationCardsSimpleRoutes(
    userRepository: UserRepository,
    participantRepository: ConversationParticipantRepository,
    serviceMessageRepository: ServiceMessageRepository,
    botRateLimiter: BoundedRateLimiter,
    json: Json,
) {
    configureBotPresentationEchoRoutes(
        botRateLimiter = botRateLimiter,
    )

    configureBotPresentationSimpleSendRoutes(
        userRepository = userRepository,
        participantRepository = participantRepository,
        serviceMessageRepository = serviceMessageRepository,
        botRateLimiter = botRateLimiter,
        json = json,
    )
}
