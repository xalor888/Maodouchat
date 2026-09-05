package com.maodouchat.ai.agent.approval

import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

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

/**
 * Manages approval records and idempotency keys to guarantee that:
 * 1. Write operations carry an approval record and idempotency key.
 * 2. Duplicate executions with the same idempotency key are rejected.
 * 3. Cancelled or rejected approvals block execution immediately.
 */
class AgentApprovalCoordinator {
    private val records = ConcurrentHashMap<String, AgentApprovalRecord>()
    private val executedIdempotencyKeys = ConcurrentHashMap.newKeySet<String>()

    fun createPendingApproval(toolName: String, args: Map<String, String>): AgentApprovalRecord {
        val hash = hashArguments(args)
        val record = AgentApprovalRecord(
            toolName = toolName,
            argumentsHash = hash,
            status = ApprovalStatus.PENDING
        )
        records[record.approvalId] = record
        return record
    }

    fun approve(approvalId: String): AgentApprovalRecord? {
        val record = records[approvalId] ?: return null
        val approved = record.copy(status = ApprovalStatus.APPROVED)
        records[approvalId] = approved
        return approved
    }

    fun reject(approvalId: String): AgentApprovalRecord? {
        val record = records[approvalId] ?: return null
        val rejected = record.copy(status = ApprovalStatus.REJECTED)
        records[approvalId] = rejected
        return rejected
    }

    fun cancel(approvalId: String): AgentApprovalRecord? {
        val record = records[approvalId] ?: return null
        val cancelled = record.copy(status = ApprovalStatus.CANCELLED)
        records[approvalId] = cancelled
        return cancelled
    }

    fun getRecord(approvalId: String): AgentApprovalRecord? = records[approvalId]

    /**
     * Checks if this idempotency key has already been executed.
     */
    fun isExecuted(idempotencyKey: String): Boolean = executedIdempotencyKeys.contains(idempotencyKey)

    /**
     * Marks an idempotency key as executed. Returns false if it was already marked.
     */
    fun markExecuted(idempotencyKey: String): Boolean = executedIdempotencyKeys.add(idempotencyKey)

    fun clear() {
        records.clear()
        executedIdempotencyKeys.clear()
    }

    private fun hashArguments(args: Map<String, String>): String {
        val sorted = args.entries.sortedBy { it.key }.joinToString("&") { "${it.key}=${it.value}" }
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(sorted.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
