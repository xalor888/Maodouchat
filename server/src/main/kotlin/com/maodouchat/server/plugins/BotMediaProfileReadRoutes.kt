package com.maodouchat.server.plugins

import com.maodouchat.server.repository.*
import com.maodouchat.server.service.GroupMembershipService
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

/** Bot 自身资料：资料读（从 BotMediaProfileRoutes 按域拆出）。 */
internal fun Route.configureBotMediaProfileReadRoutes(
    userRepo: UserRepository,
    serviceMessageRepo: ServiceMessageRepository,
    groupMembershipService: GroupMembershipService,
    groupModerationRepo: GroupModerationRepository,
    conversationParticipantRepo: ConversationParticipantRepository,
    conversationQueryRepo: ConversationQueryRepository,
    botSendRateLimiter: BoundedRateLimiter,
    json: Json,
    messagingV2Repository: com.maodouchat.server.messaging.v2.MessagingV2Repository,
) {

get("/api/bot/getMyDescription") {
    val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@get
    com.maodouchat.server.repository.BotRepository.logCommand(bot.id, null, null, "getMyDescription")
    call.respond(
    buildJsonObject {
put("botId", bot.id)
put("description", (bot.description ?: ""))
put("name", bot.name)
put("username", bot.username)
    }
)
}

get("/api/bot/getMyName") {
    val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@get
    com.maodouchat.server.repository.BotRepository.logCommand(bot.id, null, null, "getMyName")
    call.respond(
    buildJsonObject {
put("botId", bot.id)
put("name", bot.name)
put("username", bot.username)
    }
)
}
}
