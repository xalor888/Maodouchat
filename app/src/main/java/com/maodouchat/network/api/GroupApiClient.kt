package com.maodouchat.network.api

import com.maodouchat.BuildConfig
import com.maodouchat.util.toHexString
import com.maodouchat.network.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

internal object GroupApiClient : GroupApi {
private val json get() = ApiService.json
private val JSON_MEDIA get() = ApiService.JSON_MEDIA
private val ATTACHMENT_CHUNK_BYTES get() = ApiService.ATTACHMENT_CHUNK_BYTES
private val ATTACHMENT_CHUNK_MAX_ATTEMPTS get() = ApiService.ATTACHMENT_CHUNK_MAX_ATTEMPTS
private val ATTACHMENT_ID_REGEX get() = ApiService.ATTACHMENT_ID_REGEX
private val POST_IMAGE_FILENAME_REGEX get() = ApiService.POST_IMAGE_FILENAME_REGEX
private fun jsonBody(value: String) = value.toRequestBody(ApiService.JSON_MEDIA)
private suspend fun <T> send(request: Request, serializer: kotlinx.serialization.KSerializer<T>): Result<T> = ApiService.send(request, serializer)
private suspend fun sendUnit(request: Request): Result<Unit> = ApiService.sendUnit(request)
private suspend fun executeForText(request: Request, errorPrefix: String): Result<String> = ApiService.executeForText(request, errorPrefix)
private suspend fun executeStreamingWithRefresh(request: Request): Response = ApiService.executeStreamingWithRefresh(request)
private fun parseError(body: String): String? = ApiService.parseError(body)


override suspend fun addGroupMembers(token: String, chatId: String, memberIds: List<String>): Result<Unit> =
    sendUnit(Request.Builder().url("${ApiConfig.BASE_URL}/api/chats/$chatId/members").addHeader("Authorization", "Bearer $token").post(jsonBody(json.encodeToString(GroupMembersRequest.serializer(), GroupMembersRequest(memberIds)))).build())

override suspend fun removeGroupMember(token: String, chatId: String, memberId: String): Result<Unit> =
    sendUnit(Request.Builder().url("${ApiConfig.BASE_URL}/api/chats/$chatId/members/$memberId").addHeader("Authorization", "Bearer $token").delete().build())

override suspend fun transferGroupOwnership(token: String, chatId: String, memberId: String): Result<Unit> =
    sendUnit(
        Request.Builder()
            .url("${ApiConfig.BASE_URL}/api/chats/$chatId/members/$memberId/ownership")
            .addHeader("Authorization", "Bearer $token")
            .put(ByteArray(0).toRequestBody(null))
            .build()
    )

override suspend fun renameGroup(token: String, chatId: String, newName: String): Result<Unit> =
    sendUnit(Request.Builder().url("${ApiConfig.BASE_URL}/api/chats/$chatId/name").addHeader("Authorization", "Bearer $token").put(jsonBody(json.encodeToString(CreateChatRequest.serializer(), CreateChatRequest(emptyList(), true, newName)))).build())

override suspend fun getGroupMembers(token: String, chatId: String): Result<List<GroupMemberDto>> =
    send(Request.Builder().url("${ApiConfig.BASE_URL}/api/chats/$chatId/members").addHeader("Authorization", "Bearer $token").get().build(), ListSerializer(GroupMemberDto.serializer()))

override suspend fun getSenderKeyDistributionStatus(
    token: String,
    chatId: String,
    epoch: Long?,
    currentDeviceId: Int?): Result<SenderKeyDistributionStatusDto> {
    val query = buildList {
        epoch?.let { add("epoch=$it") }
        currentDeviceId?.let { add("currentDeviceId=$it") }
    }.joinToString("&").let { if (it.isBlank()) "" else "?$it" }
    return send(
        Request.Builder().url("${ApiConfig.BASE_URL}/api/chats/$chatId/sender-key-distributions$query").addHeader("Authorization", "Bearer $token").get().build(),
        SenderKeyDistributionStatusDto.serializer()
    )
}

override suspend fun updateMemberRole(token: String, chatId: String, memberId: String, role: String): Result<Unit> =
    sendUnit(Request.Builder().url("${ApiConfig.BASE_URL}/api/chats/$chatId/members/$memberId/role").addHeader("Authorization", "Bearer $token").put(jsonBody(json.encodeToString(UpdateMemberRoleRequest.serializer(), UpdateMemberRoleRequest(role)))).build())

override suspend fun updateGroupNickname(token: String, chatId: String, nickname: String): Result<Unit> =
    sendUnit(Request.Builder().url("${ApiConfig.BASE_URL}/api/chats/$chatId/members/me/nickname").addHeader("Authorization", "Bearer $token").put(jsonBody(json.encodeToString(UpdateGroupNicknameRequest.serializer(), UpdateGroupNicknameRequest(nickname)))).build())

override suspend fun updateMemberTitle(token: String, chatId: String, memberId: String, title: String): Result<Unit> =
    sendUnit(Request.Builder().url("${ApiConfig.BASE_URL}/api/chats/$chatId/members/$memberId/title").addHeader("Authorization", "Bearer $token").put(jsonBody(json.encodeToString(UpdateMemberTitleRequest.serializer(), UpdateMemberTitleRequest(title)))).build())

override suspend fun updateMemberMute(token: String, chatId: String, memberId: String, mutedUntil: Long): Result<Unit> =
    sendUnit(Request.Builder().url("${ApiConfig.BASE_URL}/api/chats/$chatId/members/$memberId/mute").addHeader("Authorization", "Bearer $token").put(jsonBody(json.encodeToString(UpdateMemberMuteRequest.serializer(), UpdateMemberMuteRequest(mutedUntil)))).build())

/** 0.99：全员静音（除群主/管理员；mutedUntil=0 解除全员）。 */

override suspend fun muteAllMembers(token: String, chatId: String, mutedUntil: Long): Result<Unit> =
    sendUnit(Request.Builder().url("${ApiConfig.BASE_URL}/api/chats/$chatId/mute-all").addHeader("Authorization", "Bearer $token").post(jsonBody(json.encodeToString(UpdateMemberMuteRequest.serializer(), UpdateMemberMuteRequest(mutedUntil)))).build())

override suspend fun updateGroupAnnouncement(token: String, chatId: String, announcement: String): Result<Unit> =
    sendUnit(Request.Builder().url("${ApiConfig.BASE_URL}/api/chats/$chatId/announcement").addHeader("Authorization", "Bearer $token").put(jsonBody(json.encodeToString(UpdateGroupAnnouncementRequest.serializer(), UpdateGroupAnnouncementRequest(announcement)))).build())

override suspend fun getOrCreateGroupInvite(token: String, chatId: String, rotate: Boolean, expiresInSeconds: Long, maxUses: Int): Result<GroupInviteResponse> =
    send(Request.Builder().url("${ApiConfig.BASE_URL}/api/chats/$chatId/invite-token").addHeader("Authorization", "Bearer $token").post(jsonBody(json.encodeToString(CreateGroupInviteRequest.serializer(), CreateGroupInviteRequest(rotate, expiresInSeconds, maxUses)))).build(), GroupInviteResponse.serializer())

override suspend fun getGroupAudit(token: String, chatId: String, limit: Int, offset: Int): Result<List<GroupAuditLogDto>> =
    // 8.64：支持 offset 分页（历史审计翻页）
    send(Request.Builder().url("${ApiConfig.BASE_URL}/api/chats/$chatId/audit?limit=${limit.coerceIn(1, 100)}&offset=${offset.coerceAtLeast(0)}").addHeader("Authorization", "Bearer $token").get().build(), ListSerializer(GroupAuditLogDto.serializer()))

override suspend fun joinGroupByInvite(token: String, inviteToken: String): Result<ChatDto> =
    send(Request.Builder().url("${ApiConfig.BASE_URL}/api/chats/join-by-invite").addHeader("Authorization", "Bearer $token").post(jsonBody(json.encodeToString(JoinGroupInviteRequest.serializer(), JoinGroupInviteRequest(inviteToken)))).build(), ChatDto.serializer())
}
