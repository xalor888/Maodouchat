package com.maodouchat.domain.messaging

import com.maodouchat.core.model.MessageStatus
import com.maodouchat.core.model.MessagingV2Event
import com.maodouchat.core.model.MessagingV2EventAction

/** 送达回执事件：目标消息 + DELIVERED 状态。 */
fun deliveryReceiptEvent(messageId: String): MessagingV2Event = MessagingV2Event(
    action = MessagingV2EventAction.DELIVERY_RECEIPT,
    targetMessageId = messageId,
    status = MessageStatus.DELIVERED.name,
)

/** 已读回执事件：已读水位线消息既是目标也是 through。 */
fun readReceiptEvent(throughMessageId: String): MessagingV2Event = MessagingV2Event(
    action = MessagingV2EventAction.READ_RECEIPT,
    targetMessageId = throughMessageId,
    status = MessageStatus.READ.name,
    throughMessageId = throughMessageId,
)

/** 播放回执事件：目标消息 + PLAYED 状态。 */
fun playReceiptEvent(messageId: String): MessagingV2Event = MessagingV2Event(
    action = MessagingV2EventAction.PLAY_RECEIPT,
    targetMessageId = messageId,
    status = "PLAYED",
)
