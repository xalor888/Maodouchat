package com.maodouchat.server.plugins

import com.maodouchat.server.model.CreateChatRequest
import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.model.UpdateGroupAnnouncementRequest
import com.maodouchat.server.repository.ConversationParticipantRepository
import com.maodouchat.server.repository.ConversationQueryRepository
import com.maodouchat.server.repository.GroupProfileRepository
import com.maodouchat.server.repository.UserRepository
import com.maodouchat.server.repository.getById
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.put
import kotlinx.serialization.json.Json
import com.maodouchat.server.repository.updateAnnouncement
import com.maodouchat.server.repository.updateName

/** 群资料：改名/公告。 */
internal fun Route.configureGroupAdminProfileTextRoutes(
    userRepo: UserRepository,
    profileRepository: GroupProfileRepository,
    queryRepository: ConversationQueryRepository,
    participantRepository: ConversationParticipantRepository,
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
