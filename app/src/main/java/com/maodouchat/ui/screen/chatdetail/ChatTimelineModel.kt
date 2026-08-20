package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageType

internal sealed interface ChatItem {
    data class DateSeparator(val label: String, val dateKey: String) : ChatItem
    data class Msg(val message: Message, val showAvatar: Boolean) : ChatItem
    /** 1.03：进入聊天时存在未读 → 在未读起点插入「以下为未读消息」分隔线。 */
    data class UnreadSeparator(val messageId: String) : ChatItem
}

/** Sender-key distribution is crypto control traffic — keep Room, hide from conversation UI. */
internal fun isTimelineVisibleMessage(message: Message): Boolean =
    message.type != MessageType.SK_DIST

/** Builds the virtualized timeline in O(n) when the repository list is already chronological. */
internal fun buildChatItems(
    messages: List<Message>,
    labelForTimestamp: (Long) -> String,
    unreadSeparatorId: String? = null
): List<ChatItem> {
    // Filter first so SK_DIST never creates empty date separators or avatar breaks.
    val visible = messages.filter(::isTimelineVisibleMessage)
    if (visible.isEmpty()) return emptyList()
    var chronological = true
    for (index in 1 until visible.size) {
        val first = visible[index - 1]
        val second = visible[index]
        if (first.timestamp > second.timestamp || (first.timestamp == second.timestamp && first.id > second.id)) {
            chronological = false
            break
        }
    }
    val sorted = if (chronological) visible else visible.sortedWith(compareBy<Message> { it.timestamp }.thenBy { it.id })

    val result = ArrayList<ChatItem>(sorted.size + 8)
    var previousDateLabel: String? = null
    // 1.03：未读分隔线只插一次——放在未读起点消息之前
    var unreadPlaced = unreadSeparatorId == null
    // 9.265：TG 式头像规则——头像显示在组的最后一条消息（气泡底对齐），
    // 而非旧的首条显示；单条消息组首=尾不受影响
    sorted.forEachIndexed { i, message ->
        val dateLabel = labelForTimestamp(message.timestamp)
        val startsNewDate = dateLabel != previousDateLabel
        if (startsNewDate) result += ChatItem.DateSeparator(dateLabel, (message.timestamp / 86_400_000L).toString())
        if (!unreadPlaced && message.id == unreadSeparatorId) {
            result += ChatItem.UnreadSeparator(message.id)
            unreadPlaced = true
        }
        val next = sorted.getOrNull(i + 1)
        val endsGroup = next == null ||
            next.senderId != message.senderId ||
            labelForTimestamp(next.timestamp) != dateLabel
        result += ChatItem.Msg(
            message = message,
            showAvatar = endsGroup
        )
        previousDateLabel = dateLabel
    }
    // 未读起点消息不在当前窗口（早于已加载）时，放在最旧消息之前兜底
    if (!unreadPlaced && sorted.isNotEmpty()) {
        result.add(1, ChatItem.UnreadSeparator(sorted.first().id))
    }
    return result
}
