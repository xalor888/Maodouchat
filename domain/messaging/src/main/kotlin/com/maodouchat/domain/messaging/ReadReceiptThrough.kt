package com.maodouchat.domain.messaging

/** 已读回执的边界消息：through 缺失时回落到目标消息本身。 */
fun readReceiptThrough(throughMessageId: String?, targetMessageId: String): String =
    throughMessageId ?: targetMessageId
