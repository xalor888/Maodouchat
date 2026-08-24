package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageType

internal sealed interface ChatItem {
    /** LazyColumn key — prefixes keep date / unread / message ids from colliding. */
    val listKey: String

    data class DateSeparator(val label: String, val dateKey: String) : ChatItem {
        override val listKey: String get() = "date_$dateKey"
    }

    data class Msg(
        val message: Message,
        val showAvatar: Boolean,
        val showSenderName: Boolean = false,
        val isGroupEdge: Boolean = true,
        val occurrence: Int = 0,
    ) : ChatItem {
        override val listKey: String get() {
            val id = message.id.ifBlank { "blank" }
            return if (occurrence == 0) "msg_$id" else "msg_${id}_$occurrence"
        }
    }

    /** 1.03：进入聊天时存在未读 → 在未读起点插入「以下为未读消息」分隔线。 */
    data class UnreadSeparator(val messageId: String) : ChatItem {
        override val listKey: String get() = "unread_${messageId.ifBlank { "unknown" }}"
    }
}

/** Sender-key distribution is crypto control traffic — keep Room, hide from conversation UI. */
internal fun isTimelineVisibleMessage(message: Message): Boolean =
    message.type != MessageType.SK_DIST

/** Same visual grouping as ChatDetailScreen: same sender, same day, within 6 minutes. */
internal const val SENDER_GROUP_WINDOW_MS = 6L * 60L * 1000L

internal fun sameSenderGroup(
    first: Message,
    second: Message,
    firstDateLabel: String,
    secondDateLabel: String,
    windowMs: Long = SENDER_GROUP_WINDOW_MS,
): Boolean =
    first.senderId == second.senderId &&
        firstDateLabel == secondDateLabel &&
        kotlin.math.abs(second.timestamp - first.timestamp) < windowMs

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
    // Duplicate ids (WS retry / local echo merge) crash LazyColumn keys and double-draw bubbles.
    val deduped = ArrayList<Message>(sorted.size)
    val seenIds = HashSet<String>(sorted.size)
    for (message in sorted) {
        val id = message.id
        if (id.isBlank()) {
            deduped += message
        } else if (seenIds.add(id)) {
            deduped += message
        }
    }
    if (deduped.isEmpty()) return emptyList()

    val result = ArrayList<ChatItem>(deduped.size + 8)
    var previousDateLabel: String? = null
    var blankOccurrence = 0
    // 1.03：未读分隔线只插一次——放在未读起点消息之前
    var unreadPlaced = unreadSeparatorId.isNullOrBlank()
    val dateLabels = Array(deduped.size) { labelForTimestamp(deduped[it].timestamp) }
    // 头像和发送者名挂组首同一条消息；气泡小尾巴仍在组尾。超过 6 分钟另起一组。
    deduped.forEachIndexed { i, message ->
        val dateLabel = dateLabels[i]
        val startsNewDate = dateLabel != previousDateLabel
        if (startsNewDate) {
            // Index + label: UTC-day keys collided when local calendar labels differed.
            result += ChatItem.DateSeparator(dateLabel, "${result.size}_$dateLabel")
        }
        if (!unreadPlaced && message.id == unreadSeparatorId) {
            result += ChatItem.UnreadSeparator(message.id)
            unreadPlaced = true
        }
        val previous = deduped.getOrNull(i - 1)
        val next = deduped.getOrNull(i + 1)
        val startsGroup = previous == null ||
            !sameSenderGroup(previous, message, dateLabels[i - 1], dateLabel)
        val endsGroup = next == null ||
            !sameSenderGroup(message, next, dateLabel, dateLabels[i + 1])
        val occurrence = if (message.id.isBlank()) ++blankOccurrence else 0
        result += ChatItem.Msg(
            message = message,
            showAvatar = startsGroup,
            showSenderName = startsGroup,
            isGroupEdge = endsGroup,
            occurrence = occurrence
        )
        previousDateLabel = dateLabel
    }
    // 未读起点消息不在当前窗口（早于已加载）时，放在最旧消息之前兜底
    if (!unreadPlaced && result.isNotEmpty()) {
        val insertAt = if (result.first() is ChatItem.DateSeparator) 1 else 0
        result.add(insertAt, ChatItem.UnreadSeparator(deduped.first().id.ifBlank { "window" }))
    }
    return result
}
