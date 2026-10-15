package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.data.model.Message

internal data class Input(
    val messages: List<Message>,
    val hasChat: Boolean,
    val isActiveChat: Boolean,
    val ownerUserId: String,
    val sessionMayContinue: Boolean,
)

internal data class Plan(
    /** 本轮要标已读的消息 id（保序）。 */
    val unreadIds: Set<String>,
    /** 水印消息 id —— 「已读到这条为止」。 */
    val watermarkMessageId: String,
    /** 水印消息时间戳 —— 供 `markIncomingReadThrough` 用。 */
    val watermarkTimestamp: Long,
)
