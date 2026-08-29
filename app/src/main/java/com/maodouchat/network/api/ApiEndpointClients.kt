package com.maodouchat.network.api

import com.maodouchat.BuildConfig
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

internal object ApiEndpointClients : ApiSurface {
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
private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }


override suspend fun getSealedSenderCertificate(token: String, deviceId: Int): Result<String> = executeForText(
    Request.Builder()
        .url("${ApiConfig.BASE_URL}/api/e2ee/sealed-sender/certificate?deviceId=$deviceId")
        .header("Authorization", "Bearer $token")
        .get()
        .build(),
    "sealed_sender_cert"
)

override suspend fun getPublicStatus(): Result<String> = executeForText(
    Request.Builder()
        .url("${ApiConfig.BASE_URL}/api/public/status")
        .get()
        .build(),
    "public_status"
)

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

override suspend fun getDevices(token: String, userId: String, currentDeviceId: Int?): Result<List<DeviceInfoDto>> {
    val suffix = currentDeviceId?.let { "?currentDeviceId=$it" }.orEmpty()
    return send(
        Request.Builder().url("${ApiConfig.BASE_URL}/api/keys/$userId/devices$suffix").addHeader("Authorization", "Bearer $token").get().build(),
        ListSerializer(DeviceInfoDto.serializer())
    )
}

/** Signal 密钥包上传：走 executeWithRefresh，冷启动 JWT 过期时先 refresh */

override suspend fun uploadKeys(token: String, request: UploadKeysRequest): Result<Unit> =
    sendUnit(
        Request.Builder()
            .url("${ApiConfig.BASE_URL}/api/keys/upload")
            .addHeader("Authorization", "Bearer $token")
            .post(jsonBody(json.encodeToString(UploadKeysRequest.serializer(), request)))
            .build()
    )

override suspend fun getPreKeyBundle(token: String, userId: String): Result<PreKeyBundleDto> =
    send(
        Request.Builder()
            .url("${ApiConfig.BASE_URL}/api/keys/$userId/prekey-bundle")
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build(),
        PreKeyBundleDto.serializer()
    )

override suspend fun getDevicePreKeyBundle(token: String, userId: String, deviceId: Int): Result<DevicePreKeyBundleDto> =
    send(
        Request.Builder()
            .url("${ApiConfig.BASE_URL}/api/keys/$userId/devices/$deviceId/prekey-bundle")
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build(),
        DevicePreKeyBundleDto.serializer()
    )

override suspend fun getDevicePreKeyBundles(token: String, userId: String): Result<List<DevicePreKeyBundleDto>> =
    send(
        Request.Builder()
            .url("${ApiConfig.BASE_URL}/api/keys/$userId/prekey-bundles")
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build(),
        ListSerializer(DevicePreKeyBundleDto.serializer())
    )

override suspend fun removeMyDevice(token: String, deviceId: Int): Result<Unit> =
    sendUnit(Request.Builder().url("${ApiConfig.BASE_URL}/api/keys/devices/$deviceId").addHeader("Authorization", "Bearer $token").delete().build())

override suspend fun renameMyDevice(token: String, deviceId: Int, deviceName: String): Result<Unit> =
    sendUnit(Request.Builder().url("${ApiConfig.BASE_URL}/api/keys/devices/$deviceId/name").addHeader("Authorization", "Bearer $token").put(jsonBody(json.encodeToString(UpdateDeviceNameRequest.serializer(), UpdateDeviceNameRequest(deviceName)))).build())

override suspend fun confirmMyDevice(token: String, deviceId: Int, approverDeviceId: Int, signature: String): Result<Unit> =
    sendUnit(Request.Builder().url("${ApiConfig.BASE_URL}/api/keys/devices/$deviceId/confirm").addHeader("Authorization", "Bearer $token").post(jsonBody(json.encodeToString(ConfirmDeviceRequest.serializer(), ConfirmDeviceRequest(approverDeviceId, signature)))).build())

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

override suspend fun deleteChat(token: String, chatId: String): Result<Unit> =
    sendUnit(Request.Builder().url("${ApiConfig.BASE_URL}/api/chats/$chatId").addHeader("Authorization", "Bearer $token").delete().build())

override suspend fun toggleStarMessage(token: String, messageId: String): Result<StarMessageResponse> =
    send(Request.Builder().url("${ApiConfig.BASE_URL}/api/messages/$messageId/star").addHeader("Authorization", "Bearer $token").post(ByteArray(0).toRequestBody(null)).build(), StarMessageResponse.serializer())

override suspend fun getPinnedMessages(token: String, chatId: String): Result<PinnedMessagesListResponse> =
    send(
        Request.Builder()
            .url("${ApiConfig.BASE_URL}/api/chats/$chatId/pins")
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build(),
        PinnedMessagesListResponse.serializer()
    )

override suspend fun togglePinnedMessage(token: String, chatId: String, messageId: String): Result<TogglePinResponse> =
    send(
        Request.Builder()
            .url("${ApiConfig.BASE_URL}/api/chats/$chatId/messages/$messageId/pin")
            .addHeader("Authorization", "Bearer $token")
            .post(ByteArray(0).toRequestBody(null))
            .build(),
        TogglePinResponse.serializer()
    )

