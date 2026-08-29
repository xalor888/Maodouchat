package com.maodouchat.server.plugins

import com.maodouchat.server.auth.JwtConfig
import com.maodouchat.server.config.AdminAccess
import com.maodouchat.server.config.ServerConfig
import com.maodouchat.server.db.*
import com.maodouchat.server.model.*
import com.maodouchat.server.repository.*
import com.maodouchat.server.service.AdminDispositionPolicy
import com.maodouchat.server.service.RuntimeConfigService
import io.ktor.http.HttpStatusCode
import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.auth.principal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.delete
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.notInList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.notInSubQuery
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.update
import java.lang.management.ManagementFactory
import java.util.UUID


/** 管理后台子域路由（从 AdminManagementRouting.kt 拆出）。 */
internal fun Route.configureAdminBulkRoutes(authTokenRepo: AuthTokenRepository) {
    post("/users/bulk-force-logout") {
        if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
        val actorId = call.principal<JWTPrincipal>()!!.payload.subject
        val body = runCatching { call.receiveBoundedText(MAX_ADMIN_JSON_BODY_CHARS) }.getOrNull().orEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val rawIds = obj["userIds"]
        val ids = when {
            rawIds == null -> emptyList()
            rawIds is kotlinx.serialization.json.JsonArray -> rawIds.mapNotNull {
                runCatching { it.jsonPrimitive.content }.getOrNull()
            }
            else -> rawIds.jsonPrimitive.content.split(',', ' ', '\n', '\t').map { it.trim() }.filter { it.isNotBlank() }
        }.map { it.take(64) }.distinct().take(200)
        if (ids.isEmpty()) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("userIds required"))
        }
        val skippedAdmins = mutableListOf<String>()
        val skippedMissing = mutableListOf<String>()
        val okIds = mutableListOf<String>()
        ids.forEach { id ->
            if (AdminAccess.isAdmin(id) && id != actorId) {
                skippedAdmins += id
                return@forEach
            }
            if (authTokenRepo.rotateAccessTokenVersion(id) == 0L) {
                skippedMissing += id
                return@forEach
            }
            bestEffortAdminDisconnect { disconnectUserSessions(id, "admin bulk force logout") }
            okIds += id
        }
        transaction {
            ModerationAuditLog.insert {
                it[ModerationAuditLog.actorId] = actorId
                it[ModerationAuditLog.userId] = actorId
                it[ModerationAuditLog.action] = "ADMIN_BULK_FORCE_LOGOUT"
                it[ModerationAuditLog.detail] = "ok=${okIds.size};skippedAdmins=${skippedAdmins.size}"
                it[ModerationAuditLog.createdAt] = System.currentTimeMillis()
            }
        }
        call.respond(
        buildJsonObject {
put("ok", true)
put("loggedOut", Json.parseToJsonElement(Json.encodeToString(okIds)))
put("skippedAdmins", Json.parseToJsonElement(Json.encodeToString(skippedAdmins)))
put("skippedMissing", Json.parseToJsonElement(Json.encodeToString(skippedMissing)))
put("count", okIds.size)
        }
    )
    }


    post("/users/bulk-ban") {
        if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
        val actorId = call.principal<JWTPrincipal>()!!.payload.subject
        val body = runCatching { call.receiveBoundedText(MAX_ADMIN_JSON_BODY_CHARS) }.getOrNull().orEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val rawIds = obj["userIds"]
        val ids = when {
            rawIds == null -> emptyList()
            rawIds is kotlinx.serialization.json.JsonArray -> rawIds.mapNotNull {
                runCatching { it.jsonPrimitive.content }.getOrNull()
            }
            else -> rawIds.jsonPrimitive.content.split(',', ' ', '\n', '\t').map { it.trim() }.filter { it.isNotBlank() }
        }.map { it.take(64) }.distinct().take(100)
        val days = (obj["days"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1).coerceIn(1, AdminDispositionPolicy.MAX_BAN_DAYS)
        val reasonCode = obj["reasonCode"]?.jsonPrimitive?.content.orEmpty().ifBlank { "BULK_BAN" }
        if (ids.isEmpty()) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("userIds required"))
        }
        val until = System.currentTimeMillis() + days * 86_400_000L
        val skipped = mutableListOf<String>()
        val banned = mutableListOf<String>()
        // 8.48 修复 M3（bulk-ban）：批量存在性检查 + 单事务批量处置（此前逐 id 独立
        // 事务做「存在检查+UPDATE+审计」，最多 100 个事务）
        val existing = transaction {
            Users.select(Users.id).where { (Users.id inList ids) and Users.deletedAt.isNull() }.map { it[Users.id] }.toSet()
        }
        transaction {
            ids.forEach { id ->
                if (id == actorId || AdminAccess.isAdmin(id) || id !in existing) {
                    skipped += id
                    return@forEach
                }
                // 9.128：已有更长封禁时保长——批量封禁按「追加 N 天」语义取 maxOf，
                // 直接覆盖会把 30 天封禁缩成 1 天（此前与单用户 applyModerationRestriction 的
                // maxOf 语义不一致）
                val row = Users.selectAll().where { Users.id eq id }.firstOrNull()
                if (row == null) {
                    skipped += id
                    return@forEach
                }
                val effectiveUntil = if (until <= 0L) 0L else maxOf(row[Users.suspendedUntil], until)
                Users.update({ (Users.id eq id) and Users.deletedAt.isNull() }) { it[Users.suspendedUntil] = effectiveUntil }
                ModerationAuditLog.insert {
                    it[ModerationAuditLog.userId] = id
                    it[ModerationAuditLog.action] = "ADMIN_BULK_BAN"
                    it[ModerationAuditLog.detail] = "days=$days;reason=$reasonCode".take(200)
                    it[ModerationAuditLog.actorId] = actorId
                    it[ModerationAuditLog.createdAt] = System.currentTimeMillis()
                }
                banned += id
            }
        }
        banned.forEach { id ->
            authTokenRepo.rotateAccessTokenVersion(id)
            bestEffortAdminDisconnect { disconnectUserSessions(id, "admin bulk ban") }
        }
        call.respond(
        buildJsonObject {
put("ok", true)
put("banned", Json.parseToJsonElement(Json.encodeToString(banned)))
put("skipped", Json.parseToJsonElement(Json.encodeToString(skipped)))
put("until", until)
put("count", banned.size)
        }
    )
    }



    post("/users/bulk-unban") {
        if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
        val actorId = call.principal<JWTPrincipal>()!!.payload.subject
        val body = runCatching { call.receiveBoundedText(MAX_ADMIN_JSON_BODY_CHARS) }.getOrNull().orEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val rawIds = obj["userIds"]
        val ids = when {
            rawIds == null -> emptyList()
            rawIds is kotlinx.serialization.json.JsonArray -> rawIds.mapNotNull {
                runCatching { it.jsonPrimitive.content }.getOrNull()
            }
            else -> rawIds.jsonPrimitive.content.split(',', ' ', '\n', '\t').map { it.trim() }.filter { it.isNotBlank() }
        }.map { it.take(64) }.distinct().take(100)
        if (ids.isEmpty()) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("userIds required"))
        }
        val updated = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        // 8.48 修复 M3（bulk-unban）：批量存在性检查 + 单事务
        val existing = transaction {
            Users.select(Users.id).where { (Users.id inList ids) and Users.deletedAt.isNull() }.map { it[Users.id] }.toSet()
        }
        transaction {
            ids.forEach { id ->
                if (id == actorId || AdminAccess.isAdmin(id) || id !in existing) {
                    skipped += id
                    return@forEach
                }
                Users.update({ (Users.id eq id) and Users.deletedAt.isNull() }) { it[Users.suspendedUntil] = 0L }
                ModerationAuditLog.insert {
                    it[ModerationAuditLog.userId] = id
                    it[ModerationAuditLog.action] = "ADMIN_BULK_UNBAN"
                    it[ModerationAuditLog.detail] = "cleared suspension"
                    it[ModerationAuditLog.actorId] = actorId
                    it[ModerationAuditLog.createdAt] = System.currentTimeMillis()
                }
                updated += id
            }
        }
        call.respond(
        buildJsonObject {
put("ok", true)
put("updated", Json.parseToJsonElement(Json.encodeToString(updated)))
put("skipped", Json.parseToJsonElement(Json.encodeToString(skipped)))
put("count", updated.size)
        }
    )
    }



    post("/users/bulk-suspend-days") {
        if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
        val actorId = call.principal<JWTPrincipal>()!!.payload.subject
        val body = runCatching { call.receiveBoundedText(MAX_ADMIN_JSON_BODY_CHARS) }.getOrNull().orEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val rawIds = obj["userIds"]
        val ids = when {
            rawIds == null -> emptyList()
            rawIds is kotlinx.serialization.json.JsonArray -> rawIds.mapNotNull {
                runCatching { it.jsonPrimitive.content }.getOrNull()
            }
            else -> rawIds.jsonPrimitive.content.split(',', ' ', '\n', '\t').map { it.trim() }.filter { it.isNotBlank() }
        }.map { it.take(64) }.distinct().take(100)
        val days = (obj["days"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1).coerceIn(1, 365)
        if (ids.isEmpty()) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("userIds required"))
        val until = System.currentTimeMillis() + days * 86_400_000L
        val updated = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        // 8.48 修复 M3（bulk-suspend-days）：批量存在性检查 + 单事务
        val existing = transaction {
            Users.select(Users.id).where { (Users.id inList ids) and Users.deletedAt.isNull() }.map { it[Users.id] }.toSet()
        }
        transaction {
            ids.forEach { id ->
                if (id == actorId || AdminAccess.isAdmin(id) || id !in existing) {
                    skipped += id
                    return@forEach
                }
                // 9.128：保长语义——不缩短既有更长封禁
                val row = Users.selectAll().where { Users.id eq id }.firstOrNull()
                if (row == null) {
                    skipped += id
                    return@forEach
                }
                val effectiveUntil = if (until <= 0L) 0L else maxOf(row[Users.suspendedUntil], until)
                Users.update({ (Users.id eq id) and Users.deletedAt.isNull() }) { it[Users.suspendedUntil] = effectiveUntil }
                ModerationAuditLog.insert {
                    it[ModerationAuditLog.userId] = id
                    it[ModerationAuditLog.action] = "ADMIN_BULK_SUSPEND_DAYS"
                    it[ModerationAuditLog.detail] = "days=$days;until=$effectiveUntil".take(200)
                    it[ModerationAuditLog.actorId] = actorId
                    it[ModerationAuditLog.createdAt] = System.currentTimeMillis()
                }
                updated += id
            }
        }
        updated.forEach { id ->
            authTokenRepo.rotateAccessTokenVersion(id)
            bestEffortAdminDisconnect { disconnectUserSessions(id, "admin bulk suspend days") }
        }
        call.respond(
        buildJsonObject {
put("ok", true)
put("updated", Json.parseToJsonElement(Json.encodeToString(updated)))
put("skipped", Json.parseToJsonElement(Json.encodeToString(skipped)))
put("until", until)
put("days", days)
put("count", updated.size)
        }
    )
    }

