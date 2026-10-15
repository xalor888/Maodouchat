package com.maodouchat.server.service

import com.maodouchat.server.model.SendSignalRequest
import com.maodouchat.server.repository.SignalingMessage
import com.maodouchat.server.repository.SignalingRepository
import com.maodouchat.server.repository.consumeForUser
import com.maodouchat.server.repository.store
import com.maodouchat.server.repository.storeTerminalAndClearOthers
import io.ktor.http.HttpStatusCode

// 通话信令发送/挂断：校验 → 顺序准入 → 邀请限流 → 落库；终端信令同事务清旧信令并撤销会话级 TURN。
fun CallSignalingService.send(request: SendSignalRequest, fromUserId: String): CallSignalingService.SendOutcome {
    val rejected = validate(request, fromUserId)
    if (rejected != null) return rejected
    val stale = rejectIfStale(request, fromUserId)
    if (stale != null) return stale

    if (CallInviteRateLimiter.isInitialInvite(request.type, request.groupId, request.groupInvite)) {
        val key = CallInviteRateLimiter.sessionKey(request.callId, request.groupId, request.toUserId)
        val decision = callInviteRateLimiter.tryAcquire(fromUserId, key)
        if (!decision.allowed) {
            return CallSignalingService.SendOutcome.Rejected(
                HttpStatusCode.TooManyRequests,
                "发起通话过于频繁，请稍后再试",
                "CALL_INVITE_RATE_LIMITED",
                decision.retryAfterSeconds,
            )
        }
    }

    if (isTerminal(request.type)) {
        signalingRepository.storeTerminalAndClearOthers(
            fromUserId, request.toUserId, request.type, request.payload,
            request.callId, request.groupId, request.groupMemberIds, request.groupInvite,
            request.epoch, request.sequence, request.idempotencyKey,
        )
        revokeTurnForCall(request.callId, fromUserId, request.toUserId)
    } else {
        signalingRepository.store(
            fromUserId, request.toUserId, request.type, request.payload,
            request.callId, request.groupId, request.groupMemberIds, request.groupInvite,
            request.epoch, request.sequence, request.idempotencyKey,
        )
    }
    return CallSignalingService.SendOutcome.Stored(request)
}

fun CallSignalingService.hangUp(request: SendSignalRequest, userId: String): CallSignalingService.SendOutcome {
    val rejected = validate(request, userId)
    if (rejected != null) return rejected
    val stale = rejectIfStale(request, userId)
    if (stale != null) return stale
    signalingRepository.storeTerminalAndClearOthers(
        userId, request.toUserId, "hang-up", request.payload,
        request.callId, request.groupId, request.groupMemberIds, request.groupInvite,
        request.epoch, request.sequence, request.idempotencyKey,
    )
    revokeTurnForCall(request.callId, userId, request.toUserId)
    return CallSignalingService.SendOutcome.Stored(request.copy(type = "hang-up"))
}

fun CallSignalingService.pending(userId: String, offersOnly: Boolean): List<SignalingMessage> =
    signalingRepository.consumeForUser(userId, offersOnly)

internal fun CallSignalingService.revokeTurnForCall(callId: String, vararg userIds: String) {
    val turn = turnCredentialService ?: return
    if (callId.isBlank()) return
    userIds.forEach { turn.revokeForCall(it, callId) }
}
