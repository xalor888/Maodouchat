package com.maodouchat.data.local

import com.maodouchat.ai.AiEmotionReplySource

/** `AiEmotionReplySource` 的装配实现（G73）。 */
internal class RoomAiEmotionReplySource(
    private val context: android.content.Context,
    private val database: AppDatabase,
) : AiEmotionReplySource {
    override suspend fun reply(chatId: String): String =
        com.maodouchat.ai.AiEmotionReply.reply(context, database, chatId).getOrNull().orEmpty()
}
