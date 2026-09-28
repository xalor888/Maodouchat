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
}
