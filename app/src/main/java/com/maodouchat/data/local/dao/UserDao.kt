package com.maodouchat.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.maodouchat.data.local.entity.UserEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UserDao {

    @Query("SELECT COUNT(*) FROM users")
    suspend fun getCount(): Int

    @Query("SELECT * FROM users ORDER BY name ASC")
    fun getAllUsers(): Flow<List<UserEntity>>

    @Query("SELECT * FROM users WHERE id = :userId")
    suspend fun getUserById(userId: String): UserEntity?

    /** 批量读取用户（8.31 性能修复 F14：cacheChats 逐用户 N+1 → 一次 SQL）。 */
    @Query("SELECT * FROM users WHERE id IN (:userIds)")
    suspend fun getUsersByIds(userIds: List<String>): List<UserEntity>

    @Query("UPDATE users SET nickname = :nickname WHERE id = :userId")
    suspend fun setNickname(userId: String, nickname: String?)

    @Query(
        """
        UPDATE users
        SET isOnline = CASE
                WHEN :onlineRevoked THEN 0
                WHEN :statusRevoked THEN isOnline
                ELSE :isOnline
            END,
            status = CASE WHEN :statusRevoked THEN '' ELSE status END,
            lastUpdated = :updatedAt
        WHERE id = :userId
        """
    )
    suspend fun applyRealtimeVisibility(
        userId: String,
        isOnline: Boolean,
        onlineRevoked: Boolean,
        statusRevoked: Boolean,
        updatedAt: Long
    )

    // [keyword] must be escaped via LikeQueryPolicy.escapeForContains (ESCAPE '\').
    // 8.48 修复：去掉 LOWER() 包裹（SQLite LIKE 对 ASCII 默认大小写不敏感，语义等价且 name
    // 列可直接参与索引）；IFNULL 保留以处理 NULL
    @Query(
        """
        SELECT * FROM users
        WHERE name LIKE '%' || :keyword || '%' ESCAPE '\'
           OR IFNULL(nickname, '') LIKE '%' || :keyword || '%' ESCAPE '\'
           OR IFNULL(email, '') LIKE '%' || :keyword || '%' ESCAPE '\'
           OR IFNULL(status, '') LIKE '%' || :keyword || '%' ESCAPE '\'
        ORDER BY name ASC
        LIMIT :limit
        """
    )
    suspend fun searchUsers(keyword: String, limit: Int = 50): List<UserEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUsers(users: List<UserEntity>)

    @Query("DELETE FROM users")
    suspend fun deleteAllUsers()
}
