package com.maodouchat.domain.messaging

/** 定时发送控制器的文案键；app 侧映射到字符串资源。 */
enum class ScheduledMessageText {
    QUEUED_AT,
    CANCELLED,
    FAILED,
    SENT_NOW,
    CANCELLED_ALL,
    RESCHEDULED,
    DISABLED,
    SESSION_EXPIRED,
    BLOCKED,
    LIMIT_REACHED,
}
