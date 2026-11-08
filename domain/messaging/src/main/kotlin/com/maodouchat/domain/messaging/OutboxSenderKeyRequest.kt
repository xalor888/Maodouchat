package com.maodouchat.domain.messaging

import java.util.UUID

/** Sender Key 请求的线上类型与属性键：发件箱与时间线投影共用，拼写改动会断线。 */
const val TYPE_SENDER_KEY_REQUEST = "SENDER_KEY_REQUEST"
const val ATTRIBUTE_REQUESTED_SENDER = "requestedSenderUserId"
const val ATTRIBUTE_FAILED_MESSAGE_ID = "failedMessageId"

/** Sender Key 请求消息 id：由会话、被请求方、群版本、失败消息、属主与设备 id 确定性派生，同一输入恒得同一 id，失败重试不重复入队。 */
fun senderKeyRequestMessageId(
    conversationId: String,
    requestedSenderUserId: String,
    groupRevision: Long,
    failedMessageId: String,
    owner: String,
    deviceId: Int,
): String {
    val seed = "$conversationId|$requestedSenderUserId|$groupRevision|$failedMessageId|$owner|$deviceId"
    return "kr_${UUID.nameUUIDFromBytes(seed.toByteArray(Charsets.UTF_8))}"
}
