package com.maodouchat.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.maodouchat.data.local.entity.ArchiveSuggestionDismissalEntity

@Dao
interface ArchiveDismissalDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun add(entity: ArchiveSuggestionDismissalEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun addBlocking(entity: ArchiveSuggestionDismissalEntity)

    @Query("SELECT chatId FROM archive_suggestion_dismissals WHERE ownerUserId = :ownerUserId")
    suspend fun dismissedIds(ownerUserId: String): List<String>

    @Query("SELECT chatId FROM archive_suggestion_dismissals WHERE ownerUserId = :ownerUserId")
    fun dismissedIdsBlocking(ownerUserId: String): List<String>

    @Query("DELETE FROM archive_suggestion_dismissals WHERE ownerUserId = :ownerUserId")
    suspend fun deleteForUser(ownerUserId: String): Int

    @Query("DELETE FROM archive_suggestion_dismissals WHERE ownerUserId = :ownerUserId")
    fun deleteForUserBlocking(ownerUserId: String): Int
}
