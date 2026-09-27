package com.maodouchat.realtime

/**
 * 会话列表与聊天页之间共享的两个跨 ViewModel 事件（U02 延伸：自
 * `MaodouchatApp.Companion` 迁出）。
 *
 * 为什么迁：它们是**类型**，不是应用单例的能力——留在 app 类的 companion 里，
 * 迫使 ui（`ChatListRealtimeCoordinator`/`ChatListPorts`）在类型位置上写出
 * `MaodouchatApp.Companion.Xxx`，ui 直连持久层棘轮按符号计数因此长期计着这几处。
 * 迁到非 ui 层后，发射入口（`MaodouchatApp.emitChatRead` / `emitMessageSent` /
 * `emitChatListPreviewRefresh`）与两条流的语义**完全不变**，只是类型归属变清楚。
 *
 * 构造点全部在 `MaodouchatApp` 内且都显式传 `sessionGeneration`——所以这里**不设默认值**，
 * 免得数据类反过来依赖 app 的静态函数（默认值正是它们原先嵌在 companion 里的原因）。
 */
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
