package com.maodouchat.network.api

import com.maodouchat.BuildConfig
import com.maodouchat.network.*
import java.io.File

/** Remaining endpoints retained through ApiService during the migration. */
interface ApiSurface {
    suspend fun getSealedSenderCertificate(token: String, deviceId: Int = 1): Result<String>

    suspend fun getPublicStatus(): Result<String>

    suspend fun addGroupMembers(token: String, chatId: String, memberIds: List<String>): Result<Unit>

    suspend fun removeGroupMember(token: String, chatId: String, memberId: String): Result<Unit>

    suspend fun transferGroupOwnership(token: String, chatId: String, memberId: String): Result<Unit>

    suspend fun renameGroup(token: String, chatId: String, newName: String): Result<Unit>

    suspend fun getGroupMembers(token: String, chatId: String): Result<List<GroupMemberDto>>

    suspend fun getSenderKeyDistributionStatus(
    token: String,
    chatId: String,
    epoch: Long? = null,
    currentDeviceId: Int? = null
): Result<SenderKeyDistributionStatusDto>

    suspend fun getDevices(token: String, userId: String, currentDeviceId: Int? = null): Result<List<DeviceInfoDto>>

    suspend fun uploadKeys(token: String, request: UploadKeysRequest): Result<Unit>

    suspend fun getPreKeyBundle(token: String, userId: String): Result<PreKeyBundleDto>

    suspend fun getDevicePreKeyBundle(token: String, userId: String, deviceId: Int): Result<DevicePreKeyBundleDto>

    suspend fun getDevicePreKeyBundles(token: String, userId: String): Result<List<DevicePreKeyBundleDto>>

    suspend fun removeMyDevice(token: String, deviceId: Int): Result<Unit>

    suspend fun renameMyDevice(token: String, deviceId: Int, deviceName: String): Result<Unit>

    suspend fun confirmMyDevice(token: String, deviceId: Int, approverDeviceId: Int, signature: String): Result<Unit>

    suspend fun updateMemberRole(token: String, chatId: String, memberId: String, role: String): Result<Unit>

    suspend fun updateGroupNickname(token: String, chatId: String, nickname: String): Result<Unit>

    suspend fun updateMemberTitle(token: String, chatId: String, memberId: String, title: String): Result<Unit>

    suspend fun updateMemberMute(token: String, chatId: String, memberId: String, mutedUntil: Long): Result<Unit>

    suspend fun muteAllMembers(token: String, chatId: String, mutedUntil: Long): Result<Unit>

    suspend fun updateGroupAnnouncement(token: String, chatId: String, announcement: String): Result<Unit>

    suspend fun getOrCreateGroupInvite(token: String, chatId: String, rotate: Boolean = false, expiresInSeconds: Long = 7L * 24L * 60L * 60L, maxUses: Int = 100): Result<GroupInviteResponse>

    suspend fun getGroupAudit(token: String, chatId: String, limit: Int = 50, offset: Int = 0): Result<List<GroupAuditLogDto>>

    suspend fun joinGroupByInvite(token: String, inviteToken: String): Result<ChatDto>

    suspend fun deleteChat(token: String, chatId: String): Result<Unit>

    suspend fun toggleStarMessage(token: String, messageId: String): Result<StarMessageResponse>

    suspend fun getPinnedMessages(token: String, chatId: String): Result<PinnedMessagesListResponse>

    suspend fun togglePinnedMessage(token: String, chatId: String, messageId: String): Result<TogglePinResponse>

    suspend fun getStarredMessages(token: String, chatId: String? = null): Result<List<StarredMessageRefDto>>

    suspend fun getUsers(token: String, limit: Int = 30, offset: Int = 0): Result<List<UserDto>>

    suspend fun getAllSearchableUsers(
    token: String,
    pageSize: Int = 100,
    maxUsers: Int = 1000
): Result<List<UserDto>>

    suspend fun getUser(token: String, userId: String): Result<UserDto>

    suspend fun getNearbyLocationStatus(token: String): Result<NearbyLocationStatusResponse>

    suspend fun updateNearbyLocation(token: String, latitude: Double, longitude: Double): Result<NearbyLocationStatusResponse>

