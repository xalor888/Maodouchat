package com.maodouchat.data.repository

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
    if (error !is ApiException) return true
    return when (error.kind) {
        ApiFailureKind.HTTP -> {
            val code = error.statusCode ?: return true
            // 409 duplicate id is treated as success by flush; remaining 4xx are business rejects.
            // 408/5xx stay SENDING for a later flush.
            // 8.45：429 限流保持 SENDING 交给 flusher 退避重试——与 delete/revoke 的
            // isAmbiguousTransportFailure（429 视为请求未应用的模糊失败）口径一致；
            // 否则用户在 60/min 限流下频繁看到发送失败并手动重试（重试又 429）。
            code in 400..499 && code != 408 && code != 409 && code != 429
        }
        ApiFailureKind.INVALID_RESPONSE,
        ApiFailureKind.UNEXPECTED -> true
        ApiFailureKind.NETWORK,
        ApiFailureKind.TIMEOUT -> false
    }
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
