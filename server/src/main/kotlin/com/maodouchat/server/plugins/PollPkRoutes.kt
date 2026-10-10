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

/** 群玩法 B3 路由：群 PK。 */
internal fun Route.configurePollPkRoutes() {
    authenticate("auth-jwt") {

        // ── 群 PK ─────────────────────────────────────
        post("/api/chats/{chatId}/pk") {
            val userId = call.requireUserId()
            if (call.rejectIfSuspendedForPolls(userId)) return@post
            val chatId = call.requirePathParamOr400("chatId", "missing chatId") ?: return@post
            // 8.47：成员校验先于限流（同 checkin 口径）
            if (!PollRepository.isMember(chatId, userId)) {
                return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权访问该群"))
            }
            if (call.rejectIfMutedForPolls(chatId, userId)) return@post
            if (!pollRateLimiter.acquire("$userId:$chatId:pk_create", maxPerMinute = 10)) {
                return@post call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("创建 PK 过于频繁"))
            }
            val obj = call.requireJsonObjectOr400(call.receiveBoundedTextOrEmpty(MAX_BODY_CHARS)) ?: return@post
            val fields = parsePkCreateFields(obj)
            if (fields.leftTitle.isBlank() || fields.rightTitle.isBlank() ||
                fields.leftTitle.length > MAX_PK_TITLE_LENGTH || fields.rightTitle.length > MAX_PK_TITLE_LENGTH
            ) {
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("PK 双方标题无效"))
            }
            // 8.32 一致性：非成员 403（与群管理端点一致），其余失败保持 400
            val pk = GroupCheckinRepository.createPk(chatId, userId, fields.leftTitle, fields.rightTitle)
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("无法创建 PK"))
            broadcastGroupPlayUpdate(chatId, "pk_created") { viewerId ->
                GroupCheckinRepository.getPk(pk.id, viewerId)
            }
            call.respond(pk)
        }



        get("/api/chats/{chatId}/pk") {
            val userId = call.requireUserId()
            val chatId = call.requirePathParamOr400("chatId", "missing chatId") ?: return@get
            if (!PollRepository.isMember(chatId, userId)) {
                return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权访问该群"))
            }
            val limit = parseGroupPlayLimit(call.request.queryParameters)
            call.respond(GroupCheckinRepository.listChatPks(chatId, userId, limit))
        }



        get("/api/pk/{pkId}") {
            val userId = call.requireUserId()
            val pkId = call.requirePathParamOr400("pkId", "missing pkId") ?: return@get
            val pk = GroupCheckinRepository.getPk(pkId, userId)
                ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("PK 不存在"))
            call.respond(pk)
        }



        post("/api/pk/{pkId}/vote") {
            val userId = call.requireUserId()
            if (call.rejectIfSuspendedForPolls(userId)) return@post
            val pkId = call.requirePathParamOr400("pkId", "missing pkId") ?: return@post
            // 8.47：成员校验先于限流（同 checkin 口径）；非成员不得消耗投票配额
            val pkChatId = GroupCheckinRepository.getPk(pkId, userId)?.chatId
            if (pkChatId == null || !PollRepository.isMember(pkChatId, userId)) {
                return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权访问该群"))
            }
            if (call.rejectIfMutedForPolls(pkChatId, userId)) return@post
            if (!pollRateLimiter.acquire("$userId:$pkId:pk_vote", maxPerMinute = 30)) {
                return@post call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("投票过于频繁"))
            }
            val obj = call.requireJsonObjectOr400(call.receiveBoundedTextOrEmpty(MAX_BODY_CHARS)) ?: return@post
            val choice = parsePkVoteChoice(obj)
            // 8.32 一致性：非成员 403（与群管理端点一致），其余失败保持 400
            val pk = GroupCheckinRepository.votePk(pkId, userId, choice)
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("PK 投票失败：仅限群成员且未结束"))
            broadcastGroupPlayUpdate(pk.chatId, "pk_updated") { viewerId ->
                GroupCheckinRepository.getPk(pk.id, viewerId)
            }
            call.respond(pk)
        }



        post("/api/pk/{pkId}/close") {
            val userId = call.requireUserId()
            if (call.rejectIfSuspendedForPolls(userId)) return@post
            val pkId = call.requirePathParamOr400("pkId", "missing pkId") ?: return@post
            val pk = GroupCheckinRepository.closePk(pkId, userId)
                ?: return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("只有创建者可关闭 PK"))
            broadcastGroupPlayUpdate(pk.chatId, "pk_closed") { viewerId ->
                GroupCheckinRepository.getPk(pk.id, viewerId)
            }
            call.respond(pk)
        }
    }
}

private const val MAX_PK_TITLE_LENGTH = 120
