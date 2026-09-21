package com.maodouchat.ui.screen.chatdetail

/**
 * 「情感回复」AI 能力的读取端口（G73）。同 `AiConversationProfileSource` 手法。
 */
interface AiEmotionReplySource {
    /** 返回生成的回复文案；空串表示没有可用回复。 */
    suspend fun reply(chatId: String): String
}
