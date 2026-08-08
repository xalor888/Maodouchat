package com.maodouchat.network

import kotlinx.serialization.Serializable

@Serializable
data class UpdateNearbyLocationRequest(val latitude: Double, val longitude: Double)

@Serializable
data class NearbyLocationStatusResponse(val sharing: Boolean, val expiresAt: Long = 0)

@Serializable
data class NearbyUserResponse(val user: UserDto, val distanceMeters: Int, val locationUpdatedAt: Long)

@Serializable
data class PostDto(
    val id: String,
    val author: UserDto,
    val content: String,
    val imageUrls: List<String> = emptyList(),
    val visibility: String = "PUBLIC",
    val createdAt: Long,
    val editedAt: Long? = null,
    val likeCount: Int = 0,
    val commentCount: Int = 0,
    val likedByMe: Boolean = false,
    val isMine: Boolean = false
)

@Serializable
data class PostCommentDto(
    val id: String,
    val postId: String,
    val author: UserDto,
    val content: String,
    val createdAt: Long,
    val isMine: Boolean = false,
    /** 1.76：被回复评论 id（null=顶级评论）。 */
    val parentId: String? = null,
    /** 1.52：评论点赞数与我是否已赞。 */
    val likeCount: Int = 0,
    val likedByMe: Boolean = false
)

/** 1.93：动态点赞者列表（最新在前，过滤双向拉黑）。 */
@Serializable
data class PostLikersResponse(
    val postId: String,
    val likers: List<UserDto> = emptyList()
)

@Serializable
data class CreatePostRequest(
    val content: String = "",
    val imageUrls: List<String> = emptyList(),
    val visibility: String? = null,
    val useDefaultVisibility: Boolean = true
)

@Serializable
data class CreateCommentRequest(val content: String, /** 1.76：回复目标评论 id（可选）。 */ val replyToId: String? = null)

/** 1.52：评论点赞/取消点赞响应。 */
@Serializable
data class CommentLikeResponse(
    val status: String = "",
    val likeCount: Int = 0
)

@Serializable
data class UploadPostImageRequest(val base64Data: String)

@Serializable
data class UploadPostImageResponse(val status: String, val imageUrl: String)

@Serializable
data class EditPostRequest(val content: String = "", val visibility: String? = null)
