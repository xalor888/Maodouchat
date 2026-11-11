package com.maodouchat.domain.conversation

/**
 * 群通话入口向 UI 写入的文案种类。domain 侧只定种类，真正的本地化字符串由 app 侧
 * chatGroupCallMessageResId 映射提供——domain 模块不引用 app 的 R。
 */
enum class ChatGroupCallMessage {
    CALLS_DISABLED,
    VIDEO_CALL_DISABLED,
    VOICE_CALL_DISABLED,
    GROUP_CALL_EMPTY,
}
