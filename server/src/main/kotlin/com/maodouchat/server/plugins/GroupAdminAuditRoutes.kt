package com.maodouchat.server.plugins

import com.maodouchat.server.model.ChatType
import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.repository.ConversationParticipantRepository
import com.maodouchat.server.repository.GroupAuditRepository
import com.maodouchat.server.repository.SenderKeyDistributionRepository
import com.maodouchat.server.repository.SignalKeyRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** 群管理路由：查询与审计（成员列表/操作记录/密钥分发状态）。 */
internal fun Route.configureGroupAdminAuditRoutes(
    participantRepository: ConversationParticipantRepository,
    auditRepository: GroupAuditRepository,
    signalKeyRepository: SignalKeyRepository,
    senderKeyRepository: SenderKeyDistributionRepository,
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



        get("/api/chats/{chatId}/audit") {
            val userId = call.requireUserId()
            val chatId = parseRawOrEmpty(call.parameters, "chatId")
            if (!participantRepository.isParticipant(chatId, userId) ||
                (participantRepository.chatType(chatId) == ChatType.CHANNEL &&
                    !participantRepository.isOwnerOrAdmin(chatId, userId))
            ) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权查看群操作记录"))
                return@get
            }
            val limit = parseAdminListLimit(call.request.queryParameters, maxLimit = 100)
            val offset = parseAdminListIntOffset(call.request.queryParameters)
            call.respond(auditRepository.list(chatId, limit, offset, viewerId = userId))
        }



        get("/api/chats/{chatId}/sender-key-distributions") {
            val userId = call.requireUserId()
            val chatId = parseRawOrEmpty(call.parameters, "chatId")
            if (!participantRepository.isParticipant(chatId, userId)) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权操作"))
                return@get
            }
            if (participantRepository.chatType(chatId) == ChatType.CHANNEL &&
                !participantRepository.isOwnerOrAdmin(chatId, userId)
            ) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权查看密钥分发状态"))
                return@get
            }
            val requestedDeviceId = parseOptionalInt(call.request.queryParameters, "currentDeviceId")
            if (requestedDeviceId != null && requestedDeviceId !in 1..255) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("设备参数无效"))
                return@get
            }
            val currentDeviceId = requestedDeviceId ?: call.authDeviceId()
            val expectedTargets = signalKeyRepository
                .getConfirmedDeviceTargets(participantRepository.participantIds(chatId))
                .filterNot { (targetUserId, deviceId) ->
                    targetUserId.startsWith("bot_") ||
                        (targetUserId == userId && currentDeviceId != null && deviceId == currentDeviceId)
                }
                .toSet()
            val epoch = parseOptionalLong(call.request.queryParameters, "epoch")
            call.respond(senderKeyRepository.getStatus(chatId, userId, epoch, expectedTargets))
        }
    }
}

private val signalKeyRepoForAuthDevice = SignalKeyRepository()


// 当前登录设备的 deviceId：principal 缺失时沿用 `!!` 语义（由 StatusPages 处理），
// auth session 未绑定或 deviceId 越界时返回 null。
private fun ApplicationCall.authDeviceId(): Int? {
    val sessionId = optionalAuthSessionId() ?: return null
    return signalKeyRepoForAuthDevice.getDeviceIdForAuthSession(sessionId)?.takeIf { it in 1..255 }
}
