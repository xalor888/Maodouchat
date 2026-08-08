package com.maodouchat.server.db

import org.jetbrains.exposed.sql.Table

/**
 * B6 服务端运维增强 —— 表结构（与 Database.kt 同包，initDatabase 统一建表）。
 *
 * 约束：
 * - 绝不存储 E2EE 消息明文；公告为服务端广播的明文系统消息（非会话正文）。
 * - 管理端点相关数据仅主管理员可读写（路由层双重门控）。
 * - 所有变更操作由 AdminEnhanceRouting 写入 ModerationAuditLog 审计。
 */

/**
 * 系统公告：服务端广播的公告（维护通知 / 安全预警 / 功能上线等）。
 * 非 E2EE 会话内容，属平台级广播；`targetAudience=ALL` 全员可见，
 * `targetAudience=TAGGED` 仅对带 [targetTagId] 标签的用户可见。
 */
object SystemAnnouncements : Table("system_announcements") {
    val id = varchar("id", 100)
    val title = varchar("title", 200)
    val content = text("content")
    /** INFO / WARNING / MAINTENANCE / EMERGENCY */
    val level = varchar("level", 20).default("INFO")
    /** ALL / TAGGED */
    val targetAudience = varchar("target_audience", 20).default("ALL")
    val targetTagId = varchar("target_tag_id", 80).nullable()
    /** 生效窗口；startsAt<=now<=expiresAt 且 status=ACTIVE 才对用户可见 */
    val startsAt = long("starts_at")
    val expiresAt = long("expires_at")
    /** DRAFT / SCHEDULED / ACTIVE / EXPIRED / CANCELLED */
    val status = varchar("status", 20).default("DRAFT")
    val createdBy = varchar("created_by", 50).nullable()
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
    val publishedAt = long("published_at").nullable()
    val cancelledAt = long("cancelled_at").nullable()
    val cancelledBy = varchar("cancelled_by", 50).nullable()
    override val primaryKey = PrimaryKey(id)

    // 用户拉取活跃公告：WHERE status='ACTIVE' AND starts_at<=now AND expires_at>=now
    init {
        index("idx_announcements_status_window", false, status, startsAt, expiresAt)
        index("idx_announcements_created_at", false, createdAt)
    }
}

/** 公告已读确认：用户对公告的 ack 记录（用于「已读/未读」运营统计）。 */
object AnnouncementAcks : Table("announcement_acks") {
    val announcementId = varchar("announcement_id", 100) references SystemAnnouncements.id
    val userId = varchar("user_id", 50) references Users.id
    val ackedAt = long("acked_at")
    override val primaryKey = PrimaryKey(announcementId, userId)
}

/**
 * 用户标签：运营给用户打的风险/分群标签，与风控联动（riskLevel >= HIGH 可触发处置）。
 * 标签仅存元数据，不存任何明文内容。
 */
object UserTags : Table("user_tags") {
    val id = varchar("id", 80)
    val name = varchar("name", 60)
    val color = varchar("color", 20).default("#64748b")
    val description = varchar("description", 300).nullable()
    /** 系统内置标签（不可删除） */
    val isSystem = bool("is_system").default(false)
    /** NONE / LOW / MEDIUM / HIGH / CRITICAL —— 风控联动级别 */
    val riskLevel = varchar("risk_level", 20).default("LOW")
    val createdBy = varchar("created_by", 50).nullable()
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex("uidx_user_tags_name", name)
    }
}

/** 用户-标签多对多。assignAuto=true 时记录来源以便审计区分手动/自动打标。 */
object UserTagAssignments : Table("user_tag_assignments") {
    val tagId = varchar("tag_id", 80) references UserTags.id
    val userId = varchar("user_id", 50) references Users.id
    /** 手动：MANUAL；风控规则自动：AUTO；公告按标签定向可见依据 */
    val assignmentSource = varchar("source", 20).default("MANUAL")
    val assignedBy = varchar("assigned_by", 50).nullable()
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(tagId, userId)

    init {
        index("idx_user_tag_assignments_user", false, userId)
    }
}

/**
 * 审计时间范围导出登记：管理员按时间段导出审计/风控/标签/公告变更记录时留痕，
 * 便于追溯「谁在什么时间导出了什么范围的数据」。
 */
object AuditExportRecords : Table("audit_export_records") {
    val id = varchar("id", 100)
    val actorId = varchar("actor_id", 50)
    /** 导出范围：ADMIN_AUDIT / RISK_EVENTS / ANNOUNCEMENTS / USER_TAGS */
    val scope = varchar("scope", 30)
    val fromMs = long("from_ms")
    val toMs = long("to_ms")
    val rowCount = long("row_count").default(0)
    /** 生成的 CSV 文件名（保留元数据，不存数据正文） */
    val fileRef = varchar("file_ref", 200).nullable()
    val requestedAt = long("requested_at")
    override val primaryKey = PrimaryKey(id)

    init {
        index("idx_audit_export_actor_created", false, actorId, requestedAt)
    }
}

/**
 * 限流仪表盘快照：GlobalRateLimiter 每 60s 采样的分钟桶。
 * 仪表盘按时间范围聚合（1h / 24h / 7d）；采样器同时负责清理过期桶。
 */
object RateLimitStatsSnapshots : Table("rate_limit_stats_snapshots") {
    val id = long("id").autoIncrement()
    /** 分钟桶对齐时间（now - now % 60000） */
    val bucketStartMs = long("bucket_start_ms")
    val allowed = long("allowed")
    val rejected = long("rejected")
    val totalBuckets = integer("total_buckets")
    val maxBuckets = integer("max_buckets")
    val maxPerMinute = integer("max_per_minute")
    val rejectedAtCapacity = long("rejected_at_capacity").default(0)
    val sampledAt = long("sampled_at")
    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex("uidx_rate_limit_stats_bucket", bucketStartMs)
        index("idx_rate_limit_stats_bucket_start", false, bucketStartMs)
    }
}

/**
 * 设备事件一致性加固：
 * - [DeviceEventSequences] 记录每个 (userId, deviceId, eventType) 已应用的 seq，
 *   用于拒绝乱序/重复/过期事件（幂等写入）。
 * - [DeviceEventConsistencyLog] 记录一致性异常（STALE / DUPLICATE / OUT_OF_ORDER），
 *   供管理仪表盘观测异常率。
 */
object DeviceEventSequences : Table("device_event_sequences") {
    val userId = varchar("user_id", 50) references Users.id
    val deviceId = integer("device_id")
    /** 事件类型命名空间，如 WS_SYNC / MSG_RECEIPT / MUTATION */
    val eventType = varchar("event_type", 30)
    val lastAppliedSeq = long("last_applied_seq").default(0)
    val lastEventAt = long("last_event_at")
    override val primaryKey = PrimaryKey(userId, deviceId, eventType)
}

object DeviceEventConsistencyLog : Table("device_event_consistency_log") {
    val id = varchar("id", 100)
    val userId = varchar("user_id", 50)
    val deviceId = integer("device_id")
    val eventType = varchar("event_type", 30)
    val seq = long("seq")
    /** STALE / DUPLICATE / OUT_OF_ORDER / GAP_HEALED */
    val status = varchar("status", 20)
    val referenceId = varchar("reference_id", 100).nullable()
    val firstSeenAt = long("first_seen_at")
    val lastSeenAt = long("last_seen_at")
    val detail = varchar("detail", 300).nullable()
    override val primaryKey = PrimaryKey(id)

    init {
        index("idx_device_event_log_user_ts", false, userId, lastSeenAt)
        index("idx_device_event_log_status", false, status, lastSeenAt)
    }
}
