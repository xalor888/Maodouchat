package com.maodouchat.ui.screen.explore

import android.net.Uri
import com.maodouchat.network.PostCommentDto
import com.maodouchat.network.PostDto
import com.maodouchat.network.UserDto
import java.util.UUID

data class PostImageDraft(
    val id: String = UUID.randomUUID().toString(),
    val uri: Uri,
    val uploadUrl: String? = null,
    val isUploading: Boolean = true,
    val errorMessage: String? = null
)

data class VisibilityOption(val value: String, val label: String, val subtitle: String = "")

data class ExploreUiState(
    val posts: List<PostDto> = emptyList(),
    val detailPost: PostDto? = null,
    val comments: List<PostCommentDto> = emptyList(),
    val selectedPostId: String? = null,
    val postPendingDeleteId: String? = null,
    val postPendingEditId: String? = null,
    val editPostText: String = "",
    val editPostVisibility: String = "PUBLIC",
    val isEditingPost: Boolean = false,
    val composerText: String = "",
    /** 1.177：发布框已从本地恢复草稿（用户编辑或发布后清除）。 */
    val composerDraftRestored: Boolean = false,
    /** 1.196：发布成功计数器（UI 据此滚动流到顶部）。 */
    val publishRevision: Int = 0,
    val commentText: String = "",
    /** 1.76：正在回复的评论（null=顶级评论）。 */
    val replyToComment: PostCommentDto? = null,
    val selectedVisibility: String = "PRIVATE",
    val defaultPostVisibility: String? = null,
    val useDefaultPostVisibility: Boolean = true,
    val isVisibilityReady: Boolean = false,
    val imageDrafts: List<PostImageDraft> = emptyList(),
    val isLoading: Boolean = false,
    val isPostDetailLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val isPublishing: Boolean = false,
    val isCommentsLoading: Boolean = false,
    val isLoadingOlderComments: Boolean = false,
    val isSendingComment: Boolean = false,
    val isSavingCommentEdit: Boolean = false,
    val hasMoreComments: Boolean = true,
    val hasMore: Boolean = true,
    val errorMessage: String? = null,
    /** Feed 首屏/刷新失败且列表为空时保留，避免 snackbar 消费后只剩「还没有动态」。 */
    val feedErrorMessage: String? = null,
    val postDetailError: String? = null,
    val infoMessage: String? = null,
    /** 1.93：动态点赞者弹窗。 */
    val likersPostId: String? = null,
    val likers: List<UserDto> = emptyList(),
    val isLikersLoading: Boolean = false
) {
    val readyImageUrls: List<String>
        get() = imageDrafts.mapNotNull { it.uploadUrl }

    val isUploadingImage: Boolean
        get() = imageDrafts.any { it.isUploading }

    val canPublish: Boolean
        get() = isVisibilityReady && !isPublishing && !isUploadingImage &&
            (composerText.isNotBlank() || readyImageUrls.isNotEmpty())
}
