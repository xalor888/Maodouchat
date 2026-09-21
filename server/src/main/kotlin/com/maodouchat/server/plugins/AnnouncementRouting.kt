package com.maodouchat.server.plugins

import com.maodouchat.server.model.ActiveAnnouncementsResponse
import com.maodouchat.server.model.AnnouncementAckResponse
import com.maodouchat.server.model.AnnouncementDto
import com.maodouchat.server.model.AnnouncementStatsResponse
import com.maodouchat.server.model.CreateAnnouncementRequest
import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.model.UpdateAnnouncementRequest
import com.maodouchat.server.repository.AnnouncementRepository
import com.maodouchat.server.repository.PushTokenRepository
import com.maodouchat.server.repository.UserTagRepository
import com.maodouchat.server.service.FcmPushService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 系统公告子域路由：用户端可见公告 + 管理端公告 CRUD/发布/取消/统计。
 * 从 AdminEnhanceRouting 拆出（B13 子项 3：管理 route 按公告拆分）。
 */
fun Application.configureAnnouncementRoutes(
    announcementRepo: AnnouncementRepository,
    userTagRepo: UserTagRepository,
    fcmPushService: FcmPushService?,
    pushTokenRepo: PushTokenRepository?,
) {
    routing {
        // ─── 用户端公告（普通登录态，非管理端点）────────────────
        authenticate("auth-jwt") {
            get("/api/announcements/active") {
                val userId = call.requireUserId()
                val now = System.currentTimeMillis()
                val userTagIds = userTagRepo.userTagIds(userId)
                val active = announcementRepo.activeForUser(userId, now, userTagIds)
                val ackedIds = announcementRepo.ackedAnnouncementIds(userId)
                call.respond(
                    ActiveAnnouncementsResponse(
                        announcements = active.map { it.toDto(acked = it.id in ackedIds) },
                        serverTime = now
                    )
                )
            }

            post("/api/announcements/{id}/ack") {
                val userId = call.requireUserId()
                val id = call.parameters["id"] ?: return@post call.respond(
                    HttpStatusCode.BadRequest, ErrorResponse("缺少公告 ID")
                )
                // 8.46 修复：ack 必须与 activeForUser 同一可见性判定（status=ACTIVE + 生效窗口
                // [startsAt,expiresAt] + 受众命中）——否则任意用户可对不可见的 TAGGED/过期公告打已读，
                // 污染 stats 的 acked 统计。
                val now = System.currentTimeMillis()
                // 8.46：ack 与 activeForUser 同一可见性口径，仓库统一判定
                val visible = announcementRepo.isAckVisibleToUser(
                    id = id,
                    userId = userId,
                    now = now,
                    userTagIds = userTagRepo.userTagIds(userId).toSet(),
                )
                if (!visible) return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("公告不存在或未发布"))
                announcementRepo.markAcked(id, userId, now)
                call.respond(AnnouncementAckResponse(ok = true, announcementId = id))
            }
        }

        // ─── 管理端公告（双重门控）────────────────
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
                    val actorId = call.requireUserId()
                    val req = call.receiveAdminJson<CreateAnnouncementRequest>()
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
                    val asDraft = req.draft == true
                    val startsAt = req.startsAt ?: now
                    val expiresAt = req.expiresAt ?: (now + DEFAULT_ANNOUNCEMENT_WINDOW_MS)
                    if (startsAt > expiresAt) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("生效时间不能晚于失效时间"))
                    if (expiresAt < now) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("失效时间不能早于当前时间"))
                    val created = announcementRepo.create(
                        title = req.title, content = req.content, level = level,
                        targetAudience = audience, targetTagId = req.tagId?.takeIf { audience == "TAGGED" },
                        startsAt = startsAt, expiresAt = expiresAt, createdBy = actorId,
                        asDraft = asDraft
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
                    val actorId = call.requireUserId()
                    val id = call.parameters["id"] ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("缺少公告 ID"))
                    val req = call.receiveAdminJson<UpdateAnnouncementRequest>()
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
                    val actorId = call.requireUserId()
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
                    val actorId = call.requireUserId()
                    val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("缺少公告 ID"))
                    val cancelled = announcementRepo.cancel(id, actorId)
                        ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("公告不存在"))
                    recordAdminAudit(actorId, "ANNOUNCEMENT_CANCELLED", "id=$id")
                    call.respond(cancelled.toDto(acked = false))
                }

                delete("/announcements/{id}") {
                    if (!call.isAdminUser()) return@delete call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                    val actorId = call.requireUserId()
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
                    val ackedCount = announcementRepo.ackedCount(id)
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


            }
        }
    }
}

private fun AnnouncementRepository.AnnouncementRow.toDto(acked: Boolean): AnnouncementDto = AnnouncementDto(
    id = id, title = title, content = content, level = level,
    audience = targetAudience, tagId = targetTagId,
    startsAt = startsAt, expiresAt = expiresAt, status = status,
    createdBy = createdBy, createdAt = createdAt, updatedAt = updatedAt,
    publishedAt = publishedAt, cancelledAt = cancelledAt, acked = acked
)

private const val MAX_ANNOUNCEMENT_CONTENT_CHARS = 4_000
private const val DEFAULT_ANNOUNCEMENT_WINDOW_MS = 7L * 24L * 60L * 60L * 1_000L
