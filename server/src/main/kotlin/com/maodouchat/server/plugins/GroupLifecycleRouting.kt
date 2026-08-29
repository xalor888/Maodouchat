package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.model.UpdateMemberRoleRequest
import com.maodouchat.server.repository.GroupLifecycleService
import com.maodouchat.server.repository.UserRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** HTTP adapters for membership mutations. Domain work lives in GroupLifecycleService. */
internal suspend fun ApplicationCall.handleRemoveGroupMember(
    userRepo: UserRepository,
    groupLifecycleService: GroupLifecycleService,
    json: Json,
) {
    val actorId = principal<JWTPrincipal>()!!.payload.subject
    val chatId = parameters["chatId"].orEmpty()
    val targetUserId = parameters["memberId"].orEmpty()
    if (rejectIfSuspended(userRepo, actorId)) return

    val commit = groupLifecycleService.removeMember(chatId, actorId, targetUserId)
    if (respondGroupMemberMutationFailure(commit.result)) return
    notifyGroupRevisionChangedWithData(
        json = json,
        chatId = chatId,
        reason = "MEMBER_REMOVED",
        actorId = actorId,
        targetUserId = targetUserId,
        memberRevision = commit.memberRevisionAfter ?: 0L,
        recipientIds = commit.recipientsBefore,
    )
    respond(buildJsonObject { put("status", "ok") })
}

internal suspend fun ApplicationCall.handleUpdateGroupMemberRole(
    userRepo: UserRepository,
    groupLifecycleService: GroupLifecycleService,
    json: Json,
) {
    val actorId = principal<JWTPrincipal>()!!.payload.subject
    val chatId = parameters["chatId"].orEmpty()
    val targetUserId = parameters["memberId"].orEmpty()
    if (rejectIfSuspended(userRepo, actorId)) return
    val request = receiveBoundedText()?.let { parseJson<UpdateMemberRoleRequest>(it) }
    if (request == null) {
        respond(HttpStatusCode.BadRequest, ErrorResponse("参数无效"))
        return
    }
    if (request.role !in listOf("ADMIN", "MEMBER")) {
        respond(HttpStatusCode.BadRequest, ErrorResponse("角色只能是 ADMIN 或 MEMBER"))
        return
    }
    val commit = groupLifecycleService.updateRole(chatId, actorId, targetUserId, request.role)
    if (respondGroupMemberMutationFailure(commit.result)) return
    notifyGroupRevisionChangedWithData(
        json = json,
        chatId = chatId,
        reason = "ROLE_UPDATED",
        actorId = actorId,
        targetUserId = targetUserId,
        memberRevision = commit.memberRevisionAfter ?: 0L,
        recipientIds = commit.recipientsBefore,
    )
    respond(buildJsonObject { put("status", "ok") })
}

internal suspend fun ApplicationCall.handleTransferGroupOwnership(
    userRepo: UserRepository,
    groupLifecycleService: GroupLifecycleService,
    json: Json,
) {
    val actorId = principal<JWTPrincipal>()!!.payload.subject
    val chatId = parameters["chatId"].orEmpty()
    val targetUserId = parameters["memberId"].orEmpty()
    if (rejectIfSuspended(userRepo, actorId)) return

    val commit = groupLifecycleService.transferOwnership(chatId, actorId, targetUserId)
    when (commit.result) {
        com.maodouchat.server.repository.TransferOwnershipResult.TRANSFERRED -> {
            notifyGroupRevisionChangedWithData(
                json = json,
                chatId = chatId,
                reason = "OWNERSHIP_TRANSFERRED",
                actorId = actorId,
                targetUserId = targetUserId,
                memberRevision = commit.memberRevisionAfter ?: 0L,
                recipientIds = commit.recipientsBefore,
            )
            respond(buildJsonObject { put("status", "ok") })
        }
        com.maodouchat.server.repository.TransferOwnershipResult.CHAT_NOT_FOUND,
        com.maodouchat.server.repository.TransferOwnershipResult.NOT_GROUP ->
            respond(HttpStatusCode.NotFound, ErrorResponse("群聊不存在"))
        com.maodouchat.server.repository.TransferOwnershipResult.NOT_OWNER ->
            respond(HttpStatusCode.Forbidden, ErrorResponse("只有群主可以转让群主身份"))
        com.maodouchat.server.repository.TransferOwnershipResult.TARGET_NOT_PARTICIPANT ->
            respond(HttpStatusCode.NotFound, ErrorResponse("该用户不是群成员"))
        com.maodouchat.server.repository.TransferOwnershipResult.TARGET_DEACTIVATED ->
            respond(HttpStatusCode.Conflict, ErrorResponse("该账号已注销，不能转让群主"))
        com.maodouchat.server.repository.TransferOwnershipResult.SAME_USER ->
            respond(HttpStatusCode.BadRequest, ErrorResponse("不能将群主身份转让给自己"))
    }
}
