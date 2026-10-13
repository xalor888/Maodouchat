package com.maodouchat.server.repository

import com.maodouchat.server.model.PinnedMessageResponse

/**
 * 会话级消息置顶：群仅 OWNER/ADMIN；单聊双方均可；最多 [MAX_PINS_PER_CHAT] 条。
 * 仅存 messageId 元数据，不复制密文。
 */
class PinnedMessageRepository {

    enum class PinResult {
        PINNED,
        UNPINNED,
        NOT_FOUND,
        FORBIDDEN,
        LIMIT,
        NOT_PINNABLE
    }

    data class ToggleOutcome(
        val result: PinResult,
        val pins: List<PinnedMessageResponse> = emptyList()
    )

    companion object {
        const val MAX_PINS_PER_CHAT = 20
    }

}
