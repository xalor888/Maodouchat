package com.maodouchat.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.maodouchat.data.local.entity.ChatDraftEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDraftDao {
    @Query("SELECT * FROM chat_drafts WHERE ownerUserId = :ownerUserId ORDER BY updatedAt DESC")
    fun observeForOwner(ownerUserId: String): Flow<List<ChatDraftEntity>>

    @Query("SELECT * FROM chat_drafts WHERE ownerUserId = :ownerUserId AND chatId = :chatId LIMIT 1")
    suspend fun get(ownerUserId: String, chatId: String): ChatDraftEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(draft: ChatDraftEntity)

    @Query("DELETE FROM chat_drafts WHERE ownerUserId = :ownerUserId AND chatId = :chatId")
    suspend fun delete(ownerUserId: String, chatId: String)

    // 8.48 修复：删除会话草稿按 owner 隔离（此前仅按 chatId 全删，切换账号窗口期
    // 会清掉上一账号同名会话的草稿，违反主键 (ownerUserId, chatId) 的隔离契约）
    @Query("DELETE FROM chat_drafts WHERE ownerUserId = :ownerUserId AND chatId = :chatId")
    suspend fun deleteForChat(ownerUserId: String, chatId: String)

    @Query("DELETE FROM chat_drafts")
    suspend fun deleteAll()
}
