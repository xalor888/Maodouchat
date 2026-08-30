package com.maodouchat.server.plugins

import com.maodouchat.server.auth.JwtConfig
import com.maodouchat.server.config.AdminAccess
import com.maodouchat.server.config.ServerConfig
import com.maodouchat.server.db.*
import com.maodouchat.server.model.*
import com.maodouchat.server.repository.*
import com.maodouchat.server.service.DispositionService
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
internal fun Route.configureAdminExportsRoutes(authTokenRepo: AuthTokenRepository) {
    get("/push-tokens-export") {
        if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
        val adminId = call.principal<JWTPrincipal>()!!.payload.subject
        val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 5000).coerceIn(1, 20000)
        // Privacy-safe: no full push token secret — prefix only
        val rows = transaction {
            PushTokens.selectAll()
                .orderBy(PushTokens.updatedAt to org.jetbrains.exposed.sql.SortOrder.DESC)
                .limit(limit)
                .map { row ->
                    val tok = row[PushTokens.token]
                    val prefix = if (tok.length <= 12) tok.take(4) + "…" else tok.take(8) + "…" + tok.takeLast(4)
                    listOf(
                        csvCell(row[PushTokens.userId]),
                        csvCell(row[PushTokens.deviceId]),
                        csvCell(row[PushTokens.platform]),
                        csvCell(prefix),
                        csvCell(row[PushTokens.timezoneOffsetMinutes].toString()),
                        csvCell(row[PushTokens.updatedAt].toString())
                    ).joinToString(",")
                }
        }
        val csv = buildString {
            appendLine("userId,deviceId,platform,tokenPrefix,timezoneOffsetMinutes,updatedAt")
            rows.forEach { appendLine(it) }
        }
        recordAdminAudit(actorId = adminId, action = "push_tokens_export", detail = "count=${rows.size}")
        call.response.header(
            HttpHeaders.ContentDisposition,
            "attachment; filename=\"maodouchat-push-tokens.csv\""
        )
        call.respondText(csv, io.ktor.http.ContentType.Text.CSV)
    }




    get("/users-export") {
        if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
        val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 5000).coerceIn(1, 20000)
        val rows = transaction {
            Users.selectAll()
                .orderBy(Users.lastSeen to org.jetbrains.exposed.sql.SortOrder.DESC)
                .limit(limit)
                .map { row ->
                    listOf(
                        csvCell(row[Users.id]),
                        csvCell(row[Users.name]),
                        csvCell(row[Users.email]),
                        csvCell(row[Users.status]),
                        csvCell(row[Users.isOnline].toString()),
                        csvCell(row[Users.isModerator].toString()),
                        csvCell(row[Users.suspendedUntil].toString()),
                        csvCell(row[Users.lastSeen].toString())
                    ).joinToString(",")
                }
        }
        val csv = buildString {
            appendLine("id,name,email,status,isOnline,isModerator,suspendedUntil,lastSeen")
            rows.forEach { appendLine(it) }
        }
        call.response.header(
            HttpHeaders.ContentDisposition,
            "attachment; filename=\"maodouchat-users-${System.currentTimeMillis()}.csv\""
        )
        call.respondText(csv, io.ktor.http.ContentType.Text.CSV)
    }






    get("/bots-export") {
        if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
        // 只导出元数据：token 只含前缀，绝不导出 tokenHash
        val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 2000).coerceIn(1, 20000)
        val rows = transaction {
            BotApps.selectAll()
                .orderBy(BotApps.createdAt to org.jetbrains.exposed.sql.SortOrder.DESC)
                .limit(limit)
                .map { row ->
                    listOf(
                        csvCell(row[BotApps.id]),
                        csvCell(row[BotApps.name]),
                        csvCell(row[BotApps.username]),
                        csvCell(row[BotApps.ownerUserId]),
                        csvCell(row[BotApps.tokenPrefix]),
                        csvCell(row[BotApps.webhookUrl] ?: ""),
                        csvCell(row[BotApps.enabled].toString()),
                        csvCell(row[BotApps.createdAt].toString()),
                        csvCell(row[BotApps.updatedAt].toString())
                    ).joinToString(",")
                }
        }
        val csv = buildString {
            appendLine("id,name,username,ownerUserId,tokenPrefix,webhookUrl,enabled,createdAt,updatedAt")
            rows.forEach { appendLine(it) }
        }
        call.response.header(
            HttpHeaders.ContentDisposition,
            "attachment; filename=\"maodouchat-bots-${System.currentTimeMillis()}.csv\""
        )
        call.respondText(csv, io.ktor.http.ContentType.Text.CSV)
    }


    get("/message-stats-export") {
        if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
        // Aggregate durable transport kinds only; never export message bodies.
        val rows = transaction {
            MessagingV2Messages
                .slice(MessagingV2Messages.kind, MessagingV2Messages.kind.count())
                .selectAll()
                .where {
                    MessagingV2Messages.recordClass eq com.maodouchat.server.messaging.v2.MessagingV2RecordClass.MESSAGE
                }
                .groupBy(MessagingV2Messages.kind)
                .map { row ->
                    val type = row[MessagingV2Messages.kind]
                    val count = row[MessagingV2Messages.kind.count()]
                    listOf(csvCell(type), csvCell(count)).joinToString(",")
                }
                .sorted()
        }
        val total = transaction {
            MessagingV2Messages.selectAll().where {
                MessagingV2Messages.recordClass eq com.maodouchat.server.messaging.v2.MessagingV2RecordClass.MESSAGE
            }.count()
        }
        val csv = buildString {
            appendLine("type,count")
            rows.forEach { appendLine(it) }
            appendLine(listOf(csvCell("TOTAL"), csvCell(total)).joinToString(","))
        }
        call.response.header(
            HttpHeaders.ContentDisposition,
            "attachment; filename=\"maodouchat-message-stats-${System.currentTimeMillis()}.csv\""
        )
        call.respondText(csv, io.ktor.http.ContentType.Text.CSV)
    }


    get("/reports-export") {
        if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
        val adminId = call.principal<JWTPrincipal>()!!.payload.subject
        val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 2000).coerceIn(1, 10000)
        val rows = org.jetbrains.exposed.sql.transactions.transaction {
            com.maodouchat.server.db.Reports.selectAll()
                .orderBy(
                    com.maodouchat.server.db.Reports.createdAt to org.jetbrains.exposed.sql.SortOrder.DESC,
                    com.maodouchat.server.db.Reports.id to org.jetbrains.exposed.sql.SortOrder.DESC
                )
                .limit(limit)
                .map { row ->
                    listOf(
                        csvCell(row[com.maodouchat.server.db.Reports.id]),
                        csvCell(row[com.maodouchat.server.db.Reports.reporterId]),
                        csvCell(row[com.maodouchat.server.db.Reports.targetType]),
                        csvCell(row[com.maodouchat.server.db.Reports.targetId]),
                        csvCell(row[com.maodouchat.server.db.Reports.reason].take(200)),
                        csvCell(row[com.maodouchat.server.db.Reports.status]),
                        csvCell(row[com.maodouchat.server.db.Reports.createdAt].toString())
                    ).joinToString(",")
                }
        }
        val csv = buildString {
            appendLine("id,reporterId,targetType,targetId,reason,status,createdAt")
            rows.forEach { appendLine(it) }
        }
        recordAdminAudit(actorId = adminId, action = "reports_export", detail = "count=${rows.size}")
        call.response.header(
            io.ktor.http.HttpHeaders.ContentDisposition,
            "attachment; filename=\"maodouchat-reports.csv\""
        )
        call.respondText(csv, io.ktor.http.ContentType.Text.CSV)
    }


    get("/risk-events-export") {
        if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
        val adminId = call.principal<JWTPrincipal>()!!.payload.subject
        val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 2000).coerceIn(1, 10000)
        val rows = org.jetbrains.exposed.sql.transactions.transaction {
            com.maodouchat.server.db.RiskEvents.selectAll()
                .orderBy(
                    com.maodouchat.server.db.RiskEvents.createdAt to org.jetbrains.exposed.sql.SortOrder.DESC,
                    com.maodouchat.server.db.RiskEvents.id to org.jetbrains.exposed.sql.SortOrder.DESC
                )
                .limit(limit)
                .map { row ->
                    listOf(
                        csvCell(row[com.maodouchat.server.db.RiskEvents.id]),
                        csvCell(row[com.maodouchat.server.db.RiskEvents.userId]),
                        csvCell(row[com.maodouchat.server.db.RiskEvents.sourceValue]),
                        csvCell(row[com.maodouchat.server.db.RiskEvents.action]),
                        csvCell((row[com.maodouchat.server.db.RiskEvents.matched] ?: "").replace("\n", " ").take(200)),
                        csvCell(row[com.maodouchat.server.db.RiskEvents.needsReview].toString()),
                        csvCell(row[com.maodouchat.server.db.RiskEvents.createdAt].toString())
                    ).joinToString(",")
                }
        }
        val csv = buildString {
            appendLine("id,userId,source,action,matched,needsReview,createdAt")
            rows.forEach { appendLine(it) }
        }
        recordAdminAudit(actorId = adminId, action = "risk_events_export", detail = "count=${rows.size}")
        call.response.header(
            io.ktor.http.HttpHeaders.ContentDisposition,
            "attachment; filename=\"maodouchat-risk-events.csv\""
        )
        call.respondText(csv, io.ktor.http.ContentType.Text.CSV)
    }


    get("/online-export") {
        if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
        val adminId = call.principal<JWTPrincipal>()!!.payload.subject
        // Privacy-safe: ids + presence only, no message bodies
        val online = try {
            com.maodouchat.server.plugins.ConnectionRegistry.onlineUserIds()
        } catch (_: Exception) {
            emptyList<String>()
        }
        val csv = buildString {
            appendLine("userId,online")
            online.forEach { appendLine(listOf(csvCell(it), csvCell(1)).joinToString(",")) }
        }
        recordAdminAudit(actorId = adminId, action = "online_export", detail = "count=${online.size}")
        call.response.header(
            io.ktor.http.HttpHeaders.ContentDisposition,
            "attachment; filename=\"maodouchat-online.csv\""
        )
        call.respondText(csv, io.ktor.http.ContentType.Text.CSV)
    }





    get("/sessions-summary-export") {
        if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
        val adminId = call.principal<JWTPrincipal>()!!.payload.subject
        val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 5000).coerceIn(1, 20000)
        // Privacy-safe: session counts per user, no token secrets
        val rows = transaction {
            // Aggregate active refresh sessions by userId if table exposes userId
            try {
                // Fall back to listing users with online flag only when refresh table schema is private
                val users = Users.selectAll()
                    .orderBy(Users.lastSeen to org.jetbrains.exposed.sql.SortOrder.DESC)
                    .limit(limit)
                    .toList()
                // 8.48 修复 M7：批量统计活跃会话（此前逐用户 count → N+1）
                val activeByUser = authTokenRepo.countActiveRefreshSessionsBatch(users.map { it[Users.id] })
                users.map { row ->
                        val uid = row[Users.id]
                        listOf(
                            csvCell(uid),
                            csvCell(row[Users.name].take(40)),
                            csvCell(row[Users.isOnline].toString()),
                            csvCell((activeByUser[uid] ?: 0).toString()),
                            csvCell(row[Users.lastSeen].toString())
                        ).joinToString(",")
                    }
            } catch (_: Exception) {
                emptyList()
            }
        }
        val csv = buildString {
            appendLine("userId,name,isOnline,activeRefreshSessions,lastSeen")
            rows.forEach { appendLine(it) }
        }
        recordAdminAudit(actorId = adminId, action = "sessions_summary_export", detail = "count=${rows.size}")
        call.response.header(
            HttpHeaders.ContentDisposition,
            "attachment; filename=\"maodouchat-sessions-summary.csv\""
        )
        call.respondText(csv, io.ktor.http.ContentType.Text.CSV)
    }

