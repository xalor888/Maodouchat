package com.maodouchat.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.maodouchat.data.local.entity.NotificationCenterItemEntity

@Dao
interface NotificationCenterDao {
    @Query("SELECT * FROM notification_center_items WHERE ownerUserId = :ownerUserId ORDER BY updatedAt DESC")
    fun itemsForOwnerBlocking(ownerUserId: String): List<NotificationCenterItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertAllBlocking(entities: List<NotificationCenterItemEntity>)

    @Query("DELETE FROM notification_center_items WHERE ownerUserId = :ownerUserId")
    fun deleteForUserBlocking(ownerUserId: String): Int

    @Query("DELETE FROM notification_center_items WHERE ownerUserId = :ownerUserId AND updatedAt < :cutoff")
    fun deleteOlderThanBlocking(ownerUserId: String, cutoff: Long): Int

    @Transaction
    fun replaceAllBlocking(ownerUserId: String, entities: List<NotificationCenterItemEntity>) {
        deleteForUserBlocking(ownerUserId)
        if (entities.isNotEmpty()) upsertAllBlocking(entities)
    }
}
