package com.maodouchat.server.db

import org.jetbrains.exposed.sql.Table

// 动态、好友、位置与审核表。

object Posts : Table("posts") {
    val id = varchar("id", 100)
    val authorId = varchar("author_id", 50) references Users.id
    val content = text("content")
    val imageUrls = text("image_urls").default("[]")
    val visibility = varchar("visibility", 20).default("PUBLIC")
    val status = varchar("status", 20).default("PUBLISHED")
    val createdAt = long("created_at")
    /** 编辑时间戳，null 表示未编辑 */
    val editedAt = long("edited_at").nullable()
    override val primaryKey = PrimaryKey(id)

    // getFeed 按 createdAt DESC 分页游标翻页 → 索引截断全表排序
    init {
        index("idx_posts_created_at", false, createdAt)
    }
}

/** 动态图片唯一占用：一张上传图片只能被一条动态使用（DB 级防重复，跨进程/重启仍生效）。 */


object PostImageClaims : Table("post_image_claims") {
    val filename = varchar("filename", 200)
    val postId = varchar("post_id", 100) references Posts.id
    val claimedAt = long("claimed_at")
    override val primaryKey = PrimaryKey(filename)

    init {
        index("idx_post_image_claims_post", false, postId)
    }
}


object PostLikes : Table("post_likes") {
    val postId = varchar("post_id", 100) references Posts.id
    val userId = varchar("user_id", 50) references Users.id
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(postId, userId)

    // toPostResponse 按 postId 聚合计数 → 索引让 count() 直接走覆盖索引
    init {
        index("idx_post_likes_post_id", false, postId)
    }
}


object PostComments : Table("post_comments") {
    val id = varchar("id", 100)
    val postId = varchar("post_id", 100) references Posts.id
    val authorId = varchar("author_id", 50) references Users.id
    val content = text("content")
    val createdAt = long("created_at")
    /** 1.76：评论回复——被回复评论 id（null=顶级评论）。 */
    val parentId = varchar("parent_id", 100).nullable().default(null)
    override val primaryKey = PrimaryKey(id)

    // getComments 按 postId + createdAt 排序分页 → 复合索引避免 filesort
    init {
        index("idx_post_comments_post_created", false, postId, createdAt)
    }
}

/** 评论点赞（1.52）：一行一赞，PK(commentId, userId) 防重复，可取消。 */


object CommentLikes : Table("comment_likes") {
    val commentId = varchar("comment_id", 100) references PostComments.id
    val userId = varchar("user_id", 50) references Users.id
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(commentId, userId)

    init {
        index("idx_comment_likes_comment", false, commentId)
    }
}

/** 好友申请：PENDING / ACCEPTED / REJECTED / CANCELLED */


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


object Reports : Table("reports") {
    val id = varchar("id", 100)
    val reporterId = varchar("reporter_id", 50) references Users.id
    val targetType = varchar("target_type", 20)
    val targetId = varchar("target_id", 100)
    val chatId = varchar("chat_id", 50).nullable()
    val messageId = varchar("message_id", 100).nullable()
    // 9.144：列宽与 ReportWorkflow 常量对齐（80/800）——此前 60/500 窄于仓库截断上限，
    // PG 严格 VARCHAR(n) 下 61-80/501-800 字符直接 22001 → 500（H2 宽松模式不暴露）
    val reason = varchar("reason", 80)
    val description = varchar("description", 800).nullable()
    val status = varchar("status", 20).default("OPEN")
    val reviewerId = varchar("reviewer_id", 50).nullable()
    val resolutionNote = varchar("resolution_note", 800).nullable()
    val actionTaken = varchar("action_taken", 40).nullable()
    val actionAt = long("action_at").nullable()
    val createdAt = long("created_at").default(System.currentTimeMillis())
    val resolvedAt = long("resolved_at").nullable()
    override val primaryKey = PrimaryKey(id)

    init {
        index("idx_reports_reporter_created", false, reporterId, createdAt)
        index("idx_reports_status_created", false, status, createdAt)
        index("idx_reports_target", false, targetType, targetId)
    }
}

