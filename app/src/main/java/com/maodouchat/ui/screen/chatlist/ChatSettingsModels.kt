package com.maodouchat.ui.screen.chatlist

/**
 * 会话设置变更纯构造（ChatList 瘦身第 2 切片）。
 *
 * 自 `ChatListViewModel` 抽出的乐观设置 toggle 逻辑：四个 toggle（置顶/静音/
 * 归档/标未读）各自的乐观投影变换与服务端请求构造。ViewModel 只保留
 * RuntimeFlags 门控、会话查找与 `updateChatSettings` 编排。
 *
 * 与旧实现逐字对齐的细节：
 * - 归档同时取消置顶（乐观 `pinnedAt = 0`，请求 `pinned = false`）；
 *   取消归档则保留置顶（请求 `pinned = null`，服务端不碰置顶）；
 * - 乐观时钟 `settingsUpdatedAt = max(now, old + 1)`，保证合并时乐观值获胜。
 */
enum class ChatSettingsToggle {
    PIN,
    MUTE,
    ARCHIVE,
    MARKED_UNREAD,
}
