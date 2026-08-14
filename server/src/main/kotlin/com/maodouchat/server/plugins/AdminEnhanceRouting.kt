package com.maodouchat.server.plugins

import com.maodouchat.server.config.AdminAccess
import com.maodouchat.server.config.ServerConfig
import com.maodouchat.server.db.AnnouncementAcks
import com.maodouchat.server.db.AuditExportRecords
import com.maodouchat.server.db.DeviceEventConsistencyLog
import com.maodouchat.server.db.DeviceEventSequences
import com.maodouchat.server.db.ModerationAuditLog
import com.maodouchat.server.db.RateLimitStatsSnapshots
import com.maodouchat.server.db.RiskEvents
import com.maodouchat.server.db.SystemAnnouncements
import com.maodouchat.server.db.UserTagAssignments
import com.maodouchat.server.db.UserTags
import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.repository.AnnouncementRepository
import com.maodouchat.server.repository.RateLimitStatsRepository
import com.maodouchat.server.repository.UserTagRepository
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.upsert
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit

/**
 * B6 服务端运维增强路由。
 *
 * 安全约束（红线）：
 * - 所有 `/api/admin/` 端点双重门控：`authenticate("admin-jwt")` + `isAdminUser()`（MASTER_ADMINS）。
 * - 不导出 E2EE 明文：本模块只读写公告（平台明文广播）、用户标签、审计元数据、限流统计、设备一致性序列，
 *   绝不触碰 Messages / EncryptedAttachments 的密文列。
 * - 所有变更操作写 ModerationAuditLog 审计。
 *
 * 本文件只注册 AdminRouting.kt 中不存在的全新路径，不修改其已有路由。
 */