override suspend fun getStarredMessages(token: String, chatId: String?): Result<List<StarredMessageRefDto>> {
    val suffix = chatId?.takeIf { it.isNotBlank() }?.let { "?chatId=${java.net.URLEncoder.encode(it, "UTF-8")}" }.orEmpty()
    return send(Request.Builder().url("${ApiConfig.BASE_URL}/api/messages/starred$suffix").addHeader("Authorization", "Bearer $token").get().build(), ListSerializer(StarredMessageRefDto.serializer()))
}

override suspend fun getUsers(token: String, limit: Int, offset: Int): Result<List<UserDto>> =
    send(
        Request.Builder()
            .url(
                "${ApiConfig.BASE_URL}/api/users" +
                    "?limit=${limit.coerceIn(1, 100)}" +
                    "&offset=${offset.coerceAtLeast(0)}"
            )
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build(),
        ListSerializer(UserDto.serializer())
    )

/**
 * 翻页拉取全部可搜索用户（群加人候选等场景）。服务端每页最多 100 人，
 * 客户端按页循环直到返回空或达到 [maxUsers] 上限，避免群成员列表只显示前 30 人。
 */

override suspend fun getAllSearchableUsers(
    token: String,
    pageSize: Int,
    maxUsers: Int): Result<List<UserDto>> {
    if (token.isBlank()) return Result.failure(IllegalArgumentException("token_missing"))
    val safePageSize = pageSize.coerceIn(1, 100)
    val safeMax = maxUsers.coerceAtLeast(1)
    val collected = mutableListOf<UserDto>()
    var offset = 0
    while (offset < safeMax) {
        val page = getUsers(token, limit = safePageSize, offset = offset)
            .getOrElse { return Result.failure(it) }
        if (page.isEmpty()) break
        collected += page
        if (collected.size >= safeMax) break
        offset += safePageSize
    }
    return Result.success(collected.take(safeMax))
}

override suspend fun getUser(token: String, userId: String): Result<UserDto> =
    send(
        Request.Builder()
            .url("${ApiConfig.BASE_URL}/api/users/${java.net.URLEncoder.encode(userId, Charsets.UTF_8.name())}")
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build(),
        UserDto.serializer()
    )

override suspend fun getNearbyLocationStatus(token: String): Result<NearbyLocationStatusResponse> =
    send(Request.Builder().url("${ApiConfig.BASE_URL}/api/users/nearby-location").addHeader("Authorization", "Bearer $token").get().build(), NearbyLocationStatusResponse.serializer())

override suspend fun updateNearbyLocation(token: String, latitude: Double, longitude: Double): Result<NearbyLocationStatusResponse> =
    send(Request.Builder().url("${ApiConfig.BASE_URL}/api/users/nearby-location").addHeader("Authorization", "Bearer $token").put(jsonBody(json.encodeToString(UpdateNearbyLocationRequest.serializer(), UpdateNearbyLocationRequest(latitude, longitude)))).build(), NearbyLocationStatusResponse.serializer())

override suspend fun stopNearbyLocationSharing(token: String): Result<NearbyLocationStatusResponse> =
    send(Request.Builder().url("${ApiConfig.BASE_URL}/api/users/nearby-location").addHeader("Authorization", "Bearer $token").delete().build(), NearbyLocationStatusResponse.serializer())

override suspend fun getNearbyUsers(token: String, radiusKm: Double, limit: Int): Result<List<NearbyUserResponse>> =
    send(Request.Builder().url("${ApiConfig.BASE_URL}/api/users/nearby?radiusKm=${radiusKm.coerceIn(0.5, 20.0)}&limit=${limit.coerceIn(1, 100)}").addHeader("Authorization", "Bearer $token").get().build(), ListSerializer(NearbyUserResponse.serializer()))

override suspend fun searchUsers(token: String, query: String, limit: Int): Result<List<UserDto>> =
    send(Request.Builder().url("${ApiConfig.BASE_URL}/api/users/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}&limit=$limit").addHeader("Authorization", "Bearer $token").get().build(), ListSerializer(UserDto.serializer()))

override suspend fun getCurrentUser(token: String): Result<UserDto> =
    send(Request.Builder().url("${ApiConfig.BASE_URL}/api/users/me").addHeader("Authorization", "Bearer $token").get().build(), UserDto.serializer())

/** 获取当前用户公开信息（含用户名） */

override suspend fun getCurrentUserPublic(token: String): Result<CurrentUserPublicResponse> =
    send(Request.Builder().url("${ApiConfig.BASE_URL}/api/users/me/public").addHeader("Authorization", "Bearer $token").get().build(), CurrentUserPublicResponse.serializer())

/** 获取公开个人主页信息（无需认证） */

override suspend fun getPublicProfile(username: String): Result<PublicProfileResponse> {
    val url = "${ApiConfig.BASE_URL}/api/public/profile/${java.net.URLEncoder.encode(username, "UTF-8")}"
    return send(Request.Builder().url(url).get().build(), PublicProfileResponse.serializer())
}

/** 设置用户名 */

