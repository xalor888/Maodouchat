package com.maodouchat.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Entity(
    tableName = "ai_operations",
    indices = [
        Index("ownerUserId"),
        Index("chatId"),
        Index("state"),
        Index("updatedAt"),
        Index(value = ["ownerUserId", "chatId", "state"])
    ]
)
data class AiOperationEntity(
    @PrimaryKey
    val id: String,
    val ownerUserId: String,
    val chatId: String,
    val type: String,
    val targetMessageId: String? = null,
    val parametersJson: String,
    val state: String = AiOperationState.QUEUED,
    val attempts: Int = 0,
    val lastErrorCode: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Serializable
data class AiOperationParameters(
    val targetLanguage: String? = null,
    val summaryScope: String? = null,
    val messageIds: List<String> = emptyList(),
    val analysisMode: String? = null,
    val question: String? = null
)

object AiOperationType {
    const val TRANSCRIBE_VOICE = "TRANSCRIBE_VOICE"
    const val TRANSLATE_MESSAGE = "TRANSLATE_MESSAGE"
    const val SUMMARIZE_MESSAGES = "SUMMARIZE_MESSAGES"
    const val ANALYZE_IMAGE = "ANALYZE_IMAGE"
    const val ANALYZE_FILE = "ANALYZE_FILE"
}

object AiOperationState {
    const val QUEUED = "QUEUED"
    const val RUNNING = "RUNNING"
    const val SUCCEEDED = "SUCCEEDED"
    const val FAILED = "FAILED"
    const val CANCELLED = "CANCELLED"
}

object AiOperationError {
    const val INTERRUPTED = "INTERRUPTED"
    const val CONTEXT_MISSING = "CONTEXT_MISSING"
    const val EMPTY_RESULT = "EMPTY_RESULT"
    const val NETWORK = "NETWORK"
    const val CONNECTION_NOT_ESTABLISHED = "CONNECTION_NOT_ESTABLISHED"
    const val OUTCOME_UNKNOWN = "OUTCOME_UNKNOWN"
    const val TIMEOUT = "TIMEOUT"
    const val INVALID_RESPONSE = "INVALID_RESPONSE"
    const val SERVER = "SERVER"
    /** 服务端 / 本地限流；不自动重试，提示等待秒数 */
    const val RATE_LIMITED = "RATE_LIMITED"
    /** 配额 / 预算耗尽；不自动重试 */
    const val QUOTA_EXCEEDED = "QUOTA_EXCEEDED"
    const val UNKNOWN = "UNKNOWN"
}
