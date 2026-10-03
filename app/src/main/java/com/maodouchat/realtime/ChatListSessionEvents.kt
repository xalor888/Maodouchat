package com.maodouchat.realtime

data class ChatReadEvent(
    val chatId: String,
    val sessionGeneration: Long,
)

/**
 * 跨 ViewModel 共享的"列表预览变更"流：
 * - 发送/附件 finalize：带 previewText + typeWire，列表单调更新时间；
 * - delete/revoke 本地成功：forceFromLocal=true，列表从 Room 重算 tail（可清空/回退）。
 */
data class ChatMessageSentEvent(
    val chatId: String,
    val previewText: String = "",
    val messageTypeWire: String = "TEXT",
    val forceFromLocal: Boolean = false,
    val sessionGeneration: Long,
)
