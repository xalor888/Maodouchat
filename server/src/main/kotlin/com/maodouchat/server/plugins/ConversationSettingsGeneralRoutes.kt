package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.model.UpdateChatSettingsRequest
import com.maodouchat.server.repository.ConversationSettingsMutationResult
import com.maodouchat.server.service.ConversationCommandService
import com.maodouchat.server.service.RuntimeConfigService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.put

/** 聊天设置·通用：置顶/静音/归档/标未读等偏好。 */
internal fun Route.configureConversationSettingsGeneralRoutes(
    commandService: ConversationCommandService,
) {
    authenticate("auth-jwt") {
        put("/api/chats/{chatId}/settings") {
            val userId = call.requireUserId()
            val chatId = call.requireNonBlankParamOr400("chatId", "聊天 ID 无效") ?: return@put
            val request = call.receiveJson<UpdateChatSettingsRequest>()
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
            val outcome = commandService.updateSettings(chatId, userId, request)
            when (outcome.result) {
                ConversationSettingsMutationResult.UPDATED -> call.respond(outcome.settings!!)
                ConversationSettingsMutationResult.CHAT_NOT_FOUND,
                ConversationSettingsMutationResult.NOT_PARTICIPANT ->
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("聊天不存在"))
                else -> call.respond(HttpStatusCode.BadRequest, ErrorResponse("设置无法更新"))
            }
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