post("/users/bulk-message-restrict") {
        if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
        val actorId = call.principal<JWTPrincipal>()!!.payload.subject
        val body = runCatching { call.receiveBoundedText(MAX_ADMIN_JSON_BODY_CHARS) }.getOrNull().orEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val rawIds = obj["userIds"]
        val ids = when {
            rawIds == null -> emptyList()
            rawIds is kotlinx.serialization.json.JsonArray -> rawIds.mapNotNull {
                runCatching { it.jsonPrimitive.content }.getOrNull()
            }
            else -> rawIds.jsonPrimitive.content.split(',', ' ', '\n', '\t').map { it.trim() }.filter { it.isNotBlank() }
        }.map { it.take(64) }.distinct().take(100)
        val days = (obj["days"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1)
            .coerceIn(0, AdminDispositionPolicy.MAX_MESSAGE_RESTRICT_DAYS)
        if (ids.isEmpty()) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("userIds required"))
        }
        val until = if (days <= 0) 0L else System.currentTimeMillis() + days * 86_400_000L
        val updated = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val existing = transaction {
            Users.select(Users.id).where { (Users.id inList ids) and Users.deletedAt.isNull() }.map { it[Users.id] }.toSet()
        }
        transaction {
            ids.forEach { id ->
                if (id == actorId || AdminAccess.isAdmin(id) || id !in existing) {
                    skipped += id
                    return@forEach
                }
                // 9.128：保长语义——不缩短既有更长限制（days<=0 仍为解除）
                val row = Users.selectAll().where { Users.id eq id }.firstOrNull()
                if (row == null) {
                    skipped += id
                    return@forEach
                }
                val effectiveUntil = if (until <= 0L) 0L else maxOf(row[Users.messageRestrictedUntil], until)
                Users.update({ (Users.id eq id) and Users.deletedAt.isNull() }) { it[Users.messageRestrictedUntil] = effectiveUntil }
                ModerationAuditLog.insert {
                    it[ModerationAuditLog.userId] = id
                    it[ModerationAuditLog.action] = "ADMIN_BULK_MESSAGE_RESTRICT"
                    it[ModerationAuditLog.detail] = "days=$days;until=$effectiveUntil".take(200)
                    it[ModerationAuditLog.actorId] = actorId
                    it[ModerationAuditLog.createdAt] = System.currentTimeMillis()
                }
                updated += id
            }
        }
        call.respond(
        buildJsonObject {
put("ok", true)
put("updated", Json.parseToJsonElement(Json.encodeToString(updated)))
put("skipped", Json.parseToJsonElement(Json.encodeToString(skipped)))
put("until", until)
put("count", updated.size)
        }
    )
    }



    post("/users/bulk-message-unrestrict") {
        if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
        val actorId = call.principal<JWTPrincipal>()!!.payload.subject
        val body = runCatching { call.receiveBoundedText(MAX_ADMIN_JSON_BODY_CHARS) }.getOrNull().orEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val rawIds = obj["userIds"]
        val ids = when {
            rawIds == null -> emptyList()
            rawIds is kotlinx.serialization.json.JsonArray -> rawIds.mapNotNull {
                runCatching { it.jsonPrimitive.content }.getOrNull()
            }
            else -> rawIds.jsonPrimitive.content.split(',', ' ', '\n', '\t').map { it.trim() }.filter { it.isNotBlank() }
        }.map { it.take(64) }.distinct().take(100)
        if (ids.isEmpty()) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("userIds required"))
        }
        val updated = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val existing = transaction {
            Users.select(Users.id).where { (Users.id inList ids) and Users.deletedAt.isNull() }.map { it[Users.id] }.toSet()
        }
        transaction {
            ids.forEach { id ->
                if (id == actorId || AdminAccess.isAdmin(id) || id !in existing) {
                    skipped += id
                    return@forEach
                }
                Users.update({ (Users.id eq id) and Users.deletedAt.isNull() }) { it[Users.messageRestrictedUntil] = 0L }
                ModerationAuditLog.insert {
                    it[ModerationAuditLog.userId] = id
                    it[ModerationAuditLog.action] = "ADMIN_BULK_MESSAGE_UNRESTRICT"
                    it[ModerationAuditLog.detail] = "cleared"
                    it[ModerationAuditLog.actorId] = actorId
                    it[ModerationAuditLog.createdAt] = System.currentTimeMillis()
                }
                updated += id
            }
        }
        call.respond(
        buildJsonObject {
put("ok", true)
put("updated", Json.parseToJsonElement(Json.encodeToString(updated)))
put("skipped", Json.parseToJsonElement(Json.encodeToString(skipped)))
put("count", updated.size)
        }
    )
    }

