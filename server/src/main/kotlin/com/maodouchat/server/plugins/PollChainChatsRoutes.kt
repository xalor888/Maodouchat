package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.repository.GroupCheckinRepository
import com.maodouchat.server.repository.PollRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/** 群接龙簇：群聊上下文的接龙创建/列表（从 PollChainRoutes 按域拆出）。 */
internal fun Route.configurePollChainChatsRoutes() {
    authenticate("auth-jwt") {
        // ── 群接龙 ─────────────────────────────────────
        post("/api/chats/{chatId}/chains") {
            val userId = call.requireUserId()
            if (call.rejectIfSuspendedForPolls(userId)) return@post
            val chatId = call.requirePathParamOr400("chatId", "missing chatId") ?: return@post
            // 9.136：成员校验先于限流（与 checkin/pk 的 8.47 口径一致）——
            // 此前接龙创建限流先行，非成员可反复探测耗尽自己对该 chat 的配额
            if (!PollRepository.isMember(chatId, userId)) {
                return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权访问该群"))
            }
            if (call.rejectIfMutedForPolls(chatId, userId)) return@post
            if (!pollRateLimiter.acquire("$userId:$chatId:chain_create", maxPerMinute = 10)) {
                return@post call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("创建接龙过于频繁"))
            }
            val obj = call.requireJsonObjectOr400(call.receiveBoundedTextOrEmpty(MAX_BODY_CHARS)) ?: return@post
            val fields = parseChainCreateFields(obj)
            if (fields.title.isBlank() || fields.title.length > MAX_CHAIN_TITLE_LENGTH) {
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("接龙标题无效"))
            }
            if (fields.topic.length > MAX_CHAIN_TOPIC_LENGTH) {
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("接龙主题过长"))
            }
            val chain = GroupCheckinRepository.createChain(chatId, userId, fields.title, fields.topic, fields.maxEntries)
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("无法创建接龙"))
            broadcastGroupPlayUpdate(chatId, "chain_created") { viewerId ->
                GroupCheckinRepository.getChain(chain.id, viewerId)
            }
            call.respond(chain)
        }
        get("/api/chats/{chatId}/chains") {
            val userId = call.requireUserId()
            val chatId = call.requirePathParamOr400("chatId", "missing chatId") ?: return@get
            if (!PollRepository.isMember(chatId, userId)) {
                return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权访问该群"))
            }
            val limit = parseGroupPlayLimit(call.request.queryParameters)
            call.respond(GroupCheckinRepository.listChains(chatId, userId, limit))
        }
    }
}
