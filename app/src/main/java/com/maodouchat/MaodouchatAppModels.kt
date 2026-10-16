package com.maodouchat

import com.maodouchat.data.model.Message

data class AttachmentFinalizedEvent(
    val message: Message,
    val sessionGeneration: Long = MaodouchatApp.currentSessionGeneration(),
)

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
