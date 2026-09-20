package com.maodouchat.server.plugins

import com.maodouchat.server.config.AdminAccess
import com.maodouchat.server.db.*
import com.maodouchat.server.messaging.v2.MessagingV2RecordClass
import com.maodouchat.server.model.*
import com.maodouchat.server.repository.AuthTokenRepository
import com.maodouchat.server.repository.escapeLikePattern
import com.maodouchat.server.repository.EncryptedAttachmentRepository
import com.maodouchat.server.repository.GroupMediaReferenceRepository
import com.maodouchat.server.repository.PostRepository
import com.maodouchat.server.repository.PushTokenRepository
import com.maodouchat.server.repository.UserRepository
import com.maodouchat.server.service.DispositionService
import com.maodouchat.server.service.UserDispositionService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 管理后台「用户治理」子域路由：用户查询、封禁/禁动态/禁消息、处置模板与注销。
 * 事务与处置校验由 repository / DispositionService 拥有；注销后的磁盘清理是
 * 逐项容错的提交后副作用，任一失败不回滚已提交的注销事实。
 */
internal fun Route.configureAdminUsersRoutes(
    userRepo: UserRepository,
    postRepo: PostRepository,
    authTokenRepo: AuthTokenRepository,
    sessionService: com.maodouchat.server.service.SessionService,
    groupMediaReferenceRepo: GroupMediaReferenceRepository,
    userDispositionService: UserDispositionService,
    adminManagementRepo: com.maodouchat.server.repository.AdminManagementRepository = com.maodouchat.server.repository.AdminManagementRepository(),
) {

    // ─── 用户管理 ─────────────────────
    get("/users") {
        if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
        val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 200)
        val offset = (call.request.queryParameters["offset"]?.toLongOrNull() ?: 0L).coerceAtLeast(0L)
        val search = call.request.queryParameters["q"]?.trim()?.takeIf { it.isNotBlank() }
        val status = call.request.queryParameters["status"]?.trim()?.takeIf { it.isNotBlank() }
        val users = adminManagementRepo.listUsers(limit, offset, search, status)
        call.respond(users)
    }

    get("/users/{id}") {
        if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
        val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("缺少用户 ID"))
        val user = adminManagementRepo.getUserAdmin(id)
            ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("用户不存在"))
        call.respond(user)
    }

    get("/users/{id}/detail") {
        if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
        val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("缺少用户 ID"))
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

    put("/users/{id}/status") {
        if (!call.isAdminUser()) return@put call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
        val actorId = call.requireUserId()
        val id = call.parameters["id"] ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("缺少用户 ID"))
        if (id == actorId) return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("不能修改自己的管理状态"))
        if (AdminAccess.isAdmin(id)) return@put call.respond(HttpStatusCode.Forbidden, ErrorResponse("不能修改其他超级管理员"))
        val req = call.receiveAdminJson<UpdateUserStatusRequest>()
            ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("请求无效"))
        val bannedUntil = req.bannedUntil ?: 0L
        when (val result = userDispositionService.suspend(actorId, id, bannedUntil, req.reasonCode, req.note)) {
            is UserDispositionService.Result.Invalid ->
                return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse(result.message))
            UserDispositionService.Result.NotFound ->
                return@put call.respond(HttpStatusCode.NotFound, ErrorResponse("用户不存在"))
            is UserDispositionService.Result.Applied -> {
                // 生效中的封禁需立刻废掉已签发会话，避免仅靠写路径的 suspended 检查被绕过
                if (bannedUntil > System.currentTimeMillis()) {
                    sessionService.revokeAllUserSessions(id)
                    // 封禁后旧设备不得再收推送
                    disconnectUserSessions(id, "账号已被临时封禁")
                }
                call.respond(
                    buildJsonObject {
                        put("status", "ok")
                        put("reasonCode", result.reasonCode)
                        put("appealNoticeZh", DispositionService.APPEAL_NOTICE_ZH)
                    }
                )
            }
        }
    }

    put("/users/{id}/post-restriction") {
        if (!call.isAdminUser()) return@put call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
        val actorId = call.requireUserId()
        val id = call.parameters["id"] ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("缺少用户 ID"))
        if (id == actorId) return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("不能限制自己的发帖权限"))
        if (AdminAccess.isAdmin(id)) return@put call.respond(HttpStatusCode.Forbidden, ErrorResponse("不能限制其他超级管理员"))
        val req = call.receiveAdminJson<UpdatePostRestrictionRequest>()
            ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("请求无效"))
        val postRestrictedUntil = req.postRestrictedUntil ?: 0L
        when (val result = userDispositionService.restrictPosts(actorId, id, postRestrictedUntil, req.reasonCode, req.note)) {
            is UserDispositionService.Result.Invalid ->
                return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse(result.message))
            UserDispositionService.Result.NotFound ->
                return@put call.respond(HttpStatusCode.NotFound, ErrorResponse("用户不存在"))
            is UserDispositionService.Result.Applied -> call.respond(
                buildJsonObject {
                    put("status", "ok")
                    put("postRestrictedUntil", postRestrictedUntil)
                    put("reasonCode", result.reasonCode)
                    put("appealNoticeZh", DispositionService.APPEAL_NOTICE_ZH)
                }
            )
        }
    }

    put("/users/{id}/message-restriction") {
        if (!call.isAdminUser()) return@put call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
        val actorId = call.requireUserId()
        val id = call.parameters["id"] ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("缺少用户 ID"))
        if (id == actorId) return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("不能限制自己的发消息权限"))
        if (AdminAccess.isAdmin(id)) return@put call.respond(HttpStatusCode.Forbidden, ErrorResponse("不能限制系统主管理员"))
        val req = call.receiveAdminJson<UpdateMessageRestrictionRequest>()
            ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("请求无效"))
        val messageRestrictedUntil = req.messageRestrictedUntil ?: 0L
        when (val result = userDispositionService.restrictMessages(actorId, id, messageRestrictedUntil, req.reasonCode, req.note)) {
            is UserDispositionService.Result.Invalid ->
                return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse(result.message))
            UserDispositionService.Result.NotFound ->
                return@put call.respond(HttpStatusCode.NotFound, ErrorResponse("用户不存在"))
            is UserDispositionService.Result.Applied -> call.respond(
                buildJsonObject {
                    put("status", "ok")
                    put("reasonCode", result.reasonCode)
                    put("appealNoticeZh", DispositionService.APPEAL_NOTICE_ZH)
                    put("messageRestrictedUntil", messageRestrictedUntil)
                }
            )
        }
    }


    delete("/users/{id}") {
        if (!call.isAdminUser()) return@delete call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
        val actorId = call.requireUserId()
        val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("缺少用户 ID"))
        if (id == actorId) return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("不能删除自己的管理员账号"))
        if (AdminAccess.isAdmin(id)) return@delete call.respond(HttpStatusCode.Forbidden, ErrorResponse("不能删除其他超级管理员"))
        val groupAvatarCandidates = groupMediaReferenceRepo.avatarUrlsForParticipant(id)
        val deactivation = userRepo.adminDeactivateAccount(id, actorId)
            ?: return@delete call.respond(HttpStatusCode.NotFound, ErrorResponse("用户不存在或已注销"))
        // 8.37：DB 注销已在事务内提交，磁盘清理必须逐项容错——任一失败若抛 500，
        // 重试会命中 404（用户已注销）导致清理无法重做，产生孤儿密文/图片。
        // 失败仅记日志，由存储层 TTL/孤儿清理兜底。
        fun bestEffort(step: String, block: () -> Unit) {
            runCatching(block).onFailure { e ->
                org.slf4j.LoggerFactory.getLogger("AdminRouting").warn("admin delete user $id: cleanup $step failed", e)
            }
        }
        // 空会话级联删除的附件行已不在 DB；先清磁盘，再清仍挂在其他会话上的本人上传
        bestEffort("orphanedAttachments") {
            deactivation.orphanedAttachmentIds
                .forEach(com.maodouchat.server.service.BlobStore::delete)
        }
        bestEffort("uploaderAttachments") {
            EncryptedAttachmentRepository().deleteForUploader(id)
                .forEach(com.maodouchat.server.service.BlobStore::delete)
        }
        bestEffort("posts") { postRepo.deleteAllPostsForAuthor(id) }
        bestEffort("postImages") { com.maodouchat.server.service.FileStorageService.deletePostImagesForUser(id) }
        bestEffort("groupAvatars") {
            groupAvatarCandidates
                .filterNot(groupMediaReferenceRepo::isAvatarUrlReferenced)
                .forEach { url -> com.maodouchat.server.service.FileStorageService.deleteGroupAvatarUrl(url) }
        }
        bestEffort("avatar") { com.maodouchat.server.service.FileStorageService.deleteAvatarUrl(deactivation.avatarUrl, id) }
        // disconnectUserSessions 是挂起函数：单独 runCatching（inline 内允许挂起调用）
        // 取消必须重抛，避免吞掉协程取消
        runCatching { disconnectUserSessions(id, "账号已被管理员停用") }
            .onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                org.slf4j.LoggerFactory.getLogger("AdminRouting").warn("admin delete user $id: cleanup disconnect failed", e)
            }
        call.respond(
            AdminAccountDeactivatedResponse(
                status = "deactivated",
                deletedAt = deactivation.deletedAt
            )
        )
    }
}
