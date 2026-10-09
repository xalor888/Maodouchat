package com.maodouchat.data.local.dao

import androidx.room.Query
import com.maodouchat.data.local.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

interface MessageTimelineDao {
    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY timestamp ASC")
    fun getMessagesByChatId(chatId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE chatId = :chatId AND type IN ('TEXT', 'IMAGE', 'GIF', 'VIDEO', 'STICKER', 'FILE', 'VOICE', 'LOCATION') ORDER BY timestamp DESC")
    fun observeMediaCenterMessages(chatId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentMessages(chatId: String, limit: Int): List<MessageEntity>

    /** 最新 N 条图片消息，供自动 OCR 识别图内文字并写入搜索索引（最新优先）。密聊图片永远排除。 */
    @Query("""
        SELECT m.* FROM messages m
        WHERE m.type = 'IMAGE'
          AND m.chatId NOT IN (SELECT id FROM chats WHERE chatType = 'SECRET')
        ORDER BY m.timestamp DESC
        LIMIT :limit
    """)
    suspend fun getImageMessages(limit: Int): List<MessageEntity>

    @Query("SELECT id FROM messages WHERE chatId = :chatId")
    suspend fun getMessageIdsByChatId(chatId: String): List<String>

    @Query("SELECT * FROM messages WHERE id = :messageId")
    suspend fun getMessageById(messageId: String): MessageEntity?

    @Query(
        """
        SELECT * FROM messages
        WHERE chatId = :chatId AND senderId != :ownerUserId AND type != 'SK_DIST'
        ORDER BY timestamp DESC, id DESC
        LIMIT 1
        """,
    )
    suspend fun getLatestIncomingMessage(
        chatId: String,
        ownerUserId: String,
    ): MessageEntity?

    @Query(
        """
        SELECT * FROM messages
        WHERE chatId = :chatId AND senderId = :senderId
          AND (timestamp < :throughTimestamp OR (timestamp = :throughTimestamp AND id <= :throughMessageId))
        ORDER BY timestamp ASC, id ASC
        """,
    )
    suspend fun getOutgoingMessagesThrough(
        chatId: String,
        senderId: String,
        throughTimestamp: Long,
        throughMessageId: String,
    ): List<MessageEntity>

    /**
     * 同会话/发送者/时间戳的候选行（v2 Inbox 与乐观发送收敛时 id 可能不同）。
     * LIMIT 防止异常时间戳碰撞扫全表。
     */
    @Query(
        """
        SELECT * FROM messages
        WHERE chatId = :chatId AND senderId = :senderId AND timestamp = :timestamp
        LIMIT 8
        """
    )
    suspend fun getMessagesByDeliveryHint(
        chatId: String,
        senderId: String,
        timestamp: Long
    ): List<MessageEntity>

    /** 目标时间点（含）之后的第一条消息，用于日历/日期跳转精确定位。 */
    @Query(
        """
        SELECT * FROM messages
        WHERE chatId = :chatId AND timestamp >= :fromTimestamp
        ORDER BY timestamp ASC, id ASC
        LIMIT 1
        """
    )
    suspend fun getFirstMessageAtOrAfter(chatId: String, fromTimestamp: Long): MessageEntity?

    /** 会话内最早一条消息的时间，用于日历可选范围上限。 */
    @Query(
        """
        SELECT MIN(timestamp) FROM messages WHERE chatId = :chatId
        """
    )
    suspend fun getEarliestMessageTimestamp(chatId: String): Long?

    /** 9.213：批量预查——消除批量插入路径的逐条 SELECT（N+1）。 */
    @Query("SELECT * FROM messages WHERE id IN (:ids)")
    suspend fun getMessagesByIds(ids: List<String>): List<MessageEntity>

    @Query(
        """
        SELECT * FROM messages
        WHERE starred = 1
          AND chatId NOT IN (SELECT id FROM chats WHERE chatType = 'SECRET')
          AND chatId NOT IN (SELECT chatId FROM chat_locks)
        ORDER BY timestamp DESC
        LIMIT :limit
        """
    )
    suspend fun getStarredMessages(limit: Int): List<MessageEntity>

    @Query(
        """
        SELECT * FROM messages
        WHERE starred = 1
          AND chatId = :chatId
        ORDER BY timestamp DESC
        LIMIT :limit
        """
    )
    suspend fun getStarredMessagesForChat(chatId: String, limit: Int): List<MessageEntity>
}
