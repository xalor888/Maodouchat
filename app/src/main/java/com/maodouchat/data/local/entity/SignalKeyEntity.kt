package com.maodouchat.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Signal 加密密钥持久化存储
 *
 * 存储身份密钥对、签名预密钥、注册 ID 等
 * 进程重启后可恢复加密状态
 */
@Entity(tableName = "signal_keys")
data class SignalKeyEntity(
    @PrimaryKey
    val keyType: String, // "identity_key_pair", "signed_pre_key", "registration_id", "pre_keys"
    val keyData: String, // Base64 编码的密钥数据
    val createdAt: Long = System.currentTimeMillis()
)
