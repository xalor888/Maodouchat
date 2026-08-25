package com.maodouchat.data.repository

import com.maodouchat.crypto.SignalExchangeException
import com.maodouchat.crypto.SignalExchangeFailure
import com.maodouchat.crypto.LocalCryptoNotReadyException
import com.maodouchat.crypto.SignalStorePersistenceException
import com.maodouchat.network.ApiException
import com.maodouchat.network.ApiFailureKind

/**
 * 9.4xx：outbox 冲刷判定策略（自 UI 层 MessageMutationPolicy 迁移至 data 层）。
 *
 * 此前 TextOutboxFlusher（data 层）反向 import ui.screen.chatdetail 的这两个函数，
 * 形成 data→UI 循环依赖（Clean Architecture 违规）。两个函数都是纯业务判定，
 * 不依赖任何 UI 类型，归属 data 层。
 */

/**
 * Outbox flush: definitive business rejections must leave SENDING → FAILED so the user
 * can see the failure and retry. Transport ambiguity keeps SENDING for a later flush.
 */
fun shouldMarkOutboxFailed(error: Throwable?): Boolean {
    if (error == null) return false
    // 8.41：SenderKey 覆盖的瞬态网络失败（超时/断网）保持 SENDING 待 flusher 重试，
    // 不得标 FAILED——否则群消息在 SK 分发阶段的弱网失败永不自动恢复
    if (error is com.maodouchat.crypto.TransientCoverageException) return false
    if (error is LocalCryptoNotReadyException) return false
    // The in-memory ratchet is deliberately quarantined after a Room write failure.
    // Reinitialization reloads the last durable state, so keep the plaintext outbox pending.
    if (error is SignalStorePersistenceException) return false
    if (error is SignalExchangeException) {
        return when (error.failure) {
            SignalExchangeFailure.NETWORK,
            SignalExchangeFailure.TIMEOUT -> false
            SignalExchangeFailure.HTTP -> isDefinitiveHttpReject(error.statusCode)
            SignalExchangeFailure.SIGNED_PREKEY_MISSING,
            SignalExchangeFailure.EMPTY_RESPONSE,
            SignalExchangeFailure.INVALID_RESPONSE,
            SignalExchangeFailure.UNEXPECTED -> true
        }
    }
    if (error !is ApiException) return true
    return when (error.kind) {
        ApiFailureKind.HTTP -> isDefinitiveHttpReject(error.statusCode)
        ApiFailureKind.INVALID_RESPONSE,
        ApiFailureKind.UNEXPECTED -> true
        ApiFailureKind.NETWORK,
        ApiFailureKind.TIMEOUT -> false
    }
}

private fun isDefinitiveHttpReject(statusCode: Int?): Boolean {
    val code = statusCode ?: return true
    // A 409 is a definitive business conflict unless the caller has an explicit, stable
    // idempotency-accepted code. The text flusher handles that narrow exception before this
    // policy is reached; never infer delivery from the HTTP status or localized message alone.
    // Timeout, throttling and 5xx remain pending because transport/key-exchange failures can
    // recover without user intervention.
    return code in 400..499 && code != 408 && code != 429
}

/**
 * Resolve the 1:1 encrypt peer for an outbox row.
 *
 * Prefer the already-loaded active contact when flushing the open chat, then fall back to
 * chat participants (including stub users that only carry an id). Returns null only when no
 * peer id can be recovered — caller should mark FAILED rather than silent-skip forever.
 */
fun resolveDirectOutboxPeerId(
    chatId: String,
    activeChatId: String,
    activeContactId: String?,
    selfUserId: String,
    chatParticipants: List<com.maodouchat.data.model.User>?
): String? {
    val active = activeContactId?.takeIf { it.isNotBlank() && it != selfUserId }
    if (chatId == activeChatId && active != null) return active
    return chatParticipants
        ?.asSequence()
        ?.map { it.id }
        ?.firstOrNull { it.isNotBlank() && it != selfUserId }
}