get("/ai-usage-export") {
        if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
        val adminId = call.principal<JWTPrincipal>()!!.payload.subject
        val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 2000).coerceIn(1, 10000)
        // Metadata only — never export prompt/body
        val rows = transaction {
            val resultRows = AiAuditLogs.selectAll()
                .orderBy(
                    AiAuditLogs.createdAt to org.jetbrains.exposed.sql.SortOrder.DESC,
                    AiAuditLogs.id to org.jetbrains.exposed.sql.SortOrder.DESC
                )
                .limit(limit)
                .toList()
            // 9.137：token 列已进 Table 单例（启动迁移补列），参数化 SQL 批量回填。
            val tokenById: Map<String, Pair<Long?, Long?>> = if (resultRows.isEmpty()) emptyMap() else {
                val ids = resultRows.map { it[AiAuditLogs.id] }
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
            resultRows.map { row ->
                val tokens = tokenById[row[AiAuditLogs.id]]
                listOf(
                    csvCell(row[AiAuditLogs.id]),
                    csvCell(row[AiAuditLogs.userId]),
                    csvCell(row[AiAuditLogs.feature].take(40)),
                    csvCell(row[AiAuditLogs.status]),
                    csvCell(row[AiAuditLogs.inputChars].toString()),
                    csvCell(row[AiAuditLogs.contextMessages].toString()),
                    csvCell((row[AiAuditLogs.durationMs] ?: 0L).toString()),
                    csvCell((row[AiAuditLogs.error] ?: "").replace("\n", " ").take(80)),
                    csvCell(row[AiAuditLogs.createdAt].toString()),
                    csvCell(tokens?.first?.toString() ?: ""),
                    csvCell(tokens?.second?.toString() ?: "")
                ).joinToString(",")
            }
        }
        val csv = buildString {
            appendLine("id,userId,feature,status,inputChars,contextMessages,durationMs,error,createdAt,inputTokens,outputTokens")
            rows.forEach { appendLine(it) }
        }
        recordAdminAudit(actorId = adminId, action = "ai_usage_export", detail = "count=${rows.size}")
        call.response.header(
            HttpHeaders.ContentDisposition,
            "attachment; filename=\"maodouchat-ai-usage.csv\""
        )
        call.respondText(csv, io.ktor.http.ContentType.Text.CSV)
    }



    post("/users/bulk-post-restrict") {
        if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
        val actorId = call.principal<JWTPrincipal>()!!.payload.subject
        val body = runCatching { call.receiveBoundedText(MAX_ADMIN_JSON_BODY_CHARS) }.getOrNull().orEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val rawIds = obj["userIds"]
        val ids = when {
            rawIds == null -> emptyList()
            rawIds is kotlinx.serialization.json.JsonArray -> rawIds.mapNotNull {
                runCatching { it.jsonPrimitive.content }.getOrNull()
            }
            else -> rawIds.jsonPrimitive.content.split(',', ' ', '\n', '\t').map { it.trim() }.filter { it.isNotBlank() }
        }.map { it.take(64) }.distinct().take(100)
        val days = (obj["days"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1)
            .coerceIn(0, AdminDispositionPolicy.MAX_POST_RESTRICT_DAYS)
        if (ids.isEmpty()) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("userIds required"))
        val until = if (days <= 0) 0L else System.currentTimeMillis() + days * 86_400_000L
        val updated = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val existing = transaction {
            Users.select(Users.id).where { (Users.id inList ids) and Users.deletedAt.isNull() }.map { it[Users.id] }.toSet()
        }
        transaction {
            ids.forEach { id ->
                if (id == actorId || AdminAccess.isAdmin(id) || id !in existing) {
                    skipped += id
                    return@forEach
                }
                // 9.128：保长语义——不缩短既有更长限制（days<=0 仍为解除）
                val row = Users.selectAll().where { Users.id eq id }.firstOrNull()
                if (row == null) {
                    skipped += id
                    return@forEach
                }
                val effectiveUntil = if (until <= 0L) 0L else maxOf(row[Users.postRestrictedUntil], until)
                Users.update({ (Users.id eq id) and Users.deletedAt.isNull() }) { it[Users.postRestrictedUntil] = effectiveUntil }
                ModerationAuditLog.insert {
                    it[ModerationAuditLog.userId] = id
                    it[ModerationAuditLog.action] = "ADMIN_BULK_POST_RESTRICT"
                    it[ModerationAuditLog.detail] = "days=$days;until=$effectiveUntil".take(200)
                    it[ModerationAuditLog.actorId] = actorId
                    it[ModerationAuditLog.createdAt] = System.currentTimeMillis()
                }
                updated += id
            }
        }
        call.respond(
        buildJsonObject {
put("ok", true)
put("updated", Json.parseToJsonElement(Json.encodeToString(updated)))
put("skipped", Json.parseToJsonElement(Json.encodeToString(skipped)))
put("until", until)
put("days", days)
put("count", updated.size)
        }
    )
    }



    post("/users/bulk-post-unrestrict") {
        if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
        val actorId = call.principal<JWTPrincipal>()!!.payload.subject
        val body = runCatching { call.receiveBoundedText(MAX_ADMIN_JSON_BODY_CHARS) }.getOrNull().orEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val rawIds = obj["userIds"]
        val ids = when {
            rawIds == null -> emptyList()
            rawIds is kotlinx.serialization.json.JsonArray -> rawIds.mapNotNull {
                runCatching { it.jsonPrimitive.content }.getOrNull()
            }
            else -> rawIds.jsonPrimitive.content.split(',', ' ', '\n', '\t').map { it.trim() }.filter { it.isNotBlank() }
        }.map { it.take(64) }.distinct().take(100)
        if (ids.isEmpty()) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("userIds required"))
        val updated = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val existing = transaction {
            Users.select(Users.id).where { (Users.id inList ids) and Users.deletedAt.isNull() }.map { it[Users.id] }.toSet()
        }
        transaction {
            ids.forEach { id ->
                if (id == actorId || AdminAccess.isAdmin(id) || id !in existing) {
                    skipped += id
                    return@forEach
                }
                Users.update({ (Users.id eq id) and Users.deletedAt.isNull() }) { it[Users.postRestrictedUntil] = 0L }
                ModerationAuditLog.insert {
                    it[ModerationAuditLog.userId] = id
                    it[ModerationAuditLog.action] = "ADMIN_BULK_POST_UNRESTRICT"
                    it[ModerationAuditLog.detail] = "cleared"
                    it[ModerationAuditLog.actorId] = actorId
                    it[ModerationAuditLog.createdAt] = System.currentTimeMillis()
                }
                updated += id
            }
        }
        call.respond(
        buildJsonObject {
put("ok", true)
put("updated", Json.parseToJsonElement(Json.encodeToString(updated)))
put("skipped", Json.parseToJsonElement(Json.encodeToString(skipped)))
put("count", updated.size)
        }
    )
    }

