package com.maodouchat.server.repository

/**
 * 会话级消息置顶：群仅 OWNER/ADMIN；单聊双方均可；最多 [MAX_PINS_PER_CHAT] 条。
 * 仅存 messageId 元数据，不复制密文。
 */
class PinnedMessageRepository {

    companion object {
        const val MAX_PINS_PER_CHAT = 20
    }

}
