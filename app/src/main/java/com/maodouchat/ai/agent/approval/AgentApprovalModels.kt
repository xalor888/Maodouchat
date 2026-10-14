package com.maodouchat.ai.agent.approval

import java.util.UUID

enum class ApprovalStatus {
    PENDING,
    APPROVED,
    REJECTED,
    CANCELLED
}

data class AgentApprovalRecord(
    val approvalId: String = UUID.randomUUID().toString(),
    val toolName: String,
    val idempotencyKey: String = UUID.randomUUID().toString(),
    val argumentsHash: String,
    val createdAt: Long = System.currentTimeMillis(),
    val status: ApprovalStatus = ApprovalStatus.PENDING
) {
    fun isApproved(): Boolean = status == ApprovalStatus.APPROVED
    fun isCancelledOrRejected(): Boolean =
        status == ApprovalStatus.CANCELLED || status == ApprovalStatus.REJECTED
}