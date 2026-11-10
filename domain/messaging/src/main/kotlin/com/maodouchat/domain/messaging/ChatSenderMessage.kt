package com.maodouchat.domain.messaging

/**
 * 戳一戳与失败重发向 UI 写入的文案种类。domain 侧只定种类，真正的本地化字符串由 app 侧
 * chatSenderMessageResId 映射提供——domain 模块不引用 app 的 R。
 */
enum class ChatSenderMessage {
    SECRET_CHAT_FORWARD_BLOCKED,
    NUDGE_DISABLED,
    CHAT_WS_SEND_FAILED,
    ERROR_SESSION_EXPIRED,
    CHAT_OTHER_PERSON,
    CHAT_NUDGE_YOU_NUDGED,
    CHAT_SEND_FAILED,
}
