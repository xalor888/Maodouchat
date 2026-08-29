package com.maodouchat.server.plugins

import com.maodouchat.server.config.ServerConfig
import com.maodouchat.server.model.*
import com.maodouchat.server.repository.ConversationParticipantRepository
import com.maodouchat.server.repository.ConversationQueryRepository
import com.maodouchat.server.repository.UserRepository
import com.maodouchat.server.service.RuntimeConfigService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

internal fun Route.configurePollLegacyRoutes(
    userRepo: UserRepository,
    conversationParticipantRepo: ConversationParticipantRepository,
    conversationQueryRepo: ConversationQueryRepository,
    json: Json,
) {
    authenticate("auth-jwt") {
            post("/api/chats/{chatId}/polls") {
                if (!RuntimeConfigService.isPollsEnabled()) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("polls_disabled"))
                    return@post
                }
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                // 9.145：封禁用户不得参与群玩法写入（与 PollRouting 各写端点的 8.33 口径一致——
                // 此前本文件的 polls 三写端点只查成员/禁言，封禁账号仍可创建投票广播到全群）
                if (call.rejectIfSuspended(userRepo, userId)) return@post
                val chatId = call.parameters["chatId"] ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing chatId"))
                val body = call.receiveBoundedTextOrEmpty(32_768)
                val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
                val question = obj["question"]?.jsonPrimitive?.content.orEmpty()
                // 9.157：与投票选项一致——非法元素整体拒绝，不静默截成子集
                val options = buildList {
                    val arr = obj["options"]?.jsonArray
                    if (arr != null) {
                        for (element in arr) {
                            val text = (element as? kotlinx.serialization.json.JsonPrimitive)?.content
                            if (text == null) {
                                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("投票选项无效"))
                            }
                            add(text)
                        }
                    }
                }
                val multi = obj["multi"]?.jsonPrimitive?.booleanOrNull
                    ?: obj["multi"]?.jsonPrimitive?.content?.toBooleanStrictOrNull()
                    ?: false
                val anonymous = obj["anonymous"]?.jsonPrimitive?.booleanOrNull
                    ?: obj["anonymous"]?.jsonPrimitive?.content?.toBooleanStrictOrNull()
                    ?: false
                val closesAt = obj["closesAt"]?.jsonPrimitive?.content?.toLongOrNull()
                // 8.32 一致性：非成员 403（与群管理端点一致），其余失败保持 400
                if (!com.maodouchat.server.repository.PollRepository.isMember(chatId, userId)) {
                    return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权访问该群"))
                }
                if (com.maodouchat.server.repository.PollRepository.isMuted(chatId, userId)) {
                    return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("你已被禁言，暂时无法参与群玩法"))
                }
                val poll = com.maodouchat.server.repository.GroupPlayRepository.createPoll(
                    chatId, userId, question, options, multi, anonymous, closesAt
                ) ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("无法创建投票"))
                call.respond(poll)
            }
            get("/api/chats/{chatId}/polls") {
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                val chatId = call.parameters["chatId"] ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing chatId"))
                // 8.32 一致性：非成员 403（此前仓库层静默过滤返回 200 []，与其余群资源 403 不一致）
                if (!com.maodouchat.server.repository.PollRepository.isMember(chatId, userId)) {
                    return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权访问该群"))
                }
                call.respond(com.maodouchat.server.repository.GroupPlayRepository.listChatPolls(chatId, userId))
            }
            get("/api/polls/{pollId}") {
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                val pollId = call.parameters["pollId"] ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing pollId"))
                val poll = com.maodouchat.server.repository.GroupPlayRepository.getPoll(pollId, userId)
                    ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("poll not found"))
                call.respond(poll)
            }
            post("/api/polls/{pollId}/vote") {
                if (!RuntimeConfigService.isPollsEnabled()) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("polls_disabled"))
                    return@post
                }
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                // 9.145：封禁用户不得参与投票（同 polls 创建口径）
                if (call.rejectIfSuspended(userRepo, userId)) return@post
                val pollId = call.parameters["pollId"] ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing pollId"))
                val body = call.receiveBoundedTextOrEmpty(8_192)
                val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
                // 9.157：严格解析——此前 mapNotNull 静默丢弃非法元素（如 [0,"abc",1] 被投成 [0,1]，
                // 用户发送垃圾数据却按子集成功投票）。任一元素非非负整数即整体拒绝。
                val indexes = buildList {
                    val arr = obj["optionIndexes"]?.jsonArray
                    if (arr != null) {
                        for (element in arr) {
                            val v = (element as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()
                            if (v == null || v < 0) {
                                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("投票选项无效"))
                            }
                            add(v)
                        }
                    } else {
                        val single = (obj["optionIndex"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()
                        if (single == null || single < 0) {
                            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("投票选项无效"))
                        }
                        add(single)
                    }
                }
                com.maodouchat.server.repository.GroupPlayRepository.getPoll(pollId, userId)?.let { existing ->
                    if (com.maodouchat.server.repository.PollRepository.isMuted(existing.chatId, userId)) {
                        return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("你已被禁言，暂时无法参与群玩法"))
                    }
                }
                val poll = com.maodouchat.server.repository.GroupPlayRepository.vote(pollId, userId, indexes)
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("投票失败"))
                call.respond(poll)
            }
            post("/api/polls/{pollId}/close") {
                if (!RuntimeConfigService.isPollsEnabled()) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("polls_disabled"))
                    return@post
                }
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                // 9.145：封禁用户不得关闭投票（同 polls 创建口径）
                if (call.rejectIfSuspended(userRepo, userId)) return@post
                val pollId = call.parameters["pollId"] ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing pollId"))
                val poll = com.maodouchat.server.repository.GroupPlayRepository.closePoll(pollId, userId)
                    ?: return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("无法关闭投票"))
                call.respond(poll)
            }

    }
}
