package com.maodouchat.server.plugins

import com.maodouchat.server.model.DisappearingMessagesResponse
import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.model.UpdateDisappearingMessagesRequest
import com.maodouchat.server.model.WsMessage
import com.maodouchat.server.repository.ConversationParticipantRepository
import com.maodouchat.server.repository.ConversationSettingsMutationResult
import com.maodouchat.server.repository.UserRepository
import com.maodouchat.server.service.ConversationCommandService
import com.maodouchat.server.service.RuntimeConfigService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.put
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 聊天设置·阅后即焚：销毁时长变更与实时广播。 */
internal fun Route.configureConversationSettingsDisappearingRoutes(
    userRepo: UserRepository,
    commandService: ConversationCommandService,
    participantRepository: ConversationParticipantRepository,
    json: Json,
) {
    authenticate("auth-jwt") {
        put("/api/chats/{chatId}/disappearing-messages") {
            if (call.rejectIfMaintenance()) return@put
            if (!RuntimeConfigService.isDisappearingMessagesEnabled()) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("disappearing_messages_disabled"))
                return@put
            }
            val userId = call.requireUserId()
            if (call.rejectIfSuspended(userRepo, userId)) return@put
            val chatId = call.requireNonBlankParamOr400("chatId", "聊天 ID 无效") ?: return@put
            val request = call.receiveJson<UpdateDisappearingMessagesRequest>()
                ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("请求体无效"))
                    return@put
                }
            val outcome = commandService.setDisappearingMessages(chatId, userId, request.seconds)
            val settings = when (outcome.result) {
                ConversationSettingsMutationResult.UPDATED -> outcome.settings!!
                ConversationSettingsMutationResult.GROUP_NOT_SUPPORTED -> {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("群聊暂不支持阅后即焚"))
                    return@put
                }
                ConversationSettingsMutationResult.INVALID_TIMER -> {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("不支持的销毁时长"))
                    return@put
                }
                ConversationSettingsMutationResult.CHAT_NOT_FOUND,
                ConversationSettingsMutationResult.NOT_PARTICIPANT -> {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权访问该聊天"))
                    return@put
                }
            }
            val event = json.encodeToString(
                WsMessage.serializer(),
                WsMessage(
                    "DISAPPEARING_MESSAGES_UPDATED",
                    json.encodeToString(DisappearingMessagesResponse.serializer(), settings),
                ),
            )
            participantRepository.participantIds(chatId).forEach { participantId ->
                LocalRealtimeBus.publish(participantId, event)
            }
            call.respond(settings)
        }
    }
}