override suspend fun setUsername(token: String, username: String): Result<SetUsernameResponse> =
    send(Request.Builder().url("${ApiConfig.BASE_URL}/api/users/me/username").addHeader("Authorization", "Bearer $token")
        .put(jsonBody(json.encodeToString(SetUsernameRequest.serializer(), SetUsernameRequest(username)))).build(),
        SetUsernameResponse.serializer())

/** 清除用户名 */

override suspend fun clearUsername(token: String): Result<Unit> =
    sendUnit(Request.Builder().url("${ApiConfig.BASE_URL}/api/users/me/username").addHeader("Authorization", "Bearer $token").delete().build())

/** 高级搜索 */

override suspend fun getPrivacy(token: String): Result<UserPrivacyDto> =
    send(Request.Builder().url("${ApiConfig.BASE_URL}/api/users/privacy").addHeader("Authorization", "Bearer $token").get().build(), UserPrivacyDto.serializer())

override suspend fun updatePrivacy(
    token: String,
    showOnline: Boolean?,
    showStatus: Boolean?,
    searchable: Boolean?,
    defaultPostVisibility: String?,
    onlineVisibility: String?): Result<UserPrivacyDto> =
    send(Request.Builder().url("${ApiConfig.BASE_URL}/api/users/privacy").addHeader("Authorization", "Bearer $token").put(jsonBody(json.encodeToString(UpdatePrivacyRequest.serializer(), UpdatePrivacyRequest(showOnline, showStatus, searchable, defaultPostVisibility, onlineVisibility)))).build(), UserPrivacyDto.serializer())

override suspend fun getPublicUpdates(officialBaseUrl: String): Result<PublicUpdatesDto> =
    send(
        Request.Builder().url("${officialBaseUrl.trimEnd('/')}/api/public/updates").get().build(),
        PublicUpdatesDto.serializer()
    )

override suspend fun getNotificationSettings(token: String): Result<NotificationSettingsResponse> =
    send(Request.Builder().url("${ApiConfig.BASE_URL}/api/users/notification-settings").addHeader("Authorization", "Bearer $token").get().build(), NotificationSettingsResponse.serializer())

override suspend fun updateNotificationSettings(token: String, request: NotificationSettingsRequest): Result<NotificationSettingsResponse> =
    send(Request.Builder().url("${ApiConfig.BASE_URL}/api/users/notification-settings").addHeader("Authorization", "Bearer $token").put(jsonBody(json.encodeToString(NotificationSettingsRequest.serializer(), request))).build(), NotificationSettingsResponse.serializer())

override suspend fun registerPushToken(
    token: String,
    deviceId: String,
    pushToken: String,
    timezoneOffsetMinutes: Int
): Result<Unit> = sendUnit(
    Request.Builder()
        .url("${ApiConfig.BASE_URL}/api/users/push-tokens")
        .addHeader("Authorization", "Bearer $token")
        .post(jsonBody(json.encodeToString(
            RegisterPushTokenRequest.serializer(),
            RegisterPushTokenRequest(deviceId, pushToken, timezoneOffsetMinutes = timezoneOffsetMinutes)
        )))
        .build()
)

override suspend fun removePushToken(token: String, deviceId: String): Result<Unit> = sendUnit(
    Request.Builder()
        .url("${ApiConfig.BASE_URL}/api/users/push-tokens")
        .addHeader("Authorization", "Bearer $token")
        .delete(jsonBody(json.encodeToString(RemovePushTokenRequest.serializer(), RemovePushTokenRequest(deviceId))))
        .build()
)

/** WebRTC 信令 REST：走 executeWithRefresh，长通话 JWT 过期后仍可挂断/补发 */

override suspend fun sendSignaling(
    token: String,
    toUserId: String,
    type: String,
    payload: String,
    callId: String,
    groupId: String,
    groupMemberIds: List<String>,
    groupInvite: Boolean): Result<Unit> = sendUnit(
    Request.Builder()
        .url("${ApiConfig.BASE_URL}/api/signaling/send")
        .addHeader("Authorization", "Bearer $token")
        .post(
            jsonBody(
                json.encodeToString(
                    SignalingSendRequest.serializer(),
                    SignalingSendRequest(
                        toUserId = toUserId,
                        type = type,
                        payload = payload,
                        callId = callId,
                        groupId = groupId,
                        groupMemberIds = groupMemberIds,
                        groupInvite = groupInvite
                    )
                )
            )
        )
        .build()
)

override suspend fun hangUpCall(
    token: String,
    toUserId: String,
    callId: String,
    groupId: String,
    groupMemberIds: List<String>): Result<Unit> = sendUnit(
    Request.Builder()
        .url("${ApiConfig.BASE_URL}/api/signaling/hangup")
        .addHeader("Authorization", "Bearer $token")
        .post(
            jsonBody(
                json.encodeToString(
                    SignalingSendRequest.serializer(),
                    SignalingSendRequest(
                        toUserId = toUserId,
                        type = "hang-up",
                        payload = "",
                        callId = callId,
                        groupId = groupId,
                        groupMemberIds = groupMemberIds
                    )
                )
            )
        )
        .build()
)

/** 轮询待处理信令（含 offersOnly 冷启动）；走 executeWithRefresh */