get("/polls-export") {
        if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
        val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 2000).coerceIn(1, 10000)
        val rows = transaction {
            val polls = GroupPolls.selectAll()
                .orderBy(
                    GroupPolls.createdAt to org.jetbrains.exposed.sql.SortOrder.DESC,
                    GroupPolls.id to org.jetbrains.exposed.sql.SortOrder.DESC
                )
                .limit(limit)
                .toList()
            // 8.48 修复 H6：批量 count（此前逐投票查询 → limit 1 万次查询）
            val pollIds = polls.map { it[GroupPolls.id] }
            val votesByPoll = if (pollIds.isEmpty()) emptyMap() else
                GroupPollVotes
                    .slice(GroupPollVotes.pollId, GroupPollVotes.userId.count())
                    .selectAll()
                    .where { GroupPollVotes.pollId inList pollIds }
                    .groupBy(GroupPollVotes.pollId)
                    .associate { it[GroupPollVotes.pollId] to it[GroupPollVotes.userId.count()].toLong() }
            polls.map { row ->
                    val id = row[GroupPolls.id]
                    val votes = votesByPoll[id] ?: 0L
                    listOf(
                        csvCell(id),
                        csvCell(row[GroupPolls.chatId]),
                        csvCell(row[GroupPolls.creatorId]),
                        csvCell(row[GroupPolls.question].take(120)),
                        csvCell(row[GroupPolls.multi].toString()),
                        csvCell(row[GroupPolls.anonymous].toString()),
                        csvCell(row[GroupPolls.closed].toString()),
                        csvCell(votes.toString()),
                        csvCell(row[GroupPolls.createdAt].toString()),
                        csvCell((row[GroupPolls.closesAt] ?: 0L).toString())
                    ).joinToString(",")
                }
        }
        val csv = buildString {
            appendLine("id,chatId,creatorId,question,multi,anonymous,closed,voteRows,createdAt,closesAt")
            rows.forEach { appendLine(it) }
        }
        call.response.header(
            HttpHeaders.ContentDisposition,
            "attachment; filename=\"maodouchat-polls-${System.currentTimeMillis()}.csv\""
        )
        call.respondText(csv, io.ktor.http.ContentType.Text.CSV)
    }



    get("/moderation-audit-export") {
        if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
        val adminId = call.principal<JWTPrincipal>()!!.payload.subject
        val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 2000).coerceIn(1, 10000)
        // Audit metadata only — no message bodies
        val rows = transaction {
            ModerationAuditLog.selectAll()
                .orderBy(
                    ModerationAuditLog.createdAt to org.jetbrains.exposed.sql.SortOrder.DESC,
                    ModerationAuditLog.id to org.jetbrains.exposed.sql.SortOrder.DESC
                )
                .limit(limit)
                .map { row ->
                    listOf(
                        csvCell(row[ModerationAuditLog.id]),
                        csvCell(row[ModerationAuditLog.actorId].orEmpty()),
                        csvCell(row[ModerationAuditLog.userId].orEmpty()),
                        csvCell(row[ModerationAuditLog.action].take(40)),
                        csvCell(row[ModerationAuditLog.detail].orEmpty().replace("\n", " ").take(160)),
                        csvCell(row[ModerationAuditLog.createdAt].toString())
                    ).joinToString(",")
                }
        }
        val csv = buildString {
            appendLine("id,actorId,userId,action,detail,createdAt")
            rows.forEach { appendLine(it) }
        }
        recordAdminAudit(actorId = adminId, action = "moderation_audit_export", detail = "count=${rows.size}")
        call.response.header(
            HttpHeaders.ContentDisposition,
            "attachment; filename=\"maodouchat-moderation-audit.csv\""
        )
        call.respondText(csv, io.ktor.http.ContentType.Text.CSV)
    }



    get("/bot-command-stats-export") {
        if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
        val adminId = call.principal<JWTPrincipal>()!!.payload.subject
        val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 5000).coerceIn(1, 20000)
        // Command names only — no message bodies
        val rows = transaction {
            BotCommandLogs.selectAll()
                .orderBy(
                    BotCommandLogs.createdAt to org.jetbrains.exposed.sql.SortOrder.DESC,
                    BotCommandLogs.id to org.jetbrains.exposed.sql.SortOrder.DESC
                )
                .limit(limit)
                .map { row ->
                    listOf(
                        csvCell(row[BotCommandLogs.id]),
                        csvCell(row[BotCommandLogs.botId]),
                        csvCell((row[BotCommandLogs.chatId] ?: "").take(40)),
                        csvCell((row[BotCommandLogs.userId] ?: "").take(40)),
                        csvCell(row[BotCommandLogs.command].take(80)),
                        csvCell(row[BotCommandLogs.createdAt].toString())
                    ).joinToString(",")
                }
        }
        val csv = buildString {
            appendLine("id,botId,chatId,userId,command,createdAt")
            rows.forEach { appendLine(it) }
        }
        recordAdminAudit(actorId = adminId, action = "bot_command_stats_export", detail = "count=${rows.size}")
        call.response.header(
            HttpHeaders.ContentDisposition,
            "attachment; filename=\"maodouchat-bot-command-stats.csv\""
        )
        call.respondText(csv, io.ktor.http.ContentType.Text.CSV)
    }



    get("/friends-export") {
        if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
        val adminId = call.principal<JWTPrincipal>()!!.payload.subject
        val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 5000).coerceIn(1, 20000)
        // Friendship graph metadata only — no message bodies
        val rows = transaction {
            Friendships.selectAll()
                .orderBy(
                    Friendships.createdAt to org.jetbrains.exposed.sql.SortOrder.DESC,
                    Friendships.userLowId to org.jetbrains.exposed.sql.SortOrder.DESC,
                    Friendships.userHighId to org.jetbrains.exposed.sql.SortOrder.DESC
                )
                .limit(limit)
                .map { row ->
                    listOf(
                        csvCell(row[Friendships.userLowId]),
                        csvCell(row[Friendships.userHighId]),
                        csvCell(row[Friendships.createdAt].toString())
                    ).joinToString(",")
                }
        }
        val csv = buildString {
            appendLine("userLowId,userHighId,createdAt")
            rows.forEach { appendLine(it) }
        }
        recordAdminAudit(actorId = adminId, action = "friends_export", detail = "count=${rows.size}")
        call.response.header(
            HttpHeaders.ContentDisposition,
            "attachment; filename=\"maodouchat-friends.csv\""
        )
        call.respondText(csv, io.ktor.http.ContentType.Text.CSV)
    }



    get("/reports-meta-export") {
        if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
        val adminId = call.principal<JWTPrincipal>()!!.payload.subject
        val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 5000).coerceIn(1, 20000)
        // Report metadata only — no message bodies / E2EE plaintext
        val rows = transaction {
            Reports.selectAll()
                .orderBy(
                    Reports.createdAt to org.jetbrains.exposed.sql.SortOrder.DESC,
                    Reports.id to org.jetbrains.exposed.sql.SortOrder.DESC
                )
                .limit(limit)
                .map { row ->
                    listOf(
                        csvCell(row[Reports.id]),
                        csvCell(row[Reports.reporterId]),
                        csvCell(row[Reports.targetType]),
                        csvCell(row[Reports.targetId]),
                        csvCell((row[Reports.chatId] ?: "")),
                        csvCell(row[Reports.reason].take(60)),
                        csvCell(row[Reports.status]),
                        csvCell((row[Reports.actionTaken] ?: "")),
                        csvCell(row[Reports.createdAt].toString())
                    ).joinToString(",")
                }
        }
        val csv = buildString {
            appendLine("id,reporterId,targetType,targetId,chatId,reason,status,actionTaken,createdAt")
            rows.forEach { appendLine(it) }
        }
        recordAdminAudit(actorId = adminId, action = "reports_meta_export", detail = "count=${rows.size}")
        call.response.header(
            HttpHeaders.ContentDisposition,
            "attachment; filename=\"maodouchat-reports-meta.csv\""
        )
        call.respondText(csv, io.ktor.http.ContentType.Text.CSV)
    }



    get("/blocks-export") {
        if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
        val adminId = call.principal<JWTPrincipal>()!!.payload.subject
        val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 5000).coerceIn(1, 20000)
        // Block edges only — no message bodies
        val rows = transaction {
            BlockedUsers.selectAll()
                .limit(limit)
                .map { row ->
                    listOf(
                        csvCell(row[BlockedUsers.blockerId]),
                        csvCell(row[BlockedUsers.blockedId])
                    ).joinToString(",")
                }
        }
        val csv = buildString {
            appendLine("blockerId,blockedId")
            rows.forEach { appendLine(it) }
        }
        recordAdminAudit(actorId = adminId, action = "blocks_export", detail = "count=${rows.size}")
        call.response.header(
            HttpHeaders.ContentDisposition,
            "attachment; filename=\"maodouchat-blocks.csv\""
        )
        call.respondText(csv, io.ktor.http.ContentType.Text.CSV)
    }



    get("/chat-settings-export") {
        if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
        val adminId = call.principal<JWTPrincipal>()!!.payload.subject
        val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 5000).coerceIn(1, 20000)
        // Per-user chat settings metadata only — no message bodies / SECRET ids
        val rows = transaction {
            ChatUserSettings.selectAll()
                .andWhere {
                    ChatUserSettings.chatId notInSubQuery (
                        Chats.select(Chats.id).where { Chats.chatType eq ChatType.SECRET }
                    )
                }
                .orderBy(ChatUserSettings.updatedAt to org.jetbrains.exposed.sql.SortOrder.DESC)
                .limit(limit)
                .map { row ->
                    listOf(
                        csvCell(row[ChatUserSettings.userId]),
                        csvCell(row[ChatUserSettings.chatId]),
                        csvCell(row[ChatUserSettings.pinnedAt].toString()),
                        csvCell(row[ChatUserSettings.notificationsMuted].toString()),
                        csvCell(row[ChatUserSettings.archived].toString()),
                        csvCell(row[ChatUserSettings.markedUnread].toString()),
                        csvCell(row[ChatUserSettings.updatedAt].toString())
                    ).joinToString(",")
                }
        }
        val csv = buildString {
            appendLine("userId,chatId,pinnedAt,notificationsMuted,archived,markedUnread,updatedAt")
            rows.forEach { appendLine(it) }
        }
        recordAdminAudit(actorId = adminId, action = "chat_settings_export", detail = "count=${rows.size}")
        call.response.header(
            HttpHeaders.ContentDisposition,
            "attachment; filename=\"maodouchat-chat-settings.csv\""
        )
        call.respondText(csv, io.ktor.http.ContentType.Text.CSV)
    }



    get("/disappearing-chats-export") {
        if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
        val adminId = call.principal<JWTPrincipal>()!!.payload.subject
        val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 5000).coerceIn(1, 20000)
        // Chat disappearing timer metadata only — no message bodies
        val rows = transaction {
            Chats.selectAll()
                .andWhere { Chats.chatType neq ChatType.SECRET }
                .orderBy(Chats.memberRevision to org.jetbrains.exposed.sql.SortOrder.DESC)
                .limit(limit)
                .mapNotNull { row ->
                    val seconds = row[Chats.disappearingMessageSeconds]
                    if (seconds <= 0) return@mapNotNull null
                    listOf(
                        csvCell(row[Chats.id]),
                        csvCell(row[Chats.isGroup].toString()),
                        csvCell((row[Chats.groupName] ?: "").take(80)),
                        csvCell(seconds.toString())
                    ).joinToString(",")
                }
        }
        val csv = buildString {
            appendLine("chatId,isGroup,groupName,disappearingSeconds")
            rows.forEach { appendLine(it) }
        }
        recordAdminAudit(actorId = adminId, action = "disappearing_chats_export", detail = "count=${rows.size}")
        call.response.header(
            HttpHeaders.ContentDisposition,
            "attachment; filename=\"maodouchat-disappearing-chats.csv\""
        )
        call.respondText(csv, io.ktor.http.ContentType.Text.CSV)
    }



    get("/muted-chats-export") {
        if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
        val adminId = call.principal<JWTPrincipal>()!!.payload.subject
        val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 5000).coerceIn(1, 20000)
        // Muted chat settings metadata only — no message bodies
        val rows = transaction {
            ChatUserSettings.selectAll()
                .andWhere {
                    ChatUserSettings.chatId notInSubQuery (
                        Chats.select(Chats.id).where { Chats.chatType eq ChatType.SECRET }
                    )
                }
                .orderBy(ChatUserSettings.updatedAt to org.jetbrains.exposed.sql.SortOrder.DESC)
                .limit(limit * 2)
                .mapNotNull { row ->
                    if (!row[ChatUserSettings.notificationsMuted]) return@mapNotNull null
                    listOf(
                        csvCell(row[ChatUserSettings.userId]),
                        csvCell(row[ChatUserSettings.chatId]),
                        csvCell(row[ChatUserSettings.notificationsMuted].toString()),
                        csvCell(row[ChatUserSettings.updatedAt].toString())
                    ).joinToString(",")
                }
                .take(limit)
        }
        val csv = buildString {
            appendLine("userId,chatId,notificationsMuted,updatedAt")
            rows.forEach { appendLine(it) }
        }
        recordAdminAudit(actorId = adminId, action = "muted_chats_export", detail = "count=${rows.size}")
        call.response.header(
            HttpHeaders.ContentDisposition,
            "attachment; filename=\"maodouchat-muted-chats.csv\""
        )
        call.respondText(csv, io.ktor.http.ContentType.Text.CSV)
    }











    get("/ai-feature-flags-export") {
        if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
        val adminId = call.principal<JWTPrincipal>()!!.payload.subject
        val csv = buildString {
            appendLine("key,value")
            appendLine("ai_enabled," + RuntimeConfigService.isAiEnabled())
            appendLine("ai_content_moderation_enabled," + RuntimeConfigService.isAiContentModerationEnabled())
            appendLine("gif_send_enabled," + RuntimeConfigService.isGifSendEnabled())
            appendLine("blind_watermark_enabled," + RuntimeConfigService.isBlindWatermarkEnabled())
            appendLine("voice_call_enabled," + RuntimeConfigService.isVoiceCallEnabled())
            appendLine("video_call_enabled," + RuntimeConfigService.isVideoCallEnabled())
            appendLine("chat_wallpaper_enabled," + RuntimeConfigService.isChatWallpaperEnabled())
            appendLine("chat_font_scale_enabled," + RuntimeConfigService.isChatFontScaleEnabled())
            appendLine("unread_priority_enabled," + RuntimeConfigService.isUnreadPriorityEnabled())
            appendLine("ringtone_enabled," + RuntimeConfigService.isRingtoneEnabled())
            appendLine("notification_sound_enabled," + RuntimeConfigService.isNotificationSoundEnabled())
            appendLine("notification_preview_enabled," + RuntimeConfigService.isNotificationPreviewEnabled())
            appendLine("push_notifications_enabled," + RuntimeConfigService.isPushNotificationsEnabled())
            appendLine("task_reminders_enabled," + RuntimeConfigService.isTaskRemindersEnabled())
            appendLine("dnd_enabled," + RuntimeConfigService.isDndEnabled())
            appendLine("in_app_sounds_enabled," + RuntimeConfigService.isInAppSoundsEnabled())
            appendLine("haptics_enabled," + RuntimeConfigService.isHapticsEnabled())
            appendLine("chat_animations_enabled," + RuntimeConfigService.isChatAnimationsEnabled())
            appendLine("nav_transitions_enabled," + RuntimeConfigService.isNavTransitionsEnabled())
            appendLine("screenshot_detect_enabled," + RuntimeConfigService.isScreenshotDetectEnabled())
            appendLine("recents_exclusion_enabled," + RuntimeConfigService.isRecentsExclusionEnabled())
            appendLine("secret_copy_block_enabled," + RuntimeConfigService.isSecretCopyBlockEnabled())
            appendLine("secret_media_export_block_enabled," + RuntimeConfigService.isSecretMediaExportBlockEnabled())
            appendLine("secret_forward_block_enabled," + RuntimeConfigService.isSecretForwardBlockEnabled())
            appendLine("secret_chat_export_block_enabled," + RuntimeConfigService.isSecretChatExportBlockEnabled())
            appendLine("secret_auto_disappear_enabled," + RuntimeConfigService.isSecretAutoDisappearEnabled())
            appendLine("secret_link_preview_block_enabled," + RuntimeConfigService.isSecretLinkPreviewBlockEnabled())
            appendLine("secret_external_link_block_enabled," + RuntimeConfigService.isSecretExternalLinkBlockEnabled())
            appendLine("secret_notif_preview_block_enabled," + RuntimeConfigService.isSecretNotifPreviewBlockEnabled())
            appendLine("secret_list_preview_block_enabled," + RuntimeConfigService.isSecretListPreviewBlockEnabled())
            appendLine("secret_reaction_block_enabled," + RuntimeConfigService.isSecretReactionBlockEnabled())
            appendLine("secret_star_block_enabled," + RuntimeConfigService.isSecretStarBlockEnabled())
            appendLine("secret_typing_block_enabled," + RuntimeConfigService.isSecretTypingBlockEnabled())
            appendLine("secret_read_receipt_block_enabled," + RuntimeConfigService.isSecretReadReceiptBlockEnabled())
            appendLine("secret_presence_block_enabled," + RuntimeConfigService.isSecretPresenceBlockEnabled())
            appendLine("secret_last_seen_block_enabled," + RuntimeConfigService.isSecretLastSeenBlockEnabled())
            appendLine("image_send_enabled," + RuntimeConfigService.isImageSendEnabled())
            appendLine("video_send_enabled," + RuntimeConfigService.isVideoSendEnabled())
        }
        recordAdminAudit(actorId = adminId, action = "ai_feature_flags_export", detail = "runtime flags")
        call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"maodouchat-ai-feature-flags.csv\"")
        call.respondText(csv, io.ktor.http.ContentType.Text.CSV)
    }


    get("/online-presence-export") {
        if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
        val adminId = call.principal<JWTPrincipal>()!!.payload.subject
        val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 5000).coerceIn(1, 20000)
        val rows = transaction {
            Users.selectAll()
                .limit(limit)
                .map { row ->
                    listOf(
                        csvCell(row[Users.id]),
                        csvCell(row[Users.isOnline].toString()),
                        csvCell(row[Users.lastSeen].toString()),
                        csvCell(row[Users.showOnline].toString())
                    ).joinToString(",")
                }
        }
        val csv = buildString {
            appendLine("userId,isOnline,lastSeen,showOnline")
            rows.forEach { appendLine(it) }
        }
        recordAdminAudit(actorId = adminId, action = "online_presence_export", detail = "count=${rows.size}")
        call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"maodouchat-online-presence.csv\"")
        call.respondText(csv, io.ktor.http.ContentType.Text.CSV)
    }


    get("/privacy-flags-export") {
        if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
        val adminId = call.principal<JWTPrincipal>()!!.payload.subject
        val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 5000).coerceIn(1, 20000)
        val rows = transaction {
            Users.selectAll()
                .limit(limit)
                .map { row ->
                    listOf(
                        csvCell(row[Users.id]),
                        csvCell(row[Users.showOnline].toString()),
                        csvCell(row[Users.showStatus].toString()),
                        csvCell(row[Users.searchable].toString())
                    ).joinToString(",")
                }
        }
        val csv = buildString {
            appendLine("userId,showOnline,showStatus,searchable")
            rows.forEach { appendLine(it) }
        }
        recordAdminAudit(actorId = adminId, action = "privacy_flags_export", detail = "count=${rows.size}")
        call.response.header(
            HttpHeaders.ContentDisposition,
            "attachment; filename=\"maodouchat-privacy-flags.csv\""
        )
        call.respondText(csv, io.ktor.http.ContentType.Text.CSV)
    }


    get("/identity-users-export") {
        if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
        val adminId = call.principal<JWTPrincipal>()!!.payload.subject
        val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 5000).coerceIn(1, 20000)
        // Identity discoverability metadata only — no secrets / bodies
        val rows = transaction {
            Users.selectAll()
                .limit(limit)
                .map { row ->
                    listOf(
                        csvCell(row[Users.id]),
                        csvCell(row[Users.searchable].toString()),
                        csvCell(row[Users.showOnline].toString()),
                        csvCell(row[Users.totpEnabled].toString()),
                        csvCell(row[Users.email].take(3) + "***")
                    ).joinToString(",")
                }
        }
        val csv = buildString {
            appendLine("userId,searchable,showOnline,totpEnabled,emailHint")
            rows.forEach { appendLine(it) }
        }
        recordAdminAudit(actorId = adminId, action = "identity_users_export", detail = "count=${rows.size}")
        call.response.header(
            HttpHeaders.ContentDisposition,
            "attachment; filename=\"maodouchat-identity-users.csv\""
        )
        call.respondText(csv, io.ktor.http.ContentType.Text.CSV)
    }


    get("/totp-users-export") {
        if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
        val adminId = call.principal<JWTPrincipal>()!!.payload.subject
        val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 5000).coerceIn(1, 20000)
        // TOTP status only — no secrets / E2EE bodies
        val rows = transaction {
            Users.selectAll()
                .where { Users.totpEnabled eq true }
                .limit(limit)
                .map { row ->
                    listOf(
                        csvCell(row[Users.id]),
                        csvCell(row[Users.totpEnabled].toString()),
                        csvCell(row[Users.email].take(3) + "***")
                    ).joinToString(",")
                }
        }
        val csv = buildString {
            appendLine("userId,totpEnabled,emailHint")
            rows.forEach { appendLine(it) }
        }
        recordAdminAudit(actorId = adminId, action = "totp_users_export", detail = "count=${rows.size}")
        call.response.header(
            HttpHeaders.ContentDisposition,
            "attachment; filename=\"maodouchat-totp-users.csv\""
        )
        call.respondText(csv, io.ktor.http.ContentType.Text.CSV)
    }


    get("/group-invites-export") {
        if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
        val adminId = call.principal<JWTPrincipal>()!!.payload.subject
        val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 5000).coerceIn(1, 20000)
        // Invite metadata only — no message bodies
        val rows = transaction {
            Chats.selectAll()
                .where { Chats.groupInviteToken.isNotNull() }
                .limit(limit)
                .map { row ->
                    listOf(
                        csvCell(row[Chats.id]),
                        csvCell((row[Chats.groupInviteToken] ?: "").take(12)),
                        csvCell(row[Chats.groupInviteExpiresAt].toString()),
                        csvCell(row[Chats.groupInviteMaxUses].toString()),
                        csvCell(row[Chats.groupInviteUseCount].toString())
                    ).joinToString(",")
                }
        }
        val csv = buildString {
            appendLine("chatId,tokenPrefix,expiresAt,maxUses,useCount")
            rows.forEach { appendLine(it) }
        }
        recordAdminAudit(actorId = adminId, action = "group_invites_export", detail = "count=${rows.size}")
        call.response.header(
            HttpHeaders.ContentDisposition,
            "attachment; filename=\"maodouchat-group-invites.csv\""
        )
        call.respondText(csv, io.ktor.http.ContentType.Text.CSV)
    }

