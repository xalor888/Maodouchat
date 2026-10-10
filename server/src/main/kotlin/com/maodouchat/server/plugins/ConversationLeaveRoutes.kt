package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.repository.LeaveConversationResult
import com.maodouchat.server.service.BlobStore
import com.maodouchat.server.service.ConversationCommandService
import com.maodouchat.server.service.FileStorageService
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.Route
import io.ktor.server.auth.authenticate
import io.ktor.server.routing.delete
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 会话退出/删除：群主转让校验、附件与头像清理。 */
internal fun Route.configureConversationLeaveRoutes(
    commandService: ConversationCommandService,
    json: Json,
) {
    authenticate("auth-jwt") {
        delete("/api/chats/{id}") {
            val userId = call.requireUserId()
            val chatId = call.requireNonBlankParamOr400("id", "聊天 ID 无效") ?: return@delete
            val outcome = commandService.leave(chatId, userId)
            when (outcome.result) {
                LeaveConversationResult.OWNER_TRANSFER_REQUIRED -> {
                    call.respond(
                        HttpStatusCode.Conflict,
                        ErrorResponse("群主需先转让群主身份再退出群聊", code = "GROUP_OWNER_TRANSFER_REQUIRED"),
                    )
                    return@delete
                }
                LeaveConversationResult.NOT_PARTICIPANT -> {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权操作该聊天"))
                    return@delete
                }
                LeaveConversationResult.LEFT -> {
                    outcome.deletedAttachmentIds.forEach(BlobStore::delete)
                    FileStorageService.deleteGroupAvatarUrl(outcome.deletedGroupAvatarUrl, chatId)
                }
            }
            if (outcome.wasGroup && outcome.memberRevisionAfter != null) {
                notifyGroupRevisionChangedWithData(
                    json = json,
                    chatId = chatId,
                    reason = "MEMBER_LEFT",
                    actorId = userId,
                    targetUserId = userId,
                    memberRevision = outcome.memberRevisionAfter,
                    recipientIds = outcome.recipientsBefore,
                )
            }
            call.respond(buildJsonObject { put("status", "ok") })
        }
    }
}
