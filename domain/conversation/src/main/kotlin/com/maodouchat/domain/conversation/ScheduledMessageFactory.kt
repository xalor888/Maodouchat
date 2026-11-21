package com.maodouchat.domain.conversation

import com.maodouchat.core.model.ScheduledMessage
import com.maodouchat.core.model.ScheduledMessagePolicy
import com.maodouchat.core.model.ScheduledMessageRequest

// 定时消息构造：校验与组装纯逻辑，DAO 读写与任务调度仍在 app 侧 backend。
object ScheduledMessageFactory {
    fun build(
        request: ScheduledMessageRequest,
        ownerUserId: String,
        pendingCount: () -> Int,
        nowMs: Long,
        idSuffix: String,
        timeZoneId: String,
        idempotencyKey: String,
    ): ScheduledMessage? {
        val userId = ownerUserId.trim()
        if (userId.isBlank() || request.chatId.isBlank()) return null
        val normalized = ScheduledMessagePolicy.normalizeText(request.text)
        if (!ScheduledMessagePolicy.isValidText(normalized)) return null
        if (!ScheduledMessagePolicy.canAddMore(pendingCount())) return null
        // id 前缀 sch_ 是承重格式：ConversationScheduledMessageDispatcher 由它派生发送消息 id。
        return ScheduledMessage(
            id = "sch_$idSuffix",
            chatId = request.chatId,
            peerUserId = request.peerUserId,
            text = normalized,
            sendAtMillis = ScheduledMessagePolicy.clampSendAt(request.sendAtMillis, nowMs),
            createdAtMillis = nowMs,
            isGroup = request.isGroup,
            ownerUserId = userId,
            repeatIntervalMs = request.repeatIntervalMs.coerceAtLeast(0L),
            repeatCount = request.repeatCount.coerceAtLeast(0),
            occurrencesSent = 0,
            weekdaysOnly = request.weekdaysOnly,
            status = "PENDING",
            attempt = 0,
            timeZoneId = timeZoneId,
            idempotencyKey = idempotencyKey,
        )
    }

    fun applyUpdate(
        existing: ScheduledMessage,
        text: String?,
        sendAtMillis: Long?,
        nowMs: Long,
        timeZoneId: String,
    ): ScheduledMessage? {
        if (existing.ownerUserId.isBlank() || existing.id.isBlank()) return null
        val nextText = text?.let { ScheduledMessagePolicy.normalizeText(it) } ?: existing.text
        if (!ScheduledMessagePolicy.isValidText(nextText)) return null
        val nextSendAt = sendAtMillis?.let { ScheduledMessagePolicy.clampSendAt(it, nowMs) } ?: existing.sendAtMillis
        val nextTimeZoneId = existing.timeZoneId.ifBlank { timeZoneId }
        return existing.copy(text = nextText, sendAtMillis = nextSendAt, timeZoneId = nextTimeZoneId)
    }
}
