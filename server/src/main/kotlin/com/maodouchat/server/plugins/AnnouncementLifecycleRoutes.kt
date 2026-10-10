package com.maodouchat.server.plugins

import com.maodouchat.server.model.AnnouncementStatsResponse
import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.repository.AnnouncementRepository
import com.maodouchat.server.repository.PushTokenRepository
import com.maodouchat.server.repository.UserTagRepository
import com.maodouchat.server.service.FcmPushService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/** 系统公告生命周期（发布/取消/统计）。 */
internal fun Route.configureAnnouncementLifecycleRoutes(
    announcementRepo: AnnouncementRepository,
    userTagRepo: UserTagRepository,
    fcmPushService: FcmPushService?,
    pushTokenRepo: PushTokenRepository?,
) {
    post("/announcements/{id}/publish") {
        if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
        val actorId = call.requireUserId()
        val id = call.requirePathParamOr400("id", "缺少公告 ID") ?: return@post
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
        val id = call.requirePathParamOr400("id", "缺少公告 ID") ?: return@post
        val cancelled = announcementRepo.cancel(id, actorId)
            ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("公告不存在"))
        recordAdminAudit(actorId, "ANNOUNCEMENT_CANCELLED", "id=$id")
        call.respond(cancelled.toDto(acked = false))
    }
    get("/announcements/{id}/stats") {
        if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
        val id = call.requirePathParamOr400("id", "缺少公告 ID") ?: return@get
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