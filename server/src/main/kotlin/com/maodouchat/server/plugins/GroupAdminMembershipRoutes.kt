package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.model.UpdateGroupNicknameRequest
import com.maodouchat.server.model.UpdateMemberTitleRequest
import com.maodouchat.server.repository.ConversationParticipantRepository
import com.maodouchat.server.repository.ConversationQueryRepository
import com.maodouchat.server.repository.GroupProfileRepository
import com.maodouchat.server.repository.UserRepository
import com.maodouchat.server.service.GroupMembershipService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.put
import kotlinx.serialization.json.Json

/** 群管理路由：成员变更（移除/角色/转让/昵称/头衔）。 */
internal fun Route.configureGroupAdminMembershipRoutes(
    userRepo: UserRepository,
    membershipService: GroupMembershipService,
    profileRepository: GroupProfileRepository,
    queryRepository: ConversationQueryRepository,
    participantRepository: ConversationParticipantRepository,
    json: Json,
) {
    authenticate("auth-jwt") {

        delete("/api/chats/{chatId}/members/{memberId}") {
            call.handleRemoveGroupMember(userRepo, membershipService, json)
        }


        put("/api/chats/{chatId}/members/{memberId}/role") {
            call.handleUpdateGroupMemberRole(userRepo, membershipService, json)
        }


        put("/api/chats/{chatId}/members/{memberId}/ownership") {
            call.handleTransferGroupOwnership(userRepo, membershipService, json)
        }


        put("/api/chats/{chatId}/members/me/nickname") {
            val userId = call.requireUserId()
            val chatId = parseRawOrEmpty(call.parameters, "chatId")
            if (call.rejectIfSuspended(userRepo, userId)) return@put
            val request = call.receiveJson<UpdateGroupNicknameRequest>()
                ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("参数无效"))
                    return@put
                }
            val normalized = request.groupNickname.trim()
            if (normalized.length > 100) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("群昵称不能超过 100 字符"))
                return@put
            }
            val result = profileRepository.updateOwnNickname(chatId, userId, normalized.takeIf(String::isNotEmpty))
            if (call.respondGroupMemberMutationFailure(result)) return@put
            notifyGroupRevisionChanged(queryRepository, participantRepository, json, chatId, "NICKNAME_UPDATED", userId, userId)
            call.respondOk()
        }


        put("/api/chats/{chatId}/members/{memberId}/title") {
            val userId = call.requireUserId()
            val chatId = parseRawOrEmpty(call.parameters, "chatId")
            val memberId = parseRawOrEmpty(call.parameters, "memberId")
            if (call.rejectIfSuspended(userRepo, userId)) return@put
            val request = call.receiveJson<UpdateMemberTitleRequest>()
                ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("参数无效"))
                    return@put
                }
            val normalized = request.title.trim()
            if (normalized.length > 50) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("成员头衔不能超过 50 字符"))
                return@put
            }
            val result = profileRepository.updateMemberTitle(
                chatId,
                userId,
                memberId,
                normalized.takeIf(String::isNotEmpty),
            )
            if (call.respondGroupMemberMutationFailure(result)) return@put
            notifyGroupRevisionChanged(queryRepository, participantRepository, json, chatId, "TITLE_UPDATED", userId, memberId)
            call.respondOk()
        }


    }
}
