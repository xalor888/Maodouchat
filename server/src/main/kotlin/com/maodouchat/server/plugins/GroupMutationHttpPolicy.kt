package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond

/**
 * Boundary adapter between group-domain mutation outcomes and HTTP.
 *
 * Repository code must remain transport agnostic. Keeping this mapping outside
 * the giant routing file also makes it impossible for individual routes to
 * invent different status/code semantics for the same domain result.
 */
internal object GroupMutationHttpPolicy {
    fun response(result: com.maodouchat.server.repository.GroupMemberMutationResult): Pair<HttpStatusCode, ErrorResponse>? =
        when (result) {
            com.maodouchat.server.repository.GroupMemberMutationResult.UPDATED -> null
            com.maodouchat.server.repository.GroupMemberMutationResult.CHAT_NOT_FOUND,
            com.maodouchat.server.repository.GroupMemberMutationResult.NOT_GROUP ->
                HttpStatusCode.NotFound to ErrorResponse("群聊不存在", code = "GROUP_NOT_FOUND")
            com.maodouchat.server.repository.GroupMemberMutationResult.ACTOR_NOT_PARTICIPANT ->
                HttpStatusCode.Forbidden to ErrorResponse("操作者已不是群成员", code = "GROUP_ACTOR_NOT_MEMBER")
            com.maodouchat.server.repository.GroupMemberMutationResult.TARGET_NOT_PARTICIPANT ->
                HttpStatusCode.NotFound to ErrorResponse("该用户不是群成员", code = "GROUP_TARGET_NOT_MEMBER")
            com.maodouchat.server.repository.GroupMemberMutationResult.FORBIDDEN ->
                HttpStatusCode.Forbidden to ErrorResponse("当前角色无权执行该操作", code = "GROUP_PERMISSION_DENIED")
            com.maodouchat.server.repository.GroupMemberMutationResult.SELF_NOT_ALLOWED ->
                HttpStatusCode.BadRequest to ErrorResponse("不能对自己执行该操作", code = "GROUP_SELF_ACTION_NOT_ALLOWED")
            com.maodouchat.server.repository.GroupMemberMutationResult.OWNER_PROTECTED ->
                HttpStatusCode.Forbidden to ErrorResponse("不能对群主执行该操作", code = "GROUP_OWNER_PROTECTED")
            com.maodouchat.server.repository.GroupMemberMutationResult.PEER_ADMIN_PROTECTED ->
                HttpStatusCode.Forbidden to ErrorResponse("管理员不能操作其他管理员", code = "GROUP_ADMIN_PEER_PROTECTED")
            com.maodouchat.server.repository.GroupMemberMutationResult.MEMBER_LIMIT_EXCEEDED ->
                HttpStatusCode.Conflict to ErrorResponse("群成员数量已达上限", code = "GROUP_MEMBER_LIMIT_EXCEEDED")
            com.maodouchat.server.repository.GroupMemberMutationResult.USER_NOT_FOUND ->
                HttpStatusCode.NotFound to ErrorResponse("用户不存在", code = "GROUP_USER_NOT_FOUND")
            com.maodouchat.server.repository.GroupMemberMutationResult.BLOCKED ->
                HttpStatusCode.Forbidden to ErrorResponse("无法添加已屏蔽的用户", code = "GROUP_MEMBER_BLOCKED")
        }
}

internal suspend fun ApplicationCall.respondGroupMemberMutationFailure(
    result: com.maodouchat.server.repository.GroupMemberMutationResult?,
): Boolean {
    val mapped = result?.let(GroupMutationHttpPolicy::response) ?: return false
    respond(mapped.first, mapped.second)
    return true
}