fun Application.configureAdminEnhanceRouting(
    announcementRepo: AnnouncementRepository,
    userTagRepo: UserTagRepository,
    rateLimitStatsRepo: RateLimitStatsRepository,
    fcmPushService: com.maodouchat.server.service.FcmPushService? = null,
    pushTokenRepo: com.maodouchat.server.repository.PushTokenRepository? = null
) {
    routing {

        // ─── 用户端公告（普通登录态，非管理端点）────────────────
        authenticate("auth-jwt") {
            get("/api/announcements/active") {
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                val now = System.currentTimeMillis()
                val userTagIds = userTagRepo.userTagIds(userId)
                val active = announcementRepo.activeForUser(userId, now, userTagIds)
                val ackedIds = transaction {
                    AnnouncementAcks.selectAll().where { AnnouncementAcks.userId eq userId }
                        .map { it[AnnouncementAcks.announcementId] }.toSet()
                }
                call.respond(
                    ActiveAnnouncementsResponse(
                        announcements = active.map { it.toDto(acked = it.id in ackedIds) },
                        serverTime = now
                    )
                )
            }

            post("/api/announcements/{id}/ack") {
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                val id = call.parameters["id"] ?: return@post call.respond(
                    HttpStatusCode.BadRequest, ErrorResponse("缺少公告 ID")
                )
                // 8.46 修复：ack 必须与 activeForUser 同一可见性判定（status=ACTIVE + 生效窗口
                // [startsAt,expiresAt] + 受众命中）——否则任意用户可对不可见的 TAGGED/过期公告打已读，
                // 污染 stats 的 acked 统计。
                val now = System.currentTimeMillis()
                val tagIds = userTagRepo.userTagIds(userId).toSet()
                val visible = transaction {
                    SystemAnnouncements.selectAll().where {
                        (SystemAnnouncements.id eq id) and
                            (SystemAnnouncements.status eq "ACTIVE") and
                            (SystemAnnouncements.startsAt lessEq now) and
                            (SystemAnnouncements.expiresAt greaterEq now)
                    }.firstOrNull()?.let { row ->
                        val audience = row[SystemAnnouncements.targetAudience]
                        if (audience == "ALL") true else {
                            val tagId = row[SystemAnnouncements.targetTagId]
                            tagId != null && tagId in tagIds
                        }
                    } ?: false
                }
                if (!visible) return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("公告不存在或未发布"))
                transaction {
                    AnnouncementAcks.upsert(AnnouncementAcks.announcementId, AnnouncementAcks.userId) {
                        it[AnnouncementAcks.announcementId] = id
                        it[AnnouncementAcks.userId] = userId
                        it[AnnouncementAcks.ackedAt] = System.currentTimeMillis()
                    }
                }
                call.respond(AnnouncementAckResponse(ok = true, announcementId = id))
            }
        }

        // ─── 管理端增强（双重门控）────────────────
        authenticate("admin-jwt") {
            route("/api/admin") {

                // ═══ 系统公告广播 ═══
                get("/announcements") {
                    if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                    val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 200)
                    val offset = (call.request.queryParameters["offset"]?.toLongOrNull() ?: 0L).coerceAtLeast(0L)
                    val status = call.request.queryParameters["status"]?.trim()?.takeIf { it.isNotBlank() }
                    val q = call.request.queryParameters["q"]?.trim()?.takeIf { it.isNotBlank() }
                    val list = announcementRepo.list(status, q, limit, offset)
                    call.respond(list.map { it.toDto(acked = false) })
                }

                post("/announcements") {
                    if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                    val actorId = call.principal<JWTPrincipal>()!!.payload.subject
                    val req = call.receiveEnhanceJson<CreateAnnouncementRequest>()
                        ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("请求无效"))
                    if (req.title.isBlank()) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("公告标题不能为空"))
                    if (req.content.isBlank()) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("公告内容不能为空"))
                    if (req.content.length > MAX_ANNOUNCEMENT_CONTENT_CHARS) {
                        return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("公告内容过长"))
                    }
                    val audience = req.audience.uppercase().take(20)
                    if (audience !in setOf("ALL", "TAGGED")) {
                        return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("公告受众非法"))
                    }
                    val level = req.level.uppercase().take(20)
                    if (level !in setOf("INFO", "WARNING", "MAINTENANCE", "EMERGENCY")) {
                        return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("公告级别非法"))
                    }
                    if (audience == "TAGGED" && req.tagId.isNullOrBlank()) {
                        return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("按标签定向公告必须指定 tagId"))
                    }
                    val now = System.currentTimeMillis()
                    val startsAt = req.startsAt ?: now
                    val expiresAt = req.expiresAt ?: (now + DEFAULT_ANNOUNCEMENT_WINDOW_MS)
                    if (startsAt > expiresAt) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("生效时间不能晚于失效时间"))
                    if (expiresAt < now) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("失效时间不能早于当前时间"))
                    val created = announcementRepo.create(
                        title = req.title, content = req.content, level = level,
                        targetAudience = audience, targetTagId = req.tagId?.takeIf { audience == "TAGGED" },
                        startsAt = startsAt, expiresAt = expiresAt, createdBy = actorId
                    )
                    recordAdminAudit(
                        actorId, "ANNOUNCEMENT_CREATED",
                        "id=${created.id};audience=$audience;tag=${req.tagId ?: "-"};status=${created.status}"
                    )
                    call.respond(HttpStatusCode.Created, created.toDto(acked = false))
                }

                get("/announcements/{id}") {
                    if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                    val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("缺少公告 ID"))
                    val row = announcementRepo.get(id)
                        ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("公告不存在"))
                    call.respond(row.toDto(acked = false))
                }

                put("/announcements/{id}") {
                    if (!call.isAdminUser()) return@put call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                    val actorId = call.principal<JWTPrincipal>()!!.payload.subject
                    val id = call.parameters["id"] ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("缺少公告 ID"))
                    val req = call.receiveEnhanceJson<UpdateAnnouncementRequest>()
                        ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("请求无效"))
                    val current = announcementRepo.get(id)
                        ?: return@put call.respond(HttpStatusCode.NotFound, ErrorResponse("公告不存在"))
                    if (current.status == "CANCELLED") {
                        return@put call.respond(HttpStatusCode.Conflict, ErrorResponse("已取消的公告不可修改"))
                    }
                    val startsAt = req.startsAt ?: current.startsAt
                    val expiresAt = req.expiresAt ?: current.expiresAt
                    if (startsAt > expiresAt) return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("生效时间不能晚于失效时间"))
                    // 9.136：显式传入的失效时间不得早于当前（与创建口径一致）——
                    // 此前可把 expiresAt 改成过去值，公告状态仍为 ACTIVE 但对用户立即隐形（幽灵公告）。
                    // 仅校验显式传入值：未传时沿用旧窗口，允许对已过期公告仅改标题/内容。
                    if (req.expiresAt != null && req.expiresAt < System.currentTimeMillis()) {
                        return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("失效时间不能早于当前时间"))
                    }
                    val audience = req.audience?.uppercase()?.take(20) ?: current.targetAudience
                    if (audience !in setOf("ALL", "TAGGED")) {
                        return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("公告受众非法"))
                    }
                    // 8.34 修复：TAGGED 受众必须携带 tagId，否则公告对所有人不可见（隐形公告）；
                    // 请求未传时沿用当前值（编辑标题等场景）
                    val effectiveTagId = req.tagId ?: current.targetTagId
                    if (audience == "TAGGED" && effectiveTagId.isNullOrBlank()) {
                        return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("TAGGED 受众必须指定标签"))
                    }
                    // 8.46 修复：只改标题/内容（不传 tagId）时仍沿用当前 targetTagId——
                    // 此前直接把 req.tagId（可空）写入仓库，TAGGED 公告被静默清空标签变隐形
                    val resolvedTagId = if (audience == "TAGGED") effectiveTagId else null
                    val updated = announcementRepo.update(
                        id = id,
                        title = req.title, content = req.content, level = req.level,
                        targetAudience = req.audience, targetTagId = resolvedTagId,
                        startsAt = req.startsAt, expiresAt = req.expiresAt
                    ) ?: return@put call.respond(HttpStatusCode.NotFound, ErrorResponse("公告不存在"))
                    recordAdminAudit(actorId, "ANNOUNCEMENT_UPDATED", "id=$id;status=${updated.status}")
                    call.respond(updated.toDto(acked = false))
                }

                post("/announcements/{id}/publish") {
                    if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                    val actorId = call.principal<JWTPrincipal>()!!.payload.subject
                    val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("缺少公告 ID"))
                    // 发布前快照：仅首次发布（此前非 ACTIVE）推送 FCM，重复 publish 不重复广播
                    val before = announcementRepo.get(id)
                    val wasActive = before?.status == "ACTIVE" && before.publishedAt != null
                    val published = announcementRepo.publish(id, actorId)
                        ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("公告不存在"))
                    recordAdminAudit(actorId, "ANNOUNCEMENT_PUBLISHED", "id=$id")
                    // 高优先级公告（EMERGENCY/MAINTENANCE）向目标受众推送 FCM 通知
                    if (!wasActive && published.level in setOf("EMERGENCY", "MAINTENANCE")) {
                        val service = fcmPushService
                        val pushRepo = pushTokenRepo
                        if (service != null && pushRepo != null) {
                            val recipients = when (published.targetAudience) {
                                "TAGGED" -> {
                                    val tagId = published.targetTagId
                                    if (tagId.isNullOrBlank()) emptyList()
                                    else {
                                        // 8.48 修复 M10：每页一次查询（此前 generateSequence 的
                                        // next/flatMap 对同一 offset 各查一次 → 每页 2 次）
                                        val recipients = mutableListOf<String>()
                                        var offset = 0L
                                        while (true) {
                                            val page = userTagRepo.listUsersByTag(tagId, null, 500, offset)
                                            if (page.isEmpty()) break
                                            recipients.addAll(page.map { it.userId })
                                            if (page.size < 500) break
                                            offset += page.size
                                        }
                                        recipients.distinct()
                                    }
                                }
                                else -> pushRepo.listUserIds()
                            }
                            recipients.forEach { uid ->
                                service.enqueueAnnouncement(uid, published.id, published.title, published.level)
                            }
                        }
                    }
                    call.respond(published.toDto(acked = false))
                }

                post("/announcements/{id}/cancel") {
                    if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                    val actorId = call.principal<JWTPrincipal>()!!.payload.subject
                    val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("缺少公告 ID"))
                    val cancelled = announcementRepo.cancel(id, actorId)
                        ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("公告不存在"))
                    recordAdminAudit(actorId, "ANNOUNCEMENT_CANCELLED", "id=$id")
                    call.respond(cancelled.toDto(acked = false))
                }

                delete("/announcements/{id}") {
                    if (!call.isAdminUser()) return@delete call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                    val actorId = call.principal<JWTPrincipal>()!!.payload.subject
                    val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("缺少公告 ID"))
                    if (!announcementRepo.delete(id)) {
                        return@delete call.respond(HttpStatusCode.Conflict, ErrorResponse("仅未发布的草稿可删除；已发布公告请使用取消"))
                    }
                    recordAdminAudit(actorId, "ANNOUNCEMENT_DELETED", "id=$id")
                    call.respond(buildJsonObject { put("ok", true) })
                }

                get("/announcements/{id}/stats") {
                    if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                    val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("缺少公告 ID"))
                    val stats = announcementRepo.stats(id)
                        ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("公告不存在"))
                    val ackedCount = transaction {
                        AnnouncementAcks.selectAll().where { AnnouncementAcks.announcementId eq id }.count()
                    }
                    call.respond(
                        AnnouncementStatsResponse(
                            id = id,
                            recipientCount = stats.recipientCount,
                            audience = stats.audience,
                            targetTagId = stats.targetTagId,
                            ackedCount = ackedCount,
                            createdAt = stats.createdAt,
                            publishedAt = stats.publishedAt,
                            cancelledAt = stats.cancelledAt
                        )
                    )
                }

                // ═══ 用户标签 + 风控联动 ═══
                get("/user-tags") {
                    if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                    val q = call.request.queryParameters["q"]?.trim()?.takeIf { it.isNotBlank() }
                    val tags = userTagRepo.listTags(q)
                    call.respond(tags.map { it.toDto() })
                }

                post("/user-tags") {
                    if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                    val actorId = call.principal<JWTPrincipal>()!!.payload.subject
                    val req = call.receiveEnhanceJson<CreateUserTagRequest>()
                        ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("请求无效"))
                    if (req.name.isBlank()) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("标签名称不能为空"))
                    val riskLevel = req.riskLevel.uppercase().take(20)
                    if (riskLevel !in setOf("NONE", "LOW", "MEDIUM", "HIGH", "CRITICAL")) {
                        return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("风控级别非法"))
                    }
                    val duplicate = userTagRepo.listTags().any { it.name.equals(req.name.trim(), ignoreCase = true) }
                    if (duplicate) return@post call.respond(HttpStatusCode.Conflict, ErrorResponse("同名标签已存在"))
                    val tag = userTagRepo.createTag(
                        name = req.name, color = req.color, description = req.description,
                        riskLevel = riskLevel, isSystem = false, createdBy = actorId
                    )
                    recordAdminAudit(actorId, "USER_TAG_CREATED", "tagId=${tag.id};name=${tag.name};risk=$riskLevel")
                    call.respond(HttpStatusCode.Created, tag.toDto())
                }

                put("/user-tags/{id}") {
                    if (!call.isAdminUser()) return@put call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                    val actorId = call.principal<JWTPrincipal>()!!.payload.subject
                    val id = call.parameters["id"] ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("缺少标签 ID"))
                    val req = call.receiveEnhanceJson<UpdateUserTagRequest>()
                        ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("请求无效"))
                    val riskLevel = req.riskLevel?.uppercase()?.take(20)
                    if (riskLevel != null && riskLevel !in setOf("NONE", "LOW", "MEDIUM", "HIGH", "CRITICAL")) {
                        return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("风控级别非法"))
                    }
                    val updated = userTagRepo.updateTag(id, req.name, req.color, req.description, riskLevel)
                        ?: return@put call.respond(HttpStatusCode.NotFound, ErrorResponse("标签不存在"))
                    recordAdminAudit(actorId, "USER_TAG_UPDATED", "tagId=$id;name=${updated.name};risk=${updated.riskLevel}")
                    call.respond(updated.toDto())
                }

                delete("/user-tags/{id}") {
                    if (!call.isAdminUser()) return@delete call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                    val actorId = call.principal<JWTPrincipal>()!!.payload.subject
                    val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("缺少标签 ID"))
                    if (!userTagRepo.deleteTag(id)) {
                        return@delete call.respond(HttpStatusCode.Conflict, ErrorResponse("系统内置标签不可删除或标签不存在"))
                    }
                    recordAdminAudit(actorId, "USER_TAG_DELETED", "tagId=$id")
                    call.respond(buildJsonObject { put("ok", true) })
                }

                get("/user-tags/{id}/users") {
                    if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                    val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("缺少标签 ID"))
                    val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 200)
                    val offset = (call.request.queryParameters["offset"]?.toLongOrNull() ?: 0L).coerceAtLeast(0L)
                    val q = call.request.queryParameters["q"]?.trim()?.takeIf { it.isNotBlank() }
                    val users = userTagRepo.listUsersByTag(id, q, limit, offset)
                    call.respond(users.map { it.toDto() })
                }

                get("/tags/risk-summary") {
                    if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                    val tags = userTagRepo.listTags()
                    val riskUsers = tags.filter { it.riskLevel in setOf("HIGH", "CRITICAL") }
                        .map { t ->
                            RiskTagSummary(tagId = t.id, name = t.name, riskLevel = t.riskLevel, userCount = t.userCount)
                        }
                    call.respond(RiskTagSummaryResponse(tags = riskUsers))
                }

                get("/users/{userId}/tags") {
                    if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                    val userId = call.parameters["userId"] ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("缺少用户 ID"))
                    val assignments = userTagRepo.userAssignments(userId)
                    call.respond(assignments.map { it.toDto() })
                }

                post("/users/{userId}/tags") {
                    if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                    val actorId = call.principal<JWTPrincipal>()!!.payload.subject
                    val userId = call.parameters["userId"] ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("缺少用户 ID"))
                    val req = call.receiveEnhanceJson<AssignUserTagsRequest>()
                        ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("请求无效"))
                    if (req.tagIds.isEmpty()) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("标签列表不能为空"))
                    if (req.tagIds.size > MAX_TAGS_PER_ASSIGNMENT) {
                        return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("单次打标数量超限"))
                    }
                    val tags = userTagRepo.listTags()
                    // 9.140：目标用户不存在时 404（此前 assignTags 撞悬空 FK 抛约束异常 → 500）
                    val assigned = userTagRepo.assignTags(userId, req.tagIds, "MANUAL", actorId)
                        ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("用户不存在"))
                    // 风控联动：打上 HIGH/CRITICAL 风险标签时写入风险事件队列，进入人工复核
                    val risky = tags.filter { it.id in req.tagIds && it.riskLevel in setOf("HIGH", "CRITICAL") }
                    if (risky.isNotEmpty()) {
                        val now = System.currentTimeMillis()
                        transaction {
                            risky.forEach { tag ->
                                RiskEvents.insert {
                                    it[RiskEvents.id] = UUID.randomUUID().toString()
                                    it[RiskEvents.userId] = userId
                                    it[RiskEvents.sourceValue] = "USER_TAG"
                                    it[RiskEvents.ruleId] = tag.id
                                    it[RiskEvents.action] = "TAG_RISK"
                                    it[RiskEvents.matched] = "tag=${tag.name};risk=${tag.riskLevel}"
                                    it[RiskEvents.referenceId] = tag.id
                                    it[RiskEvents.needsReview] = true
                                    it[RiskEvents.createdAt] = now
                                }
                            }
                        }
                    }
                    recordAdminAudit(
                        actorId, "USER_TAGS_ASSIGNED",
                        "userId=$userId;tags=${req.tagIds.joinToString(",")};risk=${risky.map { it.id }}"
                    )
                    call.respond(assigned.map { it.toDto() })
                }

                delete("/users/{userId}/tags/{tagId}") {
                    if (!call.isAdminUser()) return@delete call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                    val actorId = call.principal<JWTPrincipal>()!!.payload.subject
                    val userId = call.parameters["userId"] ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("缺少用户 ID"))
                    val tagId = call.parameters["tagId"] ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("缺少标签 ID"))
                    if (!userTagRepo.detachTag(userId, tagId)) {
                        return@delete call.respond(HttpStatusCode.NotFound, ErrorResponse("该用户未打此标签"))
                    }
                    recordAdminAudit(actorId, "USER_TAG_DETACHED", "userId=$userId;tagId=$tagId")
                    call.respond(buildJsonObject { put("ok", true) })
                }

                // ═══ 审计时间范围导出 ═══
                get("/audit/time-range-export") {
                    if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                    val actorId = call.principal<JWTPrincipal>()!!.payload.subject
                    val scope = call.request.queryParameters["scope"]?.trim()?.uppercase()?.take(30)
                        ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("缺少导出范围 scope"))
                    if (scope !in setOf("ADMIN_AUDIT", "RISK_EVENTS", "ANNOUNCEMENTS", "USER_TAGS", "RATE_LIMIT")) {
                        return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("导出范围非法"))
                    }
                    val fromMs = call.request.queryParameters["fromMs"]?.toLongOrNull()
                        ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("缺少 fromMs"))
                    val toMs = call.request.queryParameters["toMs"]?.toLongOrNull()
                        ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("缺少 toMs"))
                    if (fromMs >= toMs) return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("时间范围非法"))
                    if (toMs - fromMs > MAX_EXPORT_RANGE_MS) {
                        return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("导出时间范围不得超过 90 天"))
                    }
                    val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 5_000).coerceIn(1, 10_000)
                    val (csv, exportedRows) = buildAuditExportCsv(scope, fromMs, toMs, limit)
                    val fileName = "maodouchat-${scope.lowercase()}-${fromMs}-${toMs}.csv"
                    transaction {
                        AuditExportRecords.insert {
                            it[AuditExportRecords.id] = UUID.randomUUID().toString()
                            it[AuditExportRecords.actorId] = actorId
                            it[AuditExportRecords.scope] = scope
                            it[AuditExportRecords.fromMs] = fromMs
                            it[AuditExportRecords.toMs] = toMs
                            // 9.140：此前恒记 0——审计追溯记录行数与实际导出内容不符
                            it[AuditExportRecords.rowCount] = exportedRows.toLong()
                            // fileRef 记下载文件名（CSV 流式返回不落盘，保留作为导出标识）
                            it[AuditExportRecords.fileRef] = fileName
                            it[AuditExportRecords.requestedAt] = System.currentTimeMillis()
                        }
                    }
                    recordAdminAudit(actorId, "ADMIN_AUDIT_TIME_EXPORT", "scope=$scope;from=$fromMs;to=$toMs;limit=$limit")
                    call.response.headers.append(HttpHeaders.ContentDisposition, "attachment; filename=\"$fileName\"")
                    call.respondText(csv, contentType = io.ktor.http.ContentType.parse("text/csv; charset=utf-8"))
                }

                // ═══ 限流仪表盘 ═══
                get("/rate-limit/dashboard") {
                    if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                    val range = call.request.queryParameters["range"]?.trim()?.lowercase() ?: "24h"
                    val hours = when (range) {
                        "1h" -> 1
                        "24h" -> 24
                        "7d" -> 24 * 7
                        else -> return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("range 非法：1h / 24h / 7d"))
                    }
                    val now = System.currentTimeMillis()
                    val fromMs = now - hours * 3_600_000L
                    val summary = rateLimitStatsRepo.summarize(fromMs, now)
                    call.respond(
                        RateLimitDashboardResponse(
                            range = range,
                            points = summary.points.map { p ->
                                RateLimitPoint(
                                    bucketStartMs = p.bucketStartMs,
                                    allowed = p.allowedDelta,
                                    rejected = p.rejectedDelta,
                                    totalBuckets = p.avgTotalBuckets,
                                    maxBuckets = p.maxTotalBuckets,
                                    maxPerMinute = p.maxPerMinute
                                )
                            },
                            totalAllowed = summary.totalAllowed,
                            totalRejected = summary.totalRejected,
                            peakRejectionsPerMinute = summary.peakRejectionsPerMinute,
                            live = summary.live.let {
                                RateLimitLiveStats(
                                    allowed = it.allowed, rejected = it.rejected,
                                    totalBuckets = it.totalBuckets, maxBuckets = it.maxBuckets,
                                    maxPerMinute = it.maxPerMinute
                                )
                            },
                            lastSnapshotAt = rateLimitStatsRepo.lastSnapshotAt() ?: 0L,
                            retentionDays = rateLimitStatsRepo.retentionDays
                        )
                    )
                }

                post("/rate-limit/sample") {
                    if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                    val actorId = call.principal<JWTPrincipal>()!!.payload.subject
                    rateLimitStatsRepo.recordMinute()
                    recordAdminAudit(actorId, "RATE_LIMIT_MANUAL_SAMPLE", "")
                    call.respond(buildJsonObject { put("ok", true) })
                }

                // ═══ 设备事件一致性 ═══
                get("/device-consistency/summary") {
                    if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                    val userId = call.request.queryParameters["userId"]?.trim()?.takeIf { it.isNotBlank() }
                    val sequences = transaction {
                        val q = DeviceEventSequences.selectAll()
                        (if (userId != null) q.andWhere { DeviceEventSequences.userId eq userId } else q)
                            .orderBy(DeviceEventSequences.userId to SortOrder.ASC)
                            .map { row ->
                                DeviceSeqResponse(
                                    userId = row[DeviceEventSequences.userId],
                                    deviceId = row[DeviceEventSequences.deviceId],
                                    eventType = row[DeviceEventSequences.eventType],
                                    lastAppliedSeq = row[DeviceEventSequences.lastAppliedSeq],
                                    lastEventAt = row[DeviceEventSequences.lastEventAt]
                                )
                            }
                    }
                    val anomalyCount = transaction {
                        val q = DeviceEventConsistencyLog.selectAll()
                        (if (userId != null) q.andWhere { DeviceEventConsistencyLog.userId eq userId } else q).count()
                    }
                    call.respond(DeviceConsistencySummaryResponse(sequences = sequences, anomalyCount = anomalyCount))
                }

                get("/device-consistency/events") {
                    if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                    val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 200)
                    val offset = (call.request.queryParameters["offset"]?.toLongOrNull() ?: 0L).coerceAtLeast(0L)
                    val status = call.request.queryParameters["status"]?.trim()?.uppercase()?.take(20)
                    val userId = call.request.queryParameters["userId"]?.trim()?.takeIf { it.isNotBlank() }
                    val events = transaction {
                        val q = DeviceEventConsistencyLog.selectAll()
                        val filtered = when {
                            userId != null && status != null -> q.andWhere {
                                (DeviceEventConsistencyLog.userId eq userId) and (DeviceEventConsistencyLog.status eq status)
                            }
                            userId != null -> q.andWhere { DeviceEventConsistencyLog.userId eq userId }
                            status != null -> q.andWhere { DeviceEventConsistencyLog.status eq status }
                            else -> q
                        }
                        filtered.orderBy(
                            DeviceEventConsistencyLog.lastSeenAt to SortOrder.DESC,
                            DeviceEventConsistencyLog.id to SortOrder.DESC
                        )
                            .limit(limit, offset)
                            .map { row ->
                                DeviceAnomalyResponse(
                                    id = row[DeviceEventConsistencyLog.id],
                                    userId = row[DeviceEventConsistencyLog.userId],
                                    deviceId = row[DeviceEventConsistencyLog.deviceId],
                                    eventType = row[DeviceEventConsistencyLog.eventType],
                                    seq = row[DeviceEventConsistencyLog.seq],
                                    status = row[DeviceEventConsistencyLog.status],
                                    referenceId = row[DeviceEventConsistencyLog.referenceId],
                                    firstSeenAt = row[DeviceEventConsistencyLog.firstSeenAt],
                                    lastSeenAt = row[DeviceEventConsistencyLog.lastSeenAt],
                                    detail = row[DeviceEventConsistencyLog.detail]
                                )
                            }
                    }
                    call.respond(events)
                }
            }
        }
    }
}

