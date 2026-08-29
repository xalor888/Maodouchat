package com.maodouchat.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.maodouchat.data.local.entity.ChatEntity
import kotlinx.coroutines.flow.Flow

/**
 * B7 索引映射（v27→v28 追加，见 AppDatabase.MIGRATION_27_28；查询语句本身未改动）：
 * - [getActiveChats]（archived=0 + pinnedAt DESC + lastMessageTime DESC）
 *   → index_chats_archived_pinnedAt_lastMessageTime (archived, pinnedAt, lastMessageTime)
 * - [getArchivedChats] / [getAllChats]（lastMessageTime DESC）→ index_chats_lastMessageTime 既有索引
 */
@Dao
interface ChatDao {

    // 8.48 修复：排序首键由 CASE 表达式改为 pinnedAt 列本身（pinnedAt DESC 语义等价：
    // 置顶的大值在前、未置顶的 0 在后）——CASE 表达式无法命中
    // index_chats_archived_pinnedAt_lastMessageTime 完成排序，只能过滤后回表+临时文件
    @Query("""
        SELECT * FROM chats
        WHERE archived = 0
        ORDER BY pinnedAt DESC, lastMessageTime DESC
    """)
    fun getActiveChats(): Flow<List<ChatEntity>>

    @Query("SELECT * FROM chats WHERE archived = 1 ORDER BY lastMessageTime DESC")
    fun getArchivedChats(): Flow<List<ChatEntity>>

    @Query("SELECT * FROM chats ORDER BY lastMessageTime DESC")
    fun getAllChats(): Flow<List<ChatEntity>>

    /** 一次性列表（后台同步 worker 用，避免挂起 Flow 观察）。 */
    @Query("SELECT * FROM chats WHERE id != '' ORDER BY lastMessageTime DESC")
    suspend fun getAllChatsDirect(): List<ChatEntity>

    @Query("SELECT * FROM chats WHERE id = :chatId")
    suspend fun getChatById(chatId: String): ChatEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM chats WHERE id = :chatId AND chatType = 'SECRET')")
    suspend fun isSecretChat(chatId: String): Boolean

    @Query("SELECT id FROM chats WHERE chatType = 'SECRET'")
    suspend fun listSecretChatIds(): List<String>

    // SQLite REPLACE deletes the existing parent row before inserting it again. Because
    // messages.chatId has ON DELETE CASCADE, REPLACE here used to erase the complete local
    // message history (and its search index) whenever a cached chat was refreshed.
    @Upsert
    suspend fun insertChats(chats: List<ChatEntity>)

    @Query("DELETE FROM chats WHERE id = :chatId")
    suspend fun deleteChatById(chatId: String)

    @Query("DELETE FROM chats")
    suspend fun deleteAllChats()

    @Query("UPDATE chats SET pinnedAt = :timestamp, settingsUpdatedAt = :now WHERE id = :chatId")
    suspend fun setPinned(chatId: String, timestamp: Long, now: Long = System.currentTimeMillis())

    @Query("UPDATE chats SET pinnedAt = 0, settingsUpdatedAt = :now WHERE id = :chatId")
    suspend fun setUnpinned(chatId: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE chats SET archived = :archived, settingsUpdatedAt = :now WHERE id = :chatId")
    suspend fun setArchived(chatId: String, archived: Boolean, now: Long = System.currentTimeMillis())

    @Query("UPDATE chats SET notificationsMuted = :muted, settingsUpdatedAt = :now WHERE id = :chatId")
    suspend fun setMuted(chatId: String, muted: Boolean, now: Long = System.currentTimeMillis())

    @Query("UPDATE chats SET markedUnread = :marked, settingsUpdatedAt = :now WHERE id = :chatId")
    suspend fun setMarkedUnread(chatId: String, marked: Boolean, now: Long = System.currentTimeMillis())

    /** 跨设备已读同步：清零该会话未读数（服务端 CHAT_MARKED_READ 广播）。 */
    @Query("UPDATE chats SET unreadCount = 0, settingsUpdatedAt = :now WHERE id = :chatId")
    suspend fun markAllRead(chatId: String, now: Long = System.currentTimeMillis())

    /** Backlog 同步本地增量未读（服务端会话快照会覆盖校准，仅用于断线窗口兜底）。 */
    @Query("UPDATE chats SET unreadCount = unreadCount + :delta, settingsUpdatedAt = :now WHERE id = :chatId")
    suspend fun incrementUnread(chatId: String, delta: Int, now: Long = System.currentTimeMillis())

    /** Atomically advances the durable local conversation projection for a newly inserted v2 row. */
    @Query("""
        UPDATE chats SET
            lastMessage = CASE WHEN :timestamp >= lastMessageTime THEN :content ELSE lastMessage END,
            lastMessageType = CASE WHEN :timestamp >= lastMessageTime THEN :messageType ELSE lastMessageType END,
            lastMessageTime = CASE WHEN :timestamp >= lastMessageTime THEN :timestamp ELSE lastMessageTime END,
            unreadCount = unreadCount + :unreadDelta
        WHERE id = :chatId
    """)
    suspend fun projectMessageArrival(
        chatId: String,
        content: String,
        messageType: String,
        timestamp: Long,
        unreadDelta: Int,
    )
}
