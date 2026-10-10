package com.maodouchat.server.plugins

import com.maodouchat.server.config.AdminAccess
import com.maodouchat.server.model.*
import com.maodouchat.server.repository.EncryptedAttachmentRepository
import com.maodouchat.server.repository.GroupMediaReferenceRepository
import com.maodouchat.server.repository.PostRepository
import com.maodouchat.server.repository.UserRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete

internal fun Route.configureAdminUserDeleteRoutes(
    userRepo: UserRepository,
    postRepo: PostRepository,
    groupMediaReferenceRepo: GroupMediaReferenceRepository,
) {
// 用户注销（逐项容错的提交后清理）
    delete("/users/{id}") {
        if (!call.isAdminUser()) return@delete call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
        val actorId = call.requireUserId()
        val id = call.requirePathParamOr400("id", "缺少用户 ID") ?: return@delete
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