get("/restricted-users-export") {
        if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
        val adminId = call.principal<JWTPrincipal>()!!.payload.subject
        val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 5000).coerceIn(1, 20000)
        val now = System.currentTimeMillis()
        val rows = transaction {
            Users.selectAll()
                .where {
                    (Users.messageRestrictedUntil greater now) or
                        (Users.postRestrictedUntil greater now) or
                        (Users.suspendedUntil greater now)
                }
                .limit(limit)
                .map { row ->
                    listOf(
                        csvCell(row[Users.id]),
                        csvCell(row[Users.messageRestrictedUntil].toString()),
                        csvCell(row[Users.postRestrictedUntil].toString()),
                        csvCell(row[Users.suspendedUntil].toString())
                    ).joinToString(",")
                }
        }
        val csv = buildString {
            appendLine("userId,messageRestrictedUntil,postRestrictedUntil,suspendedUntil")
            rows.forEach { appendLine(it) }
        }
        recordAdminAudit(actorId = adminId, action = "restricted_users_export", detail = "count=${rows.size}")
        call.response.header(
            HttpHeaders.ContentDisposition,
            "attachment; filename=\"maodouchat-restricted-users.csv\""
        )
        call.respondText(csv, io.ktor.http.ContentType.Text.CSV)
    }