post("/users/bulk-set-message-restrict-until") {
        if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
        val actorId = call.principal<JWTPrincipal>()!!.payload.subject
        val body = runCatching { call.receiveBoundedText(MAX_ADMIN_JSON_BODY_CHARS) }.getOrNull().orEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val rawIds = obj["userIds"]
        val ids = when {
            rawIds == null -> emptyList()
            rawIds is kotlinx.serialization.json.JsonArray -> rawIds.mapNotNull {
                runCatching { it.jsonPrimitive.content }.getOrNull()
            }
            else -> rawIds.jsonPrimitive.content.split(',', ' ', '\n', '\t').map { it.trim() }.filter { it.isNotBlank() }
        }.map { it.take(64) }.distinct().take(100)
        val until = (obj["until"]?.jsonPrimitive?.content?.toLongOrNull()
            ?: obj["untilMs"]?.jsonPrimitive?.content?.toLongOrNull()
            ?: 0L).coerceAtLeast(0L)
        // 8.37：与单用户端点一致的时间合法性校验（此前只 coerceAtLeast(0)，
        // 过去时间戳被静默写成已过期限制，Long.MAX_VALUE 绕过 10 年上限）
        val now = System.currentTimeMillis()
        if (until > now + com.maodouchat.server.service.AdminDispositionPolicy.MAX_MESSAGE_RESTRICT_DAYS * 86_400_000L ||
            (until in 1..now)
        ) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("禁发消息截止时间无效"))
        }
        if (ids.isEmpty()) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("userIds required"))
        val updated = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val existing = transaction {
            Users.select(Users.id).where { (Users.id inList ids) and Users.deletedAt.isNull() }.map { it[Users.id] }.toSet()
        }
        transaction {
            ids.forEach { id ->
                if (id == actorId || AdminAccess.isAdmin(id) || id !in existing) {
                    skipped += id
                    return@forEach
                }
                Users.update({ (Users.id eq id) and Users.deletedAt.isNull() }) { it[Users.messageRestrictedUntil] = until }
                ModerationAuditLog.insert {
                    it[ModerationAuditLog.userId] = id
                    it[ModerationAuditLog.action] = "ADMIN_BULK_SET_MSG_RESTRICT_UNTIL"
                    it[ModerationAuditLog.detail] = "until=$until".take(200)
                    it[ModerationAuditLog.actorId] = actorId
                    it[ModerationAuditLog.createdAt] = System.currentTimeMillis()
                }
                updated += id
            }
        }
        call.respond(
        buildJsonObject {
put("ok", true)
put("updated", Json.parseToJsonElement(Json.encodeToString(updated)))
put("skipped", Json.parseToJsonElement(Json.encodeToString(skipped)))
put("until", until)
put("count", updated.size)
        }
    )
    }



    post("/users/bulk-set-searchable-false") {
        if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
        val actorId = call.principal<JWTPrincipal>()!!.payload.subject
        val body = runCatching { call.receiveBoundedText(MAX_ADMIN_JSON_BODY_CHARS) }.getOrNull().orEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val rawIds = obj["userIds"]
        val ids = when {
            rawIds == null -> emptyList()
            rawIds is kotlinx.serialization.json.JsonArray -> rawIds.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }
            else -> rawIds.jsonPrimitive.content.split(',', ' ', '\n', '\t').map { it.trim() }.filter { it.isNotBlank() }
        }.map { it.take(64) }.distinct().take(100)
        if (ids.isEmpty()) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("userIds required"))
        val updated = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val existing = transaction {
            Users.select(Users.id).where { (Users.id inList ids) and Users.deletedAt.isNull() }.map { it[Users.id] }.toSet()
        }
        transaction {
            ids.forEach { id ->
                if (id == actorId || AdminAccess.isAdmin(id) || id !in existing) {
                    skipped += id
                    return@forEach
                }
                Users.update({ (Users.id eq id) and Users.deletedAt.isNull() }) { it[Users.searchable] = false }
                updated += id
            }
        }
        recordAdminAudit(actorId = actorId, action = "bulk_set_searchable_false", detail = "count=${updated.size}")
        call.respond(
        buildJsonObject {
put("ok", true)
put("updated", Json.parseToJsonElement(Json.encodeToString(updated)))
put("skipped", Json.parseToJsonElement(Json.encodeToString(skipped)))
put("count", updated.size)
        }
    )
    }


    post("/users/bulk-set-searchable-true") {
        if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
        val actorId = call.principal<JWTPrincipal>()!!.payload.subject
        val body = runCatching { call.receiveBoundedText(MAX_ADMIN_JSON_BODY_CHARS) }.getOrNull().orEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val rawIds = obj["userIds"]
        val ids = when {
            rawIds == null -> emptyList()
            rawIds is kotlinx.serialization.json.JsonArray -> rawIds.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }
            else -> rawIds.jsonPrimitive.content.split(',', ' ', '\n', '\t').map { it.trim() }.filter { it.isNotBlank() }
        }.map { it.take(64) }.distinct().take(100)
        if (ids.isEmpty()) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("userIds required"))
        val updated = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val existing = transaction {
            Users.select(Users.id).where { (Users.id inList ids) and Users.deletedAt.isNull() }.map { it[Users.id] }.toSet()
        }
        transaction {
            ids.forEach { id ->
                if (id == actorId || AdminAccess.isAdmin(id) || id !in existing) {
                    skipped += id
                    return@forEach
                }
                Users.update({ (Users.id eq id) and Users.deletedAt.isNull() }) { it[Users.searchable] = true }
                updated += id
            }
        }
        recordAdminAudit(actorId = actorId, action = "bulk_set_searchable_true", detail = "count=${updated.size}")
        call.respond(
        buildJsonObject {
put("ok", true)
put("updated", Json.parseToJsonElement(Json.encodeToString(updated)))
put("skipped", Json.parseToJsonElement(Json.encodeToString(skipped)))
put("count", updated.size)
        }
    )
    }


    post("/users/bulk-set-show-status") {
        if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
        val actorId = call.principal<JWTPrincipal>()!!.payload.subject
        val body = runCatching { call.receiveBoundedText(MAX_ADMIN_JSON_BODY_CHARS) }.getOrNull().orEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val showStatus = when (val raw = obj["showStatus"]?.jsonPrimitive?.content?.lowercase()) {
            "false", "0", "no", "off" -> false
            "true", "1", "yes", "on" -> true
            // 9.131：缺字段/拼写错误不得静默默认 true（隐私开关被反向打开）
            else -> return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("showStatus must be a boolean"))
        }
        val rawIds = obj["userIds"]
        val ids = when {
            rawIds == null -> emptyList()
            rawIds is kotlinx.serialization.json.JsonArray -> rawIds.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }
            else -> rawIds.jsonPrimitive.content.split(',', ' ', '\n', '\t').map { it.trim() }.filter { it.isNotBlank() }
        }.map { it.take(64) }.distinct().take(100)
        if (ids.isEmpty()) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("userIds required"))
        val updated = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val existing = transaction {
            Users.select(Users.id).where { (Users.id inList ids) and Users.deletedAt.isNull() }.map { it[Users.id] }.toSet()
        }
        transaction {
            ids.forEach { id ->
                if (id == actorId || AdminAccess.isAdmin(id) || id !in existing) {
                    skipped += id
                    return@forEach
                }
                Users.update({ (Users.id eq id) and Users.deletedAt.isNull() }) { it[Users.showStatus] = showStatus }
                updated += id
            }
        }
        recordAdminAudit(actorId = actorId, action = "bulk_set_show_status", detail = "count=${updated.size};showStatus=$showStatus")
        call.respond(
        buildJsonObject {
put("ok", true)
put("updated", Json.parseToJsonElement(Json.encodeToString(updated)))
put("skipped", Json.parseToJsonElement(Json.encodeToString(skipped)))
put("count", updated.size)
put("showStatus", showStatus)
        }
    )
    }


    post("/users/bulk-set-show-online") {
        if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
        val actorId = call.principal<JWTPrincipal>()!!.payload.subject
        val body = runCatching { call.receiveBoundedText(MAX_ADMIN_JSON_BODY_CHARS) }.getOrNull().orEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val showOnline = when (val raw = obj["showOnline"]?.jsonPrimitive?.content?.lowercase()) {
            "false", "0", "no", "off" -> false
            "true", "1", "yes", "on" -> true
            // 9.131：缺字段/拼写错误不得静默默认 true
            else -> return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("showOnline must be a boolean"))
        }
        val rawIds = obj["userIds"]
        val ids = when {
            rawIds == null -> emptyList()
            rawIds is kotlinx.serialization.json.JsonArray -> rawIds.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }
            else -> rawIds.jsonPrimitive.content.split(',', ' ', '\n', '\t').map { it.trim() }.filter { it.isNotBlank() }
        }.map { it.take(64) }.distinct().take(100)
        if (ids.isEmpty()) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("userIds required"))
        val updated = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val existing = transaction {
            Users.select(Users.id).where { (Users.id inList ids) and Users.deletedAt.isNull() }.map { it[Users.id] }.toSet()
        }
        transaction {
            ids.forEach { id ->
                if (id == actorId || AdminAccess.isAdmin(id) || id !in existing) {
                    skipped += id
                    return@forEach
                }
                Users.update({ (Users.id eq id) and Users.deletedAt.isNull() }) {
                    it[Users.showOnline] = showOnline
                }
                updated += id
            }
        }
        recordAdminAudit(actorId = actorId, action = "bulk_set_show_online", detail = "count=${updated.size};showOnline=$showOnline")
        call.respond(
        buildJsonObject {
put("ok", true)
put("updated", Json.parseToJsonElement(Json.encodeToString(updated)))
put("skipped", Json.parseToJsonElement(Json.encodeToString(skipped)))
put("count", updated.size)
put("showOnline", showOnline)
        }
    )
    }


    post("/users/bulk-set-searchable") {
        if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
        val actorId = call.principal<JWTPrincipal>()!!.payload.subject
        val body = runCatching { call.receiveBoundedText(MAX_ADMIN_JSON_BODY_CHARS) }.getOrNull().orEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val searchable = when (val raw = obj["searchable"]?.jsonPrimitive?.content?.lowercase()) {
            "false", "0", "no", "off" -> false
            "true", "1", "yes", "on" -> true
            // 9.131：缺字段/拼写错误不得静默默认 true（把用户批量设成可被搜索）
            else -> return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("searchable must be a boolean"))
        }
        val rawIds = obj["userIds"]
        val ids = when {
            rawIds == null -> emptyList()
            rawIds is kotlinx.serialization.json.JsonArray -> rawIds.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }
            else -> rawIds.jsonPrimitive.content.split(',', ' ', '\n', '\t').map { it.trim() }.filter { it.isNotBlank() }
        }.map { it.take(64) }.distinct().take(100)
        if (ids.isEmpty()) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("userIds required"))
        val updated = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val existing = transaction {
            Users.select(Users.id).where { (Users.id inList ids) and Users.deletedAt.isNull() }.map { it[Users.id] }.toSet()
        }
        transaction {
            ids.forEach { id ->
                if (id == actorId || AdminAccess.isAdmin(id) || id !in existing) {
                    skipped += id
                    return@forEach
                }
                Users.update({ (Users.id eq id) and Users.deletedAt.isNull() }) {
                    it[Users.searchable] = searchable
                }
                updated += id
            }
        }
        recordAdminAudit(actorId = actorId, action = "bulk_set_searchable", detail = "count=${updated.size};searchable=$searchable")
        call.respond(
        buildJsonObject {
put("ok", true)
put("updated", Json.parseToJsonElement(Json.encodeToString(updated)))
put("skipped", Json.parseToJsonElement(Json.encodeToString(skipped)))
put("count", updated.size)
put("searchable", searchable)
        }
    )
    }


    post("/users/bulk-disable-totp") {
        if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
        val actorId = call.principal<JWTPrincipal>()!!.payload.subject
        val body = runCatching { call.receiveBoundedText(MAX_ADMIN_JSON_BODY_CHARS) }.getOrNull().orEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val rawIds = obj["userIds"]
        val ids = when {
            rawIds == null -> emptyList()
            rawIds is kotlinx.serialization.json.JsonArray -> rawIds.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }
            else -> rawIds.jsonPrimitive.content.split(',', ' ', '\n', '\t').map { it.trim() }.filter { it.isNotBlank() }
        }.map { it.take(64) }.distinct().take(100)
        if (ids.isEmpty()) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("userIds required"))
        val updated = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val existing = transaction {
            Users.select(Users.id).where { (Users.id inList ids) and Users.deletedAt.isNull() }.map { it[Users.id] }.toSet()
        }
        transaction {
            ids.forEach { id ->
                if ((AdminAccess.isAdmin(id) && id != actorId) || id !in existing) {
                    skipped += id
                    return@forEach
                }
                Users.update({ (Users.id eq id) and Users.deletedAt.isNull() }) {
                    it[Users.totpSecret] = null
                    it[Users.totpEnabled] = false
                }
                updated += id
            }
        }
        recordAdminAudit(actorId = actorId, action = "bulk_disable_totp", detail = "count=${updated.size}")
        call.respond(
        buildJsonObject {
put("ok", true)
put("updated", Json.parseToJsonElement(Json.encodeToString(updated)))
put("skipped", Json.parseToJsonElement(Json.encodeToString(skipped)))
put("count", updated.size)
        }
    )
    }


    post("/chats/bulk-clear-invite-tokens") {
        if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
        val actorId = call.principal<JWTPrincipal>()!!.payload.subject
        val body = runCatching { call.receiveBoundedText(MAX_ADMIN_JSON_BODY_CHARS) }.getOrNull().orEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val rawIds = obj["chatIds"]
        val ids = when {
            rawIds == null -> emptyList()
            rawIds is kotlinx.serialization.json.JsonArray -> rawIds.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }
            else -> rawIds.jsonPrimitive.content.split(',', ' ', '\n', '\t').map { it.trim() }.filter { it.isNotBlank() }
        }.map { it.take(64) }.distinct().take(100)
        if (ids.isEmpty()) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatIds required"))
        val updated = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val existing = transaction {
            Chats.select(Chats.id).where { Chats.id inList ids }.map { it[Chats.id] }.toSet()
        }
        transaction {
            ids.forEach { id ->
                if (id !in existing) {
                    skipped += id
                    return@forEach
                }
                Chats.update({ Chats.id eq id }) {
                    it[Chats.groupInviteToken] = null
                    it[Chats.groupInviteExpiresAt] = 0L
                    it[Chats.groupInviteMaxUses] = 0
                    it[Chats.groupInviteUseCount] = 0
                }
                updated += id
            }
        }
        recordAdminAudit(actorId = actorId, action = "bulk_clear_invite_tokens", detail = "count=${updated.size}")
        call.respond(
        buildJsonObject {
put("ok", true)
put("updated", Json.parseToJsonElement(Json.encodeToString(updated)))
put("skipped", Json.parseToJsonElement(Json.encodeToString(skipped)))
put("count", updated.size)
        }
    )
    }

