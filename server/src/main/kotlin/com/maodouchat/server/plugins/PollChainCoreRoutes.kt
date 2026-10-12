package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.repository.GroupCheckinRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/** 群接龙簇：接龙本体的详情查询/参与（从 PollChainRoutes 按域拆出）。 */
internal fun Route.configurePollChainCoreRoutes() {
    authenticate("auth-jwt") {
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
