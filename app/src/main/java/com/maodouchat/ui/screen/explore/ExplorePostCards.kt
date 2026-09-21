package com.maodouchat.ui.screen.explore

import android.annotation.SuppressLint
import android.text.format.DateUtils
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.maodouchat.ui.component.SearchHighlightSurface
import com.maodouchat.ui.component.SearchHighlightAccent
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maodouchat.R
import com.maodouchat.network.PostDto
import com.maodouchat.network.PostCommentDto
import com.maodouchat.ui.component.Avatar
import com.maodouchat.ui.component.AvatarSize
import com.maodouchat.ui.theme.LocalMotionSettings
import kotlinx.coroutines.launch
import com.maodouchat.ui.theme.LocalChatPalette
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * 「发现」页的帖子卡片与评论对话框（G122 从 `ExploreScreen.kt` 拆出，原 555 行）。
 *
 * 四个 Composable：`PostCard`（动态卡片）、`AnimatedLikeButton`（点赞动效按钮）、
 * `CommentsDialog`（完整评论列表 + 点赞 + 评论输入）、`LoadingMoreBlock`（分页加载态）。
 * 加四个私有辅助：`visibilityLabel` / `visibilityOptionLabel` / `relativeTimeLocalized` /
 * `highlightedText`——它们在 `ExploreScreen.kt` / `ExplorePostDetailScreen.kt` /
 * `ExploreMomentsScreen.kt` 各有一份同名 private 副本，这是本代码库的既有模式。
 *
 * **拆解约束**：不抓任何全局单例、不读数据库、不 import `MaodouchatApp`。
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

@Composable
@SuppressLint("LocalContextGetResourceValueCall") // 资源字符串均在回调内读取，非组合作用域
internal fun PostCard(
    post: PostDto,
    modifier: Modifier = Modifier,
    onLike: () -> Unit,
    onComment: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit = {},
    onShare: () -> Unit = {},
    onReport: () -> Unit = {},
    onBlock: () -> Unit = {},
    onShowLikers: () -> Unit = {},
    onOpenPost: () -> Unit = {},
    onOpenAuthor: () -> Unit = {}
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(22.dp), modifier = modifier) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable(onClick = onOpenAuthor)
            ) {
                Avatar(name = post.author.name, avatarUrl = post.author.avatar, size = AvatarSize.MD, isOnline = post.author.isOnline)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(post.author.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                    // 1.150：作者个性签名（与详情页/作者主页一致）
                    if (post.author.status.isNotBlank()) {
                        Text(
                            post.author.status,
                            style = MaterialTheme.typography.bodySmall,
                            color = LocalChatPalette.current.textSecondary,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(relativeTimeLocalized(post.createdAt), style = MaterialTheme.typography.bodySmall, color = LocalChatPalette.current.textSecondary)
                        if (post.editedAt != null) {
                            Text(stringResource(R.string.explore_edited), style = MaterialTheme.typography.bodySmall, color = LocalChatPalette.current.textSecondary)
                        }
                        Text(visibilityLabel(post.visibility), style = MaterialTheme.typography.bodySmall, color = LocalChatPalette.current.textSecondary)
                        // 1.147：作者在线/最后在线（与详情页一致）
                        if (post.author.isOnline) {
                            Text(stringResource(R.string.chat_online), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        } else if (post.author.lastSeen > 0L) {
                            Text(
                                stringResource(R.string.user_last_seen_prefix) + " " +
                                    android.text.format.DateUtils.getRelativeTimeSpanString(
                                        post.author.lastSeen,
                                        System.currentTimeMillis(),
                                        android.text.format.DateUtils.MINUTE_IN_MILLIS
                                    ),
                                style = MaterialTheme.typography.bodySmall,
                                color = LocalChatPalette.current.textSecondary
                            )
                        }
                    }
                }
                if (post.isMine) {
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.explore_edit_post), tint = LocalChatPalette.current.textSecondary)
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Outlined.DeleteOutline, contentDescription = stringResource(R.string.chat_delete), tint = LocalChatPalette.current.textSecondary)
                    }
                } else {
                    // 1.06：举报他人动态
                    IconButton(onClick = onReport) {
                        Icon(Icons.Outlined.Flag, contentDescription = stringResource(R.string.explore_report_post), tint = LocalChatPalette.current.textSecondary)
                    }
                    // 1.172：屏蔽该作者（防骚扰）
                    IconButton(onClick = onBlock) {
                        Icon(Icons.Outlined.Block, contentDescription = stringResource(R.string.explore_block_author), tint = LocalChatPalette.current.textSecondary)
                    }
                }
            }
            if (post.content.isNotBlank()) {
                Text(
                    post.content,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 6,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.clickable(onClick = onOpenPost)
                )
            }
            if (post.imageUrls.isNotEmpty()) {
                ImageGrid(post.imageUrls)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                AnimatedLikeButton(likedByMe = post.likedByMe, onLike = onLike)
                Spacer(Modifier.width(4.dp))
                Text(
                    ExploreFeedPolicy.formatCount(post.likeCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalChatPalette.current.textSecondary,
                    modifier = Modifier
                        .clickable(enabled = post.likeCount > 0, onClick = onShowLikers)
                        .padding(vertical = 4.dp)
                )
                Spacer(Modifier.width(16.dp))
                TextButton(onClick = onComment) {
                    Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = stringResource(R.string.explore_comment), tint = LocalChatPalette.current.textSecondary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(ExploreFeedPolicy.formatCount(post.commentCount))
                }
                Spacer(Modifier.width(16.dp))
                // 1.01：动态分享到系统
                IconButton(onClick = onShare) {
                    Icon(Icons.Outlined.Share, contentDescription = stringResource(R.string.chat_share), tint = LocalChatPalette.current.textSecondary, modifier = Modifier.size(20.dp))
                }
                // 1.154：复制动态正文（评论可复制，正文此前不可）
                IconButton(onClick = {
                    val textToCopy = post.content.takeIf { it.isNotBlank() }
                        ?: if (post.imageUrls.isNotEmpty()) ctx.getString(R.string.explore_post_copied_image) else return@IconButton
                    val clipboard = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("post", textToCopy))
                    android.widget.Toast.makeText(ctx, ctx.getString(R.string.explore_post_copied), android.widget.Toast.LENGTH_SHORT).show()
                }) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.explore_copy_post), tint = LocalChatPalette.current.textSecondary, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable
