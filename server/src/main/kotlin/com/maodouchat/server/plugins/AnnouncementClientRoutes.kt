package com.maodouchat.server.plugins

import com.maodouchat.server.model.ActiveAnnouncementsResponse
import com.maodouchat.server.model.AnnouncementAckResponse
import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.repository.AnnouncementRepository
import com.maodouchat.server.repository.UserTagRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing

/** 系统公告用户端：可见公告列表 + 已读确认。 */
fun Application.configureAnnouncementClientRoutes(
    announcementRepo: AnnouncementRepository,
    userTagRepo: UserTagRepository,
) {
    routing {
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
                val id = call.requirePathParamOr400("id", "缺少公告 ID") ?: return@post
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
    }
}
