package com.maodouchat.server.plugins

import com.maodouchat.server.repository.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*
/** Bot 卡片展示（自 BotPresentationRouting.kt 拆分，B12；提示与探针已另立文件）。 */
internal fun Route.configureBotPresentationCardsRoutes(
    userRepository: UserRepository,
    participantRepository: ConversationParticipantRepository,
    serviceMessageRepository: ServiceMessageRepository,
    botRateLimiter: BoundedRateLimiter,
    json: Json,
) {
    configureBotPresentationCardsSimpleRoutes(
        userRepository = userRepository,
        participantRepository = participantRepository,
        serviceMessageRepository = serviceMessageRepository,
        botRateLimiter = botRateLimiter,
        json = json,
    )
    configureBotPresentationCardsRichRoutes(
        userRepository = userRepository,
        participantRepository = participantRepository,
        serviceMessageRepository = serviceMessageRepository,
        botRateLimiter = botRateLimiter,
        json = json,
    )
}
