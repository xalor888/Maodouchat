package com.maodouchat.notification

/**
 * Pure merge helpers for [com.maodouchat.data.repository.NotificationCenterRepository.add].
 * FCM data-only + in-app WS can both emit the same NEW_MESSAGE; count must not double.
 * Ring-timeout + peer hang-up can both emit the same MISSED_CALL; same for callId.
 */
object NotificationCenterMergePolicy {

    fun isSameMessagePayload(
        existingId: String,
        existingMessageId: String?,
        incomingId: String,
        incomingMessageId: String?
    ): Boolean {
        val incoming = incomingMessageId?.takeIf(String::isNotBlank)
        if (incoming != null && existingMessageId == incoming) return true
        if (incomingId.isNotBlank() && existingId == incomingId) return true
        return false
    }

    /**
     * Same missed-call identity: identical item id (`missed_{callId}`) or same extra callId.
     * Distinct calls from one peer still share mergeKey and should increment count.
     */
    fun isSameMissedCallPayload(
        existingId: String,
        existingCallId: String?,
        incomingId: String,
        incomingCallId: String?
    ): Boolean {
        if (incomingId.isNotBlank() && existingId == incomingId) return true
        val incoming = incomingCallId?.takeIf(String::isNotBlank) ?: return false
        return existingCallId == incoming
    }

    fun isSameStableExtra(existingValue: String?, incomingValue: String?): Boolean {
        val incoming = incomingValue?.takeIf(String::isNotBlank) ?: return false
        return existingValue == incoming
    }

    /**
     * Whether merging under the same mergeKey should leave [count] unchanged.
     * Messages use messageId, missed calls use callId, friend requests use requestId,
     * and AI reminders use taskId. Other types always increment.
     */
    fun shouldSkipCountIncrement(
        itemType: String,
        existingId: String,
        existingExtra: Map<String, String>,
        incomingId: String,
        incomingExtra: Map<String, String>,
    ): Boolean {
        return when (itemType) {
            "MESSAGE" -> isSameMessagePayload(
                existingId = existingId,
                existingMessageId = existingExtra["messageId"],
                incomingId = incomingId,
                incomingMessageId = incomingExtra["messageId"],
            )
            "MISSED_CALL" -> isSameMissedCallPayload(
                existingId = existingId,
                existingCallId = existingExtra["callId"],
                incomingId = incomingId,
                incomingCallId = incomingExtra["callId"],
            )
            "FRIEND_REQUEST" -> isSameStableExtra(
                existingValue = existingExtra["requestId"],
                incomingValue = incomingExtra["requestId"],
            )
            "AI_TASK" -> isSameStableExtra(
                existingValue = existingExtra["taskId"],
                incomingValue = incomingExtra["taskId"],
            )
            else -> false
        }
    }
}