/**
 * 限流统计采样器：每 60s 把 GlobalRateLimiter 的累计计数器写入分钟桶，
 * 同时清理超过保留期的旧桶。守护线程，不阻塞 JVM 退出。
 * 单实例部署语义与 GlobalRateLimiter 一致。
 * 返回执行器供 ApplicationStopped 关闭（8.31 运维修复：退出瞬间不再执行 DB 写）。
 */
fun startRateLimitStatsSampler(rateLimitStatsRepo: RateLimitStatsRepository): ScheduledThreadPoolExecutor {
    val exec = ScheduledThreadPoolExecutor(1, RateLimitSamplerThreadFactory)
    exec.scheduleWithFixedDelay(
        {
            runCatching {
                rateLimitStatsRepo.recordMinute()
                val cutoff = System.currentTimeMillis() - rateLimitStatsRepo.retentionDays * 86_400_000L
                rateLimitStatsRepo.prune(cutoff)
            }.onFailure { e -> samplerLogger.warn("Rate-limit stats sampling failed", e) }
        },
        SAMPLE_DELAY_MS, SAMPLE_DELAY_MS, TimeUnit.MILLISECONDS
    )
    return exec
}

// ─────────────────────────────────────────────
// DTO
// ─────────────────────────────────────────────

@Serializable
data class CreateAnnouncementRequest(
    val title: String,
    val content: String,
    val level: String = "INFO",
    val audience: String = "ALL",
    val tagId: String? = null,
    val startsAt: Long? = null,
    val expiresAt: Long? = null
)

