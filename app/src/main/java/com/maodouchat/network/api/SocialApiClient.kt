package com.maodouchat.network.api

import com.maodouchat.network.*

object SocialApiClient : SocialApi {
    override suspend fun getUsers(token: String, limit: Int, offset: Int): Result<List<UserDto>> =
        ApiEndpointClients.getUsers(token, limit, offset)

    override suspend fun getAllSearchableUsers(
        token: String,
        pageSize: Int,
        maxUsers: Int
    ): Result<List<UserDto>> =
        ApiEndpointClients.getAllSearchableUsers(token, pageSize, maxUsers)

    override suspend fun getUser(token: String, userId: String): Result<UserDto> =
        ApiEndpointClients.getUser(token, userId)

    override suspend fun searchUsers(token: String, query: String, limit: Int): Result<List<UserDto>> =
        ApiEndpointClients.searchUsers(token, query, limit)

    override suspend fun getCurrentUser(token: String): Result<UserDto> =
        ApiEndpointClients.getCurrentUser(token)

    override suspend fun getCurrentUserPublic(token: String): Result<CurrentUserPublicResponse> =
        ApiEndpointClients.getCurrentUserPublic(token)

    override suspend fun getPublicProfile(username: String): Result<PublicProfileResponse> =
        ApiEndpointClients.getPublicProfile(username)

    override suspend fun setUsername(token: String, username: String): Result<SetUsernameResponse> =
        ApiEndpointClients.setUsername(token, username)

    override suspend fun clearUsername(token: String): Result<Unit> =
        ApiEndpointClients.clearUsername(token)

    override suspend fun getNearbyLocationStatus(token: String): Result<NearbyLocationStatusResponse> =
        ApiEndpointClients.getNearbyLocationStatus(token)

    override suspend fun updateNearbyLocation(
        token: String,
        latitude: Double,
        longitude: Double
    ): Result<NearbyLocationStatusResponse> =
        ApiEndpointClients.updateNearbyLocation(token, latitude, longitude)

    override suspend fun stopNearbyLocationSharing(token: String): Result<NearbyLocationStatusResponse> =
        ApiEndpointClients.stopNearbyLocationSharing(token)

    override suspend fun getNearbyUsers(
        token: String,
        radiusKm: Double,
        limit: Int
    ): Result<List<NearbyUserResponse>> =
        ApiEndpointClients.getNearbyUsers(token, radiusKm, limit)

    override suspend fun blockUser(token: String, userId: String): Result<Unit> =
        ApiEndpointClients.blockUser(token, userId)

    override suspend fun unblockUser(token: String, userId: String): Result<Unit> =
        ApiEndpointClients.unblockUser(token, userId)

    override suspend fun getBlockedUsers(token: String): Result<List<String>> =
        ApiEndpointClients.getBlockedUsers(token)

    override suspend fun getBlockedUserDetails(token: String): Result<List<UserDto>> =
        ApiEndpointClients.getBlockedUserDetails(token)

    override suspend fun getPosts(
        token: String,
        limit: Int,
        before: Long?,
        beforeId: String?,
        authorId: String?
    ): Result<List<PostDto>> =
        ApiEndpointClients.getPosts(token, limit, before, beforeId, authorId)

    override suspend fun createPost(
        token: String,
        content: String,
        imageUrls: List<String>,
        visibility: String?
    ): Result<PostDto> =
        ApiEndpointClients.createPost(token, content, imageUrls, visibility)

    override suspend fun getPost(token: String, postId: String): Result<PostDto> =
        ApiEndpointClients.getPost(token, postId)

    override suspend fun editPost(
        token: String,
        postId: String,
        content: String,
        visibility: String?
    ): Result<PostDto> =
        ApiEndpointClients.editPost(token, postId, content, visibility)

    override suspend fun deletePost(token: String, postId: String): Result<Unit> =
        ApiEndpointClients.deletePost(token, postId)

    override suspend fun likePost(token: String, postId: String): Result<PostDto> =
        ApiEndpointClients.likePost(token, postId)

    override suspend fun unlikePost(token: String, postId: String): Result<PostDto> =
        ApiEndpointClients.unlikePost(token, postId)

    override suspend fun getPostComments(
        token: String,
        postId: String,
        limit: Int,
        before: Long?,
        beforeId: String?
    ): Result<List<PostCommentDto>> =
        ApiEndpointClients.getPostComments(token, postId, limit, before, beforeId)

    override suspend fun createPostComment(
        token: String,
        postId: String,
        content: String,
        replyToId: String?
    ): Result<PostCommentDto> =
        ApiEndpointClients.createPostComment(token, postId, content, replyToId)

    override suspend fun editPostComment(
        token: String,
        postId: String,
        commentId: String,
        content: String
    ): Result<PostCommentDto> =
        ApiEndpointClients.editPostComment(token, postId, commentId, content)

    override suspend fun getPostLikers(token: String, postId: String, limit: Int): Result<PostLikersResponse> =
        ApiEndpointClients.getPostLikers(token, postId, limit)

    override suspend fun deleteComment(token: String, postId: String, commentId: String): Result<Unit> =
        ApiEndpointClients.deleteComment(token, postId, commentId)

    override suspend fun likeComment(token: String, postId: String, commentId: String): Result<CommentLikeResponse> =
        ApiEndpointClients.likeComment(token, postId, commentId)

    override suspend fun unlikeComment(token: String, postId: String, commentId: String): Result<CommentLikeResponse> =
        ApiEndpointClients.unlikeComment(token, postId, commentId)

    override suspend fun createReport(
        token: String,
        targetType: String,
        targetId: String,
        chatId: String?,
        messageId: String?,
        reason: String,
        description: String?
    ): Result<ReportResponse> =
        ApiEndpointClients.createReport(token, targetType, targetId, chatId, messageId, reason, description)
}