override suspend fun getPendingSignaling(token: String, offersOnly: Boolean): Result<List<SignalMessageDto>> {
    val query = if (offersOnly) "?offersOnly=true" else ""
    return send(
        Request.Builder()
            .url("${ApiConfig.BASE_URL}/api/signaling/pending$query")
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build(),
        ListSerializer(SignalMessageDto.serializer())
    )
}

/** TURN/STUN 配置；走 executeWithRefresh，避免长通话 JWT 过期后 ICE 刷新失败 */

override suspend fun getIceConfig(token: String): Result<IceConfigDto> =
    send(
        Request.Builder()
            .url("${ApiConfig.BASE_URL}/api/calls/ice-config")
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build(),
        IceConfigDto.serializer()
    )

override suspend fun blockUser(token: String, userId: String): Result<Unit> =
    sendUnit(Request.Builder().url("${ApiConfig.BASE_URL}/api/users/block/$userId").addHeader("Authorization", "Bearer $token").post(ByteArray(0).toRequestBody(null)).build())

override suspend fun unblockUser(token: String, userId: String): Result<Unit> =
    sendUnit(Request.Builder().url("${ApiConfig.BASE_URL}/api/users/block/$userId").addHeader("Authorization", "Bearer $token").delete().build())

override suspend fun getBlockedUsers(token: String): Result<List<String>> =
    send(Request.Builder().url("${ApiConfig.BASE_URL}/api/users/blocks").addHeader("Authorization", "Bearer $token").get().build(), ListSerializer(String.serializer()))

override suspend fun getBlockedUserDetails(token: String): Result<List<UserDto>> =
    send(Request.Builder().url("${ApiConfig.BASE_URL}/api/users/blocks/details").addHeader("Authorization", "Bearer $token").get().build(), ListSerializer(UserDto.serializer()))

// ─── 发现页 / 动态 ─────────────────────────

override suspend fun getPosts(
    token: String,
    limit: Int,
    before: Long?,
    beforeId: String?,
    authorId: String?): Result<List<PostDto>> {
    val params = buildList {
        add("limit=$limit")
        before?.let { add("before=$it") }
        beforeId?.takeIf { before != null }?.let {
            add("beforeId=${java.net.URLEncoder.encode(it, Charsets.UTF_8.name())}")
        }
        authorId?.let {
            add("authorId=${java.net.URLEncoder.encode(it, Charsets.UTF_8.name())}")
        }
    }.joinToString("&")
    return send(Request.Builder().url("${ApiConfig.BASE_URL}/api/posts?$params").addHeader("Authorization", "Bearer $token").get().build(), ListSerializer(PostDto.serializer()))
}

override suspend fun createPost(token: String, content: String, imageUrls: List<String>, visibility: String?): Result<PostDto> =
    send(Request.Builder().url("${ApiConfig.BASE_URL}/api/posts").addHeader("Authorization", "Bearer $token").post(jsonBody(json.encodeToString(CreatePostRequest.serializer(), CreatePostRequest(content, imageUrls, visibility, visibility == null)))).build(), PostDto.serializer())

override suspend fun getPost(token: String, postId: String): Result<PostDto> =
    send(
        Request.Builder()
            .url("${ApiConfig.BASE_URL}/api/posts/${java.net.URLEncoder.encode(postId, Charsets.UTF_8.name())}")
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build(),
        PostDto.serializer()
    )

override suspend fun editPost(token: String, postId: String, content: String, visibility: String?): Result<PostDto> =
    // 8.58：postId 统一 URL 编码（与 getPost 一致，防保留字符路由错乱）
    send(Request.Builder().url("${ApiConfig.BASE_URL}/api/posts/${java.net.URLEncoder.encode(postId, Charsets.UTF_8.name())}").addHeader("Authorization", "Bearer $token").put(jsonBody(json.encodeToString(EditPostRequest.serializer(), EditPostRequest(content, visibility)))).build(), PostDto.serializer())

override suspend fun deletePost(token: String, postId: String): Result<Unit> =
    sendUnit(Request.Builder().url("${ApiConfig.BASE_URL}/api/posts/${java.net.URLEncoder.encode(postId, Charsets.UTF_8.name())}").addHeader("Authorization", "Bearer $token").delete().build())

override suspend fun likePost(token: String, postId: String): Result<PostDto> =
    send(Request.Builder().url("${ApiConfig.BASE_URL}/api/posts/${java.net.URLEncoder.encode(postId, Charsets.UTF_8.name())}/like").addHeader("Authorization", "Bearer $token").post(ByteArray(0).toRequestBody(null)).build(), PostDto.serializer())

override suspend fun unlikePost(token: String, postId: String): Result<PostDto> =
    send(Request.Builder().url("${ApiConfig.BASE_URL}/api/posts/${java.net.URLEncoder.encode(postId, Charsets.UTF_8.name())}/like").addHeader("Authorization", "Bearer $token").delete().build(), PostDto.serializer())

override suspend fun getPostComments(
    token: String,
    postId: String,
    limit: Int,
    before: Long?,
    beforeId: String?): Result<List<PostCommentDto>> {
    val params = buildList {
        add("limit=${limit.coerceIn(1, 100)}")
        before?.let { add("before=$it") }
        beforeId?.takeIf { before != null }?.let {
            add("beforeId=${java.net.URLEncoder.encode(it, Charsets.UTF_8.name())}")
        }
    }.joinToString("&")
    return send(
        Request.Builder()
            .url("${ApiConfig.BASE_URL}/api/posts/${java.net.URLEncoder.encode(postId, Charsets.UTF_8.name())}/comments?$params")
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build(),
        ListSerializer(PostCommentDto.serializer())
    )
}

