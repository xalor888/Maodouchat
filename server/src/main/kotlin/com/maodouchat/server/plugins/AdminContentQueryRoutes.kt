package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.repository.AdminManagementRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** 管理后台内容查询（动态 / 评论列表）。 */
internal fun Route.configureAdminContentQueryRoutes(
    adminManagementRepo: AdminManagementRepository,
) {

    get("/posts") {
        if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
        val limit = parseAdminListLimit(call.request.queryParameters)
        val offset = parseAdminListOffset(call.request.queryParameters)
        // status 不用 parseAdminListStatus：此处沿用未 trim 的原语义
        val status = parseNonBlankOrNull(call.request.queryParameters, "status")
        val search = parseAdminListSearch(call.request.queryParameters)
        val posts = adminManagementRepo.listAdminPosts(
            limit = limit,
            offset = offset,
            authorId = null,
            status = status,
            search = search
        )
        call.respond(posts)
    }

    get("/comments") {
        if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
        val limit = parseAdminListLimit(call.request.queryParameters)
        val offset = parseAdminListOffset(call.request.queryParameters)
        val search = parseAdminListSearch(call.request.queryParameters)
        val comments = adminManagementRepo.listAdminComments(
            limit = limit,
            offset = offset,
            search = search
        )
        call.respond(comments)
    }
}
