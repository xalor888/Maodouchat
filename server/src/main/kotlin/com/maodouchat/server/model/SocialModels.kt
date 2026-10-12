package com.maodouchat.server.model

import kotlinx.serialization.Serializable

// 社交 DTO：动态帖子/评论/点赞与群成员管理。


@Serializable
data class CreatePostRequest(
    val content: String = "",
    val imageUrls: List<String> = emptyList(),
    val visibility: String? = null,
    val useDefaultVisibility: Boolean? = null
)


@Serializable
data class PostResponse(
    val id: String,
    val author: UserResponse,
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
data class EditPostRequest(val content: String = "", val visibility: String? = null)


@Serializable
data class CreateCommentRequest(val content: String, /** 1.76：回复目标评论 id（可选）。 */ val replyToId: String? = null)


@Serializable
data class UpdateCommentRequest(val content: String)


@Serializable
data class PostCommentResponse(
    val id: String,
    val postId: String,
    val author: UserResponse,
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
    val likers: List<UserResponse>
)


@Serializable
data class UploadPostImageRequest(val base64Data: String)


@Serializable
data class UploadPostImageResponse(val status: String, val imageUrl: String)


@Serializable
data class GroupMemberResponse(
    val userId: String,
    val name: String,
    val avatar: String? = null,
    val role: String = "MEMBER",
    val title: String? = null,
    val groupNickname: String? = null,
    val joinedAt: Long = 0,
    val isOnline: Boolean = false,
    val mutedUntil: Long = 0
)


@Serializable
data class UpdateMemberRoleRequest(val role: String)


@Serializable
data class UpdateGroupNicknameRequest(val groupNickname: String)


@Serializable
data class UpdateMemberTitleRequest(val title: String)


@Serializable
data class UpdateMemberMuteRequest(val mutedUntil: Long = 0)