@Serializable
data class UpdateAnnouncementRequest(
    val title: String? = null,
    val content: String? = null,
    val level: String? = null,
    val audience: String? = null,
    val tagId: String? = null,
    val startsAt: Long? = null,
    val expiresAt: Long? = null
)

@Serializable
data class AnnouncementDto(
    val id: String,
    val title: String,
    val content: String,
    val level: String,
    val audience: String,
    val tagId: String?,
    val startsAt: Long,
    val expiresAt: Long,
    val status: String,
    val createdBy: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val publishedAt: Long?,
    val cancelledAt: Long?,
    val acked: Boolean = false
)

@Serializable
data class ActiveAnnouncementsResponse(
    val announcements: List<AnnouncementDto>,
    val serverTime: Long
)

@Serializable
data class AnnouncementAckResponse(val ok: Boolean, val announcementId: String)

@Serializable
data class AnnouncementStatsResponse(
    val id: String,
    val recipientCount: Long,
    val audience: String,
    val targetTagId: String?,
    val ackedCount: Long,
    val createdAt: Long,
    val publishedAt: Long?,
    val cancelledAt: Long?
)

@Serializable
data class CreateUserTagRequest(
    val name: String,
    val color: String = "#64748b",
    val description: String? = null,
    val riskLevel: String = "LOW"
)