get("/poll-votes-export") {
        if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
        val adminId = call.principal<JWTPrincipal>()!!.payload.subject
        val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 5000).coerceIn(1, 20000)
        val rows = transaction {
            GroupPollVotes.selectAll()
                .limit(limit)
                .map { row ->
                    listOf(
                        csvCell(row[GroupPollVotes.pollId]),
                        csvCell(row[GroupPollVotes.userId]),
                        csvCell(row[GroupPollVotes.optionIndex].toString()),
                        csvCell(row[GroupPollVotes.votedAt].toString())
                    ).joinToString(",")
                }
        }
        val csv = buildString {
            appendLine("pollId,userId,optionIndex,votedAt")
            rows.forEach { appendLine(it) }
        }
        recordAdminAudit(actorId = adminId, action = "poll_votes_export", detail = "count=${rows.size}")
        call.response.header(
            HttpHeaders.ContentDisposition,
            "attachment; filename=\"maodouchat-poll-votes.csv\""
        )
        call.respondText(csv, io.ktor.http.ContentType.Text.CSV)
    }

get("/pinned-messages-export") {
        if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
        val adminId = call.principal<JWTPrincipal>()!!.payload.subject
        val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 5000).coerceIn(1, 20000)
        // Pinned message metadata only — no message bodies / E2EE plaintext
        val rows = transaction {
            PinnedMessages.selectAll()
                .andWhere {
                    PinnedMessages.chatId notInSubQuery (
                        Chats.select(Chats.id).where { Chats.chatType eq ChatType.SECRET }
                    )
                }
                .orderBy(PinnedMessages.pinnedAt to org.jetbrains.exposed.sql.SortOrder.DESC)
                .limit(limit)
                .map { row ->
                    listOf(
                        csvCell(row[PinnedMessages.chatId]),
                        csvCell(row[PinnedMessages.messageId]),
                        csvCell(row[PinnedMessages.pinnedBy]),
                        csvCell(row[PinnedMessages.pinnedAt].toString())
                    ).joinToString(",")
                }
        }
        val csv = buildString {
            appendLine("chatId,messageId,pinnedBy,pinnedAt")
            rows.forEach { appendLine(it) }
        }
        recordAdminAudit(actorId = adminId, action = "pinned_messages_export", detail = "count=${rows.size}")
        call.response.header(
            HttpHeaders.ContentDisposition,
            "attachment; filename=\"maodouchat-pinned-messages.csv\""
        )
        call.respondText(csv, io.ktor.http.ContentType.Text.CSV)
    }










    get("/chats-export") {
        if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
        val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 2000).coerceIn(1, 10000)
        val rows = transaction {
            val chats = Chats.selectAll()
                .where { Chats.chatType neq ChatType.SECRET }
                .orderBy(Chats.memberRevision to org.jetbrains.exposed.sql.SortOrder.DESC)
                .limit(limit)
                .toList()
            // 8.48 修复 H2：GROUP BY 批量成员计数（此前逐会话 count → 最多 1 万次查询）
            val chatIds = chats.map { it[Chats.id] }
            val membersByChat = if (chatIds.isEmpty()) emptyMap() else
                ChatParticipants
                    .slice(ChatParticipants.chatId, ChatParticipants.userId.count())
                    .selectAll()
                    .where { ChatParticipants.chatId inList chatIds }
                    .groupBy(ChatParticipants.chatId)
                    .associate { it[ChatParticipants.chatId] to it[ChatParticipants.userId.count()].toLong() }
            chats.map { row ->
                    val id = row[Chats.id]
                    val type = row[Chats.chatType].ifBlank { if (row[Chats.isGroup]) "GROUP" else "DIRECT" }
                    val name = (row[Chats.groupName] ?: "").take(80)
                    val members = membersByChat[id] ?: 0L
                    listOf(
                        csvCell(id),
                        csvCell(type.lowercase()),
                        csvCell(name),
                        csvCell(members.toString()),
                        csvCell(row[Chats.memberRevision].toString()),
                        csvCell(row[Chats.disappearingMessageSeconds].toString())
                    ).joinToString(",")
                }
        }
        val csv = buildString {
            appendLine("id,type,title,memberCount,memberRevision,disappearingSeconds")
            rows.forEach { appendLine(it) }
        }
        call.response.header(
            HttpHeaders.ContentDisposition,
            "attachment; filename=\"maodouchat-chats-${System.currentTimeMillis()}.csv\""
        )
        call.respondText(csv, io.ktor.http.ContentType.Text.CSV)
    }


    get("/runtime-export") {
        if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
        val actorId = call.principal<JWTPrincipal>()!!.payload.subject
        recordAdminAudit(actorId, "ADMIN_RUNTIME_EXPORT", "settings snapshot")
        call.respond(
        buildJsonObject {
put("generatedAt", System.currentTimeMillis())
put("settings", Json.parseToJsonElement(Json.encodeToString(RuntimeConfigService.all())))
put("defaults", Json.parseToJsonElement(Json.encodeToString(RuntimeConfigService.defaults())))
put("security", buildJsonObject {
put("sealedSenderEnabled", RuntimeConfigService.isSealedSenderEnabled())
put("aiEnabled", RuntimeConfigService.isAiEnabled())
put("botsAllowed", RuntimeConfigService.isBotsAllowed())
put("captureAlertEnabled", RuntimeConfigService.isCaptureAlertEnabled())
put("pqxdhPreview", RuntimeConfigService.isPqxdhPreviewEnabled())
put("minAppVersion", RuntimeConfigService.minAppVersion())
put("maxBotsPerUser", RuntimeConfigService.maxBotsPerUser())
put("ipBlocklistCount", RuntimeConfigService.ipBlocklist().size)
put("maxMessagePerMin", RuntimeConfigService.maxMessagePerMinute())
})
        }
    )
    }