post("/users/bulk-set-suspend-until") {
        if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
        val actorId = call.principal<JWTPrincipal>()!!.payload.subject
        val body = runCatching { call.receiveBoundedText(MAX_ADMIN_JSON_BODY_CHARS) }.getOrNull().orEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val until = obj["suspendedUntil"]?.jsonPrimitive?.content?.toLongOrNull()
            ?: obj["until"]?.jsonPrimitive?.content?.toLongOrNull()
            ?: 0L
        val now = System.currentTimeMillis()
        if (until < 0 || until > now + MAX_ADMIN_SUSPEND_MS || (until in 1..now)) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("suspendedUntil invalid"))
        }
        val rawIds = obj["userIds"]
        val ids = when {
            rawIds == null -> emptyList()
            rawIds is kotlinx.serialization.json.JsonArray -> rawIds.mapNotNull {
                runCatching { it.jsonPrimitive.content }.getOrNull()
            }
            else -> rawIds.jsonPrimitive.content.split(',', ' ', '\n', '	').map { it.trim() }.filter { it.isNotBlank() }
        }.map { it.take(64) }.distinct().take(100)
        if (ids.isEmpty()) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("userIds required"))
        val updated = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val shouldInvalidate = until > now
        val existing = transaction {
            Users.select(Users.id).where { (Users.id inList ids) and Users.deletedAt.isNull() }.map { it[Users.id] }.toSet()
        }
        transaction {
            ids.forEach { id ->
                if (id == actorId || AdminAccess.isAdmin(id) || id !in existing) {
                    skipped += id
                    return@forEach
                }
                Users.update({ (Users.id eq id) and Users.deletedAt.isNull() }) {
                    it[Users.suspendedUntil] = until
                }
                ModerationAuditLog.insert {
                    it[ModerationAuditLog.userId] = id
                    it[ModerationAuditLog.action] = "ADMIN_BULK_SET_SUSPEND_UNTIL"
                    it[ModerationAuditLog.detail] = "until=$until"
                    it[ModerationAuditLog.actorId] = actorId
                    it[ModerationAuditLog.createdAt] = System.currentTimeMillis()
                }
                updated += id
            }
        }
        if (shouldInvalidate) {
            updated.forEach { id ->
                authTokenRepo.rotateAccessTokenVersion(id)
                bestEffortAdminDisconnect { disconnectUserSessions(id, "admin bulk suspend until") }
            }
        }
        call.respond(
        buildJsonObject {
put("ok", true)
put("updated", Json.parseToJsonElement(Json.encodeToString(updated)))
put("skipped", Json.parseToJsonElement(Json.encodeToString(skipped)))
put("count", updated.size)
put("suspendedUntil", until)
        }
    )
    }