override suspend fun createPostComment(token: String, postId: String, content: String, replyToId: String?): Result<PostCommentDto> =
    // 8.58：postId 统一 URL 编码（与 getPost 一致，防保留字符路由错乱）
    send(Request.Builder().url("${ApiConfig.BASE_URL}/api/posts/${java.net.URLEncoder.encode(postId, Charsets.UTF_8.name())}/comments").addHeader("Authorization", "Bearer $token").post(jsonBody(json.encodeToString(CreateCommentRequest.serializer(), CreateCommentRequest(content, replyToId)))).build(), PostCommentDto.serializer())

override suspend fun editPostComment(token: String, postId: String, commentId: String, content: String): Result<PostCommentDto> =
    send(
        Request.Builder()
            .url(
                "${ApiConfig.BASE_URL}/api/posts/${java.net.URLEncoder.encode(postId, Charsets.UTF_8.name())}" +
                    "/comments/${java.net.URLEncoder.encode(commentId, Charsets.UTF_8.name())}"
            )
            .addHeader("Authorization", "Bearer $token")
            .put(jsonBody(json.encodeToString(UpdateCommentRequest.serializer(), UpdateCommentRequest(content))))
            .build(),
        PostCommentDto.serializer()
    )

/** 1.93：动态点赞者列表。 */

override suspend fun getPostLikers(token: String, postId: String, limit: Int): Result<PostLikersResponse> =
    send(
        Request.Builder()
            .url("${ApiConfig.BASE_URL}/api/posts/${java.net.URLEncoder.encode(postId, Charsets.UTF_8.name())}/likers?limit=${limit.coerceIn(1, 100)}")
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build(),
        PostLikersResponse.serializer()
    )

/** 1.00：删除自己的评论。 */

override suspend fun deleteComment(token: String, postId: String, commentId: String): Result<Unit> =
    sendUnit(
        Request.Builder()
            .url("${ApiConfig.BASE_URL}/api/posts/${java.net.URLEncoder.encode(postId, Charsets.UTF_8.name())}/comments/${java.net.URLEncoder.encode(commentId, Charsets.UTF_8.name())}")
            .addHeader("Authorization", "Bearer $token")
            .delete()
            .build()
    )

/** 1.52：点赞评论。 */

override suspend fun likeComment(token: String, postId: String, commentId: String): Result<CommentLikeResponse> =
    send(
        Request.Builder()
            .url("${ApiConfig.BASE_URL}/api/posts/${java.net.URLEncoder.encode(postId, Charsets.UTF_8.name())}/comments/${java.net.URLEncoder.encode(commentId, Charsets.UTF_8.name())}/like")
            .addHeader("Authorization", "Bearer $token")
            .post(ByteArray(0).toRequestBody(null))
            .build(),
        CommentLikeResponse.serializer()
    )

/** 1.52：取消点赞评论。 */

override suspend fun unlikeComment(token: String, postId: String, commentId: String): Result<CommentLikeResponse> =
    send(
        Request.Builder()
            .url("${ApiConfig.BASE_URL}/api/posts/${java.net.URLEncoder.encode(postId, Charsets.UTF_8.name())}/comments/${java.net.URLEncoder.encode(commentId, Charsets.UTF_8.name())}/like")
            .addHeader("Authorization", "Bearer $token")
            .delete()
            .build(),
        CommentLikeResponse.serializer()
    )

override suspend fun sendFriendRequest(token: String, toUserId: String, message: String): Result<FriendRequestDto> =
    send(Request.Builder().url("${ApiConfig.BASE_URL}/api/friends/requests").addHeader("Authorization", "Bearer $token").post(jsonBody(json.encodeToString(SendFriendRequestBody.serializer(), SendFriendRequestBody(toUserId, message)))).build(), FriendRequestDto.serializer())

override suspend fun getIncomingFriendRequests(token: String, status: String, limit: Int): Result<List<FriendRequestDto>> =
    send(Request.Builder().url("${ApiConfig.BASE_URL}/api/friends/requests/incoming?status=$status&limit=${limit.coerceIn(1, 100)}").addHeader("Authorization", "Bearer $token").get().build(), ListSerializer(FriendRequestDto.serializer()))

override suspend fun getOutgoingFriendRequests(token: String, status: String, limit: Int): Result<List<FriendRequestDto>> =
    send(Request.Builder().url("${ApiConfig.BASE_URL}/api/friends/requests/outgoing?status=$status&limit=${limit.coerceIn(1, 100)}").addHeader("Authorization", "Bearer $token").get().build(), ListSerializer(FriendRequestDto.serializer()))

override suspend fun acceptFriendRequest(token: String, requestId: String): Result<FriendRequestDto> =
    send(Request.Builder().url("${ApiConfig.BASE_URL}/api/friends/requests/$requestId/accept").addHeader("Authorization", "Bearer $token").post(ByteArray(0).toRequestBody(null)).build(), FriendRequestDto.serializer())

