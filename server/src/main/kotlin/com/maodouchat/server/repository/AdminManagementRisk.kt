package com.maodouchat.server.repository

// 风控事件簇：原 AdminManagementRepository 成员按域拆出的顶层扩展函数（同包调用，零行为改动）。
import com.maodouchat.server.db.RiskEvents
import com.maodouchat.server.model.RiskEventAdminResponse
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

fun AdminManagementRepository.listRiskEvents(
    limit: Int,
    offset: Long,
    needsReviewOnly: Boolean,
): List<RiskEventAdminResponse> = transaction {
    val query = RiskEvents.selectAll()
    if (needsReviewOnly) query.andWhere { RiskEvents.needsReview eq true }
    query.orderBy(RiskEvents.createdAt to SortOrder.DESC, RiskEvents.id to SortOrder.DESC)
        .limit(limit, offset)
        .map {
            RiskEventAdminResponse(
                id = it[RiskEvents.id],
                userId = it[RiskEvents.userId],
                source = it[RiskEvents.sourceValue],
                ruleId = it[RiskEvents.ruleId],
                action = it[RiskEvents.action],
                matched = it[RiskEvents.matched],
                referenceId = it[RiskEvents.referenceId],
                needsReview = it[RiskEvents.needsReview],
                createdAt = it[RiskEvents.createdAt],
            )
        }
}

fun AdminManagementRepository.resolveRiskEvent(id: String): Boolean = transaction {
    RiskEvents.selectAll().where { RiskEvents.id eq id }.firstOrNull()
        ?: return@transaction false
    RiskEvents.update({ RiskEvents.id eq id }) { it[needsReview] = false }
    true
}