post("/users/bulk-clear-all-restrictions") {
        if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
        val actorId = call.principal<JWTPrincipal>()!!.payload.subject
        val body = runCatching { call.receiveBoundedText(MAX_ADMIN_JSON_BODY_CHARS) }.getOrNull().orEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val rawIds = obj["userIds"]
        val ids = when {
            rawIds == null -> emptyList()
            rawIds is kotlinx.serialization.json.JsonArray -> rawIds.mapNotNull {
                runCatching { it.jsonPrimitive.content }.getOrNull()
            }
            else -> rawIds.jsonPrimitive.content.split(',', ' ', '\n', '\t').map { it.trim() }.filter { it.isNotBlank() }
        }.map { it.take(64) }.distinct().take(100)
        if (ids.isEmpty()) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("userIds required"))
        val updated = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val existing = transaction {
            Users.select(Users.id).where { (Users.id inList ids) and Users.deletedAt.isNull() }.map { it[Users.id] }.toSet()
        }
        transaction {
            ids.forEach { id ->
                if (id == actorId || AdminAccess.isAdmin(id) || id !in existing) {
                    skipped += id
                    return@forEach
                }
                Users.update({ (Users.id eq id) and Users.deletedAt.isNull() }) {
                    it[Users.messageRestrictedUntil] = 0L
                    it[Users.postRestrictedUntil] = 0L
                    it[Users.suspendedUntil] = 0L
                }
                ModerationAuditLog.insert {
                    it[ModerationAuditLog.userId] = id
                    it[ModerationAuditLog.action] = "ADMIN_BULK_CLEAR_ALL_RESTRICTIONS"
                    it[ModerationAuditLog.detail] = "cleared msg+post+suspend"
                    it[ModerationAuditLog.actorId] = actorId
                    it[ModerationAuditLog.createdAt] = System.currentTimeMillis()
                }
                updated += id
            }
        }
        call.respond(
        buildJsonObject {
put("ok", true)
put("updated", Json.parseToJsonElement(Json.encodeToString(updated)))
put("skipped", Json.parseToJsonElement(Json.encodeToString(skipped)))
put("count", updated.size)
        }
    )
    }

