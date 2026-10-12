package com.maodouchat.server.plugins

import com.maodouchat.server.model.ChatType
import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.repository.ConversationParticipantRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** 群成员列表查询。 */
internal fun Route.configureGroupAdminAuditMemberRoutes(
    participantRepository: ConversationParticipantRepository,
) {
    authenticate("auth-jwt") {

        get("/api/chats/{chatId}/members") {
            val userId = call.requireUserId()
            val chatId = parseRawOrEmpty(call.parameters, "chatId")
            if (!participantRepository.isParticipant(chatId, userId)) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权操作"))
                return@get
            }
            val members = participantRepository.groupMembers(chatId, viewerId = userId)
            if (participantRepository.chatType(chatId) == ChatType.CHANNEL &&
                !participantRepository.isOwnerOrAdmin(chatId, userId)
            ) {
                call.respond(members.filter { it.role == "OWNER" || it.role == "ADMIN" })
            } else {
                call.respond(members)
            }
        }
    }
}