post("/watermark/extract") {
        if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
        val adminId = call.principal<JWTPrincipal>()!!.payload.subject
        val bodyText = runCatching { call.receiveBoundedText(MAX_ADMIN_WATERMARK_BODY_CHARS) }.getOrNull().orEmpty()
        if (bodyText.length > MAX_ADMIN_WATERMARK_BODY_CHARS) {
            return@post call.respond(HttpStatusCode.PayloadTooLarge, ErrorResponse("请求体过大"))
        }
        val imageB64 = runCatching {
            val el = adminJson.parseToJsonElement(bodyText)
            el.jsonObject["imageBase64"]?.jsonPrimitive?.content.orEmpty()
        }.getOrDefault("")
        if (imageB64.isBlank()) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("imageBase64_required"))
        }
        val result = com.maodouchat.server.watermark.AdminWatermarkExtractor.extractFromBase64(imageB64)
        recordAdminAudit(
            actorId = adminId,
            action = "watermark_extract",
            detail = "found=${result.found};msg=${result.message};hex=${result.payloadHex.orEmpty().take(24)}"
        )
        call.respond(
            WatermarkExtractResponse(
                found = result.found,
                payloadHex = result.payloadHex.orEmpty(),
                width = result.width,
                height = result.height,
                message = result.message,
                notes = "payload is FNV-1a48 of userId|chatId|deviceHint; visible tiles also embed wall-clock time"
            )
        )
    }


}