package com.maodouchat.server.repository

// 规则评估簇：evaluate + 匹配 helpers，原 ModerationRuleRepository 成员按域拆出的顶层扩展函数。
import com.maodouchat.server.db.ModerationRules
import com.maodouchat.server.db.RiskEvents
import java.util.UUID
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

fun ModerationRuleRepository.evaluate(userId: String, source: String, content: String, referenceId: String? = null): Evaluation {
    val normalizedSource = source.trim().uppercase()
    val now = System.currentTimeMillis()
    // RE2/J is linear-time, but bounding user input also caps allocation and overall rule-evaluation work.
    val boundedContent = content.take(ModerationRuleRepository.MAX_EVALUATE_CONTENT_LENGTH)

    // Only database reads/counts happen here. User-controlled regex compilation and matching must not
    // hold a JDBC connection or transaction open.
    val rules = transaction {
        val previousCleanup = ModerationRuleRepository.lastCleanupAt.get()
        if (now - previousCleanup >= ModerationRuleRepository.CLEANUP_INTERVAL_MS && ModerationRuleRepository.lastCleanupAt.compareAndSet(previousCleanup, now)) {
            RiskEvents.deleteWhere { RiskEvents.createdAt less now - ModerationRuleRepository.EVENT_RETENTION_MS }
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
            "REGEX" -> findRegexMatch(rule.pattern, boundedContent)?.take(ModerationRuleRepository.MAX_MATCHED_LENGTH)
            // KEYWORD/URL are deliberately literal matches, not regular expressions.
            "KEYWORD", "URL" -> if (boundedContent.contains(rule.pattern, ignoreCase = true)) {
                rule.pattern.take(ModerationRuleRepository.MAX_MATCHED_LENGTH)
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
            needsReview = effectiveAction in ModerationRuleRepository.REVIEW_ACTIONS
        )
    }

    val insertedIds = mutableListOf<String>()
    transaction {
        pendingEvents.forEach { event ->
            val id = "risk_${UUID.randomUUID()}"
            insertedIds += id
            RiskEvents.insert {
                it[RiskEvents.id] = id
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
        .mapIndexed { index, event -> Triple(event, insertedIds.getOrNull(index), index) }
        .filter { it.first.action != "OBSERVED" }
        .map { (event, eventId, _) -> RuleMatch(event.ruleId, event.action, event.matched, event.needsReview, eventId) }
    val strongest = matches.maxByOrNull { ModerationRuleRepository.ACTION_RANK[it.action] ?: 0 }
    return when (strongest?.action) {
        "AUTO_DELETE" -> Evaluation(true, strongest.action, "内容触发安全规则，已被拦截", matches)
        "AUTO_HOLD" -> Evaluation(true, strongest.action, "内容已进入安全审核，请稍后再试", matches)
        "AUTO_RATE_LIMIT" -> Evaluation(true, strongest.action, "操作过于频繁，请稍后再试", matches)
        else -> Evaluation(false, strongest?.action, matches = matches)
    }
}

private fun ModerationRuleRepository.findRegexMatch(pattern: String, content: String): String? {
    val compiled = runCatching { ModerationRuleRepository.compileModerationRegex(pattern) }.getOrNull() ?: return null
    val matcher = compiled.matcher(content)
    return if (matcher.find()) matcher.group() else null
}

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
