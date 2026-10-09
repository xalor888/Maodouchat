package com.maodouchat.network.api

import com.maodouchat.network.*
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.KSerializer

// 社交内容域（动态/评论/好友）：从 ApiEndpointClients 按主题拆出，纯搬移。
internal object ApiSocialContentEndpoints {
private val json get() = ApiService.json
private fun jsonBody(value: String) = value.toRequestBody(ApiService.JSON_MEDIA)
private suspend fun <T> send(request: Request, serializer: kotlinx.serialization.KSerializer<T>): Result<T> = ApiService.send(request, serializer)
private suspend fun sendUnit(request: Request): Result<Unit> = ApiService.sendUnit(request)

// ─── 发现页 / 动态 ─────────────────────────

suspend fun getPosts(
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

suspend fun createPost(token: String, content: String, imageUrls: List<String>, visibility: String?): Result<PostDto> =
    send(Request.Builder().url("${ApiConfig.BASE_URL}/api/posts").addHeader("Authorization", "Bearer $token").post(jsonBody(json.encodeToString(CreatePostRequest.serializer(), CreatePostRequest(content, imageUrls, visibility, visibility == null)))).build(), PostDto.serializer())

suspend fun getPost(token: String, postId: String): Result<PostDto> =
    send(
        Request.Builder()
            .url("${ApiConfig.BASE_URL}/api/posts/${java.net.URLEncoder.encode(postId, Charsets.UTF_8.name())}")
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build(),
        PostDto.serializer()
    )

suspend fun editPost(token: String, postId: String, content: String, visibility: String?): Result<PostDto> =
    // 8.58：postId 统一 URL 编码（与 getPost 一致，防保留字符路由错乱）
    send(Request.Builder().url("${ApiConfig.BASE_URL}/api/posts/${java.net.URLEncoder.encode(postId, Charsets.UTF_8.name())}").addHeader("Authorization", "Bearer $token").put(jsonBody(json.encodeToString(EditPostRequest.serializer(), EditPostRequest(content, visibility)))).build(), PostDto.serializer())

suspend fun deletePost(token: String, postId: String): Result<Unit> =
    sendUnit(Request.Builder().url("${ApiConfig.BASE_URL}/api/posts/${java.net.URLEncoder.encode(postId, Charsets.UTF_8.name())}").addHeader("Authorization", "Bearer $token").delete().build())

suspend fun likePost(token: String, postId: String): Result<PostDto> =
    send(Request.Builder().url("${ApiConfig.BASE_URL}/api/posts/${java.net.URLEncoder.encode(postId, Charsets.UTF_8.name())}/like").addHeader("Authorization", "Bearer $token").post(ByteArray(0).toRequestBody(null)).build(), PostDto.serializer())

suspend fun unlikePost(token: String, postId: String): Result<PostDto> =
    send(Request.Builder().url("${ApiConfig.BASE_URL}/api/posts/${java.net.URLEncoder.encode(postId, Charsets.UTF_8.name())}/like").addHeader("Authorization", "Bearer $token").delete().build(), PostDto.serializer())

suspend fun getPostComments(
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

suspend fun createPostComment(token: String, postId: String, content: String, replyToId: String?): Result<PostCommentDto> =
    // 8.58：postId 统一 URL 编码（与 getPost 一致，防保留字符路由错乱）
    send(Request.Builder().url("${ApiConfig.BASE_URL}/api/posts/${java.net.URLEncoder.encode(postId, Charsets.UTF_8.name())}/comments").addHeader("Authorization", "Bearer $token").post(jsonBody(json.encodeToString(CreateCommentRequest.serializer(), CreateCommentRequest(content, replyToId)))).build(), PostCommentDto.serializer())

suspend fun editPostComment(token: String, postId: String, commentId: String, content: String): Result<PostCommentDto> =
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

/** 1.93：动态点赞者列表。 */

suspend fun getPostLikers(token: String, postId: String, limit: Int): Result<PostLikersResponse> =
    send(
        Request.Builder()
            .url("${ApiConfig.BASE_URL}/api/posts/${java.net.URLEncoder.encode(postId, Charsets.UTF_8.name())}/likers?limit=${limit.coerceIn(1, 100)}")
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build(),
        PostLikersResponse.serializer()
    )

/** 1.00：删除自己的评论。 */

/** 1.00：删除自己的评论。 */

suspend fun deleteComment(token: String, postId: String, commentId: String): Result<Unit> =
    sendUnit(
        Request.Builder()
            .url("${ApiConfig.BASE_URL}/api/posts/${java.net.URLEncoder.encode(postId, Charsets.UTF_8.name())}/comments/${java.net.URLEncoder.encode(commentId, Charsets.UTF_8.name())}")
            .addHeader("Authorization", "Bearer $token")
            .delete()
            .build()
    )

/** 1.52：点赞评论。 */

/** 1.52：点赞评论。 */

suspend fun likeComment(token: String, postId: String, commentId: String): Result<CommentLikeResponse> =
    send(
        Request.Builder()
            .url("${ApiConfig.BASE_URL}/api/posts/${java.net.URLEncoder.encode(postId, Charsets.UTF_8.name())}/comments/${java.net.URLEncoder.encode(commentId, Charsets.UTF_8.name())}/like")
            .addHeader("Authorization", "Bearer $token")
            .post(ByteArray(0).toRequestBody(null))
            .build(),
        CommentLikeResponse.serializer()
    )

/** 1.52：取消点赞评论。 */

/** 1.52：取消点赞评论。 */

suspend fun unlikeComment(token: String, postId: String, commentId: String): Result<CommentLikeResponse> =
    send(
        Request.Builder()
            .url("${ApiConfig.BASE_URL}/api/posts/${java.net.URLEncoder.encode(postId, Charsets.UTF_8.name())}/comments/${java.net.URLEncoder.encode(commentId, Charsets.UTF_8.name())}/like")
            .addHeader("Authorization", "Bearer $token")
            .delete()
            .build(),
        CommentLikeResponse.serializer()
    )

suspend fun sendFriendRequest(token: String, toUserId: String, message: String): Result<FriendRequestDto> =
    send(Request.Builder().url("${ApiConfig.BASE_URL}/api/friends/requests").addHeader("Authorization", "Bearer $token").post(jsonBody(json.encodeToString(SendFriendRequestBody.serializer(), SendFriendRequestBody(toUserId, message)))).build(), FriendRequestDto.serializer())

suspend fun getIncomingFriendRequests(token: String, status: String, limit: Int): Result<List<FriendRequestDto>> =
    send(Request.Builder().url("${ApiConfig.BASE_URL}/api/friends/requests/incoming?status=$status&limit=${limit.coerceIn(1, 100)}").addHeader("Authorization", "Bearer $token").get().build(), ListSerializer(FriendRequestDto.serializer()))

suspend fun getOutgoingFriendRequests(token: String, status: String, limit: Int): Result<List<FriendRequestDto>> =
    send(Request.Builder().url("${ApiConfig.BASE_URL}/api/friends/requests/outgoing?status=$status&limit=${limit.coerceIn(1, 100)}").addHeader("Authorization", "Bearer $token").get().build(), ListSerializer(FriendRequestDto.serializer()))

suspend fun acceptFriendRequest(token: String, requestId: String): Result<FriendRequestDto> =
    send(Request.Builder().url("${ApiConfig.BASE_URL}/api/friends/requests/$requestId/accept").addHeader("Authorization", "Bearer $token").post(ByteArray(0).toRequestBody(null)).build(), FriendRequestDto.serializer())

suspend fun rejectFriendRequest(token: String, requestId: String): Result<FriendRequestDto> =
    send(Request.Builder().url("${ApiConfig.BASE_URL}/api/friends/requests/$requestId/reject").addHeader("Authorization", "Bearer $token").post(ByteArray(0).toRequestBody(null)).build(), FriendRequestDto.serializer())

suspend fun cancelFriendRequest(token: String, requestId: String): Result<FriendRequestDto> =
    send(Request.Builder().url("${ApiConfig.BASE_URL}/api/friends/requests/$requestId/cancel").addHeader("Authorization", "Bearer $token").post(ByteArray(0).toRequestBody(null)).build(), FriendRequestDto.serializer())

suspend fun getFriends(token: String): Result<List<UserDto>> =
    send(Request.Builder().url("${ApiConfig.BASE_URL}/api/friends").addHeader("Authorization", "Bearer $token").get().build(), ListSerializer(UserDto.serializer()))

suspend fun removeFriend(token: String, friendId: String): Result<Unit> =
    sendUnit(Request.Builder().url("${ApiConfig.BASE_URL}/api/friends/$friendId").addHeader("Authorization", "Bearer $token").delete().build())

// ─── 9.3xx：群邀请同意流程 ─────────────────
}
