package com.maodouchat.server.plugins

import com.maodouchat.server.messaging.v2.MessagingV2ConversationNotFoundException
import com.maodouchat.server.messaging.v2.MessagingV2BlockedConversationException
import com.maodouchat.server.messaging.v2.MessagingV2NotParticipantException
import com.maodouchat.server.messaging.v2.MessagingV2Repository
import com.maodouchat.server.model.ErrorResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.response.respond
import io.ktor.server.routing.*

/** V2 快照簇：GET /conversations/{conversationId}/snapshot。 */
internal fun Route.configureMessagingV2SnapshotRoutes(
    repository: MessagingV2Repository,
) {

    get("/conversations/{conversationId}/snapshot") {
        val binding = call.deviceSessionBinding(repository)
        if (binding == null) {
            call.respond(
                HttpStatusCode.Conflict,
                ErrorResponse("当前登录会话尚未绑定已确认设备", "DEVICE_NOT_READY"),
            )
            return@get
        }
        val conversationId = call.parameters["conversationId"]
        if (conversationId == null || !conversationIdV2.matches(conversationId)) {
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("会话 ID 无效", "INVALID_CONVERSATION_ID"),
            )
            return@get
        }
        val snapshot = try {
            repository.conversationSnapshot(conversationId, binding.userId, binding.deviceId)
        } catch (error: MessagingV2ConversationNotFoundException) {
            call.respond(
                HttpStatusCode.NotFound,
                ErrorResponse("会话不存在", "CONVERSATION_NOT_FOUND"),
            )
            return@get
        } catch (error: MessagingV2NotParticipantException) {
            call.respond(
                HttpStatusCode.Forbidden,
                ErrorResponse("无权访问该会话", "NOT_PARTICIPANT"),
            )
            return@get
        } catch (error: MessagingV2BlockedConversationException) {
            call.respond(
                HttpStatusCode.Forbidden,
                ErrorResponse("存在屏蔽关系，无法访问该会话设备", "CONVERSATION_BLOCKED"),
            )
            return@get
        }
        call.respond(snapshot)
    }
}
