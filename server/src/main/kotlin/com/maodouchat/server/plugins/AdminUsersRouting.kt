package com.maodouchat.server.plugins

import com.maodouchat.server.config.AdminAccess
import com.maodouchat.server.db.*
import com.maodouchat.server.messaging.v2.MessagingV2RecordClass
import com.maodouchat.server.model.*
import com.maodouchat.server.repository.AuthTokenRepository
import com.maodouchat.server.repository.EncryptedAttachmentRepository
import com.maodouchat.server.repository.GroupMediaReferenceRepository
import com.maodouchat.server.repository.PostRepository
import com.maodouchat.server.repository.PushTokenRepository
import com.maodouchat.server.repository.UserRepository
import com.maodouchat.server.service.DispositionService
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
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * 管理后台「用户治理」子域路由：用户查询、封禁/禁动态/禁消息、处置模板与注销。
 * 事务与处置校验由 repository / DispositionService 拥有；注销后的磁盘清理是
 * 逐项容错的提交后副作用，任一失败不回滚已提交的注销事实。
 */
internal fun Route.configureAdminUsersRoutes(
    userRepo: UserRepository,
    postRepo: PostRepository,
    authTokenRepo: AuthTokenRepository,
    groupMediaReferenceRepo: GroupMediaReferenceRepository,
) {

    // ─── 用户管理 ─────────────────────
    get("/users") {
        if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
        val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 200)
        val offset = (call.request.queryParameters["offset"]?.toLongOrNull() ?: 0L).coerceAtLeast(0L)
        val search = call.request.queryParameters["q"]?.trim()?.takeIf { it.isNotBlank() }
        val status = call.request.queryParameters["status"]?.trim()?.takeIf { it.isNotBlank() }
        val users = transaction {
            val escapedSearch = search?.let { escapeLikePattern(it) }
            val base = if (escapedSearch != null) {
                val likePattern = org.jetbrains.exposed.sql.LikePattern("%$escapedSearch%", '\\')
                Users.selectAll().where {
                    (Users.name like likePattern) or (Users.email like likePattern)
                }
            } else Users.selectAll()
            val now = System.currentTimeMillis()
            val filtered = when (status) {
                "active" -> base.andWhere { Users.deletedAt.isNull() and (Users.suspendedUntil lessEq now) }
                "banned" -> base.andWhere { Users.deletedAt.isNull() and (Users.suspendedUntil greater now) }
                "deleted" -> base.andWhere { Users.deletedAt.isNotNull() }
                "online" -> base.andWhere { Users.deletedAt.isNull() and (Users.isOnline eq true) }
                // 无关键字的默认列表隐藏已注销；带 q 时包含 tombstone，方便按 deleted_ 邮箱找回。
                else -> if (escapedSearch != null) base else base.andWhere { Users.deletedAt.isNull() }
            }
            filtered.orderBy(Users.lastSeen to SortOrder.DESC, Users.id to SortOrder.DESC).limit(limit, offset)
                .map { it.toUserAdminResponse() }
        }
        call.respond(users)
    }

    get("/users/{id}") {
        if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
        val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("缺少用户 ID"))
        val user = transaction { Users.selectAll().where { Users.id eq id }.firstOrNull()?.toUserAdminResponse() }
            ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("用户不存在"))
        call.respond(user)
    }

    get("/users/{id}/detail") {
        if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
        val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("缺少用户 ID"))
        val detail = transaction {
            val row = Users.selectAll().where { Users.id eq id }.firstOrNull()
                ?: return@transaction null
            UserDetailAdminResponse(
                id = row[Users.id],
                name = row[Users.name],
                email = row[Users.email],
                isModerator = row[Users.isModerator],
                lastActiveAt = row[Users.lastSeen],
                suspendedUntil = row[Users.suspendedUntil],
                postRestrictedUntil = row[Users.postRestrictedUntil],
                messageRestrictedUntil = row[Users.messageRestrictedUntil],
                deletedAt = row[Users.deletedAt],
                messageCount = MessagingV2Messages.selectAll().where {
                    (MessagingV2Messages.senderUserId eq id) and
                        (MessagingV2Messages.recordClass eq com.maodouchat.server.messaging.v2.MessagingV2RecordClass.MESSAGE)
                }.count(),
                postCount = Posts.selectAll().where { Posts.authorId eq id }.count(),
                commentCount = PostComments.selectAll().where { PostComments.authorId eq id }.count(),
                chatCount = ChatParticipants.selectAll().where { ChatParticipants.userId eq id }.count(),
                pushTokenCount = PushTokens.selectAll().where { PushTokens.userId eq id }.count(),
                reportCount = Reports.selectAll().where {
                    (Reports.reporterId eq id) or (Reports.targetId eq id)
                }.count(),
                avatar = row[Users.avatar]
            )
        } ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("用户不存在"))
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
        val actorId = call.principal<JWTPrincipal>()!!.payload.subject
        val id = call.parameters["id"] ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("缺少用户 ID"))
        if (id == actorId) return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("不能修改自己的管理状态"))
        if (AdminAccess.isAdmin(id)) return@put call.respond(HttpStatusCode.Forbidden, ErrorResponse("不能修改其他超级管理员"))
        val req = call.receiveAdminJson<UpdateUserStatusRequest>()
            ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("请求无效"))
        val now = System.currentTimeMillis()
        val bannedUntil = req.bannedUntil ?: 0L
        if (bannedUntil < 0 || bannedUntil > now + MAX_ADMIN_SUSPEND_MS || (bannedUntil in 1..now)) {
            return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("封禁截止时间无效"))
        }
        val banDays = if (bannedUntil <= 0L) {
            0
        } else {
            // ceil days for template validation; exact until still stored from client/server clock
            (((bannedUntil - now) + 86_399_999L) / 86_400_000L).toInt().coerceIn(1, DispositionService.MAX_BAN_DAYS)
        }
        val disposition = DispositionService.validateDisposition(
            banDays = banDays,
            reasonCode = req.reasonCode,
            note = req.note
        )
        val okDisposition = when (disposition) {
            is DispositionService.DispositionValidation.Invalid ->
                return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse(disposition.message))
            is DispositionService.DispositionValidation.Ok -> disposition
        }
        val updated = transaction {
            // 9.154：以 update 影响行数为准——firstOrNull 与 update 之间并发删除会
            // 让封禁落空却仍写审计行、回 200 并轮换会话
            val changed = Users.update({ (Users.id eq id) and Users.deletedAt.isNull() }) { it[suspendedUntil] = bannedUntil }
            if (changed == 0) return@transaction false
            ModerationAuditLog.insert {
                it[userId] = id
                it[action] = "ADMIN_STATUS_UPDATE"
                it[detail] = DispositionService.auditDetail(
                    bannedUntil = bannedUntil,
                    reasonCode = okDisposition.reasonCode,
                    note = okDisposition.note
                ).take(com.maodouchat.server.db.MODERATION_AUDIT_DETAIL_MAX_CHARS)
                it[ModerationAuditLog.actorId] = actorId
                it[createdAt] = now
            }
            true
        }
        if (!updated) return@put call.respond(HttpStatusCode.NotFound, ErrorResponse("用户不存在"))
        // 生效中的封禁需立刻废掉已签发会话，避免仅靠写路径的 suspended 检查被绕过
        if (bannedUntil > now) {
            authTokenRepo.rotateAccessTokenVersion(id)
            // 封禁后旧设备不得再收推送
            PushTokenRepository().removeAllForUser(id)
            disconnectUserSessions(id, "账号已被临时封禁")
        }
        call.respond(
        buildJsonObject {
put("status", "ok")
put("reasonCode", okDisposition.reasonCode)
put("appealNoticeZh", DispositionService.APPEAL_NOTICE_ZH)
        }
    )
    }

    put("/users/{id}/post-restriction") {
        if (!call.isAdminUser()) return@put call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
        val actorId = call.principal<JWTPrincipal>()!!.payload.subject
        val id = call.parameters["id"] ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("缺少用户 ID"))
        if (id == actorId) return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("不能限制自己的发帖权限"))
        if (AdminAccess.isAdmin(id)) return@put call.respond(HttpStatusCode.Forbidden, ErrorResponse("不能限制其他超级管理员"))
        val req = call.receiveAdminJson<UpdatePostRestrictionRequest>()
            ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("请求无效"))
        val now = System.currentTimeMillis()
        val postRestrictedUntil = req.postRestrictedUntil ?: 0L
        if (postRestrictedUntil < 0 ||
            postRestrictedUntil > now + DispositionService.MAX_POST_RESTRICT_DAYS * 86_400_000L ||
            (postRestrictedUntil in 1..now)
        ) {
            return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("禁动态截止时间无效"))
        }
        val durationDays = if (postRestrictedUntil <= 0L) {
            0
        } else {
            (((postRestrictedUntil - now) + 86_399_999L) / 86_400_000L)
                .toInt()
                .coerceIn(1, DispositionService.MAX_POST_RESTRICT_DAYS)
        }
        val disposition = DispositionService.validatePostRestrict(
            durationDays = durationDays,
            reasonCode = req.reasonCode,
            note = req.note
        )
        val okDisposition = when (disposition) {
            is DispositionService.DispositionValidation.Invalid ->
                return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse(disposition.message))
            is DispositionService.DispositionValidation.Ok -> disposition
        }
        val updated = transaction {
            // 9.154：同封禁端点——以 update 影响行数为准
            val changed = Users.update({ (Users.id eq id) and Users.deletedAt.isNull() }) { it[Users.postRestrictedUntil] = postRestrictedUntil }
            if (changed == 0) return@transaction false
            ModerationAuditLog.insert {
                it[userId] = id
                it[action] = "ADMIN_POST_RESTRICT"
                it[detail] = DispositionService.auditPostRestrictDetail(
                    postRestrictedUntil = postRestrictedUntil,
                    reasonCode = okDisposition.reasonCode,
                    note = okDisposition.note
                ).take(com.maodouchat.server.db.MODERATION_AUDIT_DETAIL_MAX_CHARS)
                it[ModerationAuditLog.actorId] = actorId
                it[createdAt] = now
            }
            true
        }
        if (!updated) return@put call.respond(HttpStatusCode.NotFound, ErrorResponse("用户不存在"))
        call.respond(
            buildJsonObject {
                put("status", "ok")
                put("postRestrictedUntil", postRestrictedUntil)
                put("reasonCode", okDisposition.reasonCode)
                put("appealNoticeZh", DispositionService.APPEAL_NOTICE_ZH)
            }
        )
    }

    put("/users/{id}/message-restriction") {
        if (!call.isAdminUser()) return@put call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
        val actorId = call.principal<JWTPrincipal>()!!.payload.subject
        val id = call.parameters["id"] ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("缺少用户 ID"))
        if (id == actorId) return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("不能限制自己的发消息权限"))
        if (AdminAccess.isAdmin(id)) return@put call.respond(HttpStatusCode.Forbidden, ErrorResponse("不能限制系统主管理员"))
        val req = call.receiveAdminJson<UpdateMessageRestrictionRequest>()
            ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("请求无效"))
        val now = System.currentTimeMillis()
        val messageRestrictedUntil = req.messageRestrictedUntil ?: 0L
        if (messageRestrictedUntil < 0 ||
            messageRestrictedUntil > now + DispositionService.MAX_MESSAGE_RESTRICT_DAYS * 86_400_000L ||
            (messageRestrictedUntil in 1..now)
        ) {
            return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("禁消息截止时间无效"))
        }
        val durationDays = if (messageRestrictedUntil <= 0L) {
            0
        } else {
            (((messageRestrictedUntil - now) + 86_399_999L) / 86_400_000L)
                .toInt()
                .coerceIn(1, DispositionService.MAX_MESSAGE_RESTRICT_DAYS)
        }
        val disposition = DispositionService.validateMessageRestrict(
            durationDays = durationDays,
            reasonCode = req.reasonCode,
            note = req.note
        )
        val okDisposition = when (disposition) {
            is DispositionService.DispositionValidation.Invalid ->
                return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse(disposition.message))
            is DispositionService.DispositionValidation.Ok -> disposition
        }
        val updated = transaction {
            // 9.154：同封禁端点——以 update 影响行数为准
            val changed = Users.update({ (Users.id eq id) and Users.deletedAt.isNull() }) { it[Users.messageRestrictedUntil] = messageRestrictedUntil }
            if (changed == 0) return@transaction false
            ModerationAuditLog.insert {
                it[userId] = id
                it[action] = "ADMIN_MESSAGE_RESTRICT"
                it[detail] = DispositionService.auditMessageRestrictDetail(
                    messageRestrictedUntil = messageRestrictedUntil,
                    reasonCode = okDisposition.reasonCode,
                    note = okDisposition.note
                ).take(com.maodouchat.server.db.MODERATION_AUDIT_DETAIL_MAX_CHARS)
                it[ModerationAuditLog.actorId] = actorId
                it[createdAt] = now
            }
            true
        }
        if (!updated) return@put call.respond(HttpStatusCode.NotFound, ErrorResponse("用户不存在"))
        call.respond(
            buildJsonObject {
                put("status", "ok")
                put("reasonCode", okDisposition.reasonCode)
                put("appealNoticeZh", DispositionService.APPEAL_NOTICE_ZH)
                put("messageRestrictedUntil", messageRestrictedUntil)
            }
        )
    }


    delete("/users/{id}") {
        if (!call.isAdminUser()) return@delete call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
        val actorId = call.principal<JWTPrincipal>()!!.payload.subject
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
