package com.maodouchat.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.maodouchat.data.local.entity.SignalKeyEntity

@Dao
interface SignalKeyDao {

    @Query("SELECT * FROM signal_keys WHERE keyType = :keyType")
    suspend fun getKey(keyType: String): SignalKeyEntity?

    @Query("SELECT * FROM signal_keys WHERE keyType = :keyType")
    fun getKeyBlocking(keyType: String): SignalKeyEntity?

    @Query("SELECT * FROM signal_keys WHERE keyType LIKE :prefix || '%'")
    suspend fun getKeysWithPrefix(prefix: String): List<SignalKeyEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertKey(key: SignalKeyEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertKeyBlocking(key: SignalKeyEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertKeys(keys: List<SignalKeyEntity>)

    @Query("DELETE FROM signal_keys WHERE keyType = :keyType")
    suspend fun deleteKey(keyType: String)

    @Query("DELETE FROM signal_keys WHERE keyType = :keyType")
    fun deleteKeyBlocking(keyType: String)

    @Query("DELETE FROM signal_keys")
    suspend fun deleteAllKeys()
}
