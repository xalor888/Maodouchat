package com.maodouchat.network.api

import com.maodouchat.network.*
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.KSerializer

// 群组域（群邀请/群投票）：从 ApiEndpointClients 按主题拆出，纯搬移。
internal object ApiGroupEndpoints {
private val json get() = ApiService.json
private suspend fun <T> send(request: Request, serializer: kotlinx.serialization.KSerializer<T>): Result<T> = ApiService.send(request, serializer)
private suspend fun sendUnit(request: Request): Result<Unit> = ApiService.sendUnit(request)
private suspend fun executeForText(request: Request, errorPrefix: String): Result<String> = ApiService.executeForText(request, errorPrefix)

// ─── 9.3xx：群邀请同意流程 ─────────────────

suspend fun getGroupInvitations(token: String): Result<List<GroupInvitationDto>> =
    send(Request.Builder().url("${ApiConfig.BASE_URL}/api/group-invitations").addHeader("Authorization", "Bearer $token").get().build(), ListSerializer(GroupInvitationDto.serializer()))

suspend fun acceptGroupInvitation(token: String, inviteId: String): Result<GroupInviteAcceptResponse> =
    send(Request.Builder().url("${ApiConfig.BASE_URL}/api/group-invitations/$inviteId/accept").addHeader("Authorization", "Bearer $token").post(ByteArray(0).toRequestBody(null)).build(), GroupInviteAcceptResponse.serializer())

suspend fun declineGroupInvitation(token: String, inviteId: String): Result<GroupInviteAcceptResponse> =
    send(Request.Builder().url("${ApiConfig.BASE_URL}/api/group-invitations/$inviteId/decline").addHeader("Authorization", "Bearer $token").post(ByteArray(0).toRequestBody(null)).build(), GroupInviteAcceptResponse.serializer())

suspend fun cancelGroupInvitation(token: String, inviteId: String): Result<Unit> =
    sendUnit(Request.Builder().url("${ApiConfig.BASE_URL}/api/group-invitations/$inviteId").addHeader("Authorization", "Bearer $token").delete().build())

suspend fun getChatGroupInvitations(token: String, chatId: String): Result<List<GroupInvitationDto>> =
    send(Request.Builder().url("${ApiConfig.BASE_URL}/api/chats/$chatId/invitations").addHeader("Authorization", "Bearer $token").get().build(), ListSerializer(GroupInvitationDto.serializer()))

suspend fun createGroupPoll(
    token: String,
    chatId: String,
    question: String,
    options: List<String>,
    multi: Boolean,
    anonymous: Boolean,
    closesAt: Long?): Result<String> {
    val body = buildString {
        append("{")
        append("\"question\":"); append(org.json.JSONObject.quote(question)); append(',')
        append("\"options\":[")
        append(options.joinToString(",") { org.json.JSONObject.quote(it) })
        append("],")
        append("\"multi\":"); append(multi); append(',')
        append("\"anonymous\":"); append(anonymous)
        if (closesAt != null) { append(",\"closesAt\":"); append(closesAt) }
        append("}")
    }
    val req = Request.Builder()
        .url("${ApiConfig.BASE_URL}/api/chats/$chatId/polls")
        .header("Authorization", "Bearer $token")
        .post(body.toRequestBody("application/json".toMediaType()))
        .build()
    return executeForText(req, "poll_create")
}

suspend fun voteGroupPoll(token: String, pollId: String, optionIndexes: List<Int>): Result<String> {
    val body = """{"optionIndexes":[${optionIndexes.joinToString(",")}]}"""
    val req = Request.Builder()
        .url("${ApiConfig.BASE_URL}/api/polls/$pollId/vote")
        .header("Authorization", "Bearer $token")
        .post(body.toRequestBody("application/json".toMediaType()))
        .build()
    return executeForText(req, "poll_vote")
}

suspend fun getGroupPoll(token: String, pollId: String): Result<String> {
    val req = Request.Builder()
        .url("${ApiConfig.BASE_URL}/api/polls/$pollId")
        .header("Authorization", "Bearer $token")
        .get()
        .build()
    return executeForText(req, "poll_get")
}
}
