package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.repository.GroupCheckinRepository
import com.maodouchat.server.repository.PollRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/** 群玩法 B3 路由：群接龙。 */
internal fun Route.configurePollChainRoutes() {
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



        get("/api/chains/{chainId}") {
            val userId = call.requireUserId()
            val chainId = call.requirePathParamOr400("chainId", "missing chainId") ?: return@get
            val chain = GroupCheckinRepository.getChain(chainId, userId)
                ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("接龙不存在"))
            call.respond(chain)
        }



        post("/api/chains/{chainId}/entries") {
            val userId = call.requireUserId()
            if (call.rejectIfSuspendedForPolls(userId)) return@post
            val chainId = call.requirePathParamOr400("chainId", "missing chainId") ?: return@post
            // 8.52 一致性：接龙不存在或非成员统一 403（与 PK 投票口径一致），
            // 并在限流前拦截，避免非成员/禁言成员消耗群玩法配额。
            val chainForMute = GroupCheckinRepository.getChain(chainId, userId)
                ?: return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权访问该群"))
            if (call.rejectIfMutedForPolls(chainForMute.chatId, userId)) return@post
            if (!pollRateLimiter.acquire("$userId:$chainId:chain_join", maxPerMinute = 30)) {
                return@post call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("接龙过于频繁"))
            }
            val obj = call.requireJsonObjectOr400(call.receiveBoundedTextOrEmpty(MAX_BODY_CHARS)) ?: return@post
            val content = parseChainJoinContent(obj)
            if (content.isBlank() || content.length > MAX_CHAIN_CONTENT_LENGTH) {
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("接龙内容无效"))
            }
            val chain = GroupCheckinRepository.joinChain(chainId, userId, content)
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("接龙已结束或人数已满"))
            broadcastGroupPlayUpdate(chain.chatId, "chain_updated") { viewerId ->
                GroupCheckinRepository.getChain(chain.id, viewerId)
            }
            call.respond(chain)
        }
    }
}

private const val MAX_CHAIN_TITLE_LENGTH = 200
private const val MAX_CHAIN_TOPIC_LENGTH = 500
private const val MAX_CHAIN_CONTENT_LENGTH = 500
