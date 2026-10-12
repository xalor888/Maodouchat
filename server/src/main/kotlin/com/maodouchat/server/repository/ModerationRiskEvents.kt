package com.maodouchat.server.repository

// 风险事件簇：风险事件查询/确认/AI 决策/引用挂接，原 ModerationRuleRepository 成员按域拆出的顶层扩展函数。
import com.maodouchat.server.db.RiskEvents
import com.maodouchat.server.model.RiskEventResponse
import java.util.UUID
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

fun ModerationRuleRepository.getRiskEvents(limit: Int = 100, needsReview: Boolean? = null): List<RiskEventResponse> = transaction {
    val query = RiskEvents.selectAll()
    if (needsReview != null) query.andWhere { RiskEvents.needsReview eq needsReview }
    query.orderBy(RiskEvents.createdAt to SortOrder.DESC, RiskEvents.id to SortOrder.DESC)
        .limit(limit.coerceIn(1, 200))
        .map { it.toRiskEventResponse() }
}

fun ModerationRuleRepository.acknowledgeRiskEvent(eventId: String): Boolean = transaction {
    RiskEvents.update({ RiskEvents.id eq eventId }) { it[RiskEvents.needsReview] = false } > 0
}

fun ModerationRuleRepository.recordAiDecision(
    userId: String,
    source: String,
    action: String,
    matched: String?,
    referenceId: String? = null,
    needsReview: Boolean = true
): String = transaction {
    val id = "risk_${UUID.randomUUID()}"
    RiskEvents.insert {
        it[RiskEvents.id] = id
        it[RiskEvents.userId] = userId
        it[RiskEvents.sourceValue] = source.trim().uppercase().take(20)
        it[RiskEvents.ruleId] = com.maodouchat.server.service.AiContentModerationPolicy.RULE_ID
        it[RiskEvents.action] = action.take(20)
        it[RiskEvents.matched] = matched?.take(MAX_MATCHED_LENGTH)
        it[RiskEvents.referenceId] = referenceId
        it[RiskEvents.needsReview] = needsReview
        it[RiskEvents.createdAt] = System.currentTimeMillis()
    }
    id
}

fun ModerationRuleRepository.attachReference(eventIds: List<String>, referenceId: String): Int {
    if (eventIds.isEmpty() || referenceId.isBlank()) return 0
    return transaction {
        RiskEvents.update({ RiskEvents.id inList eventIds }) {
            it[RiskEvents.referenceId] = referenceId
        }
    }
}

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
