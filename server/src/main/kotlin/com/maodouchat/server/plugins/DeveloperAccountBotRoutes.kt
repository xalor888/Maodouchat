package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.repository.BotRepository
import com.maodouchat.server.repository.ConversationParticipantRepository
import com.maodouchat.server.service.RuntimeConfigService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 开发者账号·Bot 管理：创建/换 token/webhook/启停/删除。 */
internal fun Route.configureDeveloperAccountBotRoutes(
    devParticipantRepo: ConversationParticipantRepository,
    devJson: Json,
    developerBotCreateRateLimiter: BoundedRateLimiter,
    developerBotTokenRateLimiter: BoundedRateLimiter,
    developerBotSettingsRateLimiter: BoundedRateLimiter,
) {

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

        put("/bots/{id}/webhook") {
            val userId = devSessionUserId(call)
                ?: return@put call.respond(HttpStatusCode.Unauthorized, ErrorResponse("开发者会话无效或已过期"))
            if (call.rejectIfDeveloperMaintenance()) return@put
            if (!developerBotSettingsRateLimiter.acquire(userId, maxPerMinute = 60)) {
                return@put call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("操作太频繁，请稍后再试"))
            }
            val botId = call.requirePathParamOr400("id", "missing botId") ?: return@put
            val body = call.receiveBoundedText().orEmpty()
            val url = parseDeveloperWebhookUrl(body)
            if (!url.isNullOrBlank() && !BotRepository.isAllowedWebhookUrl(url)) {
                return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("webhook 无效"))
            }
            // secret is accepted for forward-compat but not persisted (no repo column yet).
            val bot = BotRepository.setWebhook(botId, userId, url)
                ?: return@put call.respondDeveloperBotUnavailable()
            call.respond(bot)
        }

        delete("/bots/{id}") {
            val userId = devSessionUserId(call)
                ?: return@delete call.respond(HttpStatusCode.Unauthorized, ErrorResponse("开发者会话无效或已过期"))
            if (call.rejectIfDeveloperMaintenance()) return@delete
            if (!developerBotSettingsRateLimiter.acquire(userId, maxPerMinute = 60)) {
                return@delete call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("操作太频繁，请稍后再试"))
            }
            val botId = call.requirePathParamOr400("id", "missing botId") ?: return@delete
            // 与 REST 删除机器人一致：删除会 bump 群成员版本；此前开发者账号路径只删 DB，
            // 客户端成员列表会残留已删除 bot。
            val affectedGroupIds = BotRepository.groupChatIdsFor(botId)
            val ok = BotRepository.delete(botId, userId)
            if (!ok) return@delete call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权操作"))
            val groupSnapshots = devParticipantRepo.groupRevisionAndParticipantIds(affectedGroupIds)
            groupSnapshots.forEach { (chatId, snapshot) ->
                notifyGroupRevisionChangedWithData(
                    json = devJson,
                    chatId = chatId,
                    reason = "BOT_REMOVED",
                    actorId = userId,
                    targetUserId = botId,
                    memberRevision = snapshot.first,
                    recipientIds = snapshot.second
                )
            }
            call.respond(
            buildJsonObject {
    put("ok", true)
            }
        )
        }

        put("/bots/{id}/enabled") {
            val userId = devSessionUserId(call)
                ?: return@put call.respond(HttpStatusCode.Unauthorized, ErrorResponse("开发者会话无效或已过期"))
            if (call.rejectIfDeveloperMaintenance()) return@put
            if (!developerBotSettingsRateLimiter.acquire(userId, maxPerMinute = 60)) {
                return@put call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("操作太频繁，请稍后再试"))
            }
            val botId = call.requirePathParamOr400("id", "missing botId") ?: return@put
            val body = call.receiveBoundedText().orEmpty()
            val enabled = parseDeveloperBotEnabled(body)
                ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("enabled required"))
            val bot = BotRepository.setEnabled(botId, userId, enabled)
                ?: return@put call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权操作"))
            call.respond(bot)
        }
}
