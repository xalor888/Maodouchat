package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.repository.AnnouncementRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** 系统公告 CRUD：读（列表/详情）。 */
internal fun Route.configureAnnouncementCrudReadRoutes(
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
get("/announcements/{id}") {
    if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
    val id = call.requirePathParamOr400("id", "缺少公告 ID") ?: return@get
    val row = announcementRepo.get(id)
        ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("公告不存在"))
    call.respond(row.toDto(acked = false))
}
}