    suspend fun stopNearbyLocationSharing(token: String): Result<NearbyLocationStatusResponse>

    suspend fun getNearbyUsers(token: String, radiusKm: Double = 10.0, limit: Int = 50): Result<List<NearbyUserResponse>>

    suspend fun searchUsers(token: String, query: String, limit: Int = 30): Result<List<UserDto>>

    suspend fun getCurrentUser(token: String): Result<UserDto>

    suspend fun getCurrentUserPublic(token: String): Result<CurrentUserPublicResponse>

    suspend fun getPublicProfile(username: String): Result<PublicProfileResponse>

    suspend fun setUsername(token: String, username: String): Result<SetUsernameResponse>

    suspend fun clearUsername(token: String): Result<Unit>

    suspend fun getPrivacy(token: String): Result<UserPrivacyDto>

    suspend fun updatePrivacy(
    token: String,
    showOnline: Boolean? = null,
    showStatus: Boolean? = null,
    searchable: Boolean? = null,
    defaultPostVisibility: String? = null,
    onlineVisibility: String? = null
): Result<UserPrivacyDto>

    suspend fun getPublicUpdates(officialBaseUrl: String = BuildConfig.API_BASE_URL): Result<PublicUpdatesDto>

    suspend fun getNotificationSettings(token: String): Result<NotificationSettingsResponse>

    suspend fun updateNotificationSettings(token: String, request: NotificationSettingsRequest): Result<NotificationSettingsResponse>

    suspend fun registerPushToken(
    token: String,
    deviceId: String,
    pushToken: String,
    timezoneOffsetMinutes: Int
): Result<Unit>

    suspend fun removePushToken(token: String, deviceId: String): Result<Unit>

    suspend fun sendSignaling(
    token: String,
    toUserId: String,
    type: String,
    payload: String,
    callId: String = "",
    groupId: String = "",
    groupMemberIds: List<String> = emptyList(),
    groupInvite: Boolean = false
): Result<Unit>

    suspend fun hangUpCall(
    token: String,
    toUserId: String,
    callId: String = "",
    groupId: String = "",
    groupMemberIds: List<String> = emptyList()
): Result<Unit>

    suspend fun getPendingSignaling(token: String, offersOnly: Boolean = false): Result<List<SignalMessageDto>>

    suspend fun getIceConfig(token: String): Result<IceConfigDto>

    suspend fun blockUser(token: String, userId: String): Result<Unit>

    suspend fun unblockUser(token: String, userId: String): Result<Unit>

    suspend fun getBlockedUsers(token: String): Result<List<String>>

    suspend fun getBlockedUserDetails(token: String): Result<List<UserDto>>

    suspend fun getPosts(
    token: String,
    limit: Int = 40,
    before: Long? = null,
    beforeId: String? = null,
    authorId: String? = null
): Result<List<PostDto>>

    suspend fun createPost(token: String, content: String, imageUrls: List<String>, visibility: String? = null): Result<PostDto>

    suspend fun getPost(token: String, postId: String): Result<PostDto>

    suspend fun editPost(token: String, postId: String, content: String, visibility: String? = null): Result<PostDto>

    suspend fun deletePost(token: String, postId: String): Result<Unit>

    suspend fun likePost(token: String, postId: String): Result<PostDto>

    suspend fun unlikePost(token: String, postId: String): Result<PostDto>

    suspend fun getPostComments(
    token: String,
    postId: String,
    limit: Int = 50,
    before: Long? = null,
    beforeId: String? = null
): Result<List<PostCommentDto>>

    suspend fun createPostComment(token: String, postId: String, content: String, replyToId: String? = null): Result<PostCommentDto>

    suspend fun editPostComment(token: String, postId: String, commentId: String, content: String): Result<PostCommentDto>

    suspend fun getPostLikers(token: String, postId: String, limit: Int = 50): Result<PostLikersResponse>

    suspend fun deleteComment(token: String, postId: String, commentId: String): Result<Unit>

    suspend fun likeComment(token: String, postId: String, commentId: String): Result<CommentLikeResponse>

    suspend fun unlikeComment(token: String, postId: String, commentId: String): Result<CommentLikeResponse>

    suspend fun sendFriendRequest(token: String, toUserId: String, message: String = ""): Result<FriendRequestDto>

