package com.maodouchat.server.plugins

import com.maodouchat.server.model.*
import com.maodouchat.server.repository.*
import com.maodouchat.server.service.BlobStore
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.auth.*
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json

/** 账号安全：改密/注销。 */
internal fun Route.configureAccountSecurityRoutes(
    userRepo: UserRepository,
    postRepo: PostRepository,
    sessionService: com.maodouchat.server.service.SessionService,
    groupMediaReferenceRepo: GroupMediaReferenceRepository,
    encryptedAttachmentRepo: EncryptedAttachmentRepository,
    conversationParticipantRepo: ConversationParticipantRepository,
    conversationQueryRepo: ConversationQueryRepository,
    json: Json,
) {
    authenticate("auth-jwt") {

        post("/api/users/change-password") {
            val userId = call.requireUserId()
            val req = call.receiveJsonOr400<ChangePasswordRequest>() ?: return@post
            if (req.oldPassword.isBlank() || !isValidPassword(req.newPassword)) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("新密码至少 6 位"))
                return@post
            }
            val ok = userRepo.changePassword(userId, req.oldPassword, req.newPassword)
            if (ok) {
                sessionService.revokeAllUserSessions(userId)
                // 与 logout-all 一致：旧设备会话已废，推送 token 必须清掉，否则仍收消息/来电推送
                disconnectUserSessions(userId, "密码已修改，请重新登录")
                call.respondOk()
            } else {
                // 403：勿用 401 — 客户端 executeWithRefresh 会把带 Authorization 的 401 当会话过期并清库
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("原密码错误", code = "WRONG_PASSWORD"))
            }
        }

        delete("/api/users/me") {
            val userId = call.requireUserId()
            val groupAvatarCandidates = groupMediaReferenceRepo.avatarUrlsForParticipant(userId)
            // 8.33 修复：删号会 bump memberRevision（含群主转让），但此前无广播，剩余成员残留成员列表
            val groupSnapshots = conversationParticipantRepo.groupMembershipSnapshotForDeletion(userId)
            val req = call.receiveJsonOr400<DeleteAccountRequest>() ?: return@delete
            if (req.password.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("请输入当前密码"))
                return@delete
            }
            val deactivation = userRepo.deleteAccount(userId, req.password)
            if (deactivation == null) {
                // 403：凭证错误，非 access token 失效
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("密码错误或账号已注销", code = "WRONG_PASSWORD"))
            } else {
                // 空会话级联删除的附件行已不在 DB；先清磁盘，再清仍挂在其他会话上的本人上传
                deactivation.orphanedAttachmentIds.forEach(BlobStore::delete)
                encryptedAttachmentRepo.deleteForUploader(userId).forEach(BlobStore::delete)
                postRepo.deleteAllPostsForAuthor(userId)
                com.maodouchat.server.service.FileStorageService.deletePostImagesForUser(userId)
                groupAvatarCandidates
                    .filterNot(groupMediaReferenceRepo::isAvatarUrlReferenced)
                    .forEach { url ->
                        com.maodouchat.server.service.FileStorageService.deleteGroupAvatarUrl(url)
                    }
                com.maodouchat.server.service.FileStorageService.deleteAvatarUrl(deactivation.avatarUrl, userId)
                // 与 logout-all / 改密码一致：吊销已签发的 access token（版本号）并清推送 token，
                // 否则注销后旧 JWT 在 TTL 内仍可调用 API；推送 token 不清则已注销设备仍收消息/来电。
                sessionService.revokeAllUserSessions(userId)
                disconnectUserSessions(userId, "账号已注销")
                // 8.33：注销后向各群剩余成员广播成员变更（含自动群主转让），客户端即时刷新成员列表
                groupSnapshots.forEach { (chatId, recipients) ->
                    val remaining = recipients.filter { it != userId }
                    if (remaining.isNotEmpty()) {
                        notifyGroupRevisionChanged(
                            queryRepository = conversationQueryRepo,
                            participantRepository = conversationParticipantRepo,
                            json = json,
                            chatId = chatId,
                            reason = "MEMBER_REMOVED",
                            actorId = userId,
                            targetUserId = userId,
                            recipientIds = remaining
                        )
                    }
                }
                call.respond(DeleteAccountResponse(deletedAt = deactivation.deletedAt))
            }
        }
    }
}
