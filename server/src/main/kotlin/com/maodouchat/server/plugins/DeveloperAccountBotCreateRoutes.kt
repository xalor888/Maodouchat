package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.repository.BotRepository
import com.maodouchat.server.repository.create
import com.maodouchat.server.repository.regenerateToken
import com.maodouchat.server.service.RuntimeConfigService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

/** 开发者账号·Bot 创建与换 token。 */
internal fun Route.configureDeveloperAccountBotCreateRoutes(
    developerBotCreateRateLimiter: BoundedRateLimiter,
    developerBotTokenRateLimiter: BoundedRateLimiter,
)
{
        post("/bots") {
            val userId = devSessionUserId(call)
                ?: return@post call.respond(HttpStatusCode.Unauthorized, ErrorResponse("开发者会话无效或已过期"))
            if (call.rejectIfDeveloperMaintenance()) return@post
            if (!RuntimeConfigService.isBotsAllowed()) {
                return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot platform disabled"))
            }
            if (!developerBotCreateRateLimiter.acquire(userId, maxPerMinute = 5)) {
                return@post call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("创建机器人太频繁，请稍后再试"))
            }
            val body = call.receiveBoundedText().orEmpty()
            val obj = call.requireJsonObjectOr400(body) ?: return@post
            val (name, username, description) = parseDeveloperBotCreateFields(obj)
            when (val result = BotRepository.create(userId, name, username, description)) {
                is BotRepository.BotCreateResult.Success -> call.respond(result.bot)
                BotRepository.BotCreateResult.UsernameTaken ->
                    call.respond(HttpStatusCode.Conflict, ErrorResponse("机器人用户名已被占用"))
                BotRepository.BotCreateResult.MaxBotsReached ->
                    call.respond(HttpStatusCode.Conflict, ErrorResponse("机器人数量已达上限"))
                BotRepository.BotCreateResult.InvalidInput ->
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("创建机器人失败（用户名非法）"))
                BotRepository.BotCreateResult.OwnerInvalid ->
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("开发者账号状态不可用"))
            }
        }

        post("/bots/{id}/token") {
            val userId = devSessionUserId(call)
                ?: return@post call.respond(HttpStatusCode.Unauthorized, ErrorResponse("开发者会话无效或已过期"))
            if (call.rejectIfDeveloperMaintenance()) return@post
            if (!developerBotTokenRateLimiter.acquire(userId, maxPerMinute = 10)) {
                return@post call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("操作太频繁，请稍后再试"))
            }
            val botId = call.requirePathParamOr400("id", "missing botId") ?: return@post
            val bot = BotRepository.regenerateToken(botId, userId)
                ?: return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权操作"))
            call.respond(bot)
        }
}
