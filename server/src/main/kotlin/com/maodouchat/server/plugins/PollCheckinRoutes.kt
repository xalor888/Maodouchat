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

/** 群玩法 B3 路由：群签到 / 排行。 */
internal fun Route.configurePollCheckinRoutes() {
    authenticate("auth-jwt") {

        // ── 群签到 ─────────────────────────────────────
        post("/api/chats/{chatId}/checkins") {
            val userId = call.requireUserId()
            if (call.rejectIfSuspendedForPolls(userId)) return@post
            val chatId = call.requirePathParamOr400("chatId", "missing chatId") ?: return@post
            // 8.47：成员校验先于限流——非成员不应能消耗自己对该 chat 的配额
            //（此前限流先行，非成员可反复探测耗尽配额）
            if (!PollRepository.isMember(chatId, userId)) {
                return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权访问该群"))
            }
            if (call.rejectIfMutedForPolls(chatId, userId)) return@post
            if (!pollRateLimiter.acquire("$userId:$chatId:checkin", maxPerMinute = 20)) {
                return@post call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("签到过于频繁，请稍后再试"))
            }
            // 8.32 一致性：非成员 403（与群管理端点一致），其余失败保持 400
            val dto = GroupCheckinRepository.checkIn(chatId, userId)
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("无法签到"))
            broadcastGroupPlayUpdate(chatId, "checkin_updated") { viewerId ->
                GroupCheckinRepository.checkinForViewer(chatId, userId, viewerId)
            }
            call.respond(dto)
        }



        get("/api/chats/{chatId}/checkins/me") {
            val userId = call.requireUserId()
            val chatId = call.requirePathParamOr400("chatId", "missing chatId") ?: return@get
            // 8.39：与同文件其余读端点一致，先校验成员返回 403（此前落到 404「签到信息不存在」，
            // 把非成员与群不存在合并暴露权限边界）
            if (!PollRepository.isMember(chatId, userId)) {
                return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权访问该群"))
            }
            val dto = GroupCheckinRepository.myCheckin(chatId, userId)
                ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("签到信息不存在"))
            call.respond(dto)
        }



        get("/api/chats/{chatId}/checkins/rank") {
            val userId = call.requireUserId()
            val chatId = call.requirePathParamOr400("chatId", "missing chatId") ?: return@get
            if (!PollRepository.isMember(chatId, userId)) {
                return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权访问该群"))
            }
            val limit = parseGroupPlayLimit(call.request.queryParameters, defaultLimit = 20)
            call.respond(GroupCheckinRepository.checkinRanking(chatId, limit, viewerId = userId))
        }
    }
}
