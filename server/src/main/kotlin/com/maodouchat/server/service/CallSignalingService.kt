package com.maodouchat.server.service

import com.maodouchat.server.repository.ConversationQueryRepository
import com.maodouchat.server.repository.SignalingRepository
import com.maodouchat.server.repository.UserRepository

/**
 * B09：通话信令领域门面。REST durable fallback 与 WS 共享同一校验、邀请限流与仓储，
 * 终端信令（hang-up/busy/reject）与同 callId 旧信令清理同事务；
 * epoch/sequence 准入由 [CallSignalingOrderPolicy] 判定；挂断撤销会话级 TURN。
 *
 * 实现已按域拆出：[CallSignalSend.kt]（发送/挂断/待取）、[CallSignalValidation.kt]（校验与顺序准入）。
 */
class CallSignalingService(
    internal val signalingRepository: SignalingRepository,
    internal val userRepository: UserRepository,
    internal val conversationQueryRepository: ConversationQueryRepository,
    internal val callInviteRateLimiter: CallInviteRateLimiter,
    internal val turnCredentialService: TurnCredentialService? = null,
) {
    companion object {
        val TERMINAL_SIGNAL_TYPES = setOf("hang-up", "busy", "reject")
    }
}
