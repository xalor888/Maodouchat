package com.maodouchat.server.plugins

// WebSocket 入站消息处理簇：sendError/handleWsMessage（private→internal，同包调用；零行为改动）。
import com.maodouchat.server.model.*
import com.maodouchat.server.repository.ConversationParticipantRepository
import com.maodouchat.server.repository.ConversationQueryRepository
import com.maodouchat.server.repository.UserRepository
import com.maodouchat.server.service.FcmPushService
import com.maodouchat.server.service.RuntimeConfigService
import com.maodouchat.server.service.CallInviteRateLimiter
import io.ktor.websocket.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal suspend fun WebSocketSession.sendError(
    message: String,
    json: Json,
    code: String? = null,
    retryAfterSeconds: Long? = null,
    messageId: String? = null
) {
    sendSafe(
        this,
        json.encodeToString(
            WsMessage("ERROR", json.encodeToString(ErrorResponse(message, code, retryAfterSeconds, messageId)))
        )
    )
}


internal suspend fun WebSocketSession.handleWsMessage(
    wsMsg: WsMessage,
    senderId: String,
    json: Json,
    userRepo: UserRepository,
    participantRepository: ConversationParticipantRepository,
    conversationQueryRepository: ConversationQueryRepository,
    callSignalingService: com.maodouchat.server.service.CallSignalingService,
    pushService: FcmPushService,
    wsMessageRateLimiter: BoundedRateLimiter,
    wsTypingRateLimiter: BoundedRateLimiter,
    wsSignalingRateLimiter: BoundedRateLimiter
) {
    when (wsMsg.type) {
        // 9.3xx（Ideaura 式应用层心跳）：客户端每 20s 发 PING，服务端回 PONG。
        // 协议层 WebSocket ping 经部分 NAT/代理会被吞掉，应用层心跳让客户端能确定性
        // 检测死连接并快速重连（保证推送不静默中断）。PONG 负载回显客户端时间戳。
        "PING" -> {
            val payload = runCatching { wsMsg.payload }.getOrDefault("")
            sendSafe(this, json.encodeToString(WsMessage("PONG", payload)))
        }
        "PRESENCE" -> {
            val foreground = wsMsg.payload.equals("true", ignoreCase = true) ||
                wsMsg.payload.contains("\"foreground\":true", ignoreCase = true)
            PresenceService.setForeground(senderId, foreground, json, userRepo)
        }
        "TYPING" -> {
                // 打字指示：每用户每分钟 120 次。30/min 会被正常连打 + 3s debounce 打满，
                // 表现为「正在输入」丢失；丢弃即可，不回 ERROR（避免客户端 toast 频繁）。
                if (!wsTypingRateLimiter.acquire(senderId, maxPerMinute = 120)) return
                val payload = json.decodeFromString<TypingPayload>(wsMsg.payload)
                TypingService.fanout(senderId, payload, json, userRepo, participantRepository)
            }

            "SIGNALING" -> {
            // Signaling is persisted before real-time fanout and disabled during maintenance.
            if (RuntimeConfigService.isMaintenanceMode()) {
                sendError(
                    RuntimeConfigService.get(RuntimeConfigService.KEY_MAINTENANCE_MESSAGE)
                        .ifBlank { "System under maintenance" },
                    json
                )
                return
            }
            // WebRTC 信令：只允许转发给真实用户，避免无效目标和自发自收
            val payload = json.decodeFromString<OutgoingSignalingPayload>(wsMsg.payload)
            val restriction = userRepo.getMessageRestrictionUntil(senderId).takeIf { it > 0L }
                ?: userRepo.getSuspendedUntil(senderId).takeIf { it > 0L }
            if (restriction != null) {
                sendError(wsRestrictionMessage(restriction, "你已被限制发起通话"), json)
                return
            }
            // 初次 offer 走 CallInviteRateLimiter（service 内）；后续信令每用户限流（WS 特有）
            if (!CallInviteRateLimiter.isInitialInvite(payload.type, payload.groupId, payload.groupInvite) &&
                !wsSignalingRateLimiter.acquire(senderId, maxPerMinute = 120)
            ) {
                sendError("信令发送过于频繁，请稍后重试", json)
                return
            }
            val request = SendSignalRequest(
                payload.toUserId, payload.type, payload.payload,
                payload.callId, payload.groupId, payload.groupMemberIds, payload.groupInvite,
                payload.epoch, payload.sequence, payload.idempotencyKey,
            )
            when (val outcome = callSignalingService.send(request, senderId)) {
                is com.maodouchat.server.service.CallSignalingService.SendOutcome.Rejected ->
                    sendError(outcome.message, json, outcome.code, outcome.retryAfterSeconds)
                is com.maodouchat.server.service.CallSignalingService.SendOutcome.Stored -> {
                    val stored = outcome.request
                    val signalMsg = json.encodeToString(WsMessage("SIGNALING", json.encodeToString(
                        IncomingSignalingPayload(
                            senderId,
                            stored.type,
                            stored.payload,
                            stored.callId,
                            stored.groupId,
                            stored.groupMemberIds,
                            stored.groupInvite,
                            stored.epoch,
                            stored.sequence,
                            stored.idempotencyKey,
                        )
                    )))
                    LocalRealtimeBus.publish(payload.toUserId, signalMsg)
                    if (payload.type.equals("offer", ignoreCase = true) && (payload.groupId.isBlank() || payload.groupInvite)) {
                        pushService.enqueueIncomingCall(
                            recipientId = payload.toUserId,
                            senderId = senderId,
                            isVideo = sdpHasActiveVideo(payload.payload),
                            callId = payload.callId
                        )
                    }
                }
            }
        }

        // 未知消息类型：绝不静默吞掉，记录并回错误，避免客户端/服务端协议失配时无感知
        else -> {
            wsLogger.warn("WebSocket received unknown message type: {}", wsMsg.type)
            sendError("不支持的 WebSocket 命令", json, code = "UNSUPPORTED_WS_COMMAND")
        }
    }
}