    suspend fun getIncomingFriendRequests(token: String, status: String = "PENDING", limit: Int = 50): Result<List<FriendRequestDto>>

    suspend fun getOutgoingFriendRequests(token: String, status: String = "PENDING", limit: Int = 50): Result<List<FriendRequestDto>>

    suspend fun acceptFriendRequest(token: String, requestId: String): Result<FriendRequestDto>

    suspend fun rejectFriendRequest(token: String, requestId: String): Result<FriendRequestDto>

    suspend fun cancelFriendRequest(token: String, requestId: String): Result<FriendRequestDto>

    suspend fun getFriends(token: String): Result<List<UserDto>>

    suspend fun removeFriend(token: String, friendId: String): Result<Unit>

    suspend fun getGroupInvitations(token: String): Result<List<GroupInvitationDto>>

    suspend fun acceptGroupInvitation(token: String, inviteId: String): Result<GroupInviteAcceptResponse>

    suspend fun declineGroupInvitation(token: String, inviteId: String): Result<GroupInviteAcceptResponse>

    suspend fun cancelGroupInvitation(token: String, inviteId: String): Result<Unit>

    suspend fun getChatGroupInvitations(token: String, chatId: String): Result<List<GroupInvitationDto>>

    suspend fun getChatFolders(token: String): Result<ChatFoldersSyncResponse>

    suspend fun putChatFolders(token: String, folders: List<ChatFolderDto>): Result<ChatFoldersSyncResponse>

    suspend fun getClientPrefs(token: String): Result<ClientPrefsDto>

    suspend fun putClientPrefs(token: String, request: ClientPrefsUpdateRequest): Result<ClientPrefsDto>

    suspend fun createReport(
    token: String,
    targetType: String,
    targetId: String,
    chatId: String? = null,
    messageId: String? = null,
    reason: String,
    description: String? = null
): Result<ReportResponse>

    suspend fun getMyReports(token: String, limit: Int = 50): Result<List<ReportResponse>>

    suspend fun getAdminReports(token: String, status: String? = null, limit: Int = 100): Result<List<ReportResponse>>

    suspend fun updateReportStatus(token: String, reportId: String, status: String, resolutionNote: String? = null): Result<ReportResponse>

    suspend fun applyReportAction(token: String, reportId: String, action: String, resolutionNote: String? = null): Result<ReportResponse>

    suspend fun getModerationRules(token: String): Result<List<ModerationRuleResponse>>

    suspend fun updateModerationRule(token: String, ruleId: String, request: UpdateModerationRuleRequest): Result<ModerationRuleResponse>

    suspend fun getRiskEvents(token: String, needsReview: Boolean? = null, limit: Int = 100): Result<List<RiskEventResponse>>

    suspend fun acknowledgeRiskEvent(token: String, eventId: String): Result<Unit>

    suspend fun updateProfile(token: String, name: String? = null, status: String? = null): Result<UserDto>

    suspend fun createGroupPoll(
    token: String,
    chatId: String,
    question: String,
    options: List<String>,
    multi: Boolean = false,
    anonymous: Boolean = false,
    closesAt: Long? = null
): Result<String>

    suspend fun voteGroupPoll(token: String, pollId: String, optionIndexes: List<Int>): Result<String>

    suspend fun listBots(token: String): Result<String>

    suspend fun createBot(token: String, name: String, username: String, description: String? = null): Result<String>

    suspend fun getGroupPoll(token: String, pollId: String): Result<String>

    suspend fun setBotWebhook(token: String, botId: String, url: String?): Result<String>

    suspend fun regenerateBotToken(token: String, botId: String): Result<String>

    suspend fun listChatBotCommands(token: String, chatId: String): Result<String>

    suspend fun postBotInbox(token: String, chatId: String, text: String, botId: String? = null): Result<String>

    suspend fun openBotDirectChat(token: String, botId: String): Result<ChatDto>

    suspend fun inviteBotToChat(token: String, chatId: String, botId: String): Result<String>

    suspend fun postBotCallback(
    token: String,
    chatId: String,
    messageId: String,
    botUserId: String,
    callbackData: String
): Result<Boolean>

    suspend fun deleteBot(token: String, botId: String): Result<String>