post("/users/bulk-clear-message-and-post-restrict") {
        if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
        val actorId = call.principal<JWTPrincipal>()!!.payload.subject
        val body = runCatching { call.receiveBoundedText(MAX_ADMIN_JSON_BODY_CHARS) }.getOrNull().orEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val rawIds = obj["userIds"]
        val ids = when {
            rawIds == null -> emptyList()
            rawIds is kotlinx.serialization.json.JsonArray -> rawIds.mapNotNull {
                runCatching { it.jsonPrimitive.content }.getOrNull()
            }
            else -> rawIds.jsonPrimitive.content.split(',', ' ', '\n', '\t').map { it.trim() }.filter { it.isNotBlank() }
        }.map { it.take(64) }.distinct().take(100)
        if (ids.isEmpty()) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("userIds required"))
        val updated = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val existing = transaction {
            Users.select(Users.id).where { (Users.id inList ids) and Users.deletedAt.isNull() }.map { it[Users.id] }.toSet()
        }
        transaction {
            ids.forEach { id ->
                if (id == actorId || AdminAccess.isAdmin(id) || id !in existing) {
                    skipped += id
                    return@forEach
                }
                Users.update({ (Users.id eq id) and Users.deletedAt.isNull() }) {
                    it[Users.messageRestrictedUntil] = 0L
                    it[Users.postRestrictedUntil] = 0L
                }
                ModerationAuditLog.insert {
                    it[ModerationAuditLog.userId] = id
                    it[ModerationAuditLog.action] = "ADMIN_BULK_CLEAR_MSG_AND_POST_RESTRICT"
                    it[ModerationAuditLog.detail] = "cleared"
                    it[ModerationAuditLog.actorId] = actorId
                    it[ModerationAuditLog.createdAt] = System.currentTimeMillis()
                }
                updated += id
            }
        }
        call.respond(
        buildJsonObject {
put("ok", true)
put("updated", Json.parseToJsonElement(Json.encodeToString(updated)))
put("skipped", Json.parseToJsonElement(Json.encodeToString(skipped)))
put("count", updated.size)
        }
    )
    }

post("/users/bulk-force-token-bump") {
        if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
        val actorId = call.principal<JWTPrincipal>()!!.payload.subject
        val body = runCatching { call.receiveBoundedText(MAX_ADMIN_JSON_BODY_CHARS) }.getOrNull().orEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val rawIds = obj["userIds"]
        val ids = when {
            rawIds == null -> emptyList()
            rawIds is kotlinx.serialization.json.JsonArray -> rawIds.mapNotNull {
                runCatching { it.jsonPrimitive.content }.getOrNull()
            }
            else -> rawIds.jsonPrimitive.content.split(',', ' ', '\n', '\t').map { it.trim() }.filter { it.isNotBlank() }
        }.map { it.take(64) }.distinct().take(100)
        if (ids.isEmpty()) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("userIds required"))
        val updated = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val bumped = mutableListOf<Triple<String, Long, Long>>()
        ids.forEach { id ->
            if (id == actorId || AdminAccess.isAdmin(id)) {
                skipped += id
                return@forEach
            }
            val next = authTokenRepo.rotateAccessTokenVersion(id)
            val ok = next > 0L
            if (ok) {
                bumped += Triple(id, next, System.currentTimeMillis())
            }
            if (ok) updated += id else skipped += id
        }
        if (bumped.isNotEmpty()) {
            transaction {
                ModerationAuditLog.batchInsert(bumped) { entry ->
                    this[ModerationAuditLog.userId] = entry.first
                    this[ModerationAuditLog.action] = "ADMIN_BULK_TOKEN_BUMP"
                    this[ModerationAuditLog.detail] = "version=${entry.second}"
                    this[ModerationAuditLog.actorId] = actorId
                    this[ModerationAuditLog.createdAt] = entry.third
                }
            }
            bumped.forEach { (id, _, _) ->
                try {
                    disconnectUserSessions(id, "admin bulk token bump")
                } catch (cancel: kotlinx.coroutines.CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                }
            }
        }
        call.respond(
        buildJsonObject {
put("ok", true)
put("updated", Json.parseToJsonElement(Json.encodeToString(updated)))
put("skipped", Json.parseToJsonElement(Json.encodeToString(skipped)))
put("count", updated.size)
        }
    )
    }

post("/users/bulk-clear-suspend") {
        if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
        val actorId = call.principal<JWTPrincipal>()!!.payload.subject
        val body = runCatching { call.receiveBoundedText(MAX_ADMIN_JSON_BODY_CHARS) }.getOrNull().orEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val rawIds = obj["userIds"]
        val ids = when {
            rawIds == null -> emptyList()
            rawIds is kotlinx.serialization.json.JsonArray -> rawIds.mapNotNull {
                runCatching { it.jsonPrimitive.content }.getOrNull()
            }
            else -> rawIds.jsonPrimitive.content.split(',', ' ', '\n', '\t').map { it.trim() }.filter { it.isNotBlank() }
        }.map { it.take(64) }.distinct().take(100)
        if (ids.isEmpty()) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("userIds required"))
        val updated = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val existing = transaction {
            Users.select(Users.id).where { (Users.id inList ids) and Users.deletedAt.isNull() }.map { it[Users.id] }.toSet()
        }
        transaction {
            ids.forEach { id ->
                if (id == actorId || AdminAccess.isAdmin(id) || id !in existing) {
                    skipped += id
                    return@forEach
                }
                Users.update({ (Users.id eq id) and Users.deletedAt.isNull() }) { it[Users.suspendedUntil] = 0L }
                ModerationAuditLog.insert {
                    it[ModerationAuditLog.userId] = id
                    it[ModerationAuditLog.action] = "ADMIN_BULK_CLEAR_SUSPEND"
                    it[ModerationAuditLog.detail] = "cleared"
                    it[ModerationAuditLog.actorId] = actorId
                    it[ModerationAuditLog.createdAt] = System.currentTimeMillis()
                }
                updated += id
            }
        }
        call.respond(
        buildJsonObject {
put("ok", true)
put("updated", Json.parseToJsonElement(Json.encodeToString(updated)))
put("skipped", Json.parseToJsonElement(Json.encodeToString(skipped)))
put("count", updated.size)
        }
    )
    }