@Serializable
data class UpdateUserTagRequest(
    val name: String? = null,
    val color: String? = null,
    val description: String? = null,
    val riskLevel: String? = null
)

@Serializable
data class AssignUserTagsRequest(val tagIds: List<String> = emptyList())

@Serializable
data class UserTagDto(
    val id: String,
    val name: String,
    val color: String,
    val description: String?,
    val isSystem: Boolean,
    val riskLevel: String,
    val createdAt: Long,
    val updatedAt: Long,
    val userCount: Long = 0
)

@Serializable
data class UserTagAssignmentDto(
    val userId: String,
    val tagId: String,
    val source: String,
    val assignedBy: String?,
    val createdAt: Long
)

@Serializable
data class RiskTagSummary(val tagId: String, val name: String, val riskLevel: String, val userCount: Long)

@Serializable
data class RiskTagSummaryResponse(val tags: List<RiskTagSummary>)

@Serializable
data class RateLimitPoint(
    val bucketStartMs: Long,
    val allowed: Long,
    val rejected: Long,
    val totalBuckets: Long,
    val maxBuckets: Long,
    val maxPerMinute: Int
)

@Serializable
data class RateLimitLiveStats(
    val allowed: Long,
    val rejected: Long,
    val totalBuckets: Int,
    val maxBuckets: Int,
    val maxPerMinute: Int
)

