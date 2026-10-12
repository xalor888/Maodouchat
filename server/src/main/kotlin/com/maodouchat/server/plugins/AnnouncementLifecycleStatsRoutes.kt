package com.maodouchat.server.plugins

import com.maodouchat.server.model.AnnouncementStatsResponse
import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.repository.AnnouncementRepository
import com.maodouchat.server.repository.ackedCount
import com.maodouchat.server.repository.stats
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** 系统公告生命周期：统计。 */
internal fun Route.configureAnnouncementLifecycleStatsRoutes(
    announcementRepo: AnnouncementRepository,
) {
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