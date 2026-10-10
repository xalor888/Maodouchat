package com.maodouchat.server.plugins

import com.maodouchat.server.auth.JwtConfig
import com.maodouchat.server.messaging.v2.AcknowledgeEnvelopesV2Request
import com.maodouchat.server.messaging.v2.AcknowledgeEnvelopesV2Response
import com.maodouchat.server.messaging.v2.DeviceTarget
import com.maodouchat.server.messaging.v2.MessagingV2ConversationNotFoundException
import com.maodouchat.server.messaging.v2.MessagingV2CoverageException
import com.maodouchat.server.messaging.v2.MessagingV2AttachmentNotReadyException
import com.maodouchat.server.messaging.v2.MessagingV2BlockedConversationException
import com.maodouchat.server.messaging.v2.MessagingV2ChannelReadOnlyException
import com.maodouchat.server.messaging.v2.MessagingV2DuplicateMessageException
import com.maodouchat.server.messaging.v2.MessagingV2NotParticipantException
import com.maodouchat.server.messaging.v2.MessagingV2ProtocolViolationException
import com.maodouchat.server.messaging.v2.MessagingV2RateLimitedException
import com.maodouchat.server.messaging.v2.MessagingV2Repository
import com.maodouchat.server.messaging.v2.MessagingV2RevisionMismatchException
import com.maodouchat.server.messaging.v2.MessagingV2SenderMutedException
import com.maodouchat.server.messaging.v2.MessagingV2SenderRestrictedException
import com.maodouchat.server.messaging.v2.OutboundEnvelope
import com.maodouchat.server.messaging.v2.SendMessageV2Command
import com.maodouchat.server.messaging.v2.SendMessageV2Request
import com.maodouchat.server.messaging.v2.SendMessageV2Response
import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.model.WsMessage
import com.maodouchat.server.service.RuntimeConfigService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** V2 收件箱簇：GET /inbox、POST /inbox/ack。 */
internal fun Route.configureMessagingV2InboxRoutes(
    repository: MessagingV2Repository,
) {

    get("/inbox") {
        val binding = call.deviceSessionBinding(repository)
        if (binding == null) {
            call.respond(
                HttpStatusCode.Conflict,
                ErrorResponse("当前登录会话尚未绑定已确认设备", "DEVICE_NOT_READY"),
            )
            return@get
        }
        val limit = parseAdminListLimit(call.request.queryParameters, defaultLimit = 100, maxLimit = 500)
        call.respond(repository.pending(binding.userId, binding.deviceId, limit))
    }

    post("/inbox/ack") {
        val binding = call.deviceSessionBinding(repository)
        if (binding == null) {
            call.respond(
                HttpStatusCode.Conflict,
                ErrorResponse("当前登录会话尚未绑定已确认设备", "DEVICE_NOT_READY"),
            )
            return@post
        }
        val request = runCatching {
            val ackBody = call.receiveBoundedText(maxChars = 64_000) ?: return@runCatching null
            messagingV2Json.decodeFromString<AcknowledgeEnvelopesV2Request>(ackBody)
        }.getOrNull()
        if (
            request == null || request.envelopeIds.size !in 1..500 ||
            request.envelopeIds.any { !messageIdV2.matches(it) }
        ) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("ACK 参数无效", "INVALID_ACK"))
            return@post
        }
        val acknowledged = repository.acknowledge(binding.userId, binding.deviceId, request.envelopeIds.toSet())
        call.respond(AcknowledgeEnvelopesV2Response(acknowledged))
    }
}
