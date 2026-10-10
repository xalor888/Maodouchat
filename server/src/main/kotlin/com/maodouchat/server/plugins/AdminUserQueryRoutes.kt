package com.maodouchat.server.plugins

import com.maodouchat.server.model.*
import com.maodouchat.server.service.DispositionService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

internal fun Route.configureAdminUserQueryRoutes(
    adminManagementRepo: com.maodouchat.server.repository.AdminManagementRepository = com.maodouchat.server.repository.AdminManagementRepository(),
) {
// 用户查询 + 处置模板
    get("/users") {
        if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
        val limit = parseAdminListLimit(call.request.queryParameters)
        val offset = parseAdminListOffset(call.request.queryParameters)
        val search = parseAdminListSearch(call.request.queryParameters)
        val status = parseAdminListStatus(call.request.queryParameters)
        val users = adminManagementRepo.listUsers(limit, offset, search, status)
        call.respond(users)
    }

    get("/users/{id}") {
        if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
        val id = call.requirePathParamOr400("id", "缺少用户 ID") ?: return@get
        val user = adminManagementRepo.getUserAdmin(id)
            ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("用户不存在"))
        call.respond(user)
    }

    get("/users/{id}/detail") {
        if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
        val id = call.requirePathParamOr400("id", "缺少用户 ID") ?: return@get
        val detail = adminManagementRepo.getUserDetail(id)
            ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("用户不存在"))
        call.respond(detail)
    }

    get("/disposition-templates") {
        if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
        call.respond(
            DispositionTemplatesResponse(
                banReasons = DispositionService.banReasonTemplates.map {
                    DispositionReasonDto(
                        code = it.code,
                        labelZh = it.labelZh,
                        defaultDays = it.defaultDays,
                        requiresCustomNote = it.requiresCustomNote
                    )
                },
                muteReasons = DispositionService.muteReasonTemplates.map {
                    MuteReasonDto(
                        code = it.code,
                        labelZh = it.labelZh,
                        durationHours = it.durationHours,
                        requiresCustomNote = it.requiresCustomNote
                    )
                },
                postRestrictReasons = DispositionService.postRestrictReasonTemplates.map {
                    DispositionReasonDto(
                        code = it.code,
                        labelZh = it.labelZh,
                        defaultDays = it.defaultDays,
                        requiresCustomNote = it.requiresCustomNote
                    )
                },
                messageRestrictReasons = DispositionService.messageRestrictReasonTemplates.map {
                    DispositionReasonDto(
                        code = it.code,
                        labelZh = it.labelZh,
                        defaultDays = it.defaultDays,
                        requiresCustomNote = it.requiresCustomNote
                    )
                },
                unbanReasonCode = DispositionService.unbanReasonCode,
                unmuteReasonCode = DispositionService.unmuteReasonCode,
                unrestrictPostsReasonCode = DispositionService.unrestrictPostsReasonCode,
                unrestrictMessagesReasonCode = DispositionService.unrestrictMessagesReasonCode,
                appealNoticeZh = DispositionService.APPEAL_NOTICE_ZH,
                maxBanDays = DispositionService.MAX_BAN_DAYS,
                maxPostRestrictDays = DispositionService.MAX_POST_RESTRICT_DAYS,
                maxMessageRestrictDays = DispositionService.MAX_MESSAGE_RESTRICT_DAYS
            )
        )
    }
}