/** 管理员操作审计：用户/内容/规则变更留痕 */
/** 9.154：moderation_audit_log.detail 列宽与入库截断上限（auditDetail 前缀 + 500 字备注超 500）。 */
const val MODERATION_AUDIT_DETAIL_MAX_CHARS = 800


object ModerationAuditLog : Table("moderation_audit_log") {
    val id = varchar("id", 100).clientDefault { java.util.UUID.randomUUID().toString() }
    val actorId = varchar("actor_id", 50).nullable()
    val userId = varchar("user_id", 50).nullable()
    val action = varchar("action", 40)
    // 9.154：detail 加宽 500→800——auditDetail 前缀 + 500 字备注超 500 字节（PG 22001 → 整事务回滚，
    // 封禁落空且 500）；入库侧统一按 MODERATION_AUDIT_DETAIL_MAX_CHARS 截断
    val detail = varchar("detail", 800).nullable()
    val createdAt = long("created_at").default(System.currentTimeMillis())
    override val primaryKey = PrimaryKey(id)
}


object AiAuditLogs : Table("ai_audit_logs") {
    val id = varchar("id", 100)
    val userId = varchar("user_id", 50) references Users.id
    val chatId = varchar("chat_id", 50).nullable()
    val feature = varchar("feature", 40)
    val model = varchar("model", 80).nullable()
    val status = varchar("status", 30)
    val inputChars = integer("input_chars").default(0)
    val contextMessages = integer("context_messages").default(0)
    val durationMs = long("duration_ms").nullable()
    val error = varchar("error", 200).nullable()
    // 9.137：token 列正式进 Table 单例（此前靠运行时 ALTER TABLE + 裸 SQL 写入，
    // ALTER 失败会毒化 PG 事务并让 AI 主流程 500）。启动期 createMissingTablesAndColumns 自动补列。
    val inputTokens = long("input_tokens").nullable()
    val outputTokens = long("output_tokens").nullable()
    val createdAt = long("created_at").default(System.currentTimeMillis())
    override val primaryKey = PrimaryKey(id)

    init {
        index("idx_ai_audit_user_created", false, userId, createdAt)
    }
}


object ModerationRules : Table("moderation_rules") {
    val id = varchar("id", 80)
    val name = varchar("name", 100)
    val description = varchar("description", 500).nullable()
    val scope = varchar("scope", 20)
    val matchType = varchar("match_type", 20)
    val pattern = text("pattern")
    val action = varchar("action", 20)
    val windowMs = long("window_ms").default(0)
    val hitThreshold = integer("hit_threshold").default(0)
    val escalationAction = varchar("escalation_action", 20).nullable()
    val enabled = bool("enabled").default(true)
    val priority = integer("priority").default(100)
    val createdAt = long("created_at").default(System.currentTimeMillis())
    val updatedAt = long("updated_at").default(System.currentTimeMillis())
    override val primaryKey = PrimaryKey(id)

    init {
        index("idx_moderation_rules_enabled_priority", false, enabled, priority)
    }
}


object RiskEvents : Table("risk_events") {
    val id = varchar("id", 80)
    val userId = varchar("user_id", 50) references Users.id
    val sourceValue = varchar("source", 20)
    val ruleId = varchar("rule_id", 80).nullable()
    val action = varchar("action", 20)
    // 9.144：与 ModerationRuleRepository.MAX_MATCHED_LENGTH(280) 对齐，防 PG 22001
    val matched = varchar("matched", 280).nullable()
    val referenceId = varchar("reference_id", 100).nullable()
    val needsReview = bool("needs_review").default(false)
    val createdAt = long("created_at").default(System.currentTimeMillis())
    override val primaryKey = PrimaryKey(id)

    init {
        index("idx_risk_events_user_created", false, userId, createdAt)
        index("idx_risk_events_needs_review", false, needsReview, createdAt)
        index("idx_risk_events_rule_created", false, ruleId, createdAt)
    }
}
