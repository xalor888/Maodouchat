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

/** V2 消息发送簇：POST /messages（含请求体校验）。 */
internal fun Route.configureMessagingV2SendRoutes(
    repository: MessagingV2Repository,
    messageRateLimiter: BoundedRateLimiter,
) {

    post("/messages") {
        val binding = call.deviceSessionBinding(repository)
        if (binding == null) {
            call.respond(
                HttpStatusCode.Conflict,
                ErrorResponse("当前登录会话尚未绑定已确认设备", "DEVICE_NOT_READY"),
            )
            return@post
        }
        val body = call.receiveBoundedText(maxChars = 4_000_000)
        if (body == null) {
            call.respond(
                HttpStatusCode.PayloadTooLarge,
                ErrorResponse("密文载荷过大", "PAYLOAD_TOO_LARGE"),
            )
            return@post
        }
        val request = runCatching {
            messagingV2Json.decodeFromString<SendMessageV2Request>(body)
        }.getOrNull()
        if (request == null || !request.isValid()) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("v2 消息参数无效", "INVALID_MESSAGE"))
            return@post
        }
        if (RuntimeConfigService.isMaintenanceMode()) {
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                ErrorResponse(
                    RuntimeConfigService.get(RuntimeConfigService.KEY_MAINTENANCE_MESSAGE)
                        .ifBlank { "系统维护中" },
                    "MAINTENANCE",
                ),
            )
            return@post
        }
        val command = SendMessageV2Command(
            id = request.id,
            conversationId = request.conversationId,
            senderUserId = binding.userId,
            senderDeviceId = binding.deviceId,
            kind = request.kind,
            clientTimestamp = request.clientTimestamp,
            groupRevision = request.groupRevision,
            attachmentIds = request.attachmentIds,
            envelopes = request.envelopes.map {
                OutboundEnvelope(
                    target = DeviceTarget(it.recipientUserId, it.recipientDeviceId),
                    ciphertextType = it.ciphertextType,
                    ciphertext = it.ciphertext,
                )
            },
        )
        val result = try {
            repository.send(command) {
                messageRateLimiter.acquire(
                    key = "messaging-v2:${binding.userId}",
                    maxPerMinute = RuntimeConfigService.maxMessagePerMinute(),
                )
            }
        } catch (error: MessagingV2CoverageException) {
            call.respond(
                HttpStatusCode.Conflict,
                ErrorResponse("设备列表已变化，请刷新密钥后重试", "DEVICE_COVERAGE_MISMATCH"),
            )
            return@post
        } catch (error: MessagingV2RevisionMismatchException) {
            call.respond(
                HttpStatusCode.Conflict,
                ErrorResponse("群成员版本已变化:${error.expected}", "GROUP_REVISION_MISMATCH"),
            )
            return@post
        } catch (error: MessagingV2DuplicateMessageException) {
            call.respond(
                HttpStatusCode.Conflict,
                ErrorResponse("消息 ID 已被不同内容占用", "MESSAGE_ID_CONFLICT"),
            )
            return@post
        } catch (error: MessagingV2ConversationNotFoundException) {
            call.respond(
                HttpStatusCode.NotFound,
                ErrorResponse("会话不存在", "CONVERSATION_NOT_FOUND"),
            )
            return@post
        } catch (error: MessagingV2NotParticipantException) {
            call.respond(
                HttpStatusCode.Forbidden,
                ErrorResponse("无权向该会话发送消息", "NOT_PARTICIPANT"),
            )
            return@post
        } catch (error: MessagingV2SenderMutedException) {
            call.respond(
                HttpStatusCode.Forbidden,
                ErrorResponse("当前账号已被禁言", "SENDER_MUTED"),
            )
            return@post
        } catch (error: MessagingV2SenderRestrictedException) {
            call.respond(
                HttpStatusCode.Forbidden,
                ErrorResponse("当前账号已被限制发送消息", "SENDER_RESTRICTED"),
            )
            return@post
        } catch (error: MessagingV2BlockedConversationException) {
            call.respond(
                HttpStatusCode.Forbidden,
                ErrorResponse("存在屏蔽关系，无法发送消息", "CONVERSATION_BLOCKED"),
            )
            return@post
        } catch (error: MessagingV2ChannelReadOnlyException) {
            call.respond(
                HttpStatusCode.Forbidden,
                ErrorResponse("频道为单向广播，仅创建者可发送消息", "CHANNEL_READ_ONLY"),
            )
            return@post
        } catch (error: MessagingV2RateLimitedException) {
            call.respond(
                HttpStatusCode.TooManyRequests,
                ErrorResponse("发送过于频繁，请稍后再试", "RATE_LIMITED"),
            )
            return@post
        } catch (error: MessagingV2AttachmentNotReadyException) {
            call.respond(
                HttpStatusCode.Conflict,
                ErrorResponse("附件尚未上传完成，请稍后重试", "ATTACHMENT_NOT_READY"),
            )
            return@post
        } catch (error: MessagingV2ProtocolViolationException) {
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("协议控制消息与会话类型不匹配", "INVALID_PROTOCOL_MESSAGE"),
            )
            return@post
        }
        val wakeup = messagingV2Json.encodeToString(WsMessage("INBOX_AVAILABLE_V2", "{}"))
        result.recipientUserIds.forEach { LocalRealtimeBus.publish(it, wakeup) }
        call.respond(
            HttpStatusCode.Accepted,
            SendMessageV2Response(
                result.messageId,
                result.serverTimestamp,
                result.envelopeCount,
                result.idempotentReplay,
            ),
        )
    }
}

private fun SendMessageV2Request.isValid(): Boolean =
    messageIdV2.matches(id) &&
        conversationIdV2.matches(conversationId) &&
        kind in messageKindsV2 &&
        clientTimestamp > 0L &&
        attachmentIds.size <= 8 &&
        attachmentIds.all { messageIdV2.matches(it) } &&
        envelopes.size <= 500 &&
        envelopes.all {
            it.recipientUserId.isNotBlank() &&
                it.recipientUserId.length <= 50 &&
                it.recipientDeviceId in 1..255 &&
                ciphertextTypeV2.matches(it.ciphertextType) &&
                it.ciphertext.isNotBlank() &&
                it.ciphertext.length <= 1_000_000
        }
