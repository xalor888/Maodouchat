package com.maodouchat.server.plugins

import com.maodouchat.server.model.CreateChatRequest
import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.model.UpdateGroupAnnouncementRequest
import com.maodouchat.server.model.UploadAvatarRequest
import com.maodouchat.server.repository.ConversationParticipantRepository
import com.maodouchat.server.repository.ConversationQueryRepository
import com.maodouchat.server.repository.GroupMemberMutationResult
import com.maodouchat.server.repository.GroupProfileRepository
import com.maodouchat.server.repository.UserRepository
import com.maodouchat.server.service.FileStorageService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 群管理路由：群资料（改名/公告/头像）。 */
internal fun Route.configureGroupAdminProfileCoreRoutes(
    userRepo: UserRepository,
    profileRepository: GroupProfileRepository,
    queryRepository: ConversationQueryRepository,
    participantRepository: ConversationParticipantRepository,
    avatarRateLimiter: BoundedRateLimiter,
    json: Json,
) {
    authenticate("auth-jwt") {

        put("/api/chats/{chatId}/name") {
            val userId = call.requireUserId()
            val chatId = parseRawOrEmpty(call.parameters, "chatId")
            if (call.rejectIfSuspended(userRepo, userId)) return@put
            val name = call.receiveBoundedText()
                ?.let { parseJson<CreateChatRequest>(it) }
                ?.groupName
                .orEmpty()
                .trim()
            if (name.isEmpty() || name.length > 50) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("群名长度需 1-50 字符"))
                return@put
            }
            val result = profileRepository.updateName(chatId, userId, name)
            if (call.respondGroupMemberMutationFailure(result)) return@put
            notifyGroupRevisionChanged(queryRepository, participantRepository, json, chatId, "GROUP_RENAMED", userId)
            call.respondUpdatedGroup(queryRepository, chatId)
        }


        put("/api/chats/{chatId}/announcement") {
            val userId = call.requireUserId()
            val chatId = parseRawOrEmpty(call.parameters, "chatId")
            if (call.rejectIfSuspended(userRepo, userId)) return@put
            val request = call.receiveJson<UpdateGroupAnnouncementRequest>()
                ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("参数无效"))
                    return@put
                }
            val announcement = request.announcement.trim().takeIf(String::isNotEmpty)
            if ((announcement?.length ?: 0) > 1200) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("群公告不能超过 1200 字符"))
                return@put
            }
            val result = profileRepository.updateAnnouncement(chatId, userId, announcement)
            if (call.respondGroupMemberMutationFailure(result)) return@put
            notifyGroupRevisionChanged(queryRepository, participantRepository, json, chatId, "ANNOUNCEMENT_UPDATED", userId)
            call.respondUpdatedGroup(queryRepository, chatId)
        }


        post("/api/chats/{chatId}/avatar") {
            val userId = call.requireUserId()
            val chatId = parseRawOrEmpty(call.parameters, "chatId")
            if (call.rejectIfSuspended(userRepo, userId)) return@post
            if (!avatarRateLimiter.acquire(userId, maxPerMinute = 10)) {
                call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("头像操作过于频繁，请稍后再试"))
                return@post
            }
            if (!participantRepository.isOwnerOrAdmin(chatId, userId)) {
                call.respond(
                    HttpStatusCode.Forbidden,
                    ErrorResponse("只有群主或管理员可以修改群头像", code = "GROUP_PERMISSION_DENIED"),
                )
                return@post
            }
            val request = call.receiveBoundedText(MAX_UPLOAD_JSON_BODY_CHARS)
                ?.let { parseJson<UploadAvatarRequest>(it) }
                ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("头像参数无效"))
                    return@post
                }
            val avatarUrl = try {
                FileStorageService.saveGroupAvatar(request.base64Data, chatId)
            } catch (error: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(error.message ?: "群头像无效"))
                return@post
            }
            var committed = false
            try {
                val result = profileRepository.updateAvatar(chatId, userId, avatarUrl)
                if (result.result != GroupMemberMutationResult.UPDATED) {
                    call.respondGroupMemberMutationFailure(result.result)
                    return@post
                }
                committed = true
                FileStorageService.deleteGroupAvatarUrl(result.previousAvatarUrl, chatId)
                notifyGroupRevisionChanged(queryRepository, participantRepository, json, chatId, "AVATAR_UPDATED", userId)
                call.respond(buildJsonObject {
                    put("status", "ok")
                    put("avatarUrl", avatarUrl)
                })
            } finally {
                if (!committed) FileStorageService.deleteGroupAvatarUrl(avatarUrl, chatId)
            }
        }


    }
}



private suspend fun io.ktor.server.application.ApplicationCall.respondUpdatedGroup(
    queryRepository: ConversationQueryRepository,
    chatId: String,
) {
    val chat = queryRepository.getById(chatId)
    if (chat == null) respond(HttpStatusCode.InternalServerError, ErrorResponse("群聊状态异常，请刷新"))
    else respond(chat)
}
