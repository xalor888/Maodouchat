package com.maodouchat.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.maodouchat.data.local.entity.IdentityTrustEntity

@Dao
interface IdentityTrustDao {

    @Query("SELECT * FROM identity_trust WHERE accountId = :accountId AND remoteUserId = :remoteUserId")
    suspend fun getAllTrustForUser(accountId: String, remoteUserId: String): List<IdentityTrustEntity>

    @Query("SELECT * FROM identity_trust WHERE accountId = :accountId AND remoteUserId = :remoteUserId AND deviceId = :deviceId")
    suspend fun getTrust(accountId: String, remoteUserId: String, deviceId: Int): IdentityTrustEntity?

    @Query("SELECT * FROM identity_trust WHERE accountId = :accountId AND remoteUserId = :remoteUserId AND deviceId = :deviceId")
    fun getTrustBlocking(accountId: String, remoteUserId: String, deviceId: Int): IdentityTrustEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTrust(entity: IdentityTrustEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertTrustBlocking(entity: IdentityTrustEntity)

    @Query("UPDATE identity_trust SET trustState = :trustState, verifiedAt = :verifiedAt, lastSeenAt = :lastSeenAt WHERE accountId = :accountId AND remoteUserId = :remoteUserId AND deviceId = :deviceId")
    suspend fun updateTrustState(accountId: String, remoteUserId: String, deviceId: Int, trustState: String, verifiedAt: Long?, lastSeenAt: Long)

    @Query("DELETE FROM identity_trust WHERE accountId = :accountId")
    suspend fun deleteForAccount(accountId: String)

    @Query("DELETE FROM identity_trust")
    suspend fun deleteAllTrust()
}
