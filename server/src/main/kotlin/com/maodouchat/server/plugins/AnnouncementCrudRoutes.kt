package com.maodouchat.server.plugins

import com.maodouchat.server.model.CreateAnnouncementRequest
import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.model.UpdateAnnouncementRequest
import com.maodouchat.server.repository.AnnouncementRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 系统公告 CRUD（列表/创建/详情/更新/删除）。 */
internal fun Route.configureAnnouncementCrudRoutes(
    announcementRepo: AnnouncementRepository,
) {
    get("/announcements") {
        if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
        val limit = parseAdminListLimit(call.request.queryParameters)
        val offset = parseAdminListOffset(call.request.queryParameters)
        val status = parseAdminListStatus(call.request.queryParameters)
        val q = parseAdminListSearch(call.request.queryParameters)
        val list = announcementRepo.list(status, q, limit, offset)
        call.respond(list.map { it.toDto(acked = false) })
    }
    post("/announcements") {
        if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
        val actorId = call.requireUserId()
        val req = call.receiveAdminJson<CreateAnnouncementRequest>()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("请求无效"))
        val title = call.requireNonBlankValueOr400(req.title, "公告标题不能为空") ?: return@post
        val content = call.requireNonBlankValueOr400(req.content, "公告内容不能为空") ?: return@post
        if (content.length > MAX_ANNOUNCEMENT_CONTENT_CHARS) {
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
            title = title, content = content, level = level,
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
        val id = call.requirePathParamOr400("id", "缺少公告 ID") ?: return@get
        val row = announcementRepo.get(id)
            ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("公告不存在"))
        call.respond(row.toDto(acked = false))
    }
    put("/announcements/{id}") {
        if (!call.isAdminUser()) return@put call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
        val actorId = call.requireUserId()
        val id = call.requirePathParamOr400("id", "缺少公告 ID") ?: return@put
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
    delete("/announcements/{id}") {
        if (!call.isAdminUser()) return@delete call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
        val actorId = call.requireUserId()
        val id = call.requirePathParamOr400("id", "缺少公告 ID") ?: return@delete
        if (!announcementRepo.delete(id)) {
            return@delete call.respond(HttpStatusCode.Conflict, ErrorResponse("仅未发布的草稿可删除；已发布公告请使用取消"))
        }
        recordAdminAudit(actorId, "ANNOUNCEMENT_DELETED", "id=$id")
        call.respond(buildJsonObject { put("ok", true) })
    }
}

private const val MAX_ANNOUNCEMENT_CONTENT_CHARS = 4_000
private const val DEFAULT_ANNOUNCEMENT_WINDOW_MS = 7L * 24L * 60L * 60L * 1_000L
