package com.maodouchat.server.service

import com.maodouchat.server.common.CallSignalingValidators.isValidCallId
import com.maodouchat.server.common.CallSignalingValidators.isValidGroupSignalMetadata
import com.maodouchat.server.common.CallSignalingValidators.isValidSignalPayload
import com.maodouchat.server.model.SendSignalRequest
import com.maodouchat.server.repository.getById
import com.maodouchat.server.repository.isBlockedEitherWay
import com.maodouchat.server.repository.latestCursor
import com.maodouchat.server.repository.shareConversation
import io.ktor.http.HttpStatusCode

// 信令准入校验：payload/callId 格式、目标用户存在、群元数据、双向拉黑、共同会话；epoch/sequence 顺序准入。
internal fun CallSignalingService.validate(
    request: SendSignalRequest,
    fromUserId: String,
): Rejected? {
    if (!isValidSignalPayload(request.type, request.payload) || !isValidCallId(request.callId)) {
        return Rejected(HttpStatusCode.BadRequest, "信令内容无效")
    }
    if (request.toUserId == fromUserId) {
        return Rejected(HttpStatusCode.BadRequest, "不能向自己发送信令")
    }
    if (userRepository.getById(request.toUserId) == null) {
        return Rejected(HttpStatusCode.NotFound, "信令目标用户不存在")
    }
    if (!isValidGroupSignalMetadata(
            request.groupId, request.groupMemberIds, request.groupInvite, request.callId,
            fromUserId, request.toUserId, conversationQueryRepository,
        )
    ) {
        return Rejected(HttpStatusCode.BadRequest, "群通话元数据无效")
    }
    if (userRepository.isBlockedEitherWay(fromUserId, request.toUserId)) {
        return Rejected(HttpStatusCode.Forbidden, "无法与已屏蔽的用户发送通话信令")
    }
    if (request.groupId.isBlank() && !conversationQueryRepository.shareConversation(fromUserId, request.toUserId)) {
        return Rejected(HttpStatusCode.Forbidden, "双方无共同会话，无法发送通话信令")
    }
    return null
}

internal fun CallSignalingService.rejectIfStale(
    request: SendSignalRequest,
    fromUserId: String,
): Rejected? {
    val last = signalingRepository.latestCursor(request.callId, fromUserId)
    return when (
        CallSignalingOrderPolicy.admit(
            Cursor(request.epoch, request.sequence),
            last,
        )
    ) {
        Accept -> null
        RejectStale -> Rejected(
            HttpStatusCode.Conflict,
            "信令顺序已过期",
            "CALL_SIGNAL_STALE",
        )
    }
}

internal fun CallSignalingService.isTerminal(type: String): Boolean =
    type.lowercase() in CallSignalingService.TERMINAL_SIGNAL_TYPES
