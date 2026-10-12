package com.maodouchat.server.db

import org.jetbrains.exposed.sql.Table

// 审核表。

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

const val MODERATION_AUDIT_DETAIL_MAX_CHARS = 800

