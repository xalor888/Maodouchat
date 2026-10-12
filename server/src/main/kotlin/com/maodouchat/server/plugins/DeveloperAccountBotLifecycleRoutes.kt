package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.repository.BotRepository
import com.maodouchat.server.repository.ConversationParticipantRepository
import com.maodouchat.server.repository.delete as deleteBot
import com.maodouchat.server.repository.groupChatIdsFor
import com.maodouchat.server.repository.setEnabled
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.put
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 开发者账号·Bot 管理：删除/启停。 */
internal fun Route.configureDeveloperAccountBotLifecycleRoutes(
    devParticipantRepo: ConversationParticipantRepository,
    devJson: Json,
    developerBotSettingsRateLimiter: BoundedRateLimiter,
) {
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
        val ok = BotRepository.deleteBot(botId, userId)
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
