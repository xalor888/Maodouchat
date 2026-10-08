package com.maodouchat.ui.screen.explore

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maodouchat.R
import com.maodouchat.network.PostCommentDto
import com.maodouchat.ui.component.Avatar
import com.maodouchat.ui.component.AvatarSize
import com.maodouchat.ui.component.SearchHighlightAccent
import com.maodouchat.ui.theme.LocalChatPalette
import kotlinx.coroutines.launch

/**
 * 「发现」评论对话框簇（从 ExplorePostCards.kt 按簇拆出）。
 *
 * `CommentsDialog`（完整评论列表 + 点赞 + 评论输入）+ 私有 `highlightedText` 搜索高亮包装。
 * 纯搬移，不改判断。
 */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CommentsDialog(
    comments: List<PostCommentDto>,
    commentText: String,
    isLoading: Boolean,
    isLoadingOlder: Boolean,
    hasMore: Boolean,
    isSending: Boolean,
    onLoadOlder: () -> Unit,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onDismiss: () -> Unit,
    // 1.00：删除自己的评论
    currentUserId: String = "",
    onDeleteComment: (PostCommentDto) -> Unit = {},
    // 1.52：点赞/取消点赞评论
    onToggleLike: (PostCommentDto) -> Unit = {},
    // 1.76：回复评论
    replyToComment: PostCommentDto? = null,
    onReplyComment: (PostCommentDto) -> Unit = {},
    onClearReply: () -> Unit = {},
    // 1.92：复制评论文本
    onCopyComment: (PostCommentDto) -> Unit = {},
    // 1.183：点击评论头像/名字打开作者主页
    onOpenAuthor: (String) -> Unit = {},
    // 1.199：动态作者 id（用于「作者」徽章）
    postAuthorId: String? = null,
    // 1.246：举报评论
    onReportComment: (PostCommentDto) -> Unit = {}
) {
    // 1.86：回复标识点击跳转到父评论
    val commentListState = androidx.compose.foundation.lazy.rememberLazyListState()
    val commentListScope = androidx.compose.runtime.rememberCoroutineScope()
    // 1.118：发送新评论后自动滚动到底部（分页在头部插入不触发，末条 id 不变）
    val prevLastCommentId = androidx.compose.runtime.remember { mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(comments.lastOrNull()?.id) {
        val lastId = comments.lastOrNull()?.id
        val prev = prevLastCommentId.value
        prevLastCommentId.value = lastId
        if (prev != null && lastId != null && prev != lastId && comments.isNotEmpty()) {
            commentListState.animateScrollToItem(comments.lastIndex)
        }
    }
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                stringResource(R.string.explore_comments_count, comments.size),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (isLoading && comments.isEmpty()) {
                    Box(Modifier.fillMaxWidth().height(96.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else if (comments.isEmpty()) {
                    Text(stringResource(R.string.explore_no_comments), color = LocalChatPalette.current.textSecondary)
                } else {
                    var commentSearch by rememberSaveable { mutableStateOf("") }
                    val filteredComments = remember(comments, commentSearch) {
                        val query = commentSearch.trim()
                        if (query.isBlank()) {
                            comments
                        } else {
                            comments.filter {
                                it.content.contains(query, ignoreCase = true) ||
                                    it.author.name.contains(query, ignoreCase = true) ||
                                    it.author.id.contains(query, ignoreCase = true)
                            }
                        }
                    }
                    if (comments.size >= 5) {
                        OutlinedTextField(
                            value = commentSearch,
                            onValueChange = { commentSearch = it.take(100) },
                            singleLine = true,
                            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                            placeholder = { Text(stringResource(R.string.explore_comment_search_hint)) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (filteredComments.isEmpty()) {
                        Text(stringResource(R.string.explore_comment_search_empty), color = LocalChatPalette.current.textSecondary)
                    } else {
                        // 1.78：父评论作者索引（避免逐条 O(n²) 扫描）
                        val commentAuthorById = remember(comments) { comments.associateBy { it.id } }
                        // 评论列表：最大高度 50% 屏幕，小屏自动收缩
                        LazyColumn(
                            state = commentListState,
                            modifier = Modifier.fillMaxHeight(0.5f).heightIn(min = 120.dp, max = 360.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            if (hasMore || isLoadingOlder) {
                                item(key = "older_comments", contentType = "loading") {
                                    TextButton(
                                        onClick = onLoadOlder,
                                        enabled = hasMore && !isLoadingOlder,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        if (isLoadingOlder) {
                                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                            Spacer(Modifier.width(8.dp))
                                        }
                                        Text(
                                            stringResource(
                                                if (isLoadingOlder) R.string.explore_loading_older_comments
                                                else R.string.explore_load_older_comments
                                            )
                                        )
                                    }
                                }
                            }
                            items(filteredComments, key = { it.id }, contentType = { "comment" }) { comment ->
                                Row(verticalAlignment = Alignment.Top) {
                                    Avatar(
                                        name = comment.author.name,
                                        avatarUrl = comment.author.avatar,
                                        size = AvatarSize.SM,
                                        // 1.183：点击头像打开作者主页；1.203：评论者在线绿点
                                        isOnline = comment.author.isOnline,
                                        modifier = Modifier.clickable { onOpenAuthor(comment.author.id) }
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        // 1.199：作者名 + 楼主徽章同行显示
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                comment.author.name,
                                                fontWeight = FontWeight.SemiBold,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1,
                                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f, fill = false).clickable { onOpenAuthor(comment.author.id) }
                                            )
                                            // 1.199：楼主徽章（评论者即动态作者）
                                            if (!postAuthorId.isNullOrBlank() && comment.author.id == postAuthorId) {
                                                Spacer(Modifier.width(4.dp))
                                                Box(
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(4.dp))
                                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                                                        .padding(horizontal = 4.dp, vertical = 1.dp)
                                                ) {
                                                    Text(
                                                        stringResource(R.string.explore_comment_author_badge),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.primary
                                                    )
                                                }
                                            }
                                        }
                                        // 1.77：回复目标标识（找父评论作者）；1.86：点击跳转父评论
                                        if (!comment.parentId.isNullOrBlank()) {
                                            val parentAuthor = commentAuthorById[comment.parentId]?.author?.name
                                            if (!parentAuthor.isNullOrBlank()) {
                                                val parentIndex = filteredComments.indexOfFirst { it.id == comment.parentId }
                                                Text(
                                                    stringResource(R.string.explore_reply_to, parentAuthor),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier
                                                        .clickable(enabled = parentIndex >= 0) {
                                                            if (parentIndex >= 0) {
                                                                commentListScope.launch {
                                                                    commentListState.animateScrollToItem(parentIndex)
                                                                }
                                                            }
                                                        }
                                                        .padding(vertical = 1.dp)
                                                )
                                            }
                                        }
                                        // 1.250：双击评论内容点赞（与详情页 1.235 一致）；1.270：搜索时高亮关键词
                                        Text(
                                            if (commentSearch.isNotBlank()) highlightedText(comment.content, commentSearch) else androidx.compose.ui.text.AnnotatedString(comment.content),
                                            color = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.combinedClickable(
                                                onClick = {},
                                                onDoubleClick = { onToggleLike(comment) }
                                            )
                                        )
                                        Text(relativeTimeLocalized(comment.createdAt), style = MaterialTheme.typography.bodySmall, color = LocalChatPalette.current.textSecondary)
                                    }
                                    // 1.00：删除自己的评论 + 1.52：评论点赞
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        if (comment.author.id == currentUserId) {
                                            IconButton(onClick = { onDeleteComment(comment) }, modifier = Modifier.size(28.dp)) {
                                                Icon(
                                                    Icons.Outlined.Delete,
                                                    contentDescription = stringResource(R.string.explore_delete_comment),
                                                    tint = LocalChatPalette.current.textSecondary,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                        IconButton(onClick = { onToggleLike(comment) }, modifier = Modifier.size(28.dp)) {
                                            Icon(
                                                if (comment.likedByMe) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                                                contentDescription = stringResource(R.string.explore_comment_like),
                                                tint = if (comment.likedByMe) Color(0xFFE91E63) else LocalChatPalette.current.textSecondary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                        if (comment.likeCount > 0) {
                                            Text(
                                                comment.likeCount.toString(),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (comment.likedByMe) Color(0xFFE91E63) else LocalChatPalette.current.textSecondary
                                            )
                                        }
                                        // 1.246：举报评论（他人评论）
                                        if (comment.author.id != currentUserId) {
                                            IconButton(onClick = { onReportComment(comment) }, modifier = Modifier.size(28.dp)) {
                                                Icon(
                                                    Icons.Outlined.Flag,
                                                    contentDescription = stringResource(R.string.explore_report_comment),
                                                    tint = LocalChatPalette.current.textSecondary,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                        // 1.76：回复该评论
                                        TextButton(
                                            onClick = { onReplyComment(comment) },
                                            modifier = Modifier.height(28.dp),
                                            contentPadding = PaddingValues(horizontal = 4.dp)
                                        ) {
                                            Text(
                                                stringResource(R.string.explore_comment_reply),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                        // 1.92：复制评论文本
                                        TextButton(
                                            onClick = { onCopyComment(comment) },
                                            modifier = Modifier.height(28.dp),
                                            contentPadding = PaddingValues(horizontal = 4.dp)
                                        ) {
                                            Text(
                                                stringResource(R.string.explore_comment_copy),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = LocalChatPalette.current.textSecondary
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                // 1.76：回复目标提示条（可取消）；1.82：附父评论内容预览
                if (replyToComment != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.explore_reply_to, replyToComment.author.name),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                replyToComment.content,
                                style = MaterialTheme.typography.labelSmall,
                                color = LocalChatPalette.current.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        TextButton(onClick = onClearReply) {
                            Text(stringResource(R.string.common_cancel), color = LocalChatPalette.current.textSecondary)
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = commentText,
                        onValueChange = onTextChange,
                        placeholder = { Text(stringResource(R.string.explore_write_comment)) },
                        singleLine = true,
                        // 1.186：键盘「发送」键直接发送评论
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Send),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                            onSend = { if (commentText.isNotBlank() && !isSending) onSend() }
                        ),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp)
                    )
                    IconButton(onClick = onSend, enabled = commentText.isNotBlank() && !isSending) {
                        if (isSending) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = stringResource(R.string.explore_send), tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.explore_close))
            }
        }
    }
}


// G156：原私有副本（18 行）收敛到 ui/component/SearchHighlightText.kt，此处仅剩薄包装。
@Composable
private fun highlightedText(text: String, query: String): AnnotatedString {
    val (c, bg) = SearchHighlightAccent
    return com.maodouchat.ui.component.highlightedText(text, query, c, bg)
}
