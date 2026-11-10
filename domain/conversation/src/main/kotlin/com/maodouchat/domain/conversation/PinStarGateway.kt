package com.maodouchat.domain.conversation

import com.maodouchat.core.model.PinnedMessagesListResponse
import com.maodouchat.core.model.StarMessageResponse
import com.maodouchat.core.model.TogglePinResponse

/** 置顶/星标的网络端口。app 侧由 PinStarNetworkRepository 实现。 */
interface PinStarGateway {
    suspend fun pinned(token: String, chatId: String): Result<PinnedMessagesListResponse>
    suspend fun togglePin(token: String, chatId: String, messageId: String): Result<TogglePinResponse>
    suspend fun toggleStar(token: String, messageId: String): Result<StarMessageResponse>
}
