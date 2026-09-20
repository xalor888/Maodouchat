package com.maodouchat.server.service

import com.maodouchat.server.model.SendSignalRequest
import com.maodouchat.server.common.CallSignalingValidators.isValidCallId
import com.maodouchat.server.common.CallSignalingValidators.isValidGroupSignalMetadata
import com.maodouchat.server.common.CallSignalingValidators.isValidSignalPayload
import com.maodouchat.server.repository.ConversationQueryRepository
import com.maodouchat.server.repository.SignalingRepository
import com.maodouchat.server.repository.UserRepository
import io.ktor.http.HttpStatusCode

/**
 * B09：通话信令领域门面。REST durable fallback 与 WS 共享同一校验、邀请限流与仓储，
 * 终端信令（hang-up/busy/reject）与同 callId 旧信令清理同事务；
 * epoch/sequence 准入由 [CallSignalingOrderPolicy] 判定；挂断撤销会话级 TURN。
 */
class CallSignalingService(
    private val signalingRepository: SignalingRepository,
    private val userRepository: UserRepository,
    private val conversationQueryRepository: ConversationQueryRepository,
    private val callInviteRateLimiter: CallInviteRateLimiter,
    private val turnCredentialService: TurnCredentialService? = null,
) {
    sealed interface SendOutcome {
        data class Stored(val request: SendSignalRequest) : SendOutcome
        data class Rejected(
            val status: HttpStatusCode,
            val message: String,
            val code: String? = null,
            val retryAfterSeconds: Long? = null,
        ) : SendOutcome
    }

    fun send(request: SendSignalRequest, fromUserId: String): SendOutcome {
        val rejected = validate(request, fromUserId)
        if (rejected != null) return rejected
        val stale = rejectIfStale(request, fromUserId)
        if (stale != null) return stale

        if (CallInviteRateLimiter.isInitialInvite(request.type, request.groupId, request.groupInvite)) {
            val key = CallInviteRateLimiter.sessionKey(request.callId, request.groupId, request.toUserId)
            val decision = callInviteRateLimiter.tryAcquire(fromUserId, key)
            if (!decision.allowed) {
                return SendOutcome.Rejected(
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
        return SendOutcome.Stored(request)
    }

    fun hangUp(request: SendSignalRequest, userId: String): SendOutcome {
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
        return SendOutcome.Stored(request.copy(type = "hang-up"))
    }

    fun pending(userId: String, offersOnly: Boolean): List<SignalingRepository.SignalingMessage> =
        signalingRepository.consumeForUser(userId, offersOnly)

    private fun rejectIfStale(request: SendSignalRequest, fromUserId: String): SendOutcome.Rejected? {
        val last = signalingRepository.latestCursor(request.callId, fromUserId)
        return when (
            CallSignalingOrderPolicy.admit(
                CallSignalingOrderPolicy.Cursor(request.epoch, request.sequence),
                last,
            )
        ) {
            CallSignalingOrderPolicy.Admit.Accept -> null
            CallSignalingOrderPolicy.Admit.RejectStale -> SendOutcome.Rejected(
                HttpStatusCode.Conflict,
                "信令顺序已过期",
                "CALL_SIGNAL_STALE",
            )
        }
    }

    private fun revokeTurnForCall(callId: String, vararg userIds: String) {
        val turn = turnCredentialService ?: return
        if (callId.isBlank()) return
        userIds.forEach { turn.revokeForCall(it, callId) }
    }

    private fun validate(request: SendSignalRequest, fromUserId: String): SendOutcome.Rejected? {
        if (!isValidSignalPayload(request.type, request.payload) || !isValidCallId(request.callId)) {
            return SendOutcome.Rejected(HttpStatusCode.BadRequest, "信令内容无效")
        }
        if (request.toUserId == fromUserId) {
            return SendOutcome.Rejected(HttpStatusCode.BadRequest, "不能向自己发送信令")
        }
        if (userRepository.getById(request.toUserId) == null) {
            return SendOutcome.Rejected(HttpStatusCode.NotFound, "信令目标用户不存在")
        }
        if (!isValidGroupSignalMetadata(
                request.groupId, request.groupMemberIds, request.groupInvite, request.callId,
                fromUserId, request.toUserId, conversationQueryRepository,
            )
        ) {
            return SendOutcome.Rejected(HttpStatusCode.BadRequest, "群通话元数据无效")
        }
        if (userRepository.isBlockedEitherWay(fromUserId, request.toUserId)) {
            return SendOutcome.Rejected(HttpStatusCode.Forbidden, "无法与已屏蔽的用户发送通话信令")
        }
        if (request.groupId.isBlank() && !conversationQueryRepository.shareConversation(fromUserId, request.toUserId)) {
            return SendOutcome.Rejected(HttpStatusCode.Forbidden, "双方无共同会话，无法发送通话信令")
        }
        return null
    }

    private fun isTerminal(type: String): Boolean = type.lowercase() in TERMINAL_SIGNAL_TYPES

    companion object {
        val TERMINAL_SIGNAL_TYPES = setOf("hang-up", "busy", "reject")
    }
}
