package com.maodouchat.server.repository

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

    fun evaluate(userId: String, source: String, content: String, referenceId: String? = null): Evaluation = transaction {
        val normalizedSource = source.trim().uppercase()
        val now = System.currentTimeMillis()
        // 截断内容以限制正则匹配的最大处理时间，防止 ReDoS 攻击
        val boundedContent = if (content.length > MAX_EVALUATE_CONTENT_LENGTH) content.take(MAX_EVALUATE_CONTENT_LENGTH) else content
        val previousCleanup = lastCleanupAt.get()
        if (now - previousCleanup >= CLEANUP_INTERVAL_MS && lastCleanupAt.compareAndSet(previousCleanup, now)) {
            RiskEvents.deleteWhere { RiskEvents.createdAt less now - EVENT_RETENTION_MS }
        }
        val rules = ModerationRules.selectAll()
            .where { ModerationRules.enabled eq true }
            .orderBy(ModerationRules.priority to SortOrder.ASC)
            .filter { row -> row[ModerationRules.scope] == "ALL" || row[ModerationRules.scope] == normalizedSource }

        val matches = mutableListOf<RuleMatch>()
        rules.forEach { rule ->
            val ruleId = rule[ModerationRules.id]
            val windowMs = rule[ModerationRules.windowMs].coerceAtLeast(0)
            val threshold = rule[ModerationRules.hitThreshold].coerceAtLeast(0)
            val recentCount = if (windowMs > 0) {
                RiskEvents.selectAll().where {
                    (RiskEvents.userId eq userId) and
                        (RiskEvents.ruleId eq ruleId) and
                        (RiskEvents.createdAt greaterEq now - windowMs)
                }.count().toInt()
            } else 0

            val matchType = rule[ModerationRules.matchType]
            val matchedText: String? = when (matchType) {
                "FREQUENCY" -> "${recentCount + 1}/$threshold"
                "REGEX" -> {
                    val regex = runCatching {
                        Regex(rule[ModerationRules.pattern], setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))
                    }.getOrNull()
                    val future = regex?.let { compiled ->
                        try {
                            regexExecutor.submit<String?> {
                                compiled.find(boundedContent)?.value?.take(MAX_MATCHED_LENGTH)
                            }
                        } catch (rejected: java.util.concurrent.RejectedExecutionException) {
                            // 8.38：池饱和时绝不内联执行（CallerRuns 会让请求线程卡死在不可中断的正则上，
                            // future.get 超时完全失效）。Abort 拒绝 + 显式记日志，避免静默 fail-open。
                            java.util.logging.Logger.getLogger("ModerationRuleRepository")
                                .warning("moderation regex pool saturated; rule ${rule[ModerationRules.id]} skipped")
                            null
                        }
                    }
                    if (future == null) {
                        null
                    } else {
                        // Java regex is not reliably interruptible. A small bounded daemon pool prevents
                        // catastrophic patterns from creating unbounded non-daemon threads.
                        try {
                            future.get(REGEX_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
                        } catch (_: Exception) {
                            future.cancel(true)
                            regexExecutor.purge()
                            null
                        }
                    }
                }
                // BUG 4.2 fix: KEYWORD/URL 使用字面量匹配，不用正则
                "KEYWORD", "URL" -> {
                    val literal = rule[ModerationRules.pattern]
                    if (boundedContent.contains(literal, ignoreCase = true)) {
                        literal.take(MAX_MATCHED_LENGTH)
                    } else null
                }
                else -> null
            }
            val isMatch = when (matchType) {
                "FREQUENCY" -> threshold > 0
                "REGEX", "KEYWORD", "URL" -> matchedText != null
                else -> false
            }
            if (!isMatch) return@forEach

            val projectedCount = recentCount + 1
            val baseAction = rule[ModerationRules.action]
            val effectiveAction = when {
                matchType == "FREQUENCY" && projectedCount < threshold -> "OBSERVED"
                threshold > 0 && projectedCount >= threshold * 2 && !rule[ModerationRules.escalationAction].isNullOrBlank() ->
                    rule[ModerationRules.escalationAction]!!
                else -> baseAction
            }
            val needsReview = effectiveAction in REVIEW_ACTIONS
            RiskEvents.insert {
                it[RiskEvents.id] = "risk_${UUID.randomUUID()}"
                it[RiskEvents.userId] = userId
                it[RiskEvents.sourceValue] = normalizedSource
                it[RiskEvents.ruleId] = ruleId
                it[RiskEvents.action] = effectiveAction
                it[RiskEvents.matched] = matchedText
                it[RiskEvents.referenceId] = referenceId
                it[RiskEvents.needsReview] = needsReview
                it[RiskEvents.createdAt] = now
            }
            if (effectiveAction != "OBSERVED") {
                matches += RuleMatch(ruleId, effectiveAction, matchedText, needsReview)
            }
        }

        val strongest = matches.maxByOrNull { ACTION_RANK[it.action] ?: 0 }
        when (strongest?.action) {
            "AUTO_DELETE" -> Evaluation(true, strongest.action, "内容触发安全规则，已被拦截", matches)
            "AUTO_HOLD" -> Evaluation(true, strongest.action, "内容已进入安全审核，请稍后再试", matches)
            "AUTO_RATE_LIMIT" -> Evaluation(true, strongest.action, "操作过于频繁，请稍后再试", matches)
            else -> Evaluation(false, strongest?.action, matches = matches)
        }
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
        if (effectiveMatchType == "REGEX" && runCatching { Regex(effectivePattern) }.isFailure) {
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
        // BUG 4.3 fix: REGEX 类型在保存时试编译，无效正则立即报错而非静默失败
        if (matchType == "REGEX") {
            require(runCatching { Regex(pattern) }.isSuccess) { "正则表达式语法无效" }
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
        const val REGEX_TIMEOUT_MS = 200L
        const val MAX_WINDOW_MS = 30L * 24L * 60L * 60L * 1000L
        val REVIEW_ACTIONS = setOf("WARN_MOD", "AUTO_RATE_LIMIT", "AUTO_HOLD", "AUTO_DELETE")
        val ALLOWED_ACTIONS = setOf("WARN_MOD", "AUTO_HOLD", "AUTO_DELETE", "AUTO_RATE_LIMIT")
        val ALLOWED_SCOPES = setOf("ALL", "POST", "COMMENT")
        val ALLOWED_MATCH_TYPES = setOf("KEYWORD", "REGEX", "URL", "FREQUENCY")
        val ACTION_RANK = mapOf("WARN_MOD" to 1, "AUTO_RATE_LIMIT" to 2, "AUTO_HOLD" to 3, "AUTO_DELETE" to 4)
        const val CLEANUP_INTERVAL_MS = 24L * 60L * 60L * 1000L
        const val EVENT_RETENTION_MS = 30L * 24L * 60L * 60L * 1000L
        val lastCleanupAt = AtomicLong(0)
        val regexExecutor = java.util.concurrent.ThreadPoolExecutor(
            2,
            2,
            0L,
            java.util.concurrent.TimeUnit.MILLISECONDS,
            java.util.concurrent.ArrayBlockingQueue(32),
            { task -> Thread(task, "moderation-regex").apply { isDaemon = true } },
            // 8.38：Abort 拒绝而非 CallerRuns——CallerRuns 在队列满时于请求线程内联执行正则，
            // future.get 超时完全失效（请求线程卡死 + 占用 DB 连接）。拒绝时 evaluate 记为跳过并告警。
            java.util.concurrent.ThreadPoolExecutor.AbortPolicy()
        )
    }
}
