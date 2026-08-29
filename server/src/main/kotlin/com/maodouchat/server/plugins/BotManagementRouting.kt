package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.repository.ConversationParticipantRepository
import com.maodouchat.server.repository.UserRepository
import com.maodouchat.server.service.RuntimeConfigService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Bot 管理（用户侧）子域路由：列出/创建机器人、token 轮换、webhook、删除、启停。
 * 从 configureRouting 抽出，仅做鉴权/DTO/校验/调用 BotRepository，不再在总路由内联。
 */
internal fun Route.configureBotManagementRoutes(
    userRepo: UserRepository,
    conversationParticipantRepo: ConversationParticipantRepository,
    botCreateRateLimiter: BoundedRateLimiter,
    botTokenRateLimiter: BoundedRateLimiter,
    json: Json,
) {

get("/api/bots") {
        val userId = call.principal<JWTPrincipal>()!!.payload.subject
        call.respond(com.maodouchat.server.repository.BotRepository.listByOwner(userId))
    }
    post("/api/bots") {
        if (call.rejectIfMaintenance()) return@post
        if (!RuntimeConfigService.isBotsAllowed()) {
            // 8.32 一致性：功能禁用统一 403（与 nearby/posts/chat_folders 等 disabled 语义一致）
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot platform disabled"))
        }
        val userId = call.principal<JWTPrincipal>()!!.payload.subject
        if (call.rejectIfSuspended(userRepo, userId)) return@post
        // 创建限流：防 create-delete churn 刷 DB（maxBotsPerUser 语义可被绕过）
        if (!botCreateRateLimiter.acquire(userId, maxPerMinute = 5)) {
            return@post call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("创建机器人太频繁，请稍后再试"))
        }
        val body = call.receiveBoundedTextOrEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val name = obj["name"]?.jsonPrimitive?.content.orEmpty()
        val username = obj["username"]?.jsonPrimitive?.content.orEmpty()
        val description = obj["description"]?.jsonPrimitive?.content
        when (val result = com.maodouchat.server.repository.BotRepository.create(userId, name, username, description)) {
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
    post("/api/bots/{botId}/token") {
        if (call.rejectIfMaintenance()) return@post
        val userId = call.principal<JWTPrincipal>()!!.payload.subject
        if (call.rejectIfSuspended(userRepo, userId)) return@post
        // token 轮换限流：防高频轮换刷 DB 写
        if (!botTokenRateLimiter.acquire(userId, maxPerMinute = 10)) {
            return@post call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("操作太频繁，请稍后再试"))
        }
        val botId = call.parameters["botId"] ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing botId"))
        val bot = com.maodouchat.server.repository.BotRepository.regenerateToken(botId, userId)
            ?: return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权操作"))
        call.respond(bot)
    }
    put("/api/bots/{botId}/webhook") {
        if (call.rejectIfMaintenance()) return@put
        val userId = call.principal<JWTPrincipal>()!!.payload.subject
        if (call.rejectIfSuspended(userRepo, userId)) return@put
        val botId = call.parameters["botId"] ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing botId"))
        val body = call.receiveBoundedTextOrEmpty()
        val url = runCatching { Json.parseToJsonElement(body).jsonObject["url"]?.jsonPrimitive?.content }
            .getOrNull()?.trim()?.take(500)
        if (!url.isNullOrBlank() && !com.maodouchat.server.repository.BotRepository.isAllowedWebhookUrl(url)) {
            return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("webhook 无效"))
        }
        val bot = com.maodouchat.server.repository.BotRepository.setWebhook(botId, userId, url)
            ?: return@put call.respondBotUnavailable()
        call.respond(bot)
    }
    delete("/api/bots/{botId}") {
        if (call.rejectIfMaintenance()) return@delete
        val userId = call.principal<JWTPrincipal>()!!.payload.subject
        if (call.rejectIfSuspended(userRepo, userId)) return@delete
        val botId = call.parameters["botId"] ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing botId"))
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
    put("/api/bots/{botId}/enabled") {
        if (call.rejectIfMaintenance()) return@put
        val userId = call.principal<JWTPrincipal>()!!.payload.subject
        if (call.rejectIfSuspended(userRepo, userId)) return@put
        val botId = call.parameters["botId"] ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing botId"))
        val body = call.receiveBoundedTextOrEmpty()
        val enabled = runCatching {
            val p = Json.parseToJsonElement(body).jsonObject["enabled"]?.jsonPrimitive
            p?.booleanOrNull ?: p?.content?.toBooleanStrictOrNull()
        }.getOrNull()
            ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("enabled required"))
        val bot = com.maodouchat.server.repository.BotRepository.setEnabled(botId, userId, enabled)
            ?: return@put call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权操作"))
        call.respond(bot)
    }
}