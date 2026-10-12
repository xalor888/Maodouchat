package com.maodouchat.server.repository

// 规则 CRUD 簇：规则增删改查，原 ModerationRuleRepository 成员按域拆出的顶层扩展函数。
import com.maodouchat.server.db.ModerationRules
import com.maodouchat.server.model.CreateModerationRuleRequest
import com.maodouchat.server.model.ModerationRuleResponse
import com.maodouchat.server.model.UpdateModerationRuleRequest
import java.util.UUID
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

fun ModerationRuleRepository.getRules(): List<ModerationRuleResponse> = transaction {
    ModerationRules.selectAll()
        .orderBy(ModerationRules.priority to SortOrder.ASC)
        .map { it.toRuleResponse() }
}

/** 规则是否存在（8.32 一致性：端点区分 404 与 400）。 */
fun ModerationRuleRepository.ruleExists(ruleId: String): Boolean = transaction {
    ModerationRules.selectAll().where { ModerationRules.id eq ruleId }.firstOrNull() != null
}

fun ModerationRuleRepository.updateRule(ruleId: String, request: UpdateModerationRuleRequest): ModerationRuleResponse? = transaction {
    val existing = ModerationRules.selectAll().where { ModerationRules.id eq ruleId }.firstOrNull()
        ?: return@transaction null
    val name = request.name?.trim()?.take(100)
    if (name != null && name.isBlank()) return@transaction null
    val scope = request.scope?.trim()?.uppercase()
    if (scope != null && scope !in ModerationRuleRepository.ALLOWED_SCOPES) return@transaction null
    val matchType = request.matchType?.trim()?.uppercase()
    if (matchType != null && matchType !in ModerationRuleRepository.ALLOWED_MATCH_TYPES) return@transaction null
    val pattern = request.pattern?.trim()?.take(2_000)
    if (pattern != null && pattern.isBlank()) return@transaction null
    val action = request.action?.trim()?.uppercase()
    if (action != null && action !in ModerationRuleRepository.ALLOWED_ACTIONS) return@transaction null
    val escalationAction = request.escalationAction?.trim()?.uppercase()
    if (escalationAction != null && escalationAction !in ModerationRuleRepository.ALLOWED_ACTIONS) return@transaction null
    val effectiveMatchType = matchType ?: existing[ModerationRules.matchType]
    val effectivePattern = pattern ?: existing[ModerationRules.pattern]
    if (effectiveMatchType == "REGEX" && runCatching { ModerationRuleRepository.compileModerationRegex(effectivePattern) }.isFailure) {
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
        request.windowMs?.let { value -> it[ModerationRules.windowMs] = value.coerceIn(0L, ModerationRuleRepository.MAX_WINDOW_MS) }
        escalationAction?.let { value -> it[ModerationRules.escalationAction] = value }
        request.priority?.let { value -> it[ModerationRules.priority] = value.coerceIn(0, 10_000) }
        it[ModerationRules.updatedAt] = System.currentTimeMillis()
    }
    ModerationRules.selectAll().where { ModerationRules.id eq existing[ModerationRules.id] }.first().toRuleResponse()
}

fun ModerationRuleRepository.createRule(request: CreateModerationRuleRequest): String = transaction {
    val name = request.name.trim().take(100)
    val scope = request.scope.trim().uppercase()
    val matchType = request.matchType.trim().uppercase()
    val pattern = request.pattern.trim().take(2_000)
    val action = request.action.trim().uppercase()
    require(name.isNotBlank() && pattern.isNotBlank()) { "规则名称和匹配内容不能为空" }
    require(scope in ModerationRuleRepository.ALLOWED_SCOPES) { "规则范围无效" }
    require(matchType in ModerationRuleRepository.ALLOWED_MATCH_TYPES) { "匹配类型无效" }
    require(action in ModerationRuleRepository.ALLOWED_ACTIONS) { "规则动作无效" }
    // Validate with the exact same RE2/J engine and flags used by evaluate().
    if (matchType == "REGEX") {
        require(runCatching { ModerationRuleRepository.compileModerationRegex(pattern) }.isSuccess) { "正则表达式语法无效" }
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
        it[ModerationRules.windowMs] = request.windowMs.coerceIn(0L, ModerationRuleRepository.MAX_WINDOW_MS)
        it[ModerationRules.escalationAction] = null
        it[ModerationRules.enabled] = request.enabled
        it[ModerationRules.priority] = request.priority.coerceIn(0, 10_000)
        it[ModerationRules.createdAt] = now
        it[ModerationRules.updatedAt] = now
    }
    id
}

fun ModerationRuleRepository.deleteRule(ruleId: String): Boolean = transaction {
    ModerationRules.deleteWhere { ModerationRules.id eq ruleId } > 0
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
