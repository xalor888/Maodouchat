package com.maodouchat.domain.conversation

/**
 * 消息「稍后提醒」向 UI 写入的文案种类。domain 侧只定种类，真正的本地化字符串由 app 侧
 * chatReminderMessageResId 映射提供——domain 模块不引用 app 的 R。
 */
enum class ChatReminderMessage {
    REMINDER_SCHEDULED,
    SESSION_EXPIRED,
    SCHEDULE_FAILED,
}
