package com.maodouchat.server.plugins

import com.maodouchat.server.db.*
import com.maodouchat.server.model.*
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * 管理后台「诊断」子域路由：AI 使用审计、推送令牌、Bot 开关、Ops 快照。
 * 只暴露元数据（绝不下发 prompt / 消息正文 / E2EE 密文），统计走只读查询。
 */
internal fun Route.configureAdminDiagnosticsRoutes() {
    // ─── AI 使用审计（仅元数据，无 prompt/正文） ──────────────────
    get("/ai-usage") {
        if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
        val limit = com.maodouchat.server.service.AdminAiAuditPolicy.normalizeLimit(
            call.request.queryParameters["limit"]?.toIntOrNull()
        )
        val offset = com.maodouchat.server.service.AdminAiAuditPolicy.normalizeOffset(
            call.request.queryParameters["offset"]?.toLongOrNull()
        )
        val featureFilter = com.maodouchat.server.service.AdminAiAuditPolicy.normalizeFeatureFilter(
            call.request.queryParameters["feature"]
        )
        val userFilter = com.maodouchat.server.service.AdminAiAuditPolicy.normalizeUserFilter(
            call.request.queryParameters["userId"] ?: call.request.queryParameters["q"]
        )
        val logs = transaction {
            val query = AiAuditLogs.selectAll()
            if (featureFilter != null) {
                query.andWhere { AiAuditLogs.feature eq featureFilter }
            }
            if (userFilter != null) {
                query.andWhere { AiAuditLogs.userId eq userFilter }
            }
            val rows = query
                .orderBy(AiAuditLogs.createdAt to SortOrder.DESC, AiAuditLogs.id to SortOrder.DESC)
                .limit(limit, offset)
                .toList()
            // input_tokens/output_tokens 已进 Table 单例；这里仍用参数化 SQL 按 id 批量回填，避免逐行二次查询。
            val tokenById: Map<String, Pair<Long?, Long?>> = if (rows.isEmpty()) emptyMap() else {
                val ids = rows.map { it[AiAuditLogs.id] }
                val placeholders = List(ids.size) { "?" }.joinToString(",")
                exec(
                    "SELECT id, input_tokens, output_tokens FROM ai_audit_logs WHERE id IN ($placeholders)",
                    ids.map { VarCharColumnType() to it }
                ) { rs ->
                    val m = mutableMapOf<String, Pair<Long?, Long?>>()
                    while (rs.next()) {
                        val input = rs.getLong(2)
                        val inputTokens: Long? = if (rs.wasNull()) null else input
                        val output = rs.getLong(3)
                        val outputTokens: Long? = if (rs.wasNull()) null else output
                        m[rs.getString(1)] = inputTokens to outputTokens
                    }
                    m
                } ?: emptyMap()
            }
            rows.map {
                val tokens = tokenById[it[AiAuditLogs.id]]
                // Never project chatId / prompt / body into admin responses.
                com.maodouchat.server.service.AdminAiAuditPolicy.toAdminResponse(
                    id = it[AiAuditLogs.id],
                    userId = it[AiAuditLogs.userId],
                    feature = it[AiAuditLogs.feature],
                    model = it[AiAuditLogs.model],
                    status = it[AiAuditLogs.status],
                    inputChars = it[AiAuditLogs.inputChars],
                    contextMessages = it[AiAuditLogs.contextMessages],
                    durationMs = it[AiAuditLogs.durationMs],
                    error = it[AiAuditLogs.error],
                    createdAt = it[AiAuditLogs.createdAt],
                    inputTokens = tokens?.first,
                    outputTokens = tokens?.second
                )
            }
        }
        call.respond(logs)
    }

    // ─── 推送令牌管理 ─────────────────
    get("/push-tokens") {
        if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
        val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 200)
        val offset = (call.request.queryParameters["offset"]?.toLongOrNull() ?: 0L).coerceAtLeast(0L)
        val search = call.request.queryParameters["q"]?.trim()?.takeIf { it.isNotBlank() }
        val tokens = transaction {
            val query = PushTokens.selectAll()
            if (search != null) query.andWhere { PushTokens.userId eq search }
            query.orderBy(
                PushTokens.updatedAt to SortOrder.DESC,
                PushTokens.userId to SortOrder.DESC,
                PushTokens.deviceId to SortOrder.DESC
            )
                .limit(limit, offset)
                .map {
                    PushTokenAdminResponse(
                        userId = it[PushTokens.userId],
                        deviceId = it[PushTokens.deviceId],
                        platform = it[PushTokens.platform],
                        timezoneOffsetMinutes = it[PushTokens.timezoneOffsetMinutes],
                        updatedAt = it[PushTokens.updatedAt]
                    )
                }
        }
        call.respond(tokens)
    }

    put("/bots/{botId}/enabled") {
        if (!call.isAdminUser()) return@put call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
        val adminId = call.principal<JWTPrincipal>()!!.payload.subject
        val botId = call.parameters["botId"] ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing botId"))
        val bodyText = runCatching { call.receiveBoundedText(MAX_ADMIN_JSON_BODY_CHARS) }.getOrNull().orEmpty()
        val enabled = runCatching {
            val p = adminJson.parseToJsonElement(bodyText).jsonObject["enabled"]?.jsonPrimitive
            p?.booleanOrNull ?: p?.content?.toBooleanStrictOrNull()
        }.getOrNull() ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("enabled required"))
        val updated = transaction {
            val changed = BotApps.update({ BotApps.id eq botId }) {
                it[BotApps.enabled] = enabled
                it[BotApps.updatedAt] = System.currentTimeMillis()
            }
            if (changed == 1) com.maodouchat.server.repository.BotRepository.get(botId) else null
        } ?: return@put call.respond(HttpStatusCode.NotFound, ErrorResponse("bot not found"))
        recordAdminAudit(adminId, "bot_enabled", "bot=$botId;enabled=$enabled")
        call.respond(updated)
    }

    get("/bots") {
        if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
        val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0
        call.respond(com.maodouchat.server.repository.BotRepository.adminList(limit, offset))
    }

    // ─── Ops snapshot (bots + polls + capture-related volume) ───
    get("/ops-snapshot") {
        if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
        val snap = transaction {
            val botTotal = BotApps.selectAll().count()
            val botEnabled = BotApps.selectAll()
                .where { BotApps.enabled eq true }.count()
            val botWithWebhook = BotApps.selectAll()
                .mapNotNull { row ->
                    val url = row[BotApps.webhookUrl]
                    val enabled = row[BotApps.enabled]
                    if (enabled && !url.isNullOrBlank()) 1 else null
                }.size.toLong()
            val pollTotal = GroupPolls.selectAll().count()
            val pollOpen = GroupPolls.selectAll()
                .where { GroupPolls.closed eq false }.count()
            val voteTotal = GroupPollVotes.selectAll().count()
            val msgTotal = MessagingV2Messages.selectAll().where {
                MessagingV2Messages.recordClass eq com.maodouchat.server.messaging.v2.MessagingV2RecordClass.MESSAGE
            }.count()
            val userTotal = Users.selectAll().count()
            OpsSnapshotResponse(
                users = userTotal,
                messages = msgTotal,
                botsTotal = botTotal,
                botsEnabled = botEnabled,
                botsWithWebhook = botWithWebhook,
                pollsTotal = pollTotal,
                pollsOpen = pollOpen,
                pollVotes = voteTotal,
                generatedAt = System.currentTimeMillis()
            )
        }
        call.respond(snap)
    }
}
