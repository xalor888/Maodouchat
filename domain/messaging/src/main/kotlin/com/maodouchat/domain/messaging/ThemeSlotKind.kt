package com.maodouchat.domain.messaging

// 主题编辑器颜色槽位 id → 种类；与 ThemeFileColorPolicy.SLOTS 同词汇表。
// "window_background" 与未知 id 均归为页面背景（原 else 分支语义）。
enum class ThemeSlotKind {
    ACCENT, CHAT_BACKGROUND, CHAT_IN_BUBBLE, CHAT_IN_TEXT, CHAT_OUT_BUBBLE,
    CHAT_OUT_TEXT, TEXT_PRIMARY, INPUT_BACKGROUND, UNREAD_BADGE, SYSTEM_MESSAGE,
    WINDOW_BACKGROUND
}

fun themeSlotKind(slot: String): ThemeSlotKind = when (slot) {
    "accent" -> ThemeSlotKind.ACCENT
    "chat_background" -> ThemeSlotKind.CHAT_BACKGROUND
    "chat_inBubble" -> ThemeSlotKind.CHAT_IN_BUBBLE
    "chat_inText" -> ThemeSlotKind.CHAT_IN_TEXT
    "chat_outBubble" -> ThemeSlotKind.CHAT_OUT_BUBBLE
    "chat_outText" -> ThemeSlotKind.CHAT_OUT_TEXT
    "text_primary" -> ThemeSlotKind.TEXT_PRIMARY
    "input_background" -> ThemeSlotKind.INPUT_BACKGROUND
    "unread_badge" -> ThemeSlotKind.UNREAD_BADGE
    "system_message" -> ThemeSlotKind.SYSTEM_MESSAGE
    else -> ThemeSlotKind.WINDOW_BACKGROUND
}
