package com.maodouchat.server.db

import org.jetbrains.exposed.sql.Index
import org.jetbrains.exposed.sql.Table

/**
 * Signal 密钥存储表
 */
object SignalKeys : Table("signal_keys") {
    val id = varchar("id", 100)
    val userId = varchar("user_id", 50) references Users.id
    val deviceId = integer("device_id").default(1)
    val keyType = varchar("key_type", 50) // "identity_key", "signed_pre_key", "pre_key"
    val keyData = text("key_data") // Base64 编码的公钥数据
    val keyId = integer("key_id").nullable() // PreKey ID / SignedPreKey ID
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(id)

    // getBundle 按 (userId, deviceId, keyType) 查 → 复合索引，消息加解密必走
    init {
        index("idx_signal_keys_user_device_type", false, userId, deviceId, keyType)
    }
}

/**
 * Signal 设备元数据。只保存展示信息和时间，不保存私钥材料。
 */
object SignalDevices : Table("signal_devices") {
    val userId = varchar("user_id", 50) references Users.id
    val deviceId = integer("device_id")
    val deviceName = varchar("device_name", 80).default("我的设备")
    val status = varchar("status", 20).default("CONFIRMED")
    val confirmedAt = long("confirmed_at").nullable()
    val confirmedByDeviceId = integer("confirmed_by_device_id").nullable()
    val createdAt = long("created_at").default(System.currentTimeMillis())
    val lastSeenAt = long("last_seen_at").default(System.currentTimeMillis())
    override val primaryKey = PrimaryKey(userId, deviceId)

    init {
        index("idx_signal_devices_user", false, userId)
        index("idx_signal_devices_user_status", false, userId, status)
    }
}

/**
 * WebRTC 信令消息表
 */
object SignalingMessages : Table("signaling_messages") {
    val id = varchar("id", 100)
    val fromUserId = varchar("from_user_id", 50) references Users.id
    val toUserId = varchar("to_user_id", 50) references Users.id
    val callId = varchar("call_id", 100).default("")
    val groupId = varchar("group_id", 100).default("")
    val groupMemberIds = text("group_member_ids").default("")
    val groupInvite = bool("group_invite").default(false)
    val type = varchar("type", 30) // "offer", "answer", "ice-candidate", "hang-up"
    val payload = text("payload") // SDP 或 ICE 数据
    val timestamp = long("timestamp")
    override val primaryKey = PrimaryKey(id)

    // consumeForUser 按 toUserId + timestamp ASC 查询 → 复合索引避免全表扫描
    init {
        index("idx_signaling_to_user_ts", false, toUserId, timestamp)
        index("idx_signaling_call", false, callId, fromUserId, toUserId)
        index("idx_signaling_group_call", false, groupId, callId)
    }
}
