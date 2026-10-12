package com.maodouchat.server.db

import org.jetbrains.exposed.sql.Table

// 好友与位置表。

object FriendRequests : Table("friend_requests") {
    val id = varchar("id", 80)
    val fromUserId = varchar("from_user_id", 50) references Users.id
    val toUserId = varchar("to_user_id", 50) references Users.id
    val message = varchar("message", 300).default("")
    val status = varchar("status", 20).default("PENDING")
    val createdAt = long("created_at").default(System.currentTimeMillis())
    val updatedAt = long("updated_at").default(System.currentTimeMillis())
    override val primaryKey = PrimaryKey(id)

    init {
        index("idx_friend_requests_to_status", false, toUserId, status)
        index("idx_friend_requests_from_status", false, fromUserId, status)
        index("idx_friend_requests_pair", false, fromUserId, toUserId)
    }
}

/** 已建立好友关系（无向边，存 min/max userId 序） */

object Friendships : Table("friendships") {
    val userLowId = varchar("user_low_id", 50) references Users.id
    val userHighId = varchar("user_high_id", 50) references Users.id
    val createdAt = long("created_at").default(System.currentTimeMillis())
    override val primaryKey = PrimaryKey(userLowId, userHighId)

    init {
        index("idx_friendships_high", false, userHighId)
    }
}

/**
 * 9.3xx：群邀请同意流程——成员被拉入群前必须由本人接受。
 * 状态：PENDING（待接受）/ ACCEPTED / DECLINED / CANCELLED。
 * 唯一索引 (chat_id, user_id)：同一用户在同一群只能存在一条邀请记录（重复邀请幂等刷新）。
 */

object BlockedUsers : Table("blocked_users") {
    val blockerId = varchar("blocker_id", 50) references Users.id
    val blockedId = varchar("blocked_id", 50) references Users.id
    override val primaryKey = PrimaryKey(blockerId, blockedId)

    // 8.48 修复 M3：每次读消息/未读都执行 blocked_id = ?（双向拉黑过滤），
    // 主键 (blocker_id, blocked_id) 无法支撑该查询 → 补单列索引消除全表扫描
    init {
        index("idx_blocked_users_blocked_id", false, blockedId)
    }
}

object UserLocations : Table("user_locations") {
    val userId = varchar("user_id", 50) references Users.id
    val latitude = double("latitude")
    val longitude = double("longitude")
    val visible = bool("visible").default(true)
    val updatedAt = long("updated_at")
    val expiresAt = long("expires_at")
    override val primaryKey = PrimaryKey(userId)

    init {
        index("idx_user_locations_visible_expires", false, visible, expiresAt)
    }
}

