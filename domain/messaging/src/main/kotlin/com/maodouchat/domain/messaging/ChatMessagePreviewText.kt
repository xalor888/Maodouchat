package com.maodouchat.domain.messaging

// 消息预览控制器向 UI 写入的文案种类。domain 侧只定种类，真正的本地化字符串由 app 侧
// chatMessagePreviewTextResId 映射提供——domain 模块不引用 app 的 R。
enum class ChatMessagePreviewText {
    NUDGE_YOU_NUDGED,
    NUDGE_THEY_NUDGED_YOU,
    NUDGE_THEY_NUDGED_TARGET,
    PREVIEW_ENCRYPTED,
    PREVIEW_IMAGE,
    PREVIEW_GIF,
    PREVIEW_STICKER,
    PREVIEW_LOCATION,
    PREVIEW_VOICE,
    PREVIEW_VIDEO,
    PREVIEW_FILE,
    MESSAGE_REVOKED_PLACEHOLDER,
}
