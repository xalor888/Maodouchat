package com.maodouchat.domain.messaging

/**
 * 群控消息的加密载荷选择：Sender Key 分发加密解码后的消息正文，其余群控消息
 * （密钥请求）直接加密原始载荷；载荷类型与之对应。
 */
data class GroupControlPayload(val useDecodedBody: Boolean, val payloadType: String)

fun decideGroupControlPayload(kind: String): GroupControlPayload =
    if (kind == "SENDER_KEY") GroupControlPayload(useDecodedBody = true, payloadType = "SK_DIST")
    else GroupControlPayload(useDecodedBody = false, payloadType = "KEY_REQUEST")
