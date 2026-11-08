package com.maodouchat.core.model

/**
 * 会话作用域事件：携带发射时 session 世代，收集器丢弃过期事件（登出/换号后送达）。
 */
interface SessionScopedEvent {
    val sessionGeneration: Long
}

/**
 * Tap from FCM/system call notification → wake IncomingCallObserver to poll
 * pending offers (SDP lives server-side; FCM only carries callId/sender).
 */
data class IncomingCallWake(
    val callId: String = "",
    val senderId: String = "",
    val isVideo: Boolean = false,
    val atMillis: Long = System.currentTimeMillis(),
    override val sessionGeneration: Long = 0L,
    val requestId: Long = 0L,
    /** 8.56：系统 Telecom 来电 UI 点击「接听」后为 true——IncomingCallRoute 直接自动接听。 */
    val autoAnswer: Boolean = false,
) : SessionScopedEvent

/**
 * Tap on a missed-call system notification → main chats tab + inbox so the
 * missed-call card is visible (EXTRA_OPEN_MISSED_CALL was previously unused).
 */
data class OpenMissedCallsRequest(
    val atMillis: Long,
    override val sessionGeneration: Long,
    val requestId: Long,
) : SessionScopedEvent

/**
 * Notification-center / tray deep-link → main contacts tab
 * (friend requests, contact-related center rows).
 */
data class OpenContactsRequest(
    val atMillis: Long,
    override val sessionGeneration: Long,
    val requestId: Long,
) : SessionScopedEvent

data class AttachmentFinalizedEvent(
    val message: Message,
    val sessionGeneration: Long,
)
