package com.maodouchat.server.repository

import com.google.re2j.Pattern
import com.maodouchat.server.db.ModerationRules
import com.maodouchat.server.db.RiskEvents
import com.maodouchat.server.model.ModerationRuleResponse
import com.maodouchat.server.model.RiskEventResponse
import com.maodouchat.server.model.CreateModerationRuleRequest
import com.maodouchat.server.model.UpdateModerationRuleRequest
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

class ModerationRuleRepository {

    data class RuleMatch(
        val ruleId: String,
        val action: String,
        val matched: String?,
        val needsReview: Boolean
    )

    data class Evaluation(
        val blocked: Boolean,
        val action: String? = null,
        val message: String? = null,
        val matches: List<RuleMatch> = emptyList()
    )

    private data class RuleCandidate(
        val id: String,
        val matchType: String,
        val pattern: String,
        val action: String,
        val hitThreshold: Int,
        val recentCount: Int,
        val escalationAction: String?
    )

    private data class PendingRiskEvent(
        val ruleId: String,
        val action: String,
        val matched: String?,
        val needsReview: Boolean
    )

    fun evaluate(userId: String, source: String, content: String, referenceId: String? = null): Evaluation {
        val normalizedSource = source.trim().uppercase()
        val now = System.currentTimeMillis()
        // RE2/J is linear-time, but bounding user input also caps allocation and overall rule-evaluation work.
        val boundedContent = content.take(MAX_EVALUATE_CONTENT_LENGTH)

        // Only database reads/counts happen here. User-controlled regex compilation and matching must not
        // hold a JDBC connection or transaction open.
        val rules = transaction {
            val previousCleanup = lastCleanupAt.get()
            if (now - previousCleanup >= CLEANUP_INTERVAL_MS && lastCleanupAt.compareAndSet(previousCleanup, now)) {
                RiskEvents.deleteWhere { RiskEvents.createdAt less now - EVENT_RETENTION_MS }
            }
            ModerationRules.selectAll()
                .where { ModerationRules.enabled eq true }
                .orderBy(ModerationRules.priority to SortOrder.ASC)
                .filter { row -> row[ModerationRules.scope] == "ALL" || row[ModerationRules.scope] == normalizedSource }
                .map { rule ->
                    val ruleId = rule[ModerationRules.id]
                    val windowMs = rule[ModerationRules.windowMs].coerceAtLeast(0)
                    val recentCount = if (windowMs > 0) {
                        RiskEvents.selectAll().where {
                            (RiskEvents.userId eq userId) and
                                (RiskEvents.ruleId eq ruleId) and
                                (RiskEvents.createdAt greaterEq now - windowMs)
                        }.count().toInt()
                    } else {
                        0
                    }
                    RuleCandidate(
                        id = ruleId,
                        matchType = rule[ModerationRules.matchType],
                        pattern = rule[ModerationRules.pattern],
                        action = rule[ModerationRules.action],
                        hitThreshold = rule[ModerationRules.hitThreshold].coerceAtLeast(0),
                        recentCount = recentCount,
                        escalationAction = rule[ModerationRules.escalationAction]
                    )
                }
        }

        val pendingEvents = rules.mapNotNull { rule ->
            val matchedText: String? = when (rule.matchType) {
                "FREQUENCY" -> "${rule.recentCount + 1}/${rule.hitThreshold}"
                "REGEX" -> findRegexMatch(rule.pattern, boundedContent)?.take(MAX_MATCHED_LENGTH)
                // KEYWORD/URL are deliberately literal matches, not regular expressions.
                "KEYWORD", "URL" -> if (boundedContent.contains(rule.pattern, ignoreCase = true)) {
                    rule.pattern.take(MAX_MATCHED_LENGTH)
                } else {
                    null
                }
                else -> null
            }
            val isMatch = when (rule.matchType) {
                "FREQUENCY" -> rule.hitThreshold > 0
                "REGEX", "KEYWORD", "URL" -> matchedText != null
                else -> false
            }
            if (!isMatch) return@mapNotNull null

            val projectedCount = rule.recentCount + 1
            val effectiveAction = when {
                rule.matchType == "FREQUENCY" && projectedCount < rule.hitThreshold -> "OBSERVED"
                rule.hitThreshold > 0 && projectedCount >= rule.hitThreshold * 2 && !rule.escalationAction.isNullOrBlank() ->
                    rule.escalationAction!!
                else -> rule.action
            }
            PendingRiskEvent(
                ruleId = rule.id,
                action = effectiveAction,
                matched = matchedText,
                needsReview = effectiveAction in REVIEW_ACTIONS
            )
        }

        transaction {
            pendingEvents.forEach { event ->
                RiskEvents.insert {
                    it[RiskEvents.id] = "risk_${UUID.randomUUID()}"
                    it[RiskEvents.userId] = userId
                    it[RiskEvents.sourceValue] = normalizedSource
                    it[RiskEvents.ruleId] = event.ruleId
                    it[RiskEvents.action] = event.action
                    it[RiskEvents.matched] = event.matched
                    it[RiskEvents.referenceId] = referenceId
                    it[RiskEvents.needsReview] = event.needsReview
                    it[RiskEvents.createdAt] = now
                }
            }
        }

        val matches = pendingEvents
            .filter { it.action != "OBSERVED" }
            .map { RuleMatch(it.ruleId, it.action, it.matched, it.needsReview) }
        val strongest = matches.maxByOrNull { ACTION_RANK[it.action] ?: 0 }
        return when (strongest?.action) {
            "AUTO_DELETE" -> Evaluation(true, strongest.action, "内容触发安全规则，已被拦截", matches)
            "AUTO_HOLD" -> Evaluation(true, strongest.action, "内容已进入安全审核，请稍后再试", matches)
            "AUTO_RATE_LIMIT" -> Evaluation(true, strongest.action, "操作过于频繁，请稍后再试", matches)
            else -> Evaluation(false, strongest?.action, matches = matches)
        }
    }

