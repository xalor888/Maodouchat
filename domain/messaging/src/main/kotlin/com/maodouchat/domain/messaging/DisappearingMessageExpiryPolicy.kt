package com.maodouchat.domain.messaging

import com.maodouchat.core.model.Message
import com.maodouchat.core.model.PinnedMessageDto

// 消失消息过期清理判定，逐字平移自 app 侧 ChatDetailDisappearing.purgeExpiredLocalMessages。
// 落库清理（SecretConversationActions.purgeExpiredMessages）留在 app 侧。
fun isDisappearingMessageExpired(expiresAt: Long?, nowMs: Long): Boolean =
    expiresAt != null && expiresAt > 0L && expiresAt <= nowMs

fun filterUnexpiredMessages(messages: List<Message>, nowMs: Long): List<Message> =
    messages.filter { !isDisappearingMessageExpired(it.expiresAt, nowMs) }

// 置顶联动过滤：消息已过期则其置顶一并去掉；置顶指向的消息不在列表里时保留。
fun filterUnexpiredPins(
    pins: List<PinnedMessageDto>,
    messages: List<Message>,
    nowMs: Long,
): List<PinnedMessageDto> = pins.filter { pin ->
    messages.none { it.id == pin.messageId && isDisappearingMessageExpired(it.expiresAt, nowMs) }
}
