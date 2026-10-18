package com.maodouchat.ui.screen.explore

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.maodouchat.R
import com.maodouchat.session.CurrentSession

@Composable
internal fun ExploreFeedDialogs(
    uiState: ExploreUiState,
    viewModel: ExploreViewModel,
    onOpenAuthor: (String) -> Unit
) {
    val selectedPostId = uiState.selectedPostId
    if (selectedPostId != null) {
        CommentsDialog(
            comments = uiState.comments,
            commentText = uiState.commentText,
            isLoading = uiState.isCommentsLoading,
            isLoadingOlder = uiState.isLoadingOlderComments,
            hasMore = uiState.hasMoreComments,
            isSending = uiState.isSendingComment,
            onLoadOlder = viewModel::loadOlderComments,
            onTextChange = viewModel::onCommentTextChange,
            onSend = viewModel::sendComment,
            onDismiss = viewModel::closeComments,
            // 1.00：删除自己的评论
            currentUserId = CurrentSession.ownerUserId(),
            onDeleteComment = viewModel::deleteComment,
            // 1.52：评论点赞
            onToggleLike = viewModel::toggleCommentLike,
            // 1.76：回复评论
            replyToComment = uiState.replyToComment,
            onReplyComment = viewModel::setReplyToComment,
            onClearReply = viewModel::clearReplyToComment,
            // 1.92：复制评论文本
            onCopyComment = viewModel::copyComment,
            // 1.183：评论头像/名字点击打开作者主页
            onOpenAuthor = { onOpenAuthor(it) },
            // 1.199：楼主（动态作者）徽章
            postAuthorId = uiState.detailPost?.author?.id
                ?: uiState.posts.firstOrNull { it.id == selectedPostId }?.author?.id,
            // 1.246：举报评论
            onReportComment = viewModel::reportComment
        )
    }

    // 1.93：动态点赞者弹窗
    if (uiState.likersPostId != null) {
        LikersDialog(
            likers = uiState.likers,
            isLoading = uiState.isLikersLoading,
            onDismiss = viewModel::closeLikers,
            // 1.184：点赞者点击打开作者主页
            onOpenUser = { onOpenAuthor(it) }
        )
    }

    if (uiState.postPendingDeleteId != null) {
        AlertDialog(
            onDismissRequest = viewModel::cancelDeletePost,
            title = { Text(stringResource(R.string.explore_delete_post)) },
            text = { Text(stringResource(R.string.explore_delete_post_message)) },
            confirmButton = { TextButton(onClick = viewModel::confirmDeletePost) { Text(stringResource(R.string.chat_delete), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = viewModel::cancelDeletePost) { Text(stringResource(R.string.common_cancel)) } }
        )
    }

    if (uiState.postPendingEditId != null) {
        AlertDialog(
            onDismissRequest = viewModel::cancelEditPost,
            title = { Text(stringResource(R.string.explore_edit_post)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = uiState.editPostText,
                        onValueChange = viewModel::onEditPostTextChange,
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 8,
                        placeholder = { Text(stringResource(R.string.explore_share_placeholder)) }
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        viewModel.visibilityOptions.forEach { option ->
                            FilterChip(
                                selected = uiState.editPostVisibility == option.value,
                                onClick = { viewModel.onEditPostVisibilitySelected(option.value) },
                                label = { Text(option.label, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = viewModel::confirmEditPost,
                    enabled = !uiState.isEditingPost && uiState.editPostText.isNotBlank()
                ) {
                    Text(if (uiState.isEditingPost) "…" else stringResource(R.string.common_save))
                }
            },
            dismissButton = { TextButton(onClick = viewModel::cancelEditPost) { Text(stringResource(R.string.common_cancel)) } }
        )
    }
}
