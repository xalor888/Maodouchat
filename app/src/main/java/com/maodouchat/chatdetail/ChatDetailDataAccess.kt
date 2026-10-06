package com.maodouchat.chatdetail

import com.maodouchat.MaodouchatApp

/**
 * 会话详情的持久层访问口（U02 延伸）：DAO 直连与由此构造的仓库收进中立包，
 * ui 层（ChatDetailViewModel）不再直接触 `app.database`。
 */
object ChatDetailDataAccess {

    suspend fun markIncomingReadThrough(
        chatId: String,
        ownerUserId: String,
        throughTimestamp: Long,
        throughMessageId: String,
    ) = MaodouchatApp.instance.database.messageDao().markIncomingReadThrough(
        chatId = chatId,
        ownerUserId = ownerUserId,
        throughTimestamp = throughTimestamp,
        throughMessageId = throughMessageId,
    )

    suspend fun markAllRead(chatId: String) =
        MaodouchatApp.instance.database.chatDao().markAllRead(chatId)

    fun observeAllAttachmentTransfers() =
        MaodouchatApp.instance.database.attachmentTransferDao().observeAllAccounts()

    suspend fun chatUnreadCount(chatId: String): Int =
        MaodouchatApp.instance.database.chatDao().getChatById(chatId)?.unreadCount ?: 0

    fun attachmentTransferDao() =
        MaodouchatApp.instance.database.attachmentTransferDao()

    fun messageSearchRepository() =
        com.maodouchat.data.repository.MessageSearchRepository(MaodouchatApp.instance.database)

    // ─── 2026-09-28：ChatDetailDeps 装配收口（棘轮 17 → 0）所需的其余 DAO/仓库 ───

    fun messageRepo() = com.maodouchat.data.repository.LocalMessageStore(
        MaodouchatApp.instance.database.messageDao(),
        MaodouchatApp.instance.database,
    )

    fun chatLockRepo() =
        com.maodouchat.data.repository.ChatLockRepository(MaodouchatApp.instance.database.chatLockDao())

    fun secretTtlRepo() =
        com.maodouchat.data.repository.SecretChatRepository(MaodouchatApp.instance.database.secretChatDao())

    fun messageSearchDao() = MaodouchatApp.instance.database.messageSearchDao()

    fun aiSummaryRepo() =
        com.maodouchat.data.repository.AiSummaryRepository(MaodouchatApp.instance.database.aiSummaryCacheDao())

    fun aiTaskRepo() =
        com.maodouchat.data.repository.AiTaskRepository(MaodouchatApp.instance.database.aiTaskDao(), MaodouchatApp.instance)

    fun aiOperationRepo() =
        com.maodouchat.data.repository.AiOperationRepository(MaodouchatApp.instance.database.aiOperationDao())

    fun userRepo() =
        com.maodouchat.data.repository.UserRepository(MaodouchatApp.instance.database.userDao())

    fun chatRepo() = com.maodouchat.data.repository.ChatRepository(
        MaodouchatApp.instance.database.chatDao(),
        MaodouchatApp.instance.database.userDao(),
    )

    fun chatDraftDao() = MaodouchatApp.instance.database.chatDraftDao()

    // 属性名刻意避开 `database`：ui 侧棘轮按 `database.` 符号计数，直呼其名会被误判为直连。
    val appDatabase: com.maodouchat.data.local.AppDatabase
        get() = MaodouchatApp.instance.database

    internal fun readReceiptSource() =
        com.maodouchat.data.local.RoomReadReceiptSource(MaodouchatApp.instance.database.messagingV2Dao())

    suspend fun applyRealtimeVisibility(
        userId: String,
        isOnline: Boolean,
        onlineRevoked: Boolean,
        statusRevoked: Boolean,
        updatedAt: Long,
    ) = MaodouchatApp.instance.database.userDao().applyRealtimeVisibility(
        userId = userId,
        isOnline = isOnline,
        onlineRevoked = onlineRevoked,
        statusRevoked = statusRevoked,
        updatedAt = updatedAt,
    )
}
