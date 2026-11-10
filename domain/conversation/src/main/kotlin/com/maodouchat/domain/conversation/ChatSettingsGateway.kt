package com.maodouchat.domain.conversation

import com.maodouchat.core.model.ChatSettingsResponse
import com.maodouchat.core.model.UpdateChatSettingsRequest

/** 会话级设置的网络端口。app 侧由 ChatNetworkRepository 实现。 */
interface ChatSettingsGateway {
    suspend fun updateChatSettings(
        token: String,
        chatId: String,
        request: UpdateChatSettingsRequest,
    ): Result<ChatSettingsResponse>
}
