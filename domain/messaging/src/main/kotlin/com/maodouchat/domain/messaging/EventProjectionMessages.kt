package com.maodouchat.domain.messaging

import com.maodouchat.core.model.Message
import com.maodouchat.core.model.MessageStatus
import com.maodouchat.core.model.MessageType
import com.maodouchat.core.model.MessagingV2Event

/** 编辑事件投影：无正文的编辑事件不落库，编辑时间缺失时回落服务端时间戳。 */
fun editedMessage(existing: Message, event: MessagingV2Event, serverTimestamp: Long): Message? {
    val body = event.content ?: return null
    return existing.copy(content = body, editedAt = event.editedAt ?: serverTimestamp)
}

/** 撤回事件投影：类型置为撤回，正文缺失时用默认文案。 */
fun revokedMessage(existing: Message, event: MessagingV2Event, serverTimestamp: Long): Message =
    existing.copy(
        type = MessageType.REVOKED,
        content = event.content ?: "Message revoked",
        editedAt = event.editedAt ?: serverTimestamp,
    )

/** 对端已读回执推进的状态：群聊只到送达，单聊到已读。 */
fun readReceiptStatus(isGroup: Boolean): MessageStatus =
    if (isGroup) MessageStatus.DELIVERED else MessageStatus.READ
