package com.maodouchat.ui.screen.explore

import com.maodouchat.notification.SocialNotificationService
import android.annotation.SuppressLint
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import android.text.format.DateUtils
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Send
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import com.maodouchat.ui.component.SearchHighlightSurface
import com.maodouchat.ui.component.SearchHighlightAccent
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.maodouchat.R
import com.maodouchat.network.TokenManager
import com.maodouchat.ui.component.Avatar
import com.maodouchat.ui.component.AvatarSize
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.maodouchat.ui.theme.LocalChatPalette
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * 「动态详情」页（G115 从 `ExploreSubScreens.kt` 拆出，原 659 行）。
 *
 * 完整评论列表 + 点赞 + 评论输入。含 `CommentComposerBar`（1.97 详情页评论输入条，
 * 支持回复目标提示条）与 `highlightedText`（搜索关键词高亮）。
 *
 * 内含一条容易在后续改动中被破坏的判定：**1.132 通知跳转定位到具体评论**
 * （best-effort：评论在已加载集合中时滚动到该条）。
 *
 * **拆解约束**：不抓任何全局单例、不读数据库、不 import `MaodouchatApp`。
 * 纯搬移，不改判断。
 */

/**
 * 「动态详情」页：完整评论列表 + 点赞
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
// 资源字符串均在回调/协程内读取，非组合作用域
@SuppressLint("LocalContextGetResourceValueCall")
fun PostDetailScreen(
    postId: String,
    onBack: () -> Unit = {},
    onOpenAuthor: (String) -> Unit = {},
    // 1.132：通知跳转定位到具体评论（best-effort：评论在已加载集合中时滚动）
    initialCommentId: String? = null,
    viewModel: ExploreViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val currentUserId = com.maodouchat.network.TokenManager.getInstance(context).getUserId().orEmpty()
    // 1.101：发送新评论后自动滚动到底部（分页加载不触发——它在头部插入，末条 id 不变）
    val commentListState = androidx.compose.foundation.lazy.rememberLazyListState()
    val commentListScope = androidx.compose.runtime.rememberCoroutineScope()
    val prevLastCommentId = androidx.compose.runtime.remember { mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(state.comments.lastOrNull()?.id) {
        val lastId = state.comments.lastOrNull()?.id
        val prev = prevLastCommentId.value
        prevLastCommentId.value = lastId
        if (prev != null && lastId != null && prev != lastId && state.comments.isNotEmpty()) {
            commentListState.animateScrollToItem(state.comments.lastIndex)
        }
    }
    // 1.132：通知跳转定位到指定评论（评论出现在已加载集合后滚动一次）；1.134：附带短暂高亮
    val commentTargetHandled = androidx.compose.runtime.remember(initialCommentId) { mutableStateOf(false) }
    var highlightedCommentId by androidx.compose.runtime.remember(initialCommentId) { mutableStateOf<String?>(null) }
    // 1.247：评论长按操作菜单（回复/复制/举报/删除）
    var commentMenuFor by androidx.compose.runtime.remember { mutableStateOf<com.maodouchat.network.PostCommentDto?>(null) }
    // 评论编辑弹窗（1.xx：编辑自己的评论）
    var editingCommentFor by androidx.compose.runtime.remember { mutableStateOf<com.maodouchat.network.PostCommentDto?>(null) }
    var editingCommentText by androidx.compose.runtime.remember { mutableStateOf("") }
    androidx.compose.runtime.LaunchedEffect(state.comments.map { it.id }, commentTargetHandled.value) {
        if (!commentTargetHandled.value && !initialCommentId.isNullOrBlank()) {
            val targetIndex = state.comments.indexOfFirst { it.id == initialCommentId }
            if (targetIndex >= 0) {
                commentListState.scrollToItem(targetIndex)
                commentTargetHandled.value = true
                highlightedCommentId = initialCommentId
                kotlinx.coroutines.delay(2_500L)
                highlightedCommentId = null
            }
        }
    }
    androidx.compose.runtime.LaunchedEffect(postId) {
        if (postId.isNotBlank()) {
            com.maodouchat.notification.SocialNotificationService.cancelPostInteraction(context.applicationContext, postId)
        }
        viewModel.openPostDetail(postId)
    }
    val comments = state.comments
    val isLoading = state.isCommentsLoading
    val post = state.detailPost?.takeIf { it.id == postId }
        ?: state.posts.firstOrNull { it.id == postId }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.explore_post_details), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f))
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (post == null && state.isPostDetailLoading) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(140.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (post == null) {
                val detailError = state.postDetailError
                if (detailError != null) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(detailError, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { viewModel.loadPostDetail(postId) }) {
                            Text(stringResource(R.string.empty_state_retry))
                        }
                    }
                }
            }
            if (post != null) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { onOpenAuthor(post.author.id) }
                        ) {
                            Avatar(name = post.author.name, avatarUrl = post.author.avatar, size = AvatarSize.SM, isOnline = post.author.isOnline)
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(post.author.name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                                // 1.123：作者状态（个性签名，非空时显示，一行省略）
                                if (post.author.status.isNotBlank()) {
                                    Text(
                                        post.author.status,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = LocalChatPalette.current.textSecondary,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                }
                                Text(relativeTime(post.createdAt), style = MaterialTheme.typography.bodySmall, color = LocalChatPalette.current.textSecondary)
                            }
                            // 1.173：详情页屏蔽该作者（他人动态）
                            if (post.author.id != currentUserId) {
                                IconButton(onClick = { viewModel.blockPostAuthor(post.author.id) }) {
                                    Icon(
                                        androidx.compose.material.icons.Icons.Outlined.Block,
                                        contentDescription = stringResource(R.string.explore_block_author),
                                        tint = LocalChatPalette.current.textSecondary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                // 1.309：详情页举报动态（与动态流卡片一致）
                                IconButton(onClick = { viewModel.reportPost(post) }) {
                                    Icon(
                                        Icons.Outlined.Flag,
                                        contentDescription = stringResource(R.string.explore_report_post),
                                        tint = LocalChatPalette.current.textSecondary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                        // 1.138：作者在线/最后在线状态（在线点已示，补文字更明确）
                        if (post.author.isOnline) {
                            Text(
                                stringResource(R.string.chat_online),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else if (post.author.lastSeen > 0L) {
                            Text(
                                stringResource(R.string.user_last_seen_prefix) + " " +
                                    android.text.format.DateUtils.getRelativeTimeSpanString(
                                        post.author.lastSeen,
                                        System.currentTimeMillis(),
                                        android.text.format.DateUtils.MINUTE_IN_MILLIS
                                    ),
                                style = MaterialTheme.typography.labelSmall,
                                color = LocalChatPalette.current.textSecondary
                            )
                        }
                        if (post.content.isNotBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(post.content, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                        }
                        // 1.99：详情页展示图片网格（点击全屏查看）
                        if (post.imageUrls.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            ImageGrid(post.imageUrls)
                        }
                        // 1.94：详情页点赞/评论操作行（点赞数可点击查看点赞者）
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { viewModel.toggleLike(post) }, modifier = Modifier.size(28.dp)) {
                                Icon(
                                    if (post.likedByMe) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
                                    contentDescription = stringResource(R.string.explore_like),
                                    tint = if (post.likedByMe) androidx.compose.ui.graphics.Color(0xFFE91E63) else LocalChatPalette.current.textSecondary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(Modifier.width(4.dp))
                            Text(
                                ExploreFeedPolicy.formatCount(post.likeCount),
                                style = MaterialTheme.typography.bodyMedium,
                                color = LocalChatPalette.current.textSecondary,
                                modifier = Modifier
                                    .clickable(enabled = post.likeCount > 0) { viewModel.openLikers(post.id) }
                                    .padding(vertical = 4.dp)
                            )
                            Spacer(Modifier.width(16.dp))
                            Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = stringResource(R.string.explore_comment), tint = LocalChatPalette.current.textSecondary, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(ExploreFeedPolicy.formatCount(post.commentCount), style = MaterialTheme.typography.bodyMedium, color = LocalChatPalette.current.textSecondary)
                            Spacer(Modifier.width(16.dp))
                            // 1.181：详情页复制正文（纯图时复制 [图片]）
                            IconButton(onClick = {
                                val textToCopy = buildString {
                                    append(post.content)
                                    if (post.imageUrls.isNotEmpty()) {
                                        if (isNotBlank()) append("\n")
                                        // 9.248：硬编码中文改字符串资源（英文界面复制不再中英混杂）
                                        append(context.getString(R.string.explore_post_copied_image))
                                    }
                                }.ifBlank { context.getString(R.string.explore_post_fallback) }
                                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                clipboard.setPrimaryClip(android.content.ClipData.newPlainText(context.getString(R.string.explore_post_fallback), textToCopy))
                                Toast.makeText(context, context.getString(R.string.explore_copied), Toast.LENGTH_SHORT).show()
                            }) {
                                Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.chat_copy), tint = LocalChatPalette.current.textSecondary, modifier = Modifier.size(20.dp))
                            }
                            // 1.100：详情页分享（与动态流一致）
                            IconButton(onClick = {
                                val shareText = buildString {
                                    append(post.content.takeIf { it.isNotBlank() }?.let { "\"$it\"" } ?: "")
                                    if (post.imageUrls.isNotEmpty()) {
                                        if (isNotBlank()) append("\n")
                                        append("[图片]")
                                    }
                                    // 1.140：附作者公开主页链接（9.289：跟随当前服务器地址，自建部署不再分享无效的官服域名）
                                    val authorUsername = post.author.username?.takeIf { it.isNotBlank() }
                                    if (authorUsername != null) {
                                        if (isNotBlank()) append("\n")
                                        append(com.maodouchat.network.ApiConfig.BASE_URL.trimEnd('/')).append("/u/").append(authorUsername)
                                    }
                                }.ifBlank { "动态" }
                                val sendIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(android.content.Intent.EXTRA_TEXT, shareText)
                                }
                                runCatching {
                                    context.startActivity(
                                        android.content.Intent.createChooser(sendIntent, context.getString(R.string.chat_share))
                                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                }
                            }) {
                                Icon(Icons.Outlined.Share, contentDescription = stringResource(R.string.chat_share), tint = LocalChatPalette.current.textSecondary, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }
            HorizontalDivider(color = LocalChatPalette.current.textHint, thickness = 0.5.dp)
            Text(
                stringResource(R.string.explore_comments_count, post?.commentCount ?: comments.size),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp)
            )
            when {
                isLoading && comments.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                comments.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.explore_no_comments), color = LocalChatPalette.current.textHint)
                    }
                }
                else -> {
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
            // 1.104：父评论作者索引（避免逐条 O(n²) 扫描）
            val commentAuthorById = remember(comments) { comments.associateBy { it.id } }
            if (comments.size >= 5) {
                OutlinedTextField(
                    value = commentSearch,
                    onValueChange = { commentSearch = it.take(100) },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    placeholder = { Text(stringResource(R.string.explore_comment_search_hint)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
            if (filteredComments.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.explore_comment_search_empty), color = LocalChatPalette.current.textHint)
                }
            } else {
                LazyColumn(
                    state = commentListState,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (state.hasMoreComments || state.isLoadingOlderComments) {
                        item(key = "older_comments", contentType = "loading") {
                            TextButton(
                                onClick = viewModel::loadOlderComments,
                                enabled = state.hasMoreComments && !state.isLoadingOlderComments,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                if (state.isLoadingOlderComments) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text(
                                    stringResource(
                                        if (state.isLoadingOlderComments) R.string.explore_loading_older_comments
                                        else R.string.explore_load_older_comments
                                    )
                                )
                            }
                        }
                    }
                    items(filteredComments, key = { it.id }, contentType = { "post_comment" }) { c ->
                        Row(
                            verticalAlignment = Alignment.Top,
                            // 1.134：通知跳转目标评论短暂高亮
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (c.id == highlightedCommentId) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                                    else Color.Transparent,
                                    RoundedCornerShape(8.dp)
                                )
                                .padding(vertical = 2.dp)
                        ) {
                            Avatar(
                                name = c.author.name,
                                avatarUrl = c.author.avatar,
                                size = AvatarSize.SM,
                                isOnline = c.author.isOnline,
                                // 1.205：详情页评论头像点击打开作者主页
                                modifier = Modifier.clickable { onOpenAuthor(c.author.id) }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                // 1.200：详情页评论作者名 + 楼主徽章同行
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        c.author.name,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        // 1.213：点击作者名打开主页
                                        modifier = Modifier.weight(1f, fill = false).clickable { onOpenAuthor(c.author.id) }
                                    )
                                    if (post?.author?.id == c.author.id) {
                                        Spacer(Modifier.width(4.dp))
                                        Box(
                                            modifier = Modifier
                                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
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
                                // 1.104：详情页回复目标标识（与评论弹窗一致，父作者不存在时隐藏）；1.105：点击跳转父评论
                                if (!c.parentId.isNullOrBlank()) {
                                    val parentAuthor = commentAuthorById[c.parentId]?.author?.name
                                    if (!parentAuthor.isNullOrBlank()) {
                                        val parentIndex = filteredComments.indexOfFirst { it.id == c.parentId }
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
                                // 1.235：双击评论内容点赞；1.247：长按弹出操作菜单
                                Text(
                                    c.content,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.combinedClickable(
                                        onClick = {},
                                        onDoubleClick = { viewModel.toggleCommentLike(c) },
                                        onLongClick = { commentMenuFor = c }
                                    )
                                )
                                Text(relativeTime(c.createdAt), style = MaterialTheme.typography.bodySmall, color = LocalChatPalette.current.textSecondary)
                            }
                            // 1.95：详情页评论操作（点赞/删除自己评论）
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                if (c.author.id == currentUserId) {
                                    IconButton(onClick = { viewModel.deleteComment(c) }, modifier = Modifier.size(28.dp)) {
                                        Icon(
                                            Icons.Outlined.Delete,
                                            contentDescription = stringResource(R.string.explore_delete_comment),
                                            tint = LocalChatPalette.current.textSecondary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                                IconButton(onClick = { viewModel.toggleCommentLike(c) }, modifier = Modifier.size(28.dp)) {
                                    Icon(
                                        if (c.likedByMe) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                                        contentDescription = stringResource(R.string.explore_comment_like),
                                        tint = if (c.likedByMe) androidx.compose.ui.graphics.Color(0xFFE91E63) else LocalChatPalette.current.textSecondary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                // 1.245：举报评论（他人评论）
                                if (c.author.id != currentUserId) {
                                    IconButton(onClick = { viewModel.reportComment(c) }, modifier = Modifier.size(28.dp)) {
                                        Icon(
                                            Icons.Outlined.Flag,
                                            contentDescription = stringResource(R.string.explore_report_comment),
                                            tint = LocalChatPalette.current.textSecondary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                                if (c.likeCount > 0) {
                                    Text(
                                        c.likeCount.toString(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (c.likedByMe) androidx.compose.ui.graphics.Color(0xFFE91E63) else LocalChatPalette.current.textSecondary
                                    )
                                }
                                // 1.97：回复该评论
                                TextButton(
                                    onClick = { viewModel.setReplyToComment(c) },
                                    modifier = Modifier.height(28.dp),
                                    contentPadding = PaddingValues(horizontal = 4.dp)
                                ) {
                                    Text(
                                        stringResource(R.string.explore_comment_reply),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                // 1.115：复制评论文本（与评论弹窗一致）
                                TextButton(
                                    onClick = { viewModel.copyComment(c) },
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
            }

            // 1.97：评论输入条（始终可见；支持回复目标提示）
            HorizontalDivider(color = LocalChatPalette.current.textHint, thickness = 0.5.dp)
            CommentComposerBar(
                text = state.commentText,
                isSending = state.isSendingComment,
                replyToComment = state.replyToComment,
                onTextChange = viewModel::onCommentTextChange,
                onSend = viewModel::sendComment,
                onClearReply = viewModel::clearReplyToComment
            )
        }
    }

    // 1.93/1.94：详情页点赞者弹窗（与 Explore 共用）
    if (state.likersPostId != null) {
        LikersDialog(
            likers = state.likers,
            isLoading = state.isLikersLoading,
            onDismiss = viewModel::closeLikers
        )
    }

    // 1.247：评论长按操作菜单
    commentMenuFor?.let { target ->
        val isOwn = target.author.id == currentUserId
        AlertDialog(
            onDismissRequest = { commentMenuFor = null },
            title = { Text(stringResource(R.string.explore_comment_actions), style = MaterialTheme.typography.titleMedium) },
            text = {
                Column {
                    TextButton(onClick = { viewModel.setReplyToComment(target); commentMenuFor = null }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.explore_comment_reply), modifier = Modifier.fillMaxWidth())
                    }
                    TextButton(onClick = {
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText(context.getString(R.string.explore_comment), target.content))
                        Toast.makeText(context, context.getString(R.string.chat_copied), Toast.LENGTH_SHORT).show()
                        commentMenuFor = null
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.chat_copy), modifier = Modifier.fillMaxWidth())
                    }
                    if (isOwn) {
                        TextButton(onClick = {
                            editingCommentFor = target
                            editingCommentText = target.content
                            commentMenuFor = null
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.explore_edit_comment), modifier = Modifier.fillMaxWidth())
                        }
                        TextButton(onClick = { viewModel.deleteComment(target); commentMenuFor = null }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.explore_delete_comment), modifier = Modifier.fillMaxWidth(), color = LocalChatPalette.current.unreadRed)
                        }
                    } else {
                        TextButton(onClick = { viewModel.reportComment(target); commentMenuFor = null }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.explore_report_comment), modifier = Modifier.fillMaxWidth(), color = LocalChatPalette.current.unreadRed)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { commentMenuFor = null }) { Text(stringResource(R.string.explore_close)) } }
        )
    }

    editingCommentFor?.let { target ->
        AlertDialog(
            onDismissRequest = { editingCommentFor = null },
            title = { Text(stringResource(R.string.explore_edit_comment), style = MaterialTheme.typography.titleMedium) },
            text = {
                OutlinedTextField(
                    value = editingCommentText,
                    onValueChange = { editingCommentText = it.take(1_000) },
                    label = { Text(stringResource(R.string.explore_comment_edit_hint)) },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !state.isSavingCommentEdit,
                    onClick = {
                        viewModel.saveCommentEdit(target, editingCommentText)
                        editingCommentFor = null
                    }
                ) {
                    if (state.isSavingCommentEdit) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text(stringResource(R.string.common_save))
                    }
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !state.isSavingCommentEdit,
                    onClick = { editingCommentFor = null }
                ) {
                    Text(stringResource(R.string.explore_close))
                }
            }
        )
    }
}

// 1.97：详情页评论输入条（支持回复目标提示条，复用 sendComment/回复状态）
@Composable
private fun CommentComposerBar(
    text: String,
    isSending: Boolean,
    replyToComment: com.maodouchat.network.PostCommentDto?,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onClearReply: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (replyToComment != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
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
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
                TextButton(onClick = onClearReply) {
                    Text(stringResource(R.string.common_cancel), color = LocalChatPalette.current.textSecondary)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                placeholder = { Text(stringResource(R.string.explore_write_comment)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp)
            )
            IconButton(onClick = onSend, enabled = text.isNotBlank() && !isSending) {
                if (isSending) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                else Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = stringResource(R.string.explore_send), tint = MaterialTheme.colorScheme.primary)
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

@Composable
private fun relativeTime(ts: Long): String {
    val diff = System.currentTimeMillis() - ts
    return when {
        diff < 60_000 -> stringResource(R.string.time_just_now)
        diff < 3600_000 -> {
            val count = (diff / 60_000).toInt()
            pluralStringResource(R.plurals.time_minutes_ago, count, count)
        }
        diff < 86_400_000 -> {
            val count = (diff / 3600_000).toInt()
            pluralStringResource(R.plurals.time_hours_ago, count, count)
        }
        else -> {
            val count = (diff / 86_400_000).toInt()
            pluralStringResource(R.plurals.time_days_ago, count, count)
        }
    }
}
