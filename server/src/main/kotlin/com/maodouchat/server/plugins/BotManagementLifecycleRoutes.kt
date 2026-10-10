package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.repository.ConversationParticipantRepository
import com.maodouchat.server.repository.UserRepository
import com.maodouchat.server.service.RuntimeConfigService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun Route.configureBotManagementLifecycleRoutes(
    userRepo: UserRepository,
    conversationParticipantRepo: ConversationParticipantRepository,
    botCreateRateLimiter: BoundedRateLimiter,
    json: Json,
) {
get("/api/bots") {
        val userId = call.requireUserId()
        call.respond(com.maodouchat.server.repository.BotRepository.listByOwner(userId))
    }
    post("/api/bots") {
        if (call.rejectIfMaintenance()) return@post
        if (!RuntimeConfigService.isBotsAllowed()) {
            // 8.32 一致性：功能禁用统一 403（与 nearby/posts/chat_folders 等 disabled 语义一致）
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot platform disabled"))
        }
        val userId = call.requireUserId()
        if (call.rejectIfSuspended(userRepo, userId)) return@post
        // 创建限流：防 create-delete churn 刷 DB（maxBotsPerUser 语义可被绕过）
        if (!botCreateRateLimiter.acquire(userId, maxPerMinute = 5)) {
            return@post call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("创建机器人太频繁，请稍后再试"))
        }
        val body = call.receiveBoundedTextOrEmpty()
        val obj = call.requireJsonObjectOr400(body) ?: return@post
        val fields = parseBotCreateFields(obj)
        when (val result = com.maodouchat.server.repository.BotRepository.create(userId, fields.name, fields.username, fields.description)) {
            is com.maodouchat.server.repository.BotRepository.BotCreateResult.Success ->
                call.respond(result.bot)
            com.maodouchat.server.repository.BotRepository.BotCreateResult.UsernameTaken ->
                call.respond(HttpStatusCode.Conflict, ErrorResponse("机器人用户名已被占用"))
            com.maodouchat.server.repository.BotRepository.BotCreateResult.MaxBotsReached ->
                call.respond(HttpStatusCode.Conflict, ErrorResponse("机器人数量已达上限"))
            com.maodouchat.server.repository.BotRepository.BotCreateResult.InvalidInput ->
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("创建机器人失败（用户名非法）"))
            com.maodouchat.server.repository.BotRepository.BotCreateResult.OwnerInvalid ->
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("账号状态不可用"))
        }
    }
    delete("/api/bots/{botId}") {
        if (call.rejectIfMaintenance()) return@delete
        val userId = call.requireUserId()
        if (call.rejectIfSuspended(userRepo, userId)) return@delete
        val botId = call.requirePathParamOr400("botId", "missing botId") ?: return@delete
        // 8.33 修复：删除 bot 会 bump memberRevision，但此前无广播，客户端成员列表残留
        val affectedGroupIds = com.maodouchat.server.repository.BotRepository.groupChatIdsFor(botId)
        val ok = com.maodouchat.server.repository.BotRepository.delete(botId, userId)
        if (!ok) return@delete call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权操作"))
        val groupSnapshots = conversationParticipantRepo.groupRevisionAndParticipantIds(affectedGroupIds)
        groupSnapshots.forEach { (chatId, snapshot) ->
            notifyGroupRevisionChangedWithData(
                json = json,
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
}
