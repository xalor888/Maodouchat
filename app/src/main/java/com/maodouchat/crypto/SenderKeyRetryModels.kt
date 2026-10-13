package com.maodouchat.crypto

/**
 * 群 SenderKey 覆盖分发时的瞬态网络失败标记（8.41）。
 * 与 ApiException.NETWORK/TIMEOUT 同语义：调用方应保留 durable v2 outbox 项等待重试。
 */
class TransientCoverageException(
    message: String,
    cause: Throwable? = null
) : java.io.IOException(message, cause)

/** Sender Key distribution is durable locally but has not committed to the server mailbox yet. */
class SenderKeyCoveragePendingException(
    val chatId: String,
    val epoch: Long,
) : IllegalStateException("sender_key_coverage_pending")

/**
 * 聊天级 SenderKey 重试状态，用于 UI 显示。
 */
data class SenderKeyRetryView(
    val chatId: String,
    val pendingCount: Int,
    val attempts: Int,
    val lastError: String?,
    val epoch: Long
)
