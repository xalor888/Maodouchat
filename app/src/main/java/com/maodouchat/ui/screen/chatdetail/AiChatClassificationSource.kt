package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.data.repository.AiProfileRepository

/**
 * 「会话分类」AI 能力的读取端口（G73）。
 *
 * 与 `AiConversationProfileSource` 同一手法：Route 只看见 `chatId` 与结果，
 * `Context` / `AppDatabase` 由 data 层实现持有。清掉 `ChatDetailRoute` 里
 * 剩下的 app 数据库直连，靠的就是把这些能力逐个端口化。
 */
interface AiChatClassificationSource {
    suspend fun classify(chatId: String): List<AiProfileRepository.CategoryCount>
}
