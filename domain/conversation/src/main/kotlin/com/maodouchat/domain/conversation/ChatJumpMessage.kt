package com.maodouchat.domain.conversation

/**
 * 会话内跳转定位向 UI 写入的文案种类。domain 侧只定种类，真正的本地化字符串由 app 侧
 * chatJumpMessageResId 映射提供——domain 模块不引用 app 的 R。
 */
enum class ChatJumpMessage {
    DATE_EMPTY,
}