override suspend fun rejectFriendRequest(token: String, requestId: String): Result<FriendRequestDto> =
    send(Request.Builder().url("${ApiConfig.BASE_URL}/api/friends/requests/$requestId/reject").addHeader("Authorization", "Bearer $token").post(ByteArray(0).toRequestBody(null)).build(), FriendRequestDto.serializer())

override suspend fun cancelFriendRequest(token: String, requestId: String): Result<FriendRequestDto> =
    send(Request.Builder().url("${ApiConfig.BASE_URL}/api/friends/requests/$requestId/cancel").addHeader("Authorization", "Bearer $token").post(ByteArray(0).toRequestBody(null)).build(), FriendRequestDto.serializer())

override suspend fun getFriends(token: String): Result<List<UserDto>> =
    send(Request.Builder().url("${ApiConfig.BASE_URL}/api/friends").addHeader("Authorization", "Bearer $token").get().build(), ListSerializer(UserDto.serializer()))

override suspend fun removeFriend(token: String, friendId: String): Result<Unit> =
    sendUnit(Request.Builder().url("${ApiConfig.BASE_URL}/api/friends/$friendId").addHeader("Authorization", "Bearer $token").delete().build())

// ─── 9.3xx：群邀请同意流程 ─────────────────

override suspend fun getGroupInvitations(token: String): Result<List<GroupInvitationDto>> =
    send(Request.Builder().url("${ApiConfig.BASE_URL}/api/group-invitations").addHeader("Authorization", "Bearer $token").get().build(), ListSerializer(GroupInvitationDto.serializer()))

override suspend fun acceptGroupInvitation(token: String, inviteId: String): Result<GroupInviteAcceptResponse> =
    send(Request.Builder().url("${ApiConfig.BASE_URL}/api/group-invitations/$inviteId/accept").addHeader("Authorization", "Bearer $token").post(ByteArray(0).toRequestBody(null)).build(), GroupInviteAcceptResponse.serializer())

override suspend fun declineGroupInvitation(token: String, inviteId: String): Result<GroupInviteAcceptResponse> =
    send(Request.Builder().url("${ApiConfig.BASE_URL}/api/group-invitations/$inviteId/decline").addHeader("Authorization", "Bearer $token").post(ByteArray(0).toRequestBody(null)).build(), GroupInviteAcceptResponse.serializer())

override suspend fun cancelGroupInvitation(token: String, inviteId: String): Result<Unit> =
    sendUnit(Request.Builder().url("${ApiConfig.BASE_URL}/api/group-invitations/$inviteId").addHeader("Authorization", "Bearer $token").delete().build())

override suspend fun getChatGroupInvitations(token: String, chatId: String): Result<List<GroupInvitationDto>> =
    send(Request.Builder().url("${ApiConfig.BASE_URL}/api/chats/$chatId/invitations").addHeader("Authorization", "Bearer $token").get().build(), ListSerializer(GroupInvitationDto.serializer()))

// ─── 会话文件夹云同步 ─────────────────

override suspend fun getChatFolders(token: String): Result<ChatFoldersSyncResponse> =
    send(Request.Builder().url("${ApiConfig.BASE_URL}/api/chat-folders").addHeader("Authorization", "Bearer $token").get().build(), ChatFoldersSyncResponse.serializer())

override suspend fun putChatFolders(token: String, folders: List<ChatFolderDto>): Result<ChatFoldersSyncResponse> =
    send(Request.Builder().url("${ApiConfig.BASE_URL}/api/chat-folders").addHeader("Authorization", "Bearer $token").put(jsonBody(json.encodeToString(ChatFoldersSyncRequest.serializer(), ChatFoldersSyncRequest(folders)))).build(), ChatFoldersSyncResponse.serializer())

// ─── 客户端外观/列表偏好云同步 ────────────

override suspend fun getClientPrefs(token: String): Result<ClientPrefsDto> =
    send(Request.Builder().url("${ApiConfig.BASE_URL}/api/client-prefs").addHeader("Authorization", "Bearer $token").get().build(), ClientPrefsDto.serializer())

override suspend fun putClientPrefs(token: String, request: ClientPrefsUpdateRequest): Result<ClientPrefsDto> =
    send(Request.Builder().url("${ApiConfig.BASE_URL}/api/client-prefs").addHeader("Authorization", "Bearer $token").put(jsonBody(json.encodeToString(ClientPrefsUpdateRequest.serializer(), request))).build(), ClientPrefsDto.serializer())

// ─── 头像上传 + 修改资料 ─────────────────

override suspend fun createReport(
    token: String,
    targetType: String,
    targetId: String,
    chatId: String?,
    messageId: String?,
    reason: String,
    description: String?): Result<ReportResponse> =
    send(
        Request.Builder()
            .url("${ApiConfig.BASE_URL}/api/reports")
            .addHeader("Authorization", "Bearer $token")
            .post(jsonBody(json.encodeToString(CreateReportRequest.serializer(), CreateReportRequest(targetType, targetId, chatId, messageId, reason, description))))
            .build(),
        ReportResponse.serializer()
    )

