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

    // 9.236：ESCAPE '\'——accountId 为服务端返回不可信数据，调用侧需经
    // LikeQueryPolicy.escapeForPrefix 转义，防止 %/_ 通配符把匹配范围扩散到别的账号作用域
    @Query("SELECT * FROM signal_keys WHERE keyType LIKE :prefix || '%' ESCAPE '\\'")
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

    // 8.49：按账号作用域删除（scopedKey 前缀 user:<accountId>: / anonymous:），
    // 供 clearLocalState 软清除使用——全表删除会误毁同库其他账号的 Signal 身份。
    // 9.236：同 getKeysWithPrefix 加 ESCAPE，调用侧须转义 accountId
    @Query("DELETE FROM signal_keys WHERE keyType LIKE :prefix || '%' ESCAPE '\\'")
    suspend fun deleteKeysWithPrefix(prefix: String)

    @Query("DELETE FROM signal_keys")
    suspend fun deleteAllKeys()
}
