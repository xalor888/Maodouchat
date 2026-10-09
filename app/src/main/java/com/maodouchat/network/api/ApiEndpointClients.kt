package com.maodouchat.network.api

import com.maodouchat.network.*

// ApiSurface 的薄复合门面：各领域实现已按主题拆到簇对象，这里只做转发。
internal object ApiEndpointClients : ApiSurface {
override suspend fun getPublicStatus(): Result<String> = ApiMessagingEndpoints.getPublicStatus()
override suspend fun deleteChat(token: String, chatId: String): Result<Unit> = ApiMessagingEndpoints.deleteChat(token, chatId)
override suspend fun toggleStarMessage(token: String, messageId: String): Result<StarMessageResponse> = ApiMessagingEndpoints.toggleStarMessage(token, messageId)
override suspend fun getPinnedMessages(token: String, chatId: String): Result<PinnedMessagesListResponse> = ApiMessagingEndpoints.getPinnedMessages(token, chatId)
override suspend fun togglePinnedMessage(token: String, chatId: String, messageId: String): Result<TogglePinResponse> = ApiMessagingEndpoints.togglePinnedMessage(token, chatId, messageId)
override suspend fun getStarredMessages(token: String, chatId: String?): Result<List<StarredMessageRefDto>> = ApiMessagingEndpoints.getStarredMessages(token, chatId)
override suspend fun getUsers(token: String, limit: Int, offset: Int): Result<List<UserDto>> = ApiSocialEndpoints.getUsers(token, limit, offset)
override suspend fun getAllSearchableUsers(
    token: String,
    pageSize: Int,
    maxUsers: Int): Result<List<UserDto>> = ApiSocialEndpoints.getAllSearchableUsers(token, pageSize, maxUsers)
override suspend fun getUser(token: String, userId: String): Result<UserDto> = ApiSocialEndpoints.getUser(token, userId)
override suspend fun getNearbyLocationStatus(token: String): Result<NearbyLocationStatusResponse> = ApiSocialEndpoints.getNearbyLocationStatus(token)
override suspend fun updateNearbyLocation(token: String, latitude: Double, longitude: Double): Result<NearbyLocationStatusResponse> = ApiSocialEndpoints.updateNearbyLocation(token, latitude, longitude)
override suspend fun stopNearbyLocationSharing(token: String): Result<NearbyLocationStatusResponse> = ApiSocialEndpoints.stopNearbyLocationSharing(token)
override suspend fun getNearbyUsers(token: String, radiusKm: Double, limit: Int): Result<List<NearbyUserResponse>> = ApiSocialEndpoints.getNearbyUsers(token, radiusKm, limit)
override suspend fun searchUsers(token: String, query: String, limit: Int): Result<List<UserDto>> = ApiSocialEndpoints.searchUsers(token, query, limit)
override suspend fun getCurrentUser(token: String): Result<UserDto> = ApiSocialEndpoints.getCurrentUser(token)
override suspend fun getCurrentUserPublic(token: String): Result<CurrentUserPublicResponse> = ApiSocialEndpoints.getCurrentUserPublic(token)
override suspend fun getPublicProfile(username: String): Result<PublicProfileResponse> = ApiSocialEndpoints.getPublicProfile(username)
override suspend fun setUsername(token: String, username: String): Result<SetUsernameResponse> = ApiSocialEndpoints.setUsername(token, username)
override suspend fun clearUsername(token: String): Result<Unit> = ApiSocialEndpoints.clearUsername(token)
override suspend fun getPrivacy(token: String): Result<UserPrivacyDto> = ApiSocialEndpoints.getPrivacy(token)
override suspend fun updatePrivacy(
    token: String,
    showOnline: Boolean?,
    showStatus: Boolean?,
    searchable: Boolean?,
    defaultPostVisibility: String?,
    onlineVisibility: String?): Result<UserPrivacyDto> = ApiSocialEndpoints.updatePrivacy(token, showOnline, showStatus, searchable, defaultPostVisibility, onlineVisibility)
override suspend fun getPublicUpdates(baseUrl: String): Result<PublicUpdatesDto> = ApiSocialEndpoints.getPublicUpdates(baseUrl)
override suspend fun getNotificationSettings(token: String): Result<NotificationSettingsResponse> = ApiSocialEndpoints.getNotificationSettings(token)
override suspend fun updateNotificationSettings(token: String, request: NotificationSettingsRequest): Result<NotificationSettingsResponse> = ApiSocialEndpoints.updateNotificationSettings(token, request)
override suspend fun registerPushToken(
    token: String,
    deviceId: String,
    pushToken: String,
    timezoneOffsetMinutes: Int
): Result<Unit> = ApiSocialEndpoints.registerPushToken(token, deviceId, pushToken, timezoneOffsetMinutes)
override suspend fun removePushToken(token: String, deviceId: String): Result<Unit> = ApiSocialEndpoints.removePushToken(token, deviceId)
override suspend fun blockUser(token: String, userId: String): Result<Unit> = ApiSocialEndpoints.blockUser(token, userId)
override suspend fun unblockUser(token: String, userId: String): Result<Unit> = ApiSocialEndpoints.unblockUser(token, userId)
override suspend fun getBlockedUsers(token: String): Result<List<String>> = ApiSocialEndpoints.getBlockedUsers(token)
override suspend fun getBlockedUserDetails(token: String): Result<List<UserDto>> = ApiSocialEndpoints.getBlockedUserDetails(token)
override suspend fun getPosts(
    token: String,
    limit: Int,
    before: Long?,
    beforeId: String?,
    authorId: String?): Result<List<PostDto>> = ApiSocialContentEndpoints.getPosts(token, limit, before, beforeId, authorId)
override suspend fun createPost(token: String, content: String, imageUrls: List<String>, visibility: String?): Result<PostDto> = ApiSocialContentEndpoints.createPost(token, content, imageUrls, visibility)
override suspend fun getPost(token: String, postId: String): Result<PostDto> = ApiSocialContentEndpoints.getPost(token, postId)
override suspend fun editPost(token: String, postId: String, content: String, visibility: String?): Result<PostDto> = ApiSocialContentEndpoints.editPost(token, postId, content, visibility)
override suspend fun deletePost(token: String, postId: String): Result<Unit> = ApiSocialContentEndpoints.deletePost(token, postId)
override suspend fun likePost(token: String, postId: String): Result<PostDto> = ApiSocialContentEndpoints.likePost(token, postId)
override suspend fun unlikePost(token: String, postId: String): Result<PostDto> = ApiSocialContentEndpoints.unlikePost(token, postId)
override suspend fun getPostComments(
    token: String,
    postId: String,
    limit: Int,
    before: Long?,
    beforeId: String?): Result<List<PostCommentDto>> = ApiSocialContentEndpoints.getPostComments(token, postId, limit, before, beforeId)
override suspend fun createPostComment(token: String, postId: String, content: String, replyToId: String?): Result<PostCommentDto> = ApiSocialContentEndpoints.createPostComment(token, postId, content, replyToId)
override suspend fun editPostComment(token: String, postId: String, commentId: String, content: String): Result<PostCommentDto> = ApiSocialContentEndpoints.editPostComment(token, postId, commentId, content)
override suspend fun getPostLikers(token: String, postId: String, limit: Int): Result<PostLikersResponse> = ApiSocialContentEndpoints.getPostLikers(token, postId, limit)
override suspend fun deleteComment(token: String, postId: String, commentId: String): Result<Unit> = ApiSocialContentEndpoints.deleteComment(token, postId, commentId)
override suspend fun likeComment(token: String, postId: String, commentId: String): Result<CommentLikeResponse> = ApiSocialContentEndpoints.likeComment(token, postId, commentId)
override suspend fun unlikeComment(token: String, postId: String, commentId: String): Result<CommentLikeResponse> = ApiSocialContentEndpoints.unlikeComment(token, postId, commentId)
override suspend fun sendFriendRequest(token: String, toUserId: String, message: String): Result<FriendRequestDto> = ApiSocialContentEndpoints.sendFriendRequest(token, toUserId, message)
override suspend fun getIncomingFriendRequests(token: String, status: String, limit: Int): Result<List<FriendRequestDto>> = ApiSocialContentEndpoints.getIncomingFriendRequests(token, status, limit)
override suspend fun getOutgoingFriendRequests(token: String, status: String, limit: Int): Result<List<FriendRequestDto>> = ApiSocialContentEndpoints.getOutgoingFriendRequests(token, status, limit)
override suspend fun acceptFriendRequest(token: String, requestId: String): Result<FriendRequestDto> = ApiSocialContentEndpoints.acceptFriendRequest(token, requestId)
override suspend fun rejectFriendRequest(token: String, requestId: String): Result<FriendRequestDto> = ApiSocialContentEndpoints.rejectFriendRequest(token, requestId)
override suspend fun cancelFriendRequest(token: String, requestId: String): Result<FriendRequestDto> = ApiSocialContentEndpoints.cancelFriendRequest(token, requestId)
override suspend fun getFriends(token: String): Result<List<UserDto>> = ApiSocialContentEndpoints.getFriends(token)
override suspend fun removeFriend(token: String, friendId: String): Result<Unit> = ApiSocialContentEndpoints.removeFriend(token, friendId)
override suspend fun getGroupInvitations(token: String): Result<List<GroupInvitationDto>> = ApiGroupEndpoints.getGroupInvitations(token)
override suspend fun acceptGroupInvitation(token: String, inviteId: String): Result<GroupInviteAcceptResponse> = ApiGroupEndpoints.acceptGroupInvitation(token, inviteId)
override suspend fun declineGroupInvitation(token: String, inviteId: String): Result<GroupInviteAcceptResponse> = ApiGroupEndpoints.declineGroupInvitation(token, inviteId)
override suspend fun cancelGroupInvitation(token: String, inviteId: String): Result<Unit> = ApiGroupEndpoints.cancelGroupInvitation(token, inviteId)
override suspend fun getChatGroupInvitations(token: String, chatId: String): Result<List<GroupInvitationDto>> = ApiGroupEndpoints.getChatGroupInvitations(token, chatId)
override suspend fun createGroupPoll(
    token: String,
    chatId: String,
    question: String,
    options: List<String>,
    multi: Boolean,
    anonymous: Boolean,
    closesAt: Long?): Result<String> = ApiGroupEndpoints.createGroupPoll(token, chatId, question, options, multi, anonymous, closesAt)
override suspend fun voteGroupPoll(token: String, pollId: String, optionIndexes: List<Int>): Result<String> = ApiGroupEndpoints.voteGroupPoll(token, pollId, optionIndexes)
override suspend fun getGroupPoll(token: String, pollId: String): Result<String> = ApiGroupEndpoints.getGroupPoll(token, pollId)
override suspend fun getChatFolders(token: String): Result<ChatFoldersSyncResponse> = ApiSettingsEndpoints.getChatFolders(token)
override suspend fun putChatFolders(token: String, folders: List<ChatFolderDto>): Result<ChatFoldersSyncResponse> = ApiSettingsEndpoints.putChatFolders(token, folders)
override suspend fun getClientPrefs(token: String): Result<ClientPrefsDto> = ApiSettingsEndpoints.getClientPrefs(token)
override suspend fun putClientPrefs(token: String, request: ClientPrefsUpdateRequest): Result<ClientPrefsDto> = ApiSettingsEndpoints.putClientPrefs(token, request)
override suspend fun updateProfile(token: String, name: String?, status: String?): Result<UserDto> = ApiSettingsEndpoints.updateProfile(token, name, status)
override suspend fun createReport(
    token: String,
    targetType: String,
    targetId: String,
    chatId: String?,
    messageId: String?,
    reason: String,
    description: String?): Result<ReportResponse> = ApiAdminEndpoints.createReport(token, targetType, targetId, chatId, messageId, reason, description)
override suspend fun getMyReports(token: String, limit: Int): Result<List<ReportResponse>> = ApiAdminEndpoints.getMyReports(token, limit)
override suspend fun getAdminReports(token: String, status: String?, limit: Int): Result<List<ReportResponse>> = ApiAdminEndpoints.getAdminReports(token, status, limit)
override suspend fun updateReportStatus(token: String, reportId: String, status: String, resolutionNote: String?): Result<ReportResponse> = ApiAdminEndpoints.updateReportStatus(token, reportId, status, resolutionNote)
override suspend fun applyReportAction(token: String, reportId: String, action: String, resolutionNote: String?): Result<ReportResponse> = ApiAdminEndpoints.applyReportAction(token, reportId, action, resolutionNote)
override suspend fun getModerationRules(token: String): Result<List<ModerationRuleResponse>> = ApiAdminEndpoints.getModerationRules(token)
override suspend fun updateModerationRule(token: String, ruleId: String, request: UpdateModerationRuleRequest): Result<ModerationRuleResponse> = ApiAdminEndpoints.updateModerationRule(token, ruleId, request)
override suspend fun getRiskEvents(token: String, needsReview: Boolean?, limit: Int): Result<List<RiskEventResponse>> = ApiAdminEndpoints.getRiskEvents(token, needsReview, limit)
override suspend fun acknowledgeRiskEvent(token: String, eventId: String): Result<Unit> = ApiAdminEndpoints.acknowledgeRiskEvent(token, eventId)
override suspend fun getActiveAnnouncements(token: String): Result<String> = ApiAdminEndpoints.getActiveAnnouncements(token)
override suspend fun ackAnnouncement(token: String, announcementId: String): Result<String> = ApiAdminEndpoints.ackAnnouncement(token, announcementId)
override suspend fun listBots(token: String): Result<String> = ApiBotEndpoints.listBots(token)
override suspend fun createBot(token: String, name: String, username: String, description: String?): Result<String> = ApiBotEndpoints.createBot(token, name, username, description)
override suspend fun setBotWebhook(token: String, botId: String, url: String?): Result<String> = ApiBotEndpoints.setBotWebhook(token, botId, url)
override suspend fun regenerateBotToken(token: String, botId: String): Result<String> = ApiBotEndpoints.regenerateBotToken(token, botId)
override suspend fun listChatBotCommands(token: String, chatId: String): Result<String> = ApiBotEndpoints.listChatBotCommands(token, chatId)
override suspend fun postBotInbox(token: String, chatId: String, text: String, botId: String?): Result<String> = ApiBotEndpoints.postBotInbox(token, chatId, text, botId)
override suspend fun openBotDirectChat(token: String, botId: String): Result<ChatDto> = ApiBotEndpoints.openBotDirectChat(token, botId)
override suspend fun inviteBotToChat(token: String, chatId: String, botId: String): Result<String> = ApiBotEndpoints.inviteBotToChat(token, chatId, botId)
override suspend fun postBotCallback(
    token: String,
    chatId: String,
    messageId: String,
    botUserId: String,
    callbackData: String
): Result<Boolean> = ApiBotEndpoints.postBotCallback(token, chatId, messageId, botUserId, callbackData)
override suspend fun deleteBot(token: String, botId: String): Result<String> = ApiBotEndpoints.deleteBot(token, botId)
override suspend fun setBotEnabled(token: String, botId: String, enabled: Boolean): Result<String> = ApiBotEndpoints.setBotEnabled(token, botId, enabled)
override suspend fun getPushVerifyKey(token: String): Result<String> = ApiBotEndpoints.getPushVerifyKey(token)
}