    private fun findRegexMatch(pattern: String, content: String): String? {
        val compiled = runCatching { compileModerationRegex(pattern) }.getOrNull() ?: return null
        val matcher = compiled.matcher(content)
        return if (matcher.find()) matcher.group() else null
    }

    fun getRules(): List<ModerationRuleResponse> = transaction {
        ModerationRules.selectAll()
            .orderBy(ModerationRules.priority to SortOrder.ASC)
            .map { it.toRuleResponse() }
    }

    /** 规则是否存在（8.32 一致性：端点区分 404 与 400）。 */
    fun ruleExists(ruleId: String): Boolean = transaction {
        ModerationRules.selectAll().where { ModerationRules.id eq ruleId }.firstOrNull() != null
    }

    fun updateRule(ruleId: String, request: UpdateModerationRuleRequest): ModerationRuleResponse? = transaction {
        val existing = ModerationRules.selectAll().where { ModerationRules.id eq ruleId }.firstOrNull()
            ?: return@transaction null
        val name = request.name?.trim()?.take(100)
        if (name != null && name.isBlank()) return@transaction null
        val scope = request.scope?.trim()?.uppercase()
        if (scope != null && scope !in ALLOWED_SCOPES) return@transaction null
        val matchType = request.matchType?.trim()?.uppercase()
        if (matchType != null && matchType !in ALLOWED_MATCH_TYPES) return@transaction null
        val pattern = request.pattern?.trim()?.take(2_000)
        if (pattern != null && pattern.isBlank()) return@transaction null
        val action = request.action?.trim()?.uppercase()
        if (action != null && action !in ALLOWED_ACTIONS) return@transaction null
        val escalationAction = request.escalationAction?.trim()?.uppercase()
        if (escalationAction != null && escalationAction !in ALLOWED_ACTIONS) return@transaction null
        val effectiveMatchType = matchType ?: existing[ModerationRules.matchType]
        val effectivePattern = pattern ?: existing[ModerationRules.pattern]
        if (effectiveMatchType == "REGEX" && runCatching { compileModerationRegex(effectivePattern) }.isFailure) {
            return@transaction null
        }
        ModerationRules.update({ ModerationRules.id eq ruleId }) {
            name?.let { value -> it[ModerationRules.name] = value }
            scope?.let { value -> it[ModerationRules.scope] = value }
            matchType?.let { value -> it[ModerationRules.matchType] = value }
            pattern?.let { value ->
                it[ModerationRules.pattern] = value
                it[ModerationRules.description] = "${matchType ?: existing[ModerationRules.matchType]}:${value.take(60)}"
            }
            request.enabled?.let { value -> it[ModerationRules.enabled] = value }
            action?.let { value -> it[ModerationRules.action] = value }
            request.hitThreshold?.let { value -> it[ModerationRules.hitThreshold] = value.coerceIn(0, 10_000) }
            request.windowMs?.let { value -> it[ModerationRules.windowMs] = value.coerceIn(0L, MAX_WINDOW_MS) }
            escalationAction?.let { value -> it[ModerationRules.escalationAction] = value }
            request.priority?.let { value -> it[ModerationRules.priority] = value.coerceIn(0, 10_000) }
            it[ModerationRules.updatedAt] = System.currentTimeMillis()
        }
        ModerationRules.selectAll().where { ModerationRules.id eq existing[ModerationRules.id] }.first().toRuleResponse()
    }

