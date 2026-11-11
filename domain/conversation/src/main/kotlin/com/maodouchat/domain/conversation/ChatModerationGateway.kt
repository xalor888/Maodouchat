package com.maodouchat.domain.conversation

/** 拉黑/举报网络端口。app 侧由 ModerationNetworkRepository / AccountSecurityNetworkRepository 实现。 */
interface ChatModerationGateway {
    suspend fun blockedUserIds(token: String): Result<List<String>>

    suspend fun blockUser(token: String, userId: String): Result<Unit>

    suspend fun unblockUser(token: String, userId: String): Result<Unit>

    suspend fun createReport(
        token: String,
        targetType: String,
        targetId: String,
        chatId: String?,
        messageId: String?,
        reason: String,
        description: String?,
    ): Result<Unit>
}
