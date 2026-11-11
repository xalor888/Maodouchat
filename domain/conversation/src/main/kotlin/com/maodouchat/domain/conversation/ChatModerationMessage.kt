package com.maodouchat.domain.conversation

/**
 * 拉黑/举报向 UI 写入的文案种类。domain 侧只定种类，真正的本地化字符串由 app 侧
 * chatModerationMessageResId 映射提供——domain 模块不引用 app 的 R。
 */
enum class ChatModerationMessage {
    FEATURE_DISABLED_BY_ADMIN,
    SESSION_EXPIRED,
    CHAT_BLOCKED_USER_STATUS,
    CHAT_BLOCK_FAILED,
    CHAT_UNBLOCKED_USER_STATUS,
    CHAT_UNBLOCK_FAILED,
    CHAT_REPORT_SUBMITTED,
    CHAT_REPORT_FAILED,
}
