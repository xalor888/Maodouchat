package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.repository.UserRepository
import com.maodouchat.server.repository.closePoll
import com.maodouchat.server.repository.createPoll
import com.maodouchat.server.repository.getPoll
import com.maodouchat.server.repository.isMember
import com.maodouchat.server.repository.isMuted
import com.maodouchat.server.repository.vote
import com.maodouchat.server.service.RuntimeConfigService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

/** 投票 legacy 写入簇：创建 / 投票 / 关闭。 */
internal fun Route.configurePollLegacyManageRoutes(userRepo: UserRepository) {
    authenticate("auth-jwt") {
        post("/api/chats/{chatId}/polls") {
            if (!RuntimeConfigService.isPollsEnabled()) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("polls_disabled"))
                return@post
            }
            val userId = call.requireUserId()
            // 9.145：封禁用户不得参与群玩法写入（与 PollRouting 各写端点的 8.33 口径一致——
            // 此前本文件的 polls 三写端点只查成员/禁言，封禁账号仍可创建投票广播到全群）
            if (call.rejectIfSuspended(userRepo, userId)) return@post
            val chatId = call.requirePathParamOr400("chatId", "missing chatId") ?: return@post
            val body = call.receiveBoundedTextOrEmpty(32_768)
            val obj = call.requireJsonObjectOr400(body) ?: return@post
            // 9.157：与投票选项一致——非法元素整体拒绝，不静默截成子集
            val fields = parsePollLegacyCreateOrNull(obj)
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("投票选项无效"))
            // 8.32 一致性：非成员 403（与群管理端点一致），其余失败保持 400
            if (!com.maodouchat.server.repository.PollRepository.isMember(chatId, userId)) {
                return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权访问该群"))
            }
            if (com.maodouchat.server.repository.PollRepository.isMuted(chatId, userId)) {
                return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("你已被禁言，暂时无法参与群玩法"))
            }
            val poll = com.maodouchat.server.repository.PollRepository.createPoll(
                chatId, userId, fields.question, fields.options, fields.multi, fields.anonymous, fields.closesAt
            ) ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("无法创建投票"))
            call.respond(poll)
        }

        post("/api/polls/{pollId}/vote") {
            if (!RuntimeConfigService.isPollsEnabled()) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("polls_disabled"))
                return@post
            }
            val userId = call.requireUserId()
            // 9.145：封禁用户不得参与投票（同 polls 创建口径）
            if (call.rejectIfSuspended(userRepo, userId)) return@post
            val pollId = call.requirePathParamOr400("pollId", "missing pollId") ?: return@post
            val body = call.receiveBoundedTextOrEmpty(8_192)
            val obj = call.requireJsonObjectOr400(body) ?: return@post
            // 9.157：严格解析——此前 mapNotNull 静默丢弃非法元素（如 [0,"abc",1] 被投成 [0,1]，
            // 用户发送垃圾数据却按子集成功投票）。任一元素非非负整数即整体拒绝。
            val indexes = parsePollLegacyVoteOrNull(obj)
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("投票选项无效"))
            com.maodouchat.server.repository.PollRepository.getPoll(pollId, userId)?.let { existing ->
                if (com.maodouchat.server.repository.PollRepository.isMuted(existing.chatId, userId)) {
                    return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("你已被禁言，暂时无法参与群玩法"))
                }
            }
            val poll = com.maodouchat.server.repository.PollRepository.vote(pollId, userId, indexes)
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("投票失败"))
            call.respond(poll)
        }

        post("/api/polls/{pollId}/close") {
            if (!RuntimeConfigService.isPollsEnabled()) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("polls_disabled"))
                return@post
            }
            val userId = call.requireUserId()
            // 9.145：封禁用户不得关闭投票（同 polls 创建口径）
            if (call.rejectIfSuspended(userRepo, userId)) return@post
            val pollId = call.requirePathParamOr400("pollId", "missing pollId") ?: return@post
            val poll = com.maodouchat.server.repository.PollRepository.closePoll(pollId, userId)
                ?: return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("无法关闭投票"))
            call.respond(poll)
        }

    }
}
