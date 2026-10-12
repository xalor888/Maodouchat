package com.maodouchat.server.db

import com.maodouchat.server.config.ServerConfig
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.update



/** 9.144：既有库加宽 reports 文本列（createMissingTablesAndColumns 只补列不扩列）。 */
internal fun widenReportsColumns() {
    val isPostgres = org.jetbrains.exposed.sql.transactions.TransactionManager.current()
        .db.vendor.contains("postgres", ignoreCase = true)
    val alters = if (isPostgres) {
        listOf(
            "ALTER TABLE reports ALTER COLUMN reason TYPE VARCHAR(80)",
            "ALTER TABLE reports ALTER COLUMN description TYPE VARCHAR(800)",
            "ALTER TABLE reports ALTER COLUMN resolution_note TYPE VARCHAR(800)",
        )
    } else {
        listOf(
            "ALTER TABLE reports ALTER COLUMN reason VARCHAR(80)",
            "ALTER TABLE reports ALTER COLUMN description VARCHAR(800)",
            "ALTER TABLE reports ALTER COLUMN resolution_note VARCHAR(800)",
        )
    }
    alters.forEach { TransactionManager.current().exec(it) }
}



/** 9.144：既有库加宽 risk_events.matched（同 reports）。 */
internal fun widenRiskEventsMatchedColumn() {
    val isPostgres = org.jetbrains.exposed.sql.transactions.TransactionManager.current()
        .db.vendor.contains("postgres", ignoreCase = true)
    val sql = if (isPostgres) {
        "ALTER TABLE risk_events ALTER COLUMN \"matched\" TYPE VARCHAR(280)"
    } else {
        "ALTER TABLE risk_events ALTER COLUMN \"matched\" VARCHAR(280)"
    }
    TransactionManager.current().exec(sql)
}



/** 9.154：既有库加宽 moderation_audit_log.detail 500→800（同 reports 口径，createMissingTablesAndColumns 只补列不扩列）。 */
internal fun widenModerationAuditDetailColumn() {
    val isPostgres = org.jetbrains.exposed.sql.transactions.TransactionManager.current()
        .db.vendor.contains("postgres", ignoreCase = true)
    val sql = if (isPostgres) {
        "ALTER TABLE moderation_audit_log ALTER COLUMN detail TYPE VARCHAR(800)"
    } else {
        "ALTER TABLE moderation_audit_log ALTER COLUMN detail VARCHAR(800)"
    }
    TransactionManager.current().exec(sql)
}



internal fun backfillModerationRules() {
    val now = System.currentTimeMillis()
    DEFAULT_MODERATION_RULES.forEach { seed ->
        if (ModerationRules.selectAll().where { ModerationRules.id eq seed.id }.firstOrNull() != null) {
            // 修复旧数据：rule_spam_keywords / rule_short_link 早期以 KEYWORD/URL 类型存了
            // 正则 pattern（字面量匹配永不命中，默认内容安全防线形同虚设），统一纠正为 REGEX。
            if (seed.matchType == "REGEX") {
                val existingType = ModerationRules.select(ModerationRules.matchType)
                    .where { ModerationRules.id eq seed.id }.firstOrNull()?.get(ModerationRules.matchType)
                if (existingType != "REGEX") {
                    ModerationRules.update({ ModerationRules.id eq seed.id }) { it[ModerationRules.matchType] = "REGEX" }
                }
            }
            return@forEach
        }
        ModerationRules.insert {
            it[ModerationRules.id] = seed.id
            it[ModerationRules.name] = seed.name
            it[ModerationRules.description] = seed.description
            it[ModerationRules.scope] = seed.scope
            it[ModerationRules.matchType] = seed.matchType
            it[ModerationRules.pattern] = seed.pattern
            it[ModerationRules.action] = seed.action
            it[ModerationRules.windowMs] = seed.windowMs
            it[ModerationRules.hitThreshold] = seed.hitThreshold
            it[ModerationRules.escalationAction] = seed.escalationAction
            it[ModerationRules.enabled] = true
            it[ModerationRules.priority] = seed.priority
            it[ModerationRules.createdAt] = now
            it[ModerationRules.updatedAt] = now
        }
    }
}



private data class SeedModerationRule(
    val id: String,
    val name: String,
    val description: String,
    val scope: String,
    val matchType: String,
    val pattern: String,
    val action: String,
    val windowMs: Long,
    val hitThreshold: Int,
    val escalationAction: String?,
    val priority: Int
)

private val DEFAULT_MODERATION_RULES = listOf(
    SeedModerationRule(
        id = "rule_spam_keywords",
        name = "常见营销引流关键词",
        description = "命中常见营销、博彩或引流词时进入人工复核",
        scope = "ALL",
        matchType = "REGEX",
        pattern = "兼职\\s*日结|免费\\s*约|加我\\s*v?x|代刷|网赚|博彩|网赌",
        action = "WARN_MOD",
        windowMs = 24L * 60L * 60L * 1000L,
        hitThreshold = 3,
        escalationAction = "AUTO_RATE_LIMIT",
        priority = 50
    ),
    SeedModerationRule(
        id = "rule_short_link",
        name = "可疑短链",
        description = "动态或评论中出现常见短链时进入人工复核",
        scope = "ALL",
        matchType = "REGEX",
        pattern = "(?:t\\.cn|bit\\.ly|tinyurl\\.com|goo\\.gl|is\\.gd|ow\\.ly|buff\\.ly|adf\\.ly)",
        action = "WARN_MOD",
        windowMs = 24L * 60L * 60L * 1000L,
        hitThreshold = 2,
        escalationAction = "AUTO_HOLD",
        priority = 80
    ),
    SeedModerationRule(
        id = "rule_post_flood",
        name = "高频发动态",
        description = "一分钟内发布超过五条动态会进入限流",
        scope = "POST",
        matchType = "FREQUENCY",
        pattern = "*",
        action = "AUTO_RATE_LIMIT",
        windowMs = 60_000L,
        hitThreshold = 5,
        escalationAction = "AUTO_HOLD",
        priority = 20
    ),
    SeedModerationRule(
        id = "rule_comment_flood",
        name = "高频发评论",
        description = "一分钟内发布超过十条评论会进入限流",
        scope = "COMMENT",
        matchType = "FREQUENCY",
        pattern = "*",
        action = "AUTO_RATE_LIMIT",
        windowMs = 60_000L,
        hitThreshold = 10,
        escalationAction = "AUTO_HOLD",
        priority = 20
    )
)



internal fun backfillModeratorEmails() {
    // 8.46：改为 Exposed 参数化 DSL，替代原生 SQL 字符串拼接（配置来源也统一走参数绑定，
    // 彻底消除注入面；语义与旧 LOWER(email) IN (...) 完全一致）。
    val emails = com.maodouchat.server.config.ServerConfig.moderatorEmails.map { it.lowercase() }
    if (emails.isEmpty()) return
    Users.update({ Users.email.lowerCase() inList emails }) {
        it[Users.isModerator] = true
    }
}



internal fun backfillMemberRoles() {
    try {
        TransactionManager.current().exec("UPDATE chat_participants SET \"role\" = 'MEMBER' WHERE \"role\" IS NULL OR \"role\" = ''")
    } catch (e: Exception) {
        // Column naming differs across H2/Postgres migrations; rethrow without leaking schema dumps.
        throw e
    }
}
