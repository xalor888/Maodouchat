package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.repository.ConversationQueryRepository
import com.maodouchat.server.repository.UserRepository
import com.maodouchat.server.repository.get
import com.maodouchat.server.repository.getById
import com.maodouchat.server.repository.isBotDeliverable
import com.maodouchat.server.service.ConversationCommandService
import com.maodouchat.server.service.RuntimeConfigService
import com.maodouchat.server.service.isBotsAllowed
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

/** DM 建联：用户与机器人创建私聊会话。 */
internal fun Route.configureBotInteractionDmRoutes(
    userRepo: UserRepository,
    conversationQueryRepo: ConversationQueryRepository,
    commandService: ConversationCommandService,
    createChatRateLimiter: BoundedRateLimiter,
) {

    post("/api/bots/{botId}/dm") {
        if (call.rejectIfMaintenance()) return@post
        val userId = call.requireUserId()
        if (call.rejectIfSuspended(userRepo, userId)) return@post
        if (!RuntimeConfigService.isBotsAllowed()) {
            call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot platform disabled"))
            return@post
        }
        val botId = call.requirePathParamOr400("botId", "缺少机器人 ID") ?: return@post
        val bot = com.maodouchat.server.repository.BotRepository.get(botId)
        if (bot == null || !bot.enabled) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("bot not found"))
            return@post
        }
        if (!com.maodouchat.server.repository.BotRepository.isBotDeliverable(botId)) {
            call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot unavailable"))
            return@post
        }
        if (!createChatRateLimiter.acquire(userId, maxPerMinute = 20)) {
            call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("创建会话过于频繁，请稍后再试"))
            return@post
        }
        val created = try {
            commandService.getOrCreateDirect(userId, botId)
        } catch (_: IllegalArgumentException) {
            call.respond(HttpStatusCode.Forbidden, ErrorResponse("无法与该机器人创建私聊"))
            return@post
        }
        val chat = conversationQueryRepo.getById(created.id, userId)
        if (chat == null) {
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("会话创建成功但读取失败，请刷新"))
            return@post
        }
        call.respond(HttpStatusCode.Created, chat)
    }
}
