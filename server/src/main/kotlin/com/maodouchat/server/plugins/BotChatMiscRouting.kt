package com.maodouchat.server.plugins

import com.maodouchat.server.db.*
import com.maodouchat.server.model.*
import com.maodouchat.server.repository.*
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

/** Bot 会话 ID/命令/星标/清单（自 BotCoreRouting.kt 拆分，B12）。 */
internal fun Route.configureBotChatMiscRoutes(
    userRepo: UserRepository,
    serviceMessageRepo: ServiceMessageRepository,
    starMessageRepo: StarMessageRepository,
    conversationParticipantRepo: ConversationParticipantRepository,
    botSendRateLimiter: BoundedRateLimiter,
    json: Json,
    messagingV2Repository: com.maodouchat.server.messaging.v2.MessagingV2Repository,
) {
    configureBotChatMiscQueryRoutes(conversationParticipantRepo, botSendRateLimiter)
    configureBotChatMiscWriteRoutes(userRepo, serviceMessageRepo, starMessageRepo, conversationParticipantRepo, botSendRateLimiter, json, messagingV2Repository)
}
