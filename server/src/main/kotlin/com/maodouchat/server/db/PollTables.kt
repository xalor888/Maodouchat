package com.maodouchat.server.db

import org.jetbrains.exposed.sql.Table

/**
 * 群玩法 B3：群签到 / 群接龙 / 群 PK 数据表。
 *
 * 与 GroupPolls/GroupPollVotes（位于 Database.kt）同属公开群内元数据，
 * 明文存储即可。表结构均为轻量自增主键 + chatId 复合索引，避免全表扫描。
 */

/**
 * 群签到记录。每人每天在每群最多一行，(chatId, userId, checkinDate) 天然去重。
 */
object GroupCheckins : Table("group_checkins") {
    val chatId = varchar("chat_id", 64)
    val userId = varchar("user_id", 64)
    val checkinDate = varchar("checkin_date", 16) // yyyy-MM-dd（服务端本地时区）
    val streak = integer("streak") // 连续签到天数（含当天）
    val totalCount = integer("total_count") // 累计签到天数
    val checkedAt = long("checked_at")
    override val primaryKey = PrimaryKey(chatId, userId, checkinDate)

    // 排行按 (chatId, checkinDate) 拉取当日签到表；我的连续天数按 (chatId, userId) 倒序扫描
    init {
        index("idx_group_checkins_chat_date", false, chatId, checkinDate)
        index("idx_group_checkins_user", false, chatId, userId)
    }
}

/**
 * 群接龙主表。一个接龙一个主题，active 期间成员可接龙。
 */
object GroupChains : Table("group_chains") {
    val id = varchar("id", 64)
    val chatId = varchar("chat_id", 64)
    val creatorId = varchar("creator_id", 64)
    val title = varchar("title", 200)
    val topic = varchar("topic", 500)
    val maxEntries = integer("max_entries").default(200)
    val active = bool("active").default(true)
    val createdAt = long("created_at")
    val closedAt = long("closed_at").nullable()
    override val primaryKey = PrimaryKey(id)

    init {
        index("idx_group_chains_chat_created", false, chatId, createdAt)
    }
}

/**
 * 群接龙明细。每人每接龙限一条，(chainId, userId) 由路由层去重检查，
 * sequence 单调递增保证展示顺序。
 */
object GroupChainEntries : Table("group_chain_entries") {
    val id = varchar("id", 64)
    val chainId = varchar("chain_id", 64)
    val userId = varchar("user_id", 64)
    val sequence = integer("sequence")
    val content = varchar("content", 500)
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(id)

    init {
        index("idx_group_chain_entries_chain_seq", false, chainId, sequence)
        index("idx_group_chain_entries_chain_user", false, chainId, userId)
    }
}

/**
 * 群 PK 回合。left/right 为双方标题（成员名或任意选项），成员投票二选一。
 */
object GroupPkRounds : Table("group_pk_rounds") {
    val id = varchar("id", 64)
    val chatId = varchar("chat_id", 64)
    val creatorId = varchar("creator_id", 64)
    val leftTitle = varchar("left_title", 120)
    val rightTitle = varchar("right_title", 120)
    val active = bool("active").default(true)
    val createdAt = long("created_at")
    val closedAt = long("closed_at").nullable()
    override val primaryKey = PrimaryKey(id)

    init {
        index("idx_group_pk_chat_created", false, chatId, createdAt)
    }
}

/**
 * 群 PK 投票。每人每回合可投一次、可改票，primaryKey(pkId, userId) 去重。
 */
object GroupPkVotes : Table("group_pk_votes") {
    val pkId = varchar("pk_id", 64)
    val userId = varchar("user_id", 64)
    val choice = varchar("choice", 10) // "left" | "right"
    val votedAt = long("voted_at")
    override val primaryKey = PrimaryKey(pkId, userId)

    init {
        index("idx_group_pk_votes_pk", false, pkId)
    }
}