private fun AnimatedLikeButton(likedByMe: Boolean, onLike: () -> Unit) {
    val motion = LocalMotionSettings.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.78f else 1f,
        animationSpec = motion.springSpec(dampingRatio = 0.55f, stiffness = 520f),
        label = "likePressScale"
    )
    val likedScale by animateFloatAsState(
        targetValue = if (likedByMe) 1.12f else 1f,
        animationSpec = motion.springSpec(dampingRatio = 0.48f, stiffness = 360f),
        label = "likedScale"
    )
    val rotation by animateFloatAsState(
        targetValue = if (likedByMe) 12f else 0f,
        animationSpec = motion.springSpec(dampingRatio = 0.6f, stiffness = 280f),
        label = "likedRotation"
    )
    TextButton(onClick = onLike, interactionSource = interactionSource) {
        Icon(
            imageVector = if (likedByMe) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
            contentDescription = stringResource(R.string.explore_like),
            tint = if (likedByMe) Color(0xFFE91E63) else LocalChatPalette.current.textSecondary,
            modifier = Modifier
                .size(20.dp)
                .graphicsLayer {
                    scaleX = pressScale * likedScale
                    scaleY = pressScale * likedScale
                    rotationZ = rotation
                }
        )
    }
}

@Composable
internal fun LoadingMoreBlock() {
    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
    }
}

@Composable
private fun visibilityLabel(value: String): String = when (value) {
    "CONTACTS" -> stringResource(R.string.explore_visibility_contacts_label)
    "PRIVATE" -> stringResource(R.string.explore_visibility_private_label)
    else -> stringResource(R.string.explore_visibility_public)
}


@Composable
private fun relativeTimeLocalized(timestamp: Long): String {
    val now = System.currentTimeMillis()
    if (RelativeTimePolicy.shouldUseJustNow(timestamp, now)) {
        return stringResource(R.string.time_just_now)
    }
    return DateUtils.getRelativeTimeSpanString(timestamp, now, DateUtils.MINUTE_IN_MILLIS).toString()
}

// G156：原私有副本（18 行）收敛到 ui/component/SearchHighlightText.kt，此处仅剩薄包装。
@Composable
private fun highlightedText(text: String, query: String): AnnotatedString {
    val (c, bg) = SearchHighlightAccent
    return com.maodouchat.ui.component.highlightedText(text, query, c, bg)
}