override suspend fun getMyReports(token: String, limit: Int): Result<List<ReportResponse>> =
    send(Request.Builder().url("${ApiConfig.BASE_URL}/api/reports/mine?limit=${limit.coerceIn(1, 100)}").addHeader("Authorization", "Bearer $token").get().build(), ListSerializer(ReportResponse.serializer()))

override suspend fun getAdminReports(token: String, status: String?, limit: Int): Result<List<ReportResponse>> {
    val statusPart = status?.takeIf { it.isNotBlank() && it != "ALL" }?.let { "&status=$it" } ?: ""
    return send(Request.Builder().url("${ApiConfig.BASE_URL}/api/moderator/reports?limit=${limit.coerceIn(1, 200)}$statusPart").addHeader("Authorization", "Bearer $token").get().build(), ListSerializer(ReportResponse.serializer()))
}

override suspend fun updateReportStatus(token: String, reportId: String, status: String, resolutionNote: String?): Result<ReportResponse> =
    send(Request.Builder().url("${ApiConfig.BASE_URL}/api/moderator/reports/$reportId/status").addHeader("Authorization", "Bearer $token").put(jsonBody(json.encodeToString(UpdateReportStatusRequest.serializer(), UpdateReportStatusRequest(status, resolutionNote)))).build(), ReportResponse.serializer())

override suspend fun applyReportAction(token: String, reportId: String, action: String, resolutionNote: String?): Result<ReportResponse> =
    send(Request.Builder().url("${ApiConfig.BASE_URL}/api/moderator/reports/$reportId/action").addHeader("Authorization", "Bearer $token").post(jsonBody(json.encodeToString(ApplyReportActionRequest.serializer(), ApplyReportActionRequest(action, resolutionNote)))).build(), ReportResponse.serializer())

override suspend fun getModerationRules(token: String): Result<List<ModerationRuleResponse>> =
    send(Request.Builder().url("${ApiConfig.BASE_URL}/api/admin/moderation/rules").addHeader("Authorization", "Bearer $token").get().build(), ListSerializer(ModerationRuleResponse.serializer()))

override suspend fun updateModerationRule(token: String, ruleId: String, request: UpdateModerationRuleRequest): Result<ModerationRuleResponse> =
    send(Request.Builder().url("${ApiConfig.BASE_URL}/api/admin/moderation/rules/$ruleId").addHeader("Authorization", "Bearer $token").put(jsonBody(json.encodeToString(UpdateModerationRuleRequest.serializer(), request))).build(), ModerationRuleResponse.serializer())

override suspend fun getRiskEvents(token: String, needsReview: Boolean?, limit: Int): Result<List<RiskEventResponse>> {
    val reviewPart = needsReview?.let { "&needsReview=$it" } ?: ""
    return send(Request.Builder().url("${ApiConfig.BASE_URL}/api/admin/moderation/events?limit=${limit.coerceIn(1, 200)}$reviewPart").addHeader("Authorization", "Bearer $token").get().build(), ListSerializer(RiskEventResponse.serializer()))
}

override suspend fun acknowledgeRiskEvent(token: String, eventId: String): Result<Unit> =
    sendUnit(Request.Builder().url("${ApiConfig.BASE_URL}/api/admin/moderation/events/$eventId/ack").addHeader("Authorization", "Bearer $token").post("".toRequestBody(JSON_MEDIA)).build())

override suspend fun updateProfile(token: String, name: String?, status: String?): Result<UserDto> =
    send(Request.Builder().url("${ApiConfig.BASE_URL}/api/users/profile").addHeader("Authorization", "Bearer $token").put(jsonBody(json.encodeToString(UpdateProfileRequest.serializer(), UpdateProfileRequest(name = name, status = status)))).build(), UserDto.serializer())

