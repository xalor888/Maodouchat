package com.maodouchat.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.maodouchat.data.local.entity.MessageSearchDocumentEntity
import com.maodouchat.data.local.entity.MessageSearchFingerprint
import com.maodouchat.data.local.entity.MessageSearchMatchRow
import com.maodouchat.data.local.entity.MessageSearchTokenEntity

@Dao
interface MessageSearchDao {

    @Query("SELECT messageId, contentHash FROM message_search_documents")
    suspend fun getFingerprints(): List<MessageSearchFingerprint>

    @Query("SELECT messageId, contentHash FROM message_search_documents WHERE messageId IN (:ids)")
    suspend fun getFingerprintsForIds(ids: List<String>): List<MessageSearchFingerprint>

    /** 索引文档总数（全量刷新新鲜度判断用）。 */
    @Query("SELECT COUNT(*) FROM message_search_documents")
    suspend fun countDocuments(): Int

    @Query("SELECT contentHash FROM message_search_documents WHERE messageId = :messageId LIMIT 1")
    suspend fun getContentHash(messageId: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDocument(document: MessageSearchDocumentEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTokens(tokens: List<MessageSearchTokenEntity>)

    @Query("DELETE FROM message_search_tokens WHERE messageId = :messageId")
    suspend fun deleteTokens(messageId: String)

    @Query("DELETE FROM message_search_documents WHERE messageId = :messageId")
    suspend fun deleteDocumentRow(messageId: String)

    /**
     * Drop both document and tokens for a message.
     * Call sites historically named this [deleteDocument]; tokens must go too or the
     * token table grows orphan rows after delete/revoke (JOIN would hide them but prefs/DB bloat).
     */
    @Transaction
    suspend fun deleteDocument(messageId: String) {
        if (messageId.isBlank()) return
        deleteTokens(messageId)
        deleteDocumentRow(messageId)
    }

    @Query("DELETE FROM message_search_tokens WHERE chatId = :chatId")
    suspend fun deleteTokensForChat(chatId: String)

    @Query("DELETE FROM message_search_documents WHERE chatId = :chatId")
    suspend fun deleteDocumentsForChat(chatId: String)

    /**
     * 清理“消息已不存在或不再可搜索”的孤儿索引文档。
     * 用 SQL 子查询替代全量载入消息 ID 集合，避免大库重建索引时 OOM。
     */
    @Query("DELETE FROM message_search_documents WHERE messageId NOT IN (SELECT id FROM messages WHERE type IN (:types))")
    suspend fun deleteDocumentsNotInSearchableTypes(types: List<String>)

    @Query("DELETE FROM message_search_tokens")
    suspend fun deleteAllTokens()

    @Query("DELETE FROM message_search_documents")
    suspend fun deleteAllDocuments()

    @Transaction
    suspend fun deleteChatIndex(chatId: String) {
        if (chatId.isBlank()) return
        deleteTokensForChat(chatId)
        deleteDocumentsForChat(chatId)
    }

    /** Full wipe used by non-destroy session purge (account switch soft path). */
    @Transaction
    suspend fun deleteAll() {
        deleteAllTokens()
        deleteAllDocuments()
    }

    @Query(
        """
        SELECT d.messageId, d.chatId, d.senderId, d.searchableText,
            d.contentHash, d.timestamp, d.indexedAt, d.messageType,
            COUNT(DISTINCT t.token) AS matchCount
        FROM message_search_documents d
        INNER JOIN message_search_tokens t ON t.messageId = d.messageId
        WHERE t.token IN (:tokens)
        GROUP BY d.messageId
        ORDER BY matchCount DESC, d.timestamp DESC
        LIMIT :limit
        """
    )
    suspend fun search(tokens: List<String>, limit: Int): List<MessageSearchMatchRow>

    /**
     * 按消息类型过滤的搜索：仅返回 messageType 属于 [types] 的结果。
     * 用于全局搜索的类型筛选（图片/文件/视频/语音/链接等）。
     */
    @Query(
        """
        SELECT d.messageId, d.chatId, d.senderId, d.searchableText,
            d.contentHash, d.timestamp, d.indexedAt, d.messageType,
            COUNT(DISTINCT t.token) AS matchCount
        FROM message_search_documents d
        INNER JOIN message_search_tokens t ON t.messageId = d.messageId
        WHERE t.token IN (:tokens) AND d.messageType IN (:types)
        GROUP BY d.messageId
        ORDER BY matchCount DESC, d.timestamp DESC
        LIMIT :limit
        """
    )
    suspend fun searchByTypes(tokens: List<String>, types: List<String>, limit: Int): List<MessageSearchMatchRow>

    @Transaction
    suspend fun replaceDocument(
        document: MessageSearchDocumentEntity,
        tokens: List<MessageSearchTokenEntity>
    ) {
        upsertDocument(document)
        deleteTokens(document.messageId)
        if (tokens.isNotEmpty()) insertTokens(tokens)
    }
}
