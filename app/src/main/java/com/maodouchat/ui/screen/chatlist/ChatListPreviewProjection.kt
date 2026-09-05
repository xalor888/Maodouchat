package com.maodouchat.ui.screen.chatlist

import com.maodouchat.data.model.Chat
import com.maodouchat.data.model.MessageType

/**
 * 会话列表预览投影纯函数（ChatList 瘦身第 4 切片）。
 *
 * 自 `ChatListViewModel.applyChatListPreview / restoreChatSorted` 抽出的内存
 * 投影：单调时间戳、未读增量钳制、置顶优先重排。Room 持久化与会话门禁仍在
 * ViewModel（编排层）。排序口径与 `filteredChats` 主排序一致：
 * 置顶组优先 → 组内按 pinnedAt → 再按最后活跃。
 */
val chatListPinnedFirstOrder: Comparator<Chat> =
    compareByDescending<Chat> { it.pinnedAt > 0 }
        .thenByDescending { it.pinnedAt }
        .thenByDescending { it.lastMessageTime }

fun restoreChatSorted(existing: List<Chat>, chat: Chat): List<Chat> =
    (existing + chat).sortedWith(chatListPinnedFirstOrder)

/**
 * 预览内存投影（[ChatListViewModel.applyChatListPreview] 的内存半区逐字搬运）：
 * - 找不到会话时原样返回；
 * - 默认单调 max，迟到事件不得回拨排序；`forceTimestamp`（删除/撤回/空尾）
 *   按给定时间绝对写；
 * - 未读增量下限钳 0；
 * - 新消息把未置顶会话顶到"未置顶区"最前，不越过置顶会话。
 */
fun applyPreviewToChats(
    chats: List<Chat>,
    chatId: String,
    previewText: String,
    messageType: MessageType,
    timestamp: Long,
    unreadDelta: Int = 0,
    forceTimestamp: Boolean = false,
): List<Chat> {
    val target = chats.find { it.id == chatId } ?: return chats
    val others = chats.filterNot { it.id == chatId }
    val nextTime = if (forceTimestamp) timestamp else maxOf(target.lastMessageTime, timestamp)
    val updatedTarget = target.copy(
        lastMessage = previewText,
        lastMessageType = messageType,
        lastMessageTime = nextTime,
        unreadCount = (target.unreadCount + unreadDelta).coerceAtLeast(0)
    )
    return (others + updatedTarget).sortedWith(chatListPinnedFirstOrder)
}
