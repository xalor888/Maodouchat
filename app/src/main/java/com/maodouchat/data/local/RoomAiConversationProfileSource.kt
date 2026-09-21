package com.maodouchat.data.local

import com.maodouchat.ai.AiConversationProfile
import com.maodouchat.ui.screen.chatdetail.AiConversationProfileSource

/**
 * `AiConversationProfileSource` 的装配实现（G73）。
 *
 * **为什么必须放在 data 层**：它是全项目唯一同时认识 `Context` / `AppDatabase` 与
 * UI 端口的地方。放进 `ui/` 会让 `ui/` 重新 import `data.local` 或抓应用单例，
 * `ClientArchitectureTest` 的两条棘轮都会红（G64 已经这样逼过一次）。
 *
 * 与 `RoomReadReceiptSource` 同一手法：端口在 ui，实现在 data。
 */
internal class RoomAiConversationProfileSource(
    private val context: android.content.Context,
    private val database: AppDatabase,
) : AiConversationProfileSource {
    override suspend fun build(chatId: String): AiConversationProfile.ConversationProfile =
        AiConversationProfile.build(context, database, chatId)
}
