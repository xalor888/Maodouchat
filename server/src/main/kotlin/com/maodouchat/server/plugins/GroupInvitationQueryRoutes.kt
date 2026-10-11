package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.repository.ConversationParticipantRepository
import com.maodouchat.server.service.GroupInvitationService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

// 群邀请查询：收到的邀请/群内邀请列表（从 GroupInvitationLifecycleRoutes 按域拆出）。
internal fun Route.configureGroupInvitationQueryRoutes(
    invitationService: GroupInvitationService,
    participantRepository: ConversationParticipantRepository,
) {
    get("/api/group-invitations") {
        val userId = call.requireUserId()
        call.respond(invitationService.listIncoming(userId))
    }

    get("/api/chats/{chatId}/invitations") {
        val userId = call.requireUserId()
        val chatId = parseRawOrEmpty(call.parameters, "chatId")
        if (!participantRepository.isParticipant(chatId, userId)) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("群聊不存在"))
            return@get
        }
        call.respond(invitationService.listForChat(chatId))
    }
}
