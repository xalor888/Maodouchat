package com.maodouchat.domain.conversation

/**
 * 名片发送向 UI 写入的文案种类。domain 侧只定种类，真正的本地化字符串由 app 侧
 * chatContactCardMessageResId 映射提供——domain 模块不引用 app 的 R。
 */
enum class ChatContactCardMessage {
    CONTACT_CARD_DISABLED,
    CONTACT_CARD_SECRET_BLOCKED,
}