@Serializable
data class RateLimitDashboardResponse(
    val range: String,
    val points: List<RateLimitPoint>,
    val totalAllowed: Long,
    val totalRejected: Long,
    val peakRejectionsPerMinute: Long,
    val live: RateLimitLiveStats,
    val lastSnapshotAt: Long,
    val retentionDays: Int
)

@Serializable
data class DeviceSeqResponse(
    val userId: String,
    val deviceId: Int,
    val eventType: String,
    val lastAppliedSeq: Long,
    val lastEventAt: Long
)

@Serializable
data class DeviceConsistencySummaryResponse(
    val sequences: List<DeviceSeqResponse>,
    val anomalyCount: Long
)

@Serializable
data class DeviceAnomalyResponse(
    val id: String,
    val userId: String,
    val deviceId: Int,
    val eventType: String,
    val seq: Long,
    val status: String,
    val referenceId: String?,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    val detail: String?
)

// ─────────────────────────────────────────────
// 设备事件一致性加固（幂等应用 + 异常记录）
// ─────────────────────────────────────────────

/**
 * 设备事件序列守卫：按 (userId, deviceId, eventType) 维护 lastAppliedSeq，
 * 拒绝 STALE（seq 落后）/ DUPLICATE（重复投递）事件；seq 跳号记为 OUT_OF_ORDER。
 * 单进程内按 key 加条纹锁串行化读-改-写；多实例部署需换 DB 行级锁（与 GlobalRateLimiter 同约束）。
 */
object DeviceEventConsistencyGuard {
    private val stripes = Array(256) { Any() }

    enum class Status { APPLIED, STALE, DUPLICATE, OUT_OF_ORDER }

    data class ApplyOutcome(val status: Status, val lastAppliedSeq: Long)

    private fun stripe(userId: String, deviceId: Int, eventType: String): Any {
        val hash = (userId.hashCode() * 31 + deviceId) * 31 + eventType.hashCode()
        return stripes[Math.floorMod(hash, stripes.size)]
    }

    fun applyEvent(
        userId: String,
        deviceId: Int,
        eventType: String,
        seq: Long,
        referenceId: String? = null,
        now: Long = System.currentTimeMillis()
    ): ApplyOutcome {
        val lock = stripe(userId, deviceId, eventType)
        synchronized(lock) {
            return transaction {
                val existing = DeviceEventSequences.selectAll().where {
                    (DeviceEventSequences.userId eq userId) and
                        (DeviceEventSequences.deviceId eq deviceId) and
                        (DeviceEventSequences.eventType eq eventType)
                }.firstOrNull()

                if (existing == null) {
                    DeviceEventSequences.insert {
                        it[DeviceEventSequences.userId] = userId
                        it[DeviceEventSequences.deviceId] = deviceId
                        it[DeviceEventSequences.eventType] = eventType
                        it[DeviceEventSequences.lastAppliedSeq] = seq
                        it[DeviceEventSequences.lastEventAt] = now
                    }
                    return@transaction ApplyOutcome(Status.APPLIED, seq)
                }

                val lastApplied = existing[DeviceEventSequences.lastAppliedSeq]
                when {
                    seq < lastApplied -> {
                        recordAnomaly(userId, deviceId, eventType, seq, "STALE", referenceId, now, "last=$lastApplied")
                        ApplyOutcome(Status.STALE, lastApplied)
                    }
                    seq == lastApplied -> {
                        recordAnomaly(userId, deviceId, eventType, seq, "DUPLICATE", referenceId, now, "already=$lastApplied")
                        ApplyOutcome(Status.DUPLICATE, lastApplied)
                    }
                    seq > lastApplied + 1 -> {
                        recordAnomaly(userId, deviceId, eventType, seq, "OUT_OF_ORDER", referenceId, now, "expected=${lastApplied + 1}")
                        ApplyOutcome(Status.OUT_OF_ORDER, lastApplied)
                    }
                    else -> {
                        DeviceEventSequences.update({
                            (DeviceEventSequences.userId eq userId) and
                                (DeviceEventSequences.deviceId eq deviceId) and
                                (DeviceEventSequences.eventType eq eventType)
                        }) {
                            it[DeviceEventSequences.lastAppliedSeq] = seq
                            it[DeviceEventSequences.lastEventAt] = now
                        }
                        ApplyOutcome(Status.APPLIED, seq)
                    }
                }
            }
        }
    }

