package com.maodouchat.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.maodouchat.data.local.entity.VoicePlayedEntity

@Dao
interface VoicePlayedDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun markPlayedBlocking(entity: VoicePlayedEntity)

    @Query("SELECT COUNT(*) FROM voice_played WHERE ownerUserId = :ownerUserId AND messageId = :messageId")
    fun isPlayedBlocking(ownerUserId: String, messageId: String): Int

    @Query("DELETE FROM voice_played WHERE ownerUserId = :ownerUserId")
    fun deleteForUserBlocking(ownerUserId: String): Int
}
