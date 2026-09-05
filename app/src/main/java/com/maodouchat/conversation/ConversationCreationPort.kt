package com.maodouchat.conversation

import com.maodouchat.data.model.Chat

/**
 * 会话创建统一端口 (Conversation Creation Port)
 *
 * 统一收敛普通私聊、端到端密聊、群聊和广播频道的创建契约，
 * 封装鉴权门禁校验、重入防抖、远程创建与本地会话投影落库。
 */
interface ConversationCreationPort {
    /**
     * 创建或打开与指定联系人的普通私聊 (1:1 DIRECT)
     */
    suspend fun createDirectChat(peerUserId: String): Result<Chat>

    /**
     * 发起端到端密聊 (1:1 SECRET)
     */
    suspend fun createSecretChat(peerUserId: String): Result<Chat>

    /**
     * 创建多人群聊 (GROUP)
     */
    suspend fun createGroupChat(name: String, memberUserIds: List<String>): Result<Chat>

    /**
     * 创建广播频道 (CHANNEL)
     */
    suspend fun createChannelChat(name: String, memberUserIds: List<String>): Result<Chat>
}