    /** 异常汇总：按事件类型 + 状态统计（供仪表盘）。 */
    fun anomalySummary(userId: String? = null): Map<String, Long> = transaction {
        val q = DeviceEventConsistencyLog.selectAll()
        val rows = if (userId != null) q.andWhere { DeviceEventConsistencyLog.userId eq userId } else q
        rows.groupBy { it[DeviceEventConsistencyLog.status] }.mapValues { (_, v) -> v.size.toLong() }
    }

    private fun recordAnomaly(
        userId: String,
        deviceId: Int,
        eventType: String,
        seq: Long,
        status: String,
        referenceId: String?,
        now: Long,
        detail: String
    ) {
        val id = "${userId.take(32)}|$deviceId|${eventType.take(16)}|$status"
        val existing = DeviceEventConsistencyLog.selectAll().where { DeviceEventConsistencyLog.id eq id }.firstOrNull()
        if (existing == null) {
            DeviceEventConsistencyLog.insert {
                it[DeviceEventConsistencyLog.id] = id
                it[DeviceEventConsistencyLog.userId] = userId
                it[DeviceEventConsistencyLog.deviceId] = deviceId
                it[DeviceEventConsistencyLog.eventType] = eventType
                it[DeviceEventConsistencyLog.seq] = seq
                it[DeviceEventConsistencyLog.status] = status
                it[DeviceEventConsistencyLog.referenceId] = referenceId
                it[DeviceEventConsistencyLog.firstSeenAt] = now
                it[DeviceEventConsistencyLog.lastSeenAt] = now
                it[DeviceEventConsistencyLog.detail] = detail.take(300)
            }
        } else {
            DeviceEventConsistencyLog.update({ DeviceEventConsistencyLog.id eq id }) {
                it[DeviceEventConsistencyLog.seq] = seq
                it[DeviceEventConsistencyLog.referenceId] = referenceId
                it[DeviceEventConsistencyLog.lastSeenAt] = now
                it[DeviceEventConsistencyLog.detail] = detail.take(300)
            }
        }
    }
}

// ─────────────────────────────────────────────
// 内部辅助
// ─────────────────────────────────────────────

private suspend fun ApplicationCall.isAdminUser(): Boolean {
    val principal = principal<JWTPrincipal>() ?: return false
    val userId = principal.payload.subject
    return AdminAccess.isAdmin(userId)
}

private fun recordAdminAudit(actorId: String, action: String, detail: String) {
    transaction {
        ModerationAuditLog.insert {
            it[ModerationAuditLog.actorId] = actorId
            it[ModerationAuditLog.action] = action.take(40)
            it[ModerationAuditLog.detail] = detail.take(500)
            it[ModerationAuditLog.createdAt] = System.currentTimeMillis()
        }
    }
}

private val enhanceJson = Json { ignoreUnknownKeys = true }

private suspend inline fun <reified T> ApplicationCall.receiveEnhanceJson(): T? {
    val body = receiveBoundedText(MAX_ENHANCE_JSON_BODY_CHARS) ?: return null
    return runCatching { enhanceJson.decodeFromString<T>(body) }.getOrNull()
}

private fun csvCell(value: Any?): String {
    val raw = value?.toString() ?: ""
    // 公式注入防护须按「去除前导空白后的首字符」判定（Excel 忽略前导空白/制表符求值）
    val formulaSafe = if (raw.trimStart().firstOrNull() in setOf('=', '+', '-', '@')) "'$raw" else raw
    return "\"${formulaSafe.replace("\"", "\"\"")}\""
}