override suspend fun createGroupPoll(
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

override suspend fun voteGroupPoll(token: String, pollId: String, optionIndexes: List<Int>): Result<String> {
    val body = """{"optionIndexes":[${optionIndexes.joinToString(",")}]}"""
    val req = Request.Builder()
        .url("${ApiConfig.BASE_URL}/api/polls/$pollId/vote")
        .header("Authorization", "Bearer $token")
        .post(body.toRequestBody("application/json".toMediaType()))
        .build()
    return executeForText(req, "poll_vote")
}

override suspend fun listBots(token: String): Result<String> {
    val req = Request.Builder()
        .url("${ApiConfig.BASE_URL}/api/bots")
        .header("Authorization", "Bearer $token")
        .get()
        .build()
    return executeForText(req, "bots")
}

override suspend fun createBot(token: String, name: String, username: String, description: String?): Result<String> {
    val o = org.json.JSONObject()
    o.put("name", name)
    o.put("username", username)
    if (description != null) o.put("description", description)
    val req = Request.Builder()
        .url("${ApiConfig.BASE_URL}/api/bots")
        .header("Authorization", "Bearer $token")
        .post(o.toString().toRequestBody("application/json".toMediaType()))
        .build()
    return executeForText(req, "bot_create")
}

override suspend fun getGroupPoll(token: String, pollId: String): Result<String> {
    val req = Request.Builder()
        .url("${ApiConfig.BASE_URL}/api/polls/$pollId")
        .header("Authorization", "Bearer $token")
        .get()
        .build()
    return executeForText(req, "poll_get")
}

override suspend fun setBotWebhook(token: String, botId: String, url: String?): Result<String> {
    val payload = if (url == null) """{"url":null}""" else org.json.JSONObject().put("url", url).toString()
    val req = Request.Builder()
        .url("${ApiConfig.BASE_URL}/api/bots/$botId/webhook")
        .header("Authorization", "Bearer $token")
        .put(payload.toRequestBody("application/json".toMediaType()))
        .build()
    return executeForText(req, "bot_webhook")
}

override suspend fun regenerateBotToken(token: String, botId: String): Result<String> {
    val req = Request.Builder()
        .url("${ApiConfig.BASE_URL}/api/bots/$botId/token")
        .header("Authorization", "Bearer $token")
        .post("{}".toRequestBody("application/json".toMediaType()))
        .build()
    return executeForText(req, "bot_token")
}

override suspend fun listChatBotCommands(token: String, chatId: String): Result<String> {
    val req = Request.Builder()
        .url("${ApiConfig.BASE_URL}/api/chats/$chatId/bot-commands")
        .header("Authorization", "Bearer $token")
        .get()
        .build()
    return executeForText(req, "bot_commands")
}

override suspend fun postBotInbox(token: String, chatId: String, text: String, botId: String?): Result<String> {
    val payload = org.json.JSONObject().put("text", text).apply {
        if (!botId.isNullOrBlank()) put("botId", botId)
    }.toString()
    val req = Request.Builder()
        .url("${ApiConfig.BASE_URL}/api/chats/$chatId/bot-inbox")
        .header("Authorization", "Bearer $token")
        .post(payload.toRequestBody("application/json".toMediaType()))
        .build()
    return executeForText(req, "bot_inbox")
}

override suspend fun openBotDirectChat(token: String, botId: String): Result<ChatDto> =
    send(
        Request.Builder()
            .url("${ApiConfig.BASE_URL}/api/bots/$botId/dm")
            .addHeader("Authorization", "Bearer $token")
            .post(jsonBody("{}"))
            .build(),
        ChatDto.serializer()
    )

override suspend fun inviteBotToChat(token: String, chatId: String, botId: String): Result<String> {
    val payload = org.json.JSONObject().put("botId", botId).toString()
    val req = Request.Builder()
        .url("${ApiConfig.BASE_URL}/api/chats/$chatId/bots")
        .header("Authorization", "Bearer $token")
        .post(payload.toRequestBody("application/json".toMediaType()))
        .build()
    return executeForText(req, "bot_invite")
}

override suspend fun postBotCallback(
    token: String,
    chatId: String,
    messageId: String,
    botUserId: String,
    callbackData: String
): Result<Boolean> {
    val payload = org.json.JSONObject()
        .put("messageId", messageId)
        .put("botUserId", botUserId)
        .put("callbackData", callbackData)
        .toString()
    val req = Request.Builder()
        .url("${ApiConfig.BASE_URL}/api/chats/$chatId/bot-callback")
        .header("Authorization", "Bearer $token")
        .post(payload.toRequestBody("application/json".toMediaType()))
        .build()
    return executeForText(req, "bot_callback").map { true }
}

private suspend fun <T> runIoCatching(block: () -> T): Result<T> = withContext(Dispatchers.IO) {
    try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        Result.failure(error)
    }
}

override suspend fun deleteBot(token: String, botId: String): Result<String> {
    val req = Request.Builder()
        .url("${ApiConfig.BASE_URL}/api/bots/$botId")
        .header("Authorization", "Bearer $token")
        .delete()
        .build()
    return executeForText(req, "bot_delete")
}

override suspend fun setBotEnabled(token: String, botId: String, enabled: Boolean): Result<String> {
    val payload = org.json.JSONObject().put("enabled", enabled).toString()
    val req = Request.Builder()
        .url("${ApiConfig.BASE_URL}/api/bots/$botId/enabled")
        .header("Authorization", "Bearer $token")
        .put(payload.toRequestBody("application/json".toMediaType()))
        .build()
    return executeForText(req, "bot_enabled")
}

/** 活跃公告（含本用户 acked 状态），返回 JSON 字符串由调用方解析。 */

override suspend fun getActiveAnnouncements(token: String): Result<String> {
    val req = Request.Builder()
        .url("${ApiConfig.BASE_URL}/api/announcements/active")
        .header("Authorization", "Bearer $token")
        .get()
        .build()
    return executeForText(req, "announcements_active")
}

/** 公告已读确认。 */

override suspend fun ackAnnouncement(token: String, announcementId: String): Result<String> {
    val req = Request.Builder()
        .url("${ApiConfig.BASE_URL}/api/announcements/$announcementId/ack")
        .header("Authorization", "Bearer $token")
        .post(ByteArray(0).toRequestBody(null))
        .build()
    return executeForText(req, "announcement_ack")
}

/** 推送 HMAC 校验密钥（经认证通道下发；返回 JSON 字符串由调用方解析，key 为 null 表示未配置）。 */

override suspend fun getPushVerifyKey(token: String): Result<String> {
    val req = Request.Builder()
        .url("${ApiConfig.BASE_URL}/api/push/verify-key")
        .header("Authorization", "Bearer $token")
        .get()
        .build()
    return executeForText(req, "push_verify_key")
}
}
