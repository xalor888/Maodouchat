package com.maodouchat.data.local.dao

import androidx.room.Query
import com.maodouchat.data.local.entity.MessageEntity

interface MessageContentSearchDao {
    // TEXT/VOICE primary; LOCATION label + NUDGE body indexable after local decrypt.
    // 加 LIMIT 防止消息量大的数据库 OOM；DESC 排序保留最新消息，配合 MessageSearchRepository.refreshIndex
    // 的孤儿删除逻辑时，避免最新消息因不在截断集合内被误删索引。
    // 调用方按需分批加载或基于时间游标分页。
    @Query(
        """
        SELECT * FROM messages
        WHERE type IN ('TEXT', 'MARKDOWN', 'VOICE', 'LOCATION', 'NUDGE', 'IMAGE', 'GIF', 'STICKER', 'VIDEO', 'FILE')
          AND chatId NOT IN (SELECT id FROM chats WHERE chatType = 'SECRET')
        ORDER BY timestamp DESC LIMIT :limit
        """
    )
    suspend fun getSearchableMessages(limit: Int = 5000): List<MessageEntity>

    // 8.48 修复：按会话查询可搜索消息（分类/周报等按会话统计场景）——
    // 此前「全库 LIMIT 后按 chatId filter」在活跃大库下目标会话历史被静默丢弃，统计失真
    @Query(
        """
        SELECT * FROM messages
        WHERE chatId = :chatId
          AND type IN ('TEXT', 'MARKDOWN', 'VOICE', 'LOCATION', 'NUDGE', 'IMAGE', 'GIF', 'STICKER', 'VIDEO', 'FILE')
          AND chatId NOT IN (SELECT id FROM chats WHERE chatType = 'SECRET')
        ORDER BY timestamp DESC LIMIT :limit
        """
    )
    suspend fun getSearchableMessagesForChat(chatId: String, limit: Int = 5000): List<MessageEntity>

    // 轻量全集：仅返回可搜索消息的 id（不含 content），供 refreshIndex 计算孤儿文档，
    // 避免一次性把全部实体载入内存导致 OOM。务必全量，不可加 LIMIT（否则历史消息索引会被当孤儿删）。
    @Query("SELECT id FROM messages WHERE type IN ('TEXT', 'MARKDOWN', 'VOICE', 'LOCATION', 'NUDGE', 'IMAGE', 'GIF', 'STICKER', 'VIDEO', 'FILE')")
    suspend fun getSearchableMessageIds(): List<String>

    // 8.48 修复：可搜索且正文非空的消息数——用于搜索索引漂移判定；
    // 空白/密文消息 indexMessage 时 deleteDocument 不产生文档，若用 getSearchableMessageIds
    // 计数则 msgCount 恒 > docCount，每次打开全局搜索都误判需全量重建
    // 8.49 修复：与 indexMessage 的拒绝规则（looksLikeWireEnvelope）对齐——密文
    // （{ / [{ / [" 开头的 wire envelope）永不产生文档，必须同样从基数排除，
    // 否则未打开会话的 backlog 密文会让 drift 恒等于密文数，每次打开搜索都全量重建
    @Query(
        """
        SELECT COUNT(*) FROM messages
        WHERE type IN ('TEXT', 'MARKDOWN', 'VOICE', 'LOCATION', 'NUDGE', 'IMAGE', 'GIF', 'STICKER', 'VIDEO', 'FILE')
          AND TRIM(content) != ''
          AND LTRIM(content) NOT LIKE '{%'
          AND LTRIM(content) NOT LIKE '[{%'
          AND LTRIM(content) NOT LIKE '["%'
          AND chatId NOT IN (SELECT id FROM chats WHERE chatType = 'SECRET')
        """
    )
    suspend fun countSearchableWithContent(): Int

    // 分批加载，用于 refreshIndex 增量建索引；游标分页（按 timestamp,id 稳定排序）替代 OFFSET，
    // 避免刷新期间并发插入使 OFFSET 跳过/重复行，导致历史消息长期不被索引、全局搜索漏结果。
    @Query("""
        SELECT * FROM messages
        WHERE type IN ('TEXT', 'MARKDOWN', 'VOICE', 'LOCATION', 'NUDGE', 'IMAGE', 'GIF', 'STICKER', 'VIDEO', 'FILE')
          AND chatId NOT IN (SELECT id FROM chats WHERE chatType = 'SECRET')
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
          AND chatId NOT IN (SELECT id FROM chats WHERE chatType = 'SECRET')
          AND chatId NOT IN (SELECT chatId FROM chat_locks)
          AND content LIKE '%' || :keyword || '%' ESCAPE '\'
        GROUP BY chatId
        ORDER BY MAX(timestamp) DESC
        LIMIT :limit
        """
    )
    suspend fun searchChatIdsByMessageContent(keyword: String, limit: Int = 50): List<String>
}
