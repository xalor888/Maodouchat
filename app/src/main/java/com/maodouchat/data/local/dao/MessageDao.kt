package com.maodouchat.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.maodouchat.data.local.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

/**
 * B7 索引映射（v27→v28 追加，见 AppDatabase.MIGRATION_27_28；查询语句本身未改动）：
 * - [getMessagesByChatId] / [getRecentMessages] / [getFirstMessageAtOrAfter] /
 *   [getEarliestMessageTimestamp] → index_messages_chatId_timestamp (chatId, timestamp)
 * - [getSendingOutbox] / [getSendingOutboxForChat] → index_messages_status_senderId_timestamp (status, senderId, timestamp)
 * - [getExpiredMessageIds] → index_messages_expiresAt (expiresAt)
 * - [getImageMessages] / [getSearchableMessages] / [getSearchableMessageIds] /
 *   [getSearchableMessagesAfterCursor] → index_messages_type_timestamp (type, timestamp)
 */
@Dao
interface MessageDao {

    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY timestamp ASC")
    fun getMessagesByChatId(chatId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE chatId = :chatId AND type IN ('TEXT', 'IMAGE', 'GIF', 'VIDEO', 'STICKER', 'FILE', 'VOICE', 'LOCATION') ORDER BY timestamp DESC")
    fun observeMediaCenterMessages(chatId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentMessages(chatId: String, limit: Int): List<MessageEntity>

    /** 最新 N 条图片消息，供自动 OCR 识别图内文字并写入搜索索引（最新优先）。 */
    @Query("SELECT * FROM messages WHERE type = 'IMAGE' ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getImageMessages(limit: Int): List<MessageEntity>

    @Query("SELECT id FROM messages WHERE chatId = :chatId")
    suspend fun getMessageIdsByChatId(chatId: String): List<String>

    @Query("SELECT * FROM messages WHERE id = :messageId")
    suspend fun getMessageById(messageId: String): MessageEntity?

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

    /**
     * Local outbox: non-attachment messages still waiting for server ACK.
     * NUDGE is WS-ephemeral (server rewrites content) — never encrypt/REST flush it.
     */
    @Query(
        """
        SELECT * FROM messages
        WHERE status = 'SENDING'
          AND senderId = :senderId
          AND type IN ('TEXT', 'MARKDOWN', 'STICKER', 'LOCATION')
        ORDER BY timestamp ASC
        LIMIT :limit
        """
    )
    suspend fun getSendingOutbox(senderId: String, limit: Int = 50): List<MessageEntity>

    @Query(
        """
        SELECT * FROM messages
        WHERE chatId = :chatId
          AND status = 'SENDING'
          AND senderId = :senderId
          AND type IN ('TEXT', 'MARKDOWN', 'STICKER', 'LOCATION')
        ORDER BY timestamp ASC
        LIMIT :limit
        """
    )
    suspend fun getSendingOutboxForChat(chatId: String, senderId: String, limit: Int = 50): List<MessageEntity>

    // TEXT/VOICE primary; LOCATION label + NUDGE body indexable after local decrypt.
    // 加 LIMIT 防止消息量大的数据库 OOM；DESC 排序保留最新消息，配合 MessageSearchRepository.refreshIndex
    // 的孤儿删除逻辑时，避免最新消息因不在截断集合内被误删索引。
    // 调用方按需分批加载或基于时间游标分页。
    @Query("SELECT * FROM messages WHERE type IN ('TEXT', 'MARKDOWN', 'VOICE', 'LOCATION', 'NUDGE', 'IMAGE', 'GIF', 'STICKER', 'VIDEO', 'FILE') ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getSearchableMessages(limit: Int = 5000): List<MessageEntity>

    // 8.48 修复：按会话查询可搜索消息（分类/周报等按会话统计场景）——
    // 此前「全库 LIMIT 后按 chatId filter」在活跃大库下目标会话历史被静默丢弃，统计失真
    @Query("SELECT * FROM messages WHERE chatId = :chatId AND type IN ('TEXT', 'MARKDOWN', 'VOICE', 'LOCATION', 'NUDGE', 'IMAGE', 'GIF', 'STICKER', 'VIDEO', 'FILE') ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getSearchableMessagesForChat(chatId: String, limit: Int = 5000): List<MessageEntity>

    // 轻量全集：仅返回可搜索消息的 id（不含 content），供 refreshIndex 计算孤儿文档，
    // 避免一次性把全部实体载入内存导致 OOM。务必全量，不可加 LIMIT（否则历史消息索引会被当孤儿删）。
    @Query("SELECT id FROM messages WHERE type IN ('TEXT', 'MARKDOWN', 'VOICE', 'LOCATION', 'NUDGE', 'IMAGE', 'GIF', 'STICKER', 'VIDEO', 'FILE')")
    suspend fun getSearchableMessageIds(): List<String>

    // 8.48 修复：可搜索且正文非空的消息数——用于搜索索引漂移判定；
    // 空白/密文消息 indexMessage 时 deleteDocument 不产生文档，若用 getSearchableMessageIds
    // 计数则 msgCount 恒 > docCount，每次打开全局搜索都误判需全量重建
    @Query("SELECT COUNT(*) FROM messages WHERE type IN ('TEXT', 'MARKDOWN', 'VOICE', 'LOCATION', 'NUDGE', 'IMAGE', 'GIF', 'STICKER', 'VIDEO', 'FILE') AND content != ''")
    suspend fun countSearchableWithContent(): Int

    // 分批加载，用于 refreshIndex 增量建索引；游标分页（按 timestamp,id 稳定排序）替代 OFFSET，
    // 避免刷新期间并发插入使 OFFSET 跳过/重复行，导致历史消息长期不被索引、全局搜索漏结果。
    @Query("""
        SELECT * FROM messages
        WHERE type IN ('TEXT', 'MARKDOWN', 'VOICE', 'LOCATION', 'NUDGE', 'IMAGE', 'GIF', 'STICKER', 'VIDEO', 'FILE')
          AND (timestamp > :lastTimestamp OR (timestamp = :lastTimestamp AND id > :lastId))
        ORDER BY timestamp ASC, id ASC
        LIMIT :limit
    """)
    suspend fun getSearchableMessagesAfterCursor(lastTimestamp: Long, lastId: String, limit: Int): List<MessageEntity>

    // Inline chat-list filter: TEXT body + NUDGE body + LOCATION payload (label lives in content JSON).
    // Global search uses MessageSearchRepository tokens; this is a lightweight LIKE fallback.
    // [keyword] must already be escaped via LikeQueryPolicy.escapeForContains (ESCAPE '\').
    // 使用 GROUP BY + MAX(timestamp) 替代 DISTINCT + ORDER BY，确保每个 chatId 按最新消息时间排序（DISTINCT 时 SQLite 选取任意行的 timestamp 排序，结果不确定）
    // 8.48 修复：去掉 LOWER() 包裹（函数包裹列使索引失效）——SQLite LIKE 对 ASCII 默认大小写不敏感，
    // 中文无大小写，直接列比较语义等价且列可参与索引（前导通配符下普通索引仍不命中，但已消除额外函数开销）
    @Query(
        """
        SELECT chatId FROM messages
        WHERE type IN ('TEXT', 'NUDGE', 'LOCATION')
          AND content LIKE '%' || :keyword || '%' ESCAPE '\'
        GROUP BY chatId
        ORDER BY MAX(timestamp) DESC
        LIMIT :limit
        """
    )
    suspend fun searchChatIdsByMessageContent(keyword: String, limit: Int = 50): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessageIfAbsent(message: MessageEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<MessageEntity>)

    /** Unconditional status write — prefer [updateMessageStatusIfAdvanced] for delivery receipts. */
    @Query("UPDATE messages SET status = :status WHERE id = :messageId")
    suspend fun updateMessageStatus(messageId: String, status: String)

    @Query("UPDATE messages SET content = :content WHERE id = :messageId AND content = :expectedContent")
    suspend fun updateMessageContentIfUnchanged(
        messageId: String,
        expectedContent: String,
        content: String
    ): Int

    /**
     * Monotonic delivery update: only advances SENDING→SENT→DELIVERED→READ.
     * FAILED is a local side-channel and must not be applied through this path for remote events.
     */
    @Query(
        """
        UPDATE messages SET status = :status
        WHERE id = :messageId
          AND (
            (:status = 'SENDING' AND status IN ('FAILED'))
            OR (:status = 'SENT' AND status IN ('SENDING', 'FAILED'))
            OR (:status = 'DELIVERED' AND status IN ('SENDING', 'SENT'))
            OR (:status = 'READ' AND status IN ('SENDING', 'SENT', 'DELIVERED'))
            OR (:status = 'FAILED' AND status = 'SENDING')
          )
        """
    )
    suspend fun updateMessageStatusIfAdvanced(messageId: String, status: String): Int

    @Query("DELETE FROM messages WHERE id = :messageId")
    suspend fun deleteMessageById(messageId: String)

    @Query("DELETE FROM messages WHERE chatId = :chatId")
    suspend fun deleteMessagesByChatId(chatId: String)

    @Query("SELECT id FROM messages WHERE expiresAt IS NOT NULL AND expiresAt > 0 AND expiresAt <= :now")
    suspend fun getExpiredMessageIds(now: Long): List<String>

    @Query("DELETE FROM messages WHERE id IN (:ids)")
    suspend fun deleteMessagesByIds(ids: List<String>)

    @Query("DELETE FROM messages")
    suspend fun deleteAllMessages()
}
