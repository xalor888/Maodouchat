package com.maodouchat.server.plugins

import com.maodouchat.server.model.DisappearingMessagesResponse
import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.model.UpdateChatSettingsRequest
import com.maodouchat.server.model.UpdateDisappearingMessagesRequest
import com.maodouchat.server.model.WsMessage
import com.maodouchat.server.repository.ConversationParticipantRepository
import com.maodouchat.server.repository.ConversationSettingsMutationResult
import com.maodouchat.server.repository.ConversationSettingsRepository
import com.maodouchat.server.repository.UserRepository
import com.maodouchat.server.service.RuntimeConfigService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.put
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Authenticated HTTP adapter for per-user preferences and shared 1:1 expiry policy. */
internal fun Route.configureConversationSettingsRoutes(
    userRepo: UserRepository,
    settingsRepository: ConversationSettingsRepository,
    participantRepository: ConversationParticipantRepository,
    json: Json,
) {
    authenticate("auth-jwt") {
        put("/api/chats/{chatId}/settings") {
            val userId = call.principal<JWTPrincipal>()!!.payload.subject
            val chatId = call.parameters["chatId"]?.takeIf(String::isNotBlank) ?: run {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("聊天 ID 无效"))
                return@put
            }
            val request = call.receiveBoundedText()?.let { parseJson<UpdateChatSettingsRequest>(it) }
                ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("设置参数无效"))
                    return@put
                }
            if (request.isEmpty()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("至少提供一项设置"))
                return@put
            }
            val disabledFeature = request.disabledFeature()
            if (disabledFeature != null) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse(disabledFeature))
                return@put
            }
            val outcome = settingsRepository.updateUserSettings(chatId, userId, request)
            when (outcome.result) {
                ConversationSettingsMutationResult.UPDATED -> call.respond(outcome.settings!!)
                ConversationSettingsMutationResult.CHAT_NOT_FOUND,
                ConversationSettingsMutationResult.NOT_PARTICIPANT ->
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("聊天不存在"))
                else -> call.respond(HttpStatusCode.BadRequest, ErrorResponse("设置无法更新"))
            }
        }

        put("/api/chats/{chatId}/disappearing-messages") {
            if (call.rejectIfMaintenance()) return@put
            if (!RuntimeConfigService.isDisappearingMessagesEnabled()) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("disappearing_messages_disabled"))
                return@put
            }
            val userId = call.principal<JWTPrincipal>()!!.payload.subject
            if (call.rejectIfSuspended(userRepo, userId)) return@put
            val chatId = call.parameters["chatId"]?.takeIf(String::isNotBlank) ?: run {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("聊天 ID 无效"))
                return@put
            }
            val request = call.receiveBoundedText()?.let { parseJson<UpdateDisappearingMessagesRequest>(it) }
                ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("请求体无效"))
                    return@put
                }
            val outcome = settingsRepository.setDisappearingMessages(chatId, userId, request.seconds)
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
                sendToUser(participantId, event)
            }
            call.respond(settings)
        }
    }
}

private fun UpdateChatSettingsRequest.isEmpty(): Boolean =
    pinned == null && notificationsMuted == null && archived == null && markedUnread == null

private fun UpdateChatSettingsRequest.disabledFeature(): String? = when {
    archived != null && !RuntimeConfigService.isChatArchiveEnabled() -> "chat_archive_disabled"
    pinned != null && !RuntimeConfigService.isChatPinEnabled() -> "chat_pin_disabled"
    markedUnread != null && !RuntimeConfigService.isMarkedUnreadEnabled() -> "marked_unread_disabled"
    notificationsMuted != null && !RuntimeConfigService.isChatMuteEnabled() -> "chat_mute_disabled"
    else -> null
}