    suspend fun setBotEnabled(token: String, botId: String, enabled: Boolean): Result<String>

    suspend fun getActiveAnnouncements(token: String): Result<String>

    suspend fun ackAnnouncement(token: String, announcementId: String): Result<String>

    suspend fun getPushVerifyKey(token: String): Result<String>
}

interface AuthApi {
    suspend fun getTotpStatus(token: String): Result<String>

    suspend fun setupTotp(token: String): Result<String>

    suspend fun totpStatus(token: String): Result<Boolean>

    suspend fun regenerateTotpCodes(token: String, code: String): Result<List<String>>

    suspend fun confirmTotp(token: String, code: String): Result<List<String>>

    suspend fun disableTotp(token: String, code: String): Result<String>

    suspend fun login(email: String, password: String, totpCode: String = ""): Result<AuthResponse>

    suspend fun register(name: String, email: String, password: String): Result<AuthResponse>

    suspend fun logout(refreshToken: String, accessToken: String? = null, deviceId: String = ""): Result<Unit>

    suspend fun logoutAll(token: String): Result<Unit>

    suspend fun sendVerificationCode(email: String, purpose: String = "register"): Result<Unit>

    suspend fun registerWithCode(name: String, email: String, password: String, code: String): Result<AuthResponse>

    suspend fun resetPassword(email: String, code: String, newPassword: String): Result<Unit>

    suspend fun changePassword(token: String, oldPassword: String, newPassword: String): Result<Unit>

    suspend fun deleteAccount(token: String, password: String): Result<DeleteAccountResponse>
}

interface MessagingApi {
    suspend fun sendMessageV2(
    token: String,
    request: SendMessageRequestV2,
): Result<SendMessageResponseV2>

    suspend fun getConversationSnapshotV2(
    token: String,
    conversationId: String,
): Result<ConversationSnapshotV2Dto>

    suspend fun getPendingInboxV2(
    token: String,
    limit: Int = 100,
): Result<PendingInboxResponseV2>

    suspend fun acknowledgeInboxV2(
    token: String,
    envelopeIds: List<String>,
): Result<AcknowledgeEnvelopesResponseV2>
}

interface ConversationApi {
    suspend fun getChats(token: String): Result<List<ChatDto>>

    suspend fun updateChatSettings(token: String, chatId: String, request: UpdateChatSettingsRequest): Result<ChatSettingsResponse>

    suspend fun updateDisappearingMessages(
    token: String,
    chatId: String,
    seconds: Int
): Result<DisappearingMessagesResponse>

    suspend fun createChat(
    token: String,
    participantIds: List<String>,
    isGroup: Boolean = false,
    groupName: String? = null,
    chatType: String? = null
): Result<ChatDto>
}

interface MediaApi {
    suspend fun uploadEncryptedAttachment(
    token: String,
    chatId: String,
    messageId: String,
    encryptedFile: File,
    cipherSha256: String,
    onProgress: (Long, Long) -> Unit = { _, _ -> },
    onCheckpoint: suspend (String, Long, Long) -> Unit = { _, _, _ -> }
): Result<AttachmentUploadResponse>

    suspend fun verifyEncryptedAttachmentReady(
    token: String,
    chatId: String,
    messageId: String,
    attachmentId: String,
    expectedSha256: String,
    expectedSize: Long
): Result<AttachmentUploadStatusResponse>

    suspend fun deleteUncommittedAttachment(token: String, attachmentId: String): Result<Unit>

    suspend fun downloadEncryptedAttachment(
    token: String,
    attachmentId: String,
    expectedSha256: String,
    expectedSize: Long,
    target: File,
    onProgress: (Long, Long) -> Unit = { _, _ -> }
): Result<Unit>

    suspend fun downloadPostImage(token: String, imageUrl: String, target: java.io.File): Result<Unit>

    suspend fun uploadPostImage(token: String, base64Data: String): Result<String>

    suspend fun discardPostImage(token: String, imageUrl: String): Result<Unit>

    suspend fun uploadAvatar(token: String, base64Data: String): Result<String>

    suspend fun removeAvatar(token: String): Result<Unit>

    suspend fun uploadGroupAvatar(token: String, chatId: String, base64Data: String): Result<String>
}