/** 时间范围导出：仅导出元数据/平台明文公告，绝不导出 E2EE 消息密文。返回 CSV 与实际行数。 */
private fun buildAuditExportCsv(scope: String, fromMs: Long, toMs: Long, limit: Int): Pair<String, Int> {
    val rows = when (scope) {
        "ADMIN_AUDIT" -> transaction {
            ModerationAuditLog.selectAll().where {
                (ModerationAuditLog.createdAt greaterEq fromMs) and (ModerationAuditLog.createdAt less toMs)
            }.orderBy(ModerationAuditLog.createdAt to SortOrder.ASC)
                .limit(limit)
                .map { row ->
                    listOf(
                        row[ModerationAuditLog.id], row[ModerationAuditLog.actorId], row[ModerationAuditLog.userId],
                        row[ModerationAuditLog.action], row[ModerationAuditLog.detail], row[ModerationAuditLog.createdAt]
                    )
                }
        }
        "RISK_EVENTS" -> transaction {
            RiskEvents.selectAll().where {
                (RiskEvents.createdAt greaterEq fromMs) and (RiskEvents.createdAt less toMs)
            }.orderBy(RiskEvents.createdAt to SortOrder.ASC)
                .limit(limit)
                .map { row ->
                    listOf(
                        row[RiskEvents.id], row[RiskEvents.userId], row[RiskEvents.sourceValue],
                        row[RiskEvents.ruleId], row[RiskEvents.action], row[RiskEvents.matched],
                        row[RiskEvents.referenceId], row[RiskEvents.needsReview], row[RiskEvents.createdAt]
                    )
                }
        }
        "ANNOUNCEMENTS" -> transaction {
            SystemAnnouncements.selectAll().where {
                (SystemAnnouncements.createdAt greaterEq fromMs) and (SystemAnnouncements.createdAt less toMs)
            }.orderBy(SystemAnnouncements.createdAt to SortOrder.ASC)
                .limit(limit)
                .map { row ->
                    listOf(
                        row[SystemAnnouncements.id], row[SystemAnnouncements.title], row[SystemAnnouncements.content],
                        row[SystemAnnouncements.level], row[SystemAnnouncements.targetAudience],
                        row[SystemAnnouncements.targetTagId], row[SystemAnnouncements.startsAt],
                        row[SystemAnnouncements.expiresAt], row[SystemAnnouncements.status],
                        row[SystemAnnouncements.createdBy], row[SystemAnnouncements.createdAt],
                        row[SystemAnnouncements.publishedAt], row[SystemAnnouncements.cancelledAt]
                    )
                }
        }
        "USER_TAGS" -> transaction {
            val rows = UserTagAssignments.selectAll().where {
                (UserTagAssignments.createdAt greaterEq fromMs) and (UserTagAssignments.createdAt less toMs)
            }.orderBy(UserTagAssignments.createdAt to SortOrder.ASC)
                .limit(limit)
                .toList()
            // 8.48 修复 M11：批量回查标签名（此前逐赋值查询 → N+1）
            val tagIds = rows.map { it[UserTagAssignments.tagId] }.distinct()
            val tagNameById = if (tagIds.isEmpty()) emptyMap() else
                UserTags.selectAll().where { UserTags.id inList tagIds }
                    .associate { it[UserTags.id] to it[UserTags.name] }
            rows.map { row ->
                    val tagName = tagNameById[row[UserTagAssignments.tagId]] ?: ""
                    listOf(
                        row[UserTagAssignments.tagId], tagName, row[UserTagAssignments.userId],
                        row[UserTagAssignments.assignmentSource], row[UserTagAssignments.assignedBy],
                        row[UserTagAssignments.createdAt]
                    )
                }
        }
        "RATE_LIMIT" -> transaction {
            RateLimitStatsSnapshots.selectAll().where {
                (RateLimitStatsSnapshots.bucketStartMs greaterEq fromMs) and
                    (RateLimitStatsSnapshots.bucketStartMs less toMs)
            }.orderBy(RateLimitStatsSnapshots.bucketStartMs to SortOrder.ASC)
                .limit(limit)
                .map { row ->
                    listOf(
                        row[RateLimitStatsSnapshots.bucketStartMs], row[RateLimitStatsSnapshots.allowed],
                        row[RateLimitStatsSnapshots.rejected], row[RateLimitStatsSnapshots.totalBuckets],
                        row[RateLimitStatsSnapshots.maxBuckets], row[RateLimitStatsSnapshots.maxPerMinute],
                        row[RateLimitStatsSnapshots.sampledAt]
                    )
                }
        }
        else -> emptyList()
    }

    val header = when (scope) {
        "ADMIN_AUDIT" -> "id,actorId,targetUserId,action,detail,createdAt"
        "RISK_EVENTS" -> "id,userId,source,ruleId,action,matched,referenceId,needsReview,createdAt"
        "ANNOUNCEMENTS" -> "id,title,content,level,audience,tagId,startsAt,expiresAt,status,createdBy,createdAt,publishedAt,cancelledAt"
        "USER_TAGS" -> "tagId,tagName,userId,source,assignedBy,createdAt"
        "RATE_LIMIT" -> "bucketStartMs,allowed,rejected,totalBuckets,maxBuckets,maxPerMinute,sampledAt"
        else -> ""
    }
    val body = rows.joinToString("\r\n") { row -> row.joinToString(",") { cell -> csvCell(cell) } }
    // 9.140：连同实际行数返回，供审计导出记录写入真实 rowCount
    return "\uFEFF$header\r\n$body\r\n" to rows.size
}

private fun AnnouncementRepository.AnnouncementRow.toDto(acked: Boolean): AnnouncementDto = AnnouncementDto(
    id = id, title = title, content = content, level = level,
    audience = targetAudience, tagId = targetTagId,
    startsAt = startsAt, expiresAt = expiresAt, status = status,
    createdBy = createdBy, createdAt = createdAt, updatedAt = updatedAt,
    publishedAt = publishedAt, cancelledAt = cancelledAt, acked = acked
)

private fun UserTagRepository.TagRow.toDto(): UserTagDto = UserTagDto(
    id = id, name = name, color = color, description = description,
    isSystem = isSystem, riskLevel = riskLevel, createdAt = createdAt,
    updatedAt = updatedAt, userCount = userCount
)

private fun UserTagRepository.AssignmentRow.toDto(): UserTagAssignmentDto = UserTagAssignmentDto(
    userId = userId, tagId = tagId, source = source, assignedBy = assignedBy, createdAt = createdAt
)

private object RateLimitSamplerThreadFactory : ThreadFactory {
    override fun newThread(r: Runnable): Thread =
        Thread(r, "rate-limit-stats-sampler").apply { isDaemon = true }
}

private val samplerLogger = LoggerFactory.getLogger("RateLimitStatsSampler")

private const val MAX_ENHANCE_JSON_BODY_CHARS = 80 * 1024
private const val MAX_ANNOUNCEMENT_CONTENT_CHARS = 4_000
private const val MAX_TAGS_PER_ASSIGNMENT = 20
private const val MAX_EXPORT_RANGE_MS = 90L * 24L * 60L * 60L * 1_000L
private const val DEFAULT_ANNOUNCEMENT_WINDOW_MS = 7L * 24L * 60L * 60L * 1_000L
private const val SAMPLE_DELAY_MS = 60_000L

/**
 * 清理 B6 运维数据中的过期记录（由 Routing.kt 的 6 小时周期循环调用），
 * 防止以下记录表无限增长：
 * - AnnouncementAcks 公告已读确认：ackedAt 超过 90 天删除
 * - DeviceEventConsistencyLog 设备一致性异常日志：lastSeenAt 超过 30 天删除
 * - AuditExportRecords 审计导出登记：requestedAt 超过 180 天删除
 * - ModerationAuditLog 管理操作审计：createdAt 超过 365 天删除
 *
 * 返回每个表本次删除的行数（仅供日志观测）。
 */
fun purgeAdminOperationalData(): Map<String, Int> {
    val now = System.currentTimeMillis()
    val day = 86_400_000L
    return transaction {
        mapOf(
            "announcementAcks" to AnnouncementAcks.deleteWhere {
                AnnouncementAcks.ackedAt less now - 90 * day
            },
            "deviceEventLogs" to DeviceEventConsistencyLog.deleteWhere {
                DeviceEventConsistencyLog.lastSeenAt less now - 30 * day
            },
            "auditExportRecords" to AuditExportRecords.deleteWhere {
                AuditExportRecords.requestedAt less now - 180 * day
            },
            "moderationAuditLogs" to ModerationAuditLog.deleteWhere {
                ModerationAuditLog.createdAt less now - 365 * day
            }
        )
    }
}
