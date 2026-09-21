package com.maodouchat.data.local

import com.maodouchat.data.repository.AiProfileRepository
import com.maodouchat.ui.screen.chatdetail.AiChatClassificationSource

/** `AiChatClassificationSource` 的装配实现（G73）；端口在 ui，实现在 data。 */
internal class RoomAiChatClassificationSource(
    private val context: android.content.Context,
    private val database: AppDatabase,
) : AiChatClassificationSource {
    override suspend fun classify(chatId: String): List<AiProfileRepository.CategoryCount> =
        com.maodouchat.ai.AiMessageClassifier.classifyChat(context, database, chatId)
}
