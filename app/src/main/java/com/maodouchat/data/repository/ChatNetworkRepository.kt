package com.maodouchat.data.repository

import com.maodouchat.network.ApiService
import com.maodouchat.network.ChatDto

/**
 * 需要鉴权的会话读写（远端）（G328c）。
 *
 * 与 `data/repository/ChatRepository` 的分工：那个是**本地 Room**（缓存、订阅、置顶/归档等
 * 本地状态），本类是**远端**（创建会话、拉会话列表、更新会话设置）。分开的理由与
 * `UserNetworkRepository` 相同：本地读永不为空、远端要处理网络失败，混在一起
 * 调用方就分不清「没数据」与「拉不到」。
 *
 * 刻意很薄（不缓存、不重试），构造器接受 lambda 因而自身可测。
 */
internal class ChatNetworkRepository(
    private val createChatApi: suspend (String, List<String>, Boolean, String?, String?) -> Result<ChatDto> =
        { token, peerIds, isGroup, groupName, chatType -> ApiService.createChat(token, peerIds, isGroup, groupName, chatType) },
    private val getChatsApi: suspend (String) -> Result<List<ChatDto>> = { token -> ApiService.getChats(token) },
    private val updateChatSettingsApi: suspend (String, String, com.maodouchat.network.UpdateChatSettingsRequest) -> Result<com.maodouchat.network.ChatSettingsResponse> =
        { token, chatId, request -> ApiService.updateChatSettings(token, chatId, request) },
    private val updateDisappearingApi: suspend (String, String, Int) -> Result<com.maodouchat.network.DisappearingMessagesResponse> =
        { token, chatId, seconds -> ApiService.updateDisappearingMessages(token, chatId, seconds) },
    private val deleteChatApi: suspend (String, String) -> Result<Unit> =
        { token, chatId -> ApiService.deleteChat(token, chatId) },
) {
    suspend fun createChat(
        token: String? = null,
        peerIds: List<String>,
        isGroup: Boolean = false,
        groupName: String? = null,
        chatType: String? = null,
    ): Result<ChatDto> = createChatApi(token ?: currentAccessToken(), peerIds, isGroup, groupName, chatType)

    suspend fun chats(token: String? = null): Result<List<ChatDto>> =
        getChatsApi(token ?: currentAccessToken())

    suspend fun updateChatSettings(
        token: String? = null,
        chatId: String,
        request: com.maodouchat.network.UpdateChatSettingsRequest,
    ): Result<com.maodouchat.network.ChatSettingsResponse> =
        updateChatSettingsApi(token ?: currentAccessToken(), chatId, request)

    suspend fun updateDisappearingMessages(
        token: String? = null,
        chatId: String,
        seconds: Int,
    ): Result<com.maodouchat.network.DisappearingMessagesResponse> =
        updateDisappearingApi(token ?: currentAccessToken(), chatId, seconds)

    suspend fun deleteChat(token: String? = null, chatId: String): Result<Unit> =
        deleteChatApi(token ?: currentAccessToken(), chatId)
}
