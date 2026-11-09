package com.maodouchat.domain.messaging

/** 播放回执的目标消息解析：目标即现有消息时直接用它，否则需查库。 */
object PlayReceiptTarget {
    enum class Decision { EXISTING, LOOKUP }

    fun decide(existingId: String, targetMessageId: String): Decision =
        if (existingId == targetMessageId) Decision.EXISTING else Decision.LOOKUP
}