post("/users/bulk-clear-message-restrict") {
        if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
        val actorId = call.principal<JWTPrincipal>()!!.payload.subject
        val body = runCatching { call.receiveBoundedText(MAX_ADMIN_JSON_BODY_CHARS) }.getOrNull().orEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val rawIds = obj["userIds"]
        val ids = when {
            rawIds == null -> emptyList()
            rawIds is kotlinx.serialization.json.JsonArray -> rawIds.mapNotNull {
                runCatching { it.jsonPrimitive.content }.getOrNull()
            }
            else -> rawIds.jsonPrimitive.content.split(',', ' ', '\n', '\t').map { it.trim() }.filter { it.isNotBlank() }
        }.map { it.take(64) }.distinct().take(100)
        if (ids.isEmpty()) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("userIds required"))
        val updated = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val existing = transaction {
            Users.select(Users.id).where { (Users.id inList ids) and Users.deletedAt.isNull() }.map { it[Users.id] }.toSet()
        }
        transaction {
            ids.forEach { id ->
                if (id == actorId || AdminAccess.isAdmin(id) || id !in existing) {
                    skipped += id
                    return@forEach
                }
                Users.update({ (Users.id eq id) and Users.deletedAt.isNull() }) { it[Users.messageRestrictedUntil] = 0L }
                ModerationAuditLog.insert {
                    it[ModerationAuditLog.userId] = id
                    it[ModerationAuditLog.action] = "ADMIN_BULK_CLEAR_MSG_RESTRICT"
                    it[ModerationAuditLog.detail] = "cleared"
                    it[ModerationAuditLog.actorId] = actorId
                    it[ModerationAuditLog.createdAt] = System.currentTimeMillis()
                }
                updated += id
            }
        }
        call.respond(
        buildJsonObject {
put("ok", true)
put("updated", Json.parseToJsonElement(Json.encodeToString(updated)))
put("skipped", Json.parseToJsonElement(Json.encodeToString(skipped)))
put("count", updated.size)
        }
    )
    }

post("/users/bulk-message-restrict-days") {
        if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
        val actorId = call.principal<JWTPrincipal>()!!.payload.subject
        val body = runCatching { call.receiveBoundedText(MAX_ADMIN_JSON_BODY_CHARS) }.getOrNull().orEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val rawIds = obj["userIds"]
        // 9.131：上限改用策略常量——此前硬编码 3650 天（10 年）绕过
        // AdminDispositionPolicy.MAX_MESSAGE_RESTRICT_DAYS(90) 的处置上限
        val days = (obj["days"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1)
            .coerceIn(1, AdminDispositionPolicy.MAX_MESSAGE_RESTRICT_DAYS)
        val ids = when {
            rawIds == null -> emptyList()
            rawIds is kotlinx.serialization.json.JsonArray -> rawIds.mapNotNull {
                runCatching { it.jsonPrimitive.content }.getOrNull()
            }
            else -> rawIds.jsonPrimitive.content.split(',', ' ', '\n', '\t').map { it.trim() }.filter { it.isNotBlank() }
        }.map { it.take(64) }.distinct().take(100)
        if (ids.isEmpty()) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("userIds required"))
        val until = System.currentTimeMillis() + days * 24L * 60L * 60L * 1000L
        val updated = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val existing = transaction {
            Users.select(Users.id).where { (Users.id inList ids) and Users.deletedAt.isNull() }.map { it[Users.id] }.toSet()
        }
        transaction {
            ids.forEach { id ->
                if (id == actorId || AdminAccess.isAdmin(id) || id !in existing) {
                    skipped += id
                    return@forEach
                }
                // 9.128：保长语义——不缩短既有更长限制
                val row = Users.selectAll().where { Users.id eq id }.firstOrNull()
                if (row == null) {
                    skipped += id
                    return@forEach
                }
                val effectiveUntil = if (until <= 0L) 0L else maxOf(row[Users.messageRestrictedUntil], until)
                Users.update({ (Users.id eq id) and Users.deletedAt.isNull() }) { it[Users.messageRestrictedUntil] = effectiveUntil }
                ModerationAuditLog.insert {
                    it[ModerationAuditLog.userId] = id
                    it[ModerationAuditLog.action] = "ADMIN_BULK_MSG_RESTRICT_DAYS"
                    it[ModerationAuditLog.detail] = "days=$days until=$effectiveUntil".take(200)
                    it[ModerationAuditLog.actorId] = actorId
                    it[ModerationAuditLog.createdAt] = System.currentTimeMillis()
                }
                updated += id
            }
        }
        call.respond(
        buildJsonObject {
put("ok", true)
put("updated", Json.parseToJsonElement(Json.encodeToString(updated)))
put("skipped", Json.parseToJsonElement(Json.encodeToString(skipped)))
put("until", until)
put("days", days)
put("count", updated.size)
        }
    )
    }

post("/users/bulk-clear-post-restrict") {
        if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
        val actorId = call.principal<JWTPrincipal>()!!.payload.subject
        val body = runCatching { call.receiveBoundedText(MAX_ADMIN_JSON_BODY_CHARS) }.getOrNull().orEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val rawIds = obj["userIds"]
        val ids = when {
            rawIds == null -> emptyList()
            rawIds is kotlinx.serialization.json.JsonArray -> rawIds.mapNotNull {
                runCatching { it.jsonPrimitive.content }.getOrNull()
            }
            else -> rawIds.jsonPrimitive.content.split(',', ' ', '\n', '\t').map { it.trim() }.filter { it.isNotBlank() }
        }.map { it.take(64) }.distinct().take(100)
        if (ids.isEmpty()) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("userIds required"))
        val updated = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val existing = transaction {
            Users.select(Users.id).where { (Users.id inList ids) and Users.deletedAt.isNull() }.map { it[Users.id] }.toSet()
        }
        transaction {
            ids.forEach { id ->
                if (id == actorId || AdminAccess.isAdmin(id) || id !in existing) {
                    skipped += id
                    return@forEach
                }
                Users.update({ (Users.id eq id) and Users.deletedAt.isNull() }) { it[Users.postRestrictedUntil] = 0L }
                ModerationAuditLog.insert {
                    it[ModerationAuditLog.userId] = id
                    it[ModerationAuditLog.action] = "ADMIN_BULK_CLEAR_POST_RESTRICT"
                    it[ModerationAuditLog.detail] = "cleared"
                    it[ModerationAuditLog.actorId] = actorId
                    it[ModerationAuditLog.createdAt] = System.currentTimeMillis()
                }
                updated += id
            }
        }
        call.respond(
        buildJsonObject {
put("ok", true)
put("updated", Json.parseToJsonElement(Json.encodeToString(updated)))
put("skipped", Json.parseToJsonElement(Json.encodeToString(skipped)))
put("count", updated.size)
        }
    )
    }


}