    fun createRule(request: CreateModerationRuleRequest): String = transaction {
        val name = request.name.trim().take(100)
        val scope = request.scope.trim().uppercase()
        val matchType = request.matchType.trim().uppercase()
        val pattern = request.pattern.trim().take(2_000)
        val action = request.action.trim().uppercase()
        require(name.isNotBlank() && pattern.isNotBlank()) { "规则名称和匹配内容不能为空" }
        require(scope in ALLOWED_SCOPES) { "规则范围无效" }
        require(matchType in ALLOWED_MATCH_TYPES) { "匹配类型无效" }
        require(action in ALLOWED_ACTIONS) { "规则动作无效" }
        // Validate with the exact same RE2/J engine and flags used by evaluate().
        if (matchType == "REGEX") {
            require(runCatching { compileModerationRegex(pattern) }.isSuccess) { "正则表达式语法无效" }
        }
        val id = "rule_${UUID.randomUUID()}"
        val now = System.currentTimeMillis()
        ModerationRules.insert {
            it[ModerationRules.id] = id
            it[ModerationRules.name] = name
            it[ModerationRules.description] = "$matchType:${pattern.take(60)}"
            it[ModerationRules.scope] = scope
            it[ModerationRules.matchType] = matchType
            it[ModerationRules.pattern] = pattern
            it[ModerationRules.action] = action
            it[ModerationRules.hitThreshold] = request.hitThreshold.coerceIn(0, 10_000)
            it[ModerationRules.windowMs] = request.windowMs.coerceIn(0L, MAX_WINDOW_MS)
            it[ModerationRules.escalationAction] = null
            it[ModerationRules.enabled] = request.enabled
            it[ModerationRules.priority] = request.priority.coerceIn(0, 10_000)
            it[ModerationRules.createdAt] = now
            it[ModerationRules.updatedAt] = now
        }
        id
    }

    fun deleteRule(ruleId: String): Boolean = transaction {
        ModerationRules.deleteWhere { ModerationRules.id eq ruleId } > 0
    }

    fun getRiskEvents(limit: Int = 100, needsReview: Boolean? = null): List<RiskEventResponse> = transaction {
        val query = RiskEvents.selectAll()
        if (needsReview != null) query.andWhere { RiskEvents.needsReview eq needsReview }
        query.orderBy(RiskEvents.createdAt to SortOrder.DESC, RiskEvents.id to SortOrder.DESC)
            .limit(limit.coerceIn(1, 200))
            .map { it.toRiskEventResponse() }
    }

    fun acknowledgeRiskEvent(eventId: String): Boolean = transaction {
        RiskEvents.update({ RiskEvents.id eq eventId }) { it[RiskEvents.needsReview] = false } > 0
    }

    private fun ResultRow.toRuleResponse() = ModerationRuleResponse(
        id = this[ModerationRules.id],
        name = this[ModerationRules.name],
        description = this[ModerationRules.description],
        scope = this[ModerationRules.scope],
        matchType = this[ModerationRules.matchType],
        pattern = this[ModerationRules.pattern],
        action = this[ModerationRules.action],
        windowMs = this[ModerationRules.windowMs],
        hitThreshold = this[ModerationRules.hitThreshold],
        escalationAction = this[ModerationRules.escalationAction],
        enabled = this[ModerationRules.enabled],
        priority = this[ModerationRules.priority],
        updatedAt = this[ModerationRules.updatedAt]
    )

    private fun ResultRow.toRiskEventResponse() = RiskEventResponse(
        id = this[RiskEvents.id],
        userId = this[RiskEvents.userId],
        source = this[RiskEvents.sourceValue],
        ruleId = this[RiskEvents.ruleId],
        action = this[RiskEvents.action],
        matched = this[RiskEvents.matched],
        referenceId = this[RiskEvents.referenceId],
        needsReview = this[RiskEvents.needsReview],
        createdAt = this[RiskEvents.createdAt]
    )

    private companion object {
        const val MAX_MATCHED_LENGTH = 280
        const val MAX_EVALUATE_CONTENT_LENGTH = 12_000
        const val REGEX_FLAGS = Pattern.CASE_INSENSITIVE or Pattern.MULTILINE
        const val MAX_WINDOW_MS = 30L * 24L * 60L * 60L * 1000L
        val REVIEW_ACTIONS = setOf("WARN_MOD", "AUTO_RATE_LIMIT", "AUTO_HOLD", "AUTO_DELETE")
        val ALLOWED_ACTIONS = setOf("WARN_MOD", "AUTO_HOLD", "AUTO_DELETE", "AUTO_RATE_LIMIT")
        val ALLOWED_SCOPES = setOf("ALL", "POST", "COMMENT")
        val ALLOWED_MATCH_TYPES = setOf("KEYWORD", "REGEX", "URL", "FREQUENCY")
        val ACTION_RANK = mapOf("WARN_MOD" to 1, "AUTO_RATE_LIMIT" to 2, "AUTO_HOLD" to 3, "AUTO_DELETE" to 4)
        const val CLEANUP_INTERVAL_MS = 24L * 60L * 60L * 1000L
        const val EVENT_RETENTION_MS = 30L * 24L * 60L * 60L * 1000L
        val lastCleanupAt = AtomicLong(0)

        fun compileModerationRegex(pattern: String): Pattern = Pattern.compile(pattern, REGEX_FLAGS)
    }
}
