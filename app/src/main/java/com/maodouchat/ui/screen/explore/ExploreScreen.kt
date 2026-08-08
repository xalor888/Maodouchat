@file:Suppress("DEPRECATION")

package com.maodouchat.ui.screen.explore

import com.maodouchat.util.RuntimeFlags
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.NearMe
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.maodouchat.ui.component.OwnerScopedImageKeys
import com.maodouchat.R
import com.maodouchat.network.ApiService
import com.maodouchat.network.PostDto
import com.maodouchat.network.PostCommentDto
import com.maodouchat.ui.component.Avatar
import com.maodouchat.ui.component.AvatarSize
import com.maodouchat.ui.component.EmptyState
import com.maodouchat.ui.component.EmptyStateType
import com.maodouchat.ui.component.ShimmerPostCard
import com.maodouchat.ui.theme.Background
import com.maodouchat.ui.theme.Error
import com.maodouchat.ui.theme.LocalMotionSettings
import com.maodouchat.ui.theme.MaodouchatTheme
import com.maodouchat.ui.theme.OnSurface
import com.maodouchat.ui.theme.Primary
import com.maodouchat.ui.theme.Secondary
import com.maodouchat.ui.theme.Surface
import com.maodouchat.ui.theme.TextSecondary
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ExploreScreen(
    onNavigateTo: (String) -> Unit = {},
    onOpenPost: (String) -> Unit = {},
    onOpenAuthor: (String) -> Unit = {},
    viewModel: ExploreViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val motion = LocalMotionSettings.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // 1.178：滚动较深时显示「回到最新」
    var showScrollToTop by remember { mutableStateOf(false) }
    var feedSearch by rememberSaveable { mutableStateOf("") }
    // 1.109：只看我发布的动态
    var showOnlyMine by rememberSaveable { mutableStateOf(false) }
    // 1.192：只看带图片的动态
    var showOnlyMedia by rememberSaveable { mutableStateOf(false) }
    val currentUserId = com.maodouchat.network.TokenManager.getInstance(context).getUserId().orEmpty()
    val filteredPosts = remember(uiState.posts, feedSearch, showOnlyMine, showOnlyMedia) {
        val mine = if (showOnlyMine) { post: PostDto ->
            post.isMine || post.author.id == currentUserId
        } else null
        val media = if (showOnlyMedia) { post: PostDto ->
            post.imageUrls.isNotEmpty()
        } else null
        val query = feedSearch.trim()
        var base = if (mine == null) uiState.posts else uiState.posts.filter(mine)
        if (media != null) base = base.filter(media)
        if (query.isBlank()) {
            base
        } else {
            base.filter { post ->
                post.content.contains(query, ignoreCase = true) ||
                    post.author.name.contains(query, ignoreCase = true) ||
                    post.author.id.contains(query, ignoreCase = true)
            }
        }
    }
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 9),
        onResult = { uris -> if (uris.isNotEmpty()) viewModel.addImages(uris) }
    )

    LaunchedEffect(uiState.errorMessage, uiState.infoMessage) {
        val message = uiState.errorMessage ?: uiState.infoMessage
        if (!message.isNullOrBlank()) {
            snackbarHostState.showSnackbar(message)
            viewModel.consumeMessage()
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow {
            val layoutInfo = listState.layoutInfo
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible >= layoutInfo.totalItemsCount - 3 && layoutInfo.totalItemsCount > 0
        }.distinctUntilChanged().collect { shouldLoadMore ->
            // 8.58：搜索过滤激活时暂停上拉加载——过滤视图不随分页增长，
            // 避免滚动到底持续拉取未过滤的下一页
            if (shouldLoadMore && feedSearch.isBlank()) viewModel.loadMore()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.entryNavigation.collect { target ->
            onNavigateTo(target)
        }
    }

    // 1.196：发布成功后滚动流到顶部（新动态在列表头部）
    LaunchedEffect(uiState.publishRevision) {
        if (uiState.publishRevision > 0) {
            listState.animateScrollToItem(0)
        }
    }

    // 1.178：滚动超过若干项后显示「回到最新」
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }.distinctUntilChanged().collect { index ->
            showScrollToTop = index > 4
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.nav_explore), style = MaterialTheme.typography.headlineMedium, color = OnSurface) },
                actions = {
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.explore_refresh))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f))
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = Background,
        // 1.178：回到最新
        floatingActionButton = {
            if (showScrollToTop) {
                SmallFloatingActionButton(
                    onClick = { scope.launch { listState.animateScrollToItem(0) } },
                    containerColor = Surface,
                    contentColor = Primary
                ) {
                    Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = stringResource(R.string.explore_back_to_top))
                }
            }
        }
    ) { padding ->
        // 1.108：动态流支持下拉刷新（复用会话列表 PullToRefreshLayout）
        com.maodouchat.ui.component.PullToRefreshLayout(
            isRefreshing = uiState.isLoading,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier.fillMaxSize()
        ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "entry_grid", contentType = "entry_grid") { EntryGrid(onEntryClick = viewModel::onEntryClick) }
            // 1.177：发布框已恢复上次未发布草稿 → 顶部提示
            if (uiState.composerDraftRestored) {
                item(key = "composer_draft_hint", contentType = "draft_hint") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Secondary.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Outlined.EditNote, contentDescription = null, tint = Secondary, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            stringResource(R.string.explore_composer_draft_restored),
                            style = MaterialTheme.typography.labelMedium,
                            color = Secondary,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { viewModel.dismissComposerDraftHint() }) {
                            Text(stringResource(R.string.common_close), color = TextSecondary, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
            item(key = "composer", contentType = "composer") {
                ComposerCard(
                    text = uiState.composerText,
                    imageDrafts = uiState.imageDrafts,
                    visibilityOptions = viewModel.visibilityOptions,
                    selectedVisibility = uiState.selectedVisibility,
                    isPublishing = uiState.isPublishing,
                    canPublish = uiState.canPublish,
                    onTextChange = viewModel::onComposerTextChange,
                    onPickImages = {
                        imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    // 1.158：从剪贴板粘贴图片（解码到缓存 file:// → addImages）
                    onPasteImages = {
                        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        val clip = cm.primaryClip
                        val item = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
                        val directUri = item?.uri?.takeIf { context.contentResolver.getType(it)?.startsWith("image/") == true }
                        if (directUri != null) {
                            viewModel.addImages(listOf(directUri))
                        } else if (item != null) {
                            androidx.compose.runtime.rememberCoroutineScope().launch(kotlinx.coroutines.Dispatchers.IO) {
                                val pasted = runCatching {
                                    val bmp = item.coerceToBitmap(context) ?: return@runCatching null
                                    val dir = java.io.File(context.cacheDir, "attachment-sources").apply { mkdirs() }
                                    val file = java.io.File(dir, "explore_paste_${System.currentTimeMillis()}.png")
                                    file.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, it) }
                                    bmp.recycle()
                                    android.net.Uri.fromFile(file)
                                }.getOrNull()
                                if (pasted != null) viewModel.addImages(listOf(pasted))
                                else android.widget.Toast.makeText(context, context.getString(R.string.explore_clipboard_no_image), android.widget.Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            android.widget.Toast.makeText(context, context.getString(R.string.explore_clipboard_no_image), android.widget.Toast.LENGTH_SHORT).show()
                        }
                    },
                    onRemoveImage = viewModel::removeImage,
                    // 1.211：重试失败上传
                    onRetryImage = viewModel::retryDraftImage,
                    onVisibilitySelected = viewModel::onVisibilitySelected,
                    // 1.202：清空发布框
                    onClear = viewModel::clearComposer,
                    onPublish = viewModel::publishPost
                )
            }

            if (!uiState.isLoading && uiState.posts.isNotEmpty()) {
                item(key = "feed_search", contentType = "feed_search") {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = feedSearch,
                            onValueChange = { feedSearch = it.take(160) },
                            singleLine = true,
                            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                            placeholder = { Text(stringResource(R.string.explore_feed_search_hint)) },
                            modifier = Modifier.weight(1f)
                        )
                        // 1.109：只看我的动态
                        FilterChip(
                            selected = showOnlyMine,
                            onClick = { showOnlyMine = !showOnlyMine },
                            label = { Text(stringResource(R.string.explore_feed_only_mine)) },
                            modifier = Modifier
                        )
                        // 1.192：只看带图片的动态
                        FilterChip(
                            selected = showOnlyMedia,
                            onClick = { showOnlyMedia = !showOnlyMedia },
                            label = { Text(stringResource(R.string.explore_feed_only_media)) },
                            modifier = Modifier
                        )
                    }
                }
            }

            when {
                uiState.isLoading -> item(key = "loading", contentType = "loading") { 
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        ShimmerPostCard()
                        ShimmerPostCard()
                    }
                }
                uiState.posts.isEmpty() -> item(key = "empty", contentType = "empty") { 
                    EmptyState(
                        title = stringResource(R.string.explore_empty_title),
                        subtitle = stringResource(R.string.explore_empty_subtitle),
                        type = EmptyStateType.MOMENTS,
                        // 1.131：空态直达发布框（composer 为列表第 2 项）
                        actionText = stringResource(R.string.explore_empty_action),
                        onAction = { listState.animateScrollToItem(1) }
                    )
                }
                filteredPosts.isEmpty() -> item(key = "search_empty", contentType = "empty") {
                    EmptyState(
                        title = stringResource(R.string.explore_feed_search_empty),
                        type = EmptyStateType.MOMENTS
                    )
                }
                else -> items(filteredPosts, key = { it.id }, contentType = { "post" }) { post ->
            PostCard(
                post = post,
                modifier = Modifier.animateItem(
                    fadeInSpec = motion.listItemFadeInSpec(),
                    fadeOutSpec = motion.listItemFadeOutSpec(),
                    placementSpec = motion.listItemPlacementSpec()
                ),
                onLike = { viewModel.toggleLike(post) },
                onComment = { viewModel.openComments(post.id) },
                onDelete = { viewModel.requestDeletePost(post.id) },
                onEdit = { viewModel.requestEditPost(post.id) },
                onReport = { viewModel.reportPost(post) },
                // 1.172：屏蔽该作者
                onBlock = { viewModel.blockPostAuthor(post.author.id) },
                // 1.93：点击点赞数查看点赞者
                onShowLikers = { viewModel.openLikers(post.id) },
                // 1.94：点击正文打开完整动态详情页
                onOpenPost = { onOpenPost(post.id) },
                // 1.110：点击作者行打开作者主页
                onOpenAuthor = { onOpenAuthor(post.author.id) },
                // 1.01：动态分享到系统（ACTION_SEND）；1.140：附作者公开主页链接
                onShare = {
                    val shareText = buildString {
                        append(post.content.takeIf { it.isNotBlank() }?.let { "\"$it\"" } ?: "")
                        if (post.imageUrls.isNotEmpty()) {
                            if (isNotBlank()) append("\n")
                            append("[图片]")
                        }
                        val authorUsername = post.author.username?.takeIf { it.isNotBlank() }
                        if (authorUsername != null) {
                            if (isNotBlank()) append("\n")
                            append("https://chat.mdou.me/u/").append(authorUsername)
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
                }
            )
                }
            }

            if (uiState.isLoadingMore) item(key = "loading_more", contentType = "loading") { LoadingMoreBlock() }
        }
        }
    }

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
            currentUserId = com.maodouchat.network.TokenManager.getInstance(context).getUserId().orEmpty(),
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
            confirmButton = { TextButton(onClick = viewModel::confirmDeletePost) { Text(stringResource(R.string.chat_delete), color = Error) } },
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

@Composable
private fun EntryGrid(onEntryClick: (String) -> Unit) {
    // 仅保留已实现的"朋友圈"（即本页动态流）；其余入口目前占位意义大于功能，暂不展示。
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val postsEnabled = RuntimeFlags.isEnabled(ctx, RuntimeFlags.POSTS)
    val nearbyEnabled = RuntimeFlags.isEnabled(ctx, RuntimeFlags.NEARBY)
    val entries = buildList {
        if (postsEnabled) {
            add(EntryItem("moments", stringResource(R.string.explore_moments), stringResource(R.string.explore_moments_subtitle), Icons.Outlined.Public))
        }
        add(EntryItem("scan", stringResource(R.string.explore_scan), stringResource(R.string.explore_scan_subtitle), Icons.Outlined.QrCodeScanner))
        if (nearbyEnabled) {
            add(EntryItem("nearby", stringResource(R.string.explore_nearby), stringResource(R.string.explore_nearby_subtitle), Icons.Outlined.NearMe))
        }
    }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        entries.forEach { entry ->
            val interactionSource = remember { MutableInteractionSource() }
            val pressed by interactionSource.collectIsPressedAsState()
            val cardScale by animateFloatAsState(
                targetValue = if (pressed) 0.96f else 1f,
                animationSpec = spring(dampingRatio = 0.7f, stiffness = 450f),
                label = "entryCardScale_${entry.id}"
            )
            Card(
                onClick = { onEntryClick(entry.id) },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(20.dp),
                interactionSource = interactionSource,
                modifier = Modifier
                    .weight(1f)
                    .height(88.dp)
                    .graphicsLayer {
                        scaleX = cardScale
                        scaleY = cardScale
                    }
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxSize().padding(14.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(42.dp).background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.12f), CircleShape)
                    ) {
                        Icon(entry.icon, contentDescription = entry.title, tint = Primary)
                    }
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(entry.title, style = MaterialTheme.typography.titleMedium, color = OnSurface, fontWeight = FontWeight.SemiBold)
                        Text(entry.subtitle, style = MaterialTheme.typography.bodySmall, color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun ComposerCard(
    text: String,
    imageDrafts: List<PostImageDraft>,
    visibilityOptions: List<VisibilityOption>,
    selectedVisibility: String,
    isPublishing: Boolean,
    canPublish: Boolean,
    onTextChange: (String) -> Unit,
    onPickImages: () -> Unit,
    // 1.158：从剪贴板粘贴图片
    onPasteImages: () -> Unit,
    onRemoveImage: (String) -> Unit,
    // 1.211：重试失败的上传图片
    onRetryImage: (String) -> Unit = {},
    onVisibilitySelected: (String) -> Unit,
    // 1.202：清空发布框
    onClear: () -> Unit = {},
    onPublish: () -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(22.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.explore_share_title), style = MaterialTheme.typography.titleMedium, color = OnSurface, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (text.isNotBlank() || imageDrafts.isNotEmpty()) {
                    TextButton(onClick = onClear) {
                        Text(stringResource(R.string.explore_composer_clear), color = TextSecondary, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            // 1.207：点击已选图片预览大图
            var previewDraftUri by remember { mutableStateOf<String?>(null) }
            if (previewDraftUri != null) {
                androidx.compose.ui.window.Dialog(
                    onDismissRequest = { previewDraftUri = null },
                    properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.95f))
                            .clickable { previewDraftUri = null },
                        contentAlignment = Alignment.Center
                    ) {
                        AsyncImage(
                            model = OwnerScopedImageKeys.request(
                                context = androidx.compose.ui.platform.LocalContext.current,
                                data = previewDraftUri,
                            ),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize().padding(16.dp)
                        )
                    }
                }
            }
            OutlinedTextField(
                value = text,
                onValueChange = { if (it.length <= 2000) onTextChange(it) },
                placeholder = { Text(stringResource(R.string.explore_share_placeholder)) },
                minLines = 2,
                maxLines = 5,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                supportingText = {
                    Text(
                        "${text.length}/2000",
                        color = if (text.length > 1800) Error else TextSecondary,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            )
            VisibilitySelector(
                options = visibilityOptions,
                selectedVisibility = selectedVisibility,
                onVisibilitySelected = onVisibilitySelected
            )
            if (imageDrafts.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    imageDrafts.forEachIndexed { index, draft ->
                        Box(modifier = Modifier.size(82.dp)) {
                            AsyncImage(
                                model = OwnerScopedImageKeys.request(
                                    context = androidx.compose.ui.platform.LocalContext.current,
                                    data = draft.uri,
                                ),
                                contentDescription = stringResource(R.string.explore_selected_image),
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(Surface)
                                    // 1.207：点击预览大图
                                    .then(if (!draft.isUploading) Modifier.clickable { previewDraftUri = draft.uri } else Modifier)
                            )
                            if (draft.isUploading || draft.errorMessage != null) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = 0.42f))
                                ) {
                                    if (draft.isUploading) {
                                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp, color = Color.White)
                                    } else {
                                        // 1.211：上传失败 → 点击重试
                                        IconButton(onClick = { onRetryImage(draft.id) }, modifier = Modifier.size(28.dp)) {
                                            Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.explore_retry_upload), tint = Color.White, modifier = Modifier.size(20.dp))
                                        }
                                    }
                                }
                            }
                            IconButton(
                                onClick = { onRemoveImage(draft.id) },
                                modifier = Modifier.align(Alignment.TopEnd).size(28.dp).background(Color.Black.copy(alpha = 0.45f), CircleShape)
                            ) {
                                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.explore_remove), tint = Color.White, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = onPickImages,
                    enabled = imageDrafts.size < 9 && !isPublishing,
                    label = { Text(stringResource(R.string.explore_add_images, imageDrafts.size)) },
                    leadingIcon = { Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(18.dp)) }
                )
                // 1.158：从剪贴板粘贴图片
                AssistChip(
                    onClick = onPasteImages,
                    enabled = imageDrafts.size < 9 && !isPublishing,
                    label = { Text(stringResource(R.string.explore_paste_images)) },
                    leadingIcon = { Icon(Icons.Outlined.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp)) }
                )
                Spacer(Modifier.weight(1f))
                Button(onClick = onPublish, enabled = canPublish) {
                    if (isPublishing) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                    else Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.explore_publish))
                }
            }
        }
    }
}

@Composable
private fun VisibilitySelector(
    options: List<VisibilityOption>,
    selectedVisibility: String,
    onVisibilitySelected: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.explore_visibility_title), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option ->
                FilterChip(
                    selected = selectedVisibility == option.value,
                    onClick = { onVisibilitySelected(option.value) },
                    label = { Text(visibilityOptionLabel(option.value)) }
                )
            }
        }
    }
}

@Composable
private fun PostCard(
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
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(22.dp), modifier = modifier) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable(onClick = onOpenAuthor)
            ) {
                Avatar(name = post.author.name, avatarUrl = post.author.avatar, size = AvatarSize.MD, isOnline = post.author.isOnline)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(post.author.name, style = MaterialTheme.typography.titleMedium, color = OnSurface, fontWeight = FontWeight.SemiBold)
                    // 1.150：作者个性签名（与详情页/作者主页一致）
                    if (post.author.status.isNotBlank()) {
                        Text(
                            post.author.status,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(relativeTime(post.createdAt), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                        if (post.editedAt != null) {
                            Text(stringResource(R.string.explore_edited), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                        }
                        Text(visibilityLabel(post.visibility), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                        // 1.147：作者在线/最后在线（与详情页一致）
                        if (post.author.isOnline) {
                            Text(stringResource(R.string.chat_online), style = MaterialTheme.typography.bodySmall, color = Primary)
                        } else if (post.author.lastSeen > 0L) {
                            Text(
                                stringResource(R.string.user_last_seen_prefix) + " " +
                                    android.text.format.DateUtils.getRelativeTimeSpanString(
                                        post.author.lastSeen,
                                        System.currentTimeMillis(),
                                        android.text.format.DateUtils.MINUTE_IN_MILLIS
                                    ),
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                    }
                }
                if (post.isMine) {
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.explore_edit_post), tint = TextSecondary)
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Outlined.DeleteOutline, contentDescription = stringResource(R.string.chat_delete), tint = TextSecondary)
                    }
                } else {
                    // 1.06：举报他人动态
                    IconButton(onClick = onReport) {
                        Icon(Icons.Outlined.Flag, contentDescription = stringResource(R.string.explore_report_post), tint = TextSecondary)
                    }
                    // 1.172：屏蔽该作者（防骚扰）
                    IconButton(onClick = onBlock) {
                        Icon(Icons.Outlined.Block, contentDescription = stringResource(R.string.explore_block_author), tint = TextSecondary)
                    }
                }
            }
            if (post.content.isNotBlank()) {
                Text(
                    post.content,
                    style = MaterialTheme.typography.bodyLarge,
                    color = OnSurface,
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
                    color = TextSecondary,
                    modifier = Modifier
                        .clickable(enabled = post.likeCount > 0, onClick = onShowLikers)
                        .padding(vertical = 4.dp)
                )
                Spacer(Modifier.width(16.dp))
                TextButton(onClick = onComment) {
                    Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = stringResource(R.string.explore_comment), tint = TextSecondary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(ExploreFeedPolicy.formatCount(post.commentCount))
                }
                Spacer(Modifier.width(16.dp))
                // 1.01：动态分享到系统
                IconButton(onClick = onShare) {
                    Icon(Icons.Outlined.Share, contentDescription = stringResource(R.string.chat_share), tint = TextSecondary, modifier = Modifier.size(20.dp))
                }
                // 1.154：复制动态正文（评论可复制，正文此前不可）
                IconButton(onClick = {
                    val ctx = androidx.compose.ui.platform.LocalContext.current
                    val textToCopy = post.content.takeIf { it.isNotBlank() }
                        ?: if (post.imageUrls.isNotEmpty()) ctx.getString(R.string.explore_post_copied_image) else return@IconButton
                    val clipboard = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("post", textToCopy))
                    android.widget.Toast.makeText(ctx, ctx.getString(R.string.explore_post_copied), android.widget.Toast.LENGTH_SHORT).show()
                }) {
                    Icon(androidx.compose.material.icons.outlined.ContentCopy, contentDescription = stringResource(R.string.explore_copy_post), tint = TextSecondary, modifier = Modifier.size(20.dp))
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
            tint = if (likedByMe) Color(0xFFE91E63) else TextSecondary,
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
// 1.94：动态图片网格（Explore 与 PostDetail 共用；点击全屏查看）
@Composable
internal fun ImageGrid(imageUrls: List<String>) {
    val columns = when (imageUrls.size) {
        1 -> 1
        2, 4 -> 2
        else -> 3
    }
    var galleryIndex by remember { mutableStateOf<Int?>(null) }
    // 1.129：保存图片时的下载状态（按钮转圈防重复点击）
    var savingImage by remember { mutableStateOf(false) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        imageUrls.forEachIndexed { index, url ->
            // 单图时用更宽的比例展示，多图保持方格
            val eachWeight = 1f / columns
            AsyncImage(
                model = OwnerScopedImageKeys.request(
                    context = androidx.compose.ui.platform.LocalContext.current,
                    data = url,
                    sizeWidth = 300,
                    sizeHeight = 300,
                ),
                contentDescription = stringResource(R.string.explore_post_image),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .then(if (imageUrls.size == 1) Modifier.fillMaxWidth(0.7f) else Modifier.fillMaxWidth(eachWeight))
                    .aspectRatio(if (imageUrls.size == 1) 16f / 9f else 1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Surface)
                    .clickable { galleryIndex = index }
            )
        }
    }

    // 全屏图片查看器：多图可左右切换，index 经 policy 夹紧
    galleryIndex?.let { rawIndex ->
        val index = ExploreFeedPolicy.clampImageIndex(rawIndex, imageUrls.size)
        val imageUrl = imageUrls.getOrNull(index) ?: return@let
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { galleryIndex = null },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.95f))
            ) {
                // 1.143：全屏图片支持捏合缩放/双击放大（复用聊天 ZoomableAsyncImage）
                com.maodouchat.ui.component.ZoomableAsyncImage(
                    model = OwnerScopedImageKeys.request(
                        context = androidx.compose.ui.platform.LocalContext.current,
                        data = imageUrl,
                    ),
                    contentDescription = stringResource(R.string.explore_fullscreen_image),
                    modifier = Modifier.fillMaxSize()
                )
                if (imageUrls.size > 1) {
                    Text(
                        text = stringResource(R.string.explore_image_index, index + 1, imageUrls.size),
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 20.dp)
                            .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(999.dp))
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                    if (index > 0) {
                        IconButton(
                            onClick = { galleryIndex = index - 1 },
                            modifier = Modifier.align(Alignment.CenterStart).padding(8.dp)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Outlined.KeyboardArrowLeft,
                                contentDescription = stringResource(R.string.explore_image_prev),
                                tint = Color.White,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                    }
                    if (index < imageUrls.lastIndex) {
                        IconButton(
                            onClick = { galleryIndex = index + 1 },
                            modifier = Modifier.align(Alignment.CenterEnd).padding(8.dp)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                                contentDescription = stringResource(R.string.explore_image_next),
                                tint = Color.White,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                    }
                }
                IconButton(
                    onClick = { galleryIndex = null },
                    modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)
                ) {
                    Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.explore_close), tint = Color.White, modifier = Modifier.size(28.dp))
                }
                // 1.127：保存动态图片到相册（认证下载 + MediaStore）；1.129：下载中转圈
                val viewerContext = androidx.compose.ui.platform.LocalContext.current
                val saveScope = androidx.compose.runtime.rememberCoroutineScope()
                IconButton(
                    onClick = {
                        if (savingImage) return@IconButton
                        savingImage = true
                        saveScope.launch {
                            try {
                                val token = com.maodouchat.network.TokenManager.getInstance(viewerContext).getToken().orEmpty()
                                if (token.isBlank()) {
                                    android.widget.Toast.makeText(viewerContext, R.string.explore_save_failed, android.widget.Toast.LENGTH_SHORT).show()
                                    return@launch
                                }
                                val file = java.io.File(viewerContext.cacheDir, "post_${System.currentTimeMillis()}.jpg")
                                ApiService.downloadPostImage(token, imageUrl, file).fold(
                                    onSuccess = {
                                        val saved = com.maodouchat.util.MediaExport.saveToGallery(
                                            viewerContext,
                                            android.net.Uri.fromFile(file).toString(),
                                            "image/jpeg",
                                            "maodouchat-post-${System.currentTimeMillis()}.jpg"
                                        )
                                        android.widget.Toast.makeText(
                                            viewerContext,
                                            if (saved) R.string.explore_saved_to_gallery else R.string.explore_save_failed,
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    },
                                    onFailure = {
                                        android.widget.Toast.makeText(viewerContext, R.string.explore_save_failed, android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                )
                            } finally {
                                savingImage = false
                            }
                        }
                    },
                    modifier = Modifier.align(Alignment.TopStart).padding(16.dp)
                ) {
                    if (savingImage) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                            color = Color.White
                        )
                    } else {
                        Icon(Icons.Outlined.Download, contentDescription = stringResource(R.string.explore_save_image), tint = Color.White, modifier = Modifier.size(28.dp))
                    }
                }
                // 1.136：分享动态图片（认证下载 → 系统分享）
                var sharingImage by remember { mutableStateOf(false) }
                IconButton(
                    onClick = {
                        if (sharingImage) return@IconButton
                        sharingImage = true
                        saveScope.launch {
                            try {
                                val token = com.maodouchat.network.TokenManager.getInstance(viewerContext).getToken().orEmpty()
                                if (token.isBlank()) return@launch
                                val file = java.io.File(viewerContext.cacheDir, "post_share_${System.currentTimeMillis()}.jpg")
                                ApiService.downloadPostImage(token, imageUrl, file).fold(
                                    onSuccess = {
                                        val shared = com.maodouchat.util.MediaExport.share(
                                            viewerContext,
                                            android.net.Uri.fromFile(file).toString(),
                                            "image/jpeg",
                                            viewerContext.getString(R.string.chat_share)
                                        )
                                        if (!shared) {
                                            android.widget.Toast.makeText(viewerContext, R.string.explore_save_failed, android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    onFailure = {
                                        android.widget.Toast.makeText(viewerContext, R.string.explore_save_failed, android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                )
                            } finally {
                                sharingImage = false
                            }
                        }
                    },
                    modifier = Modifier.align(Alignment.TopStart).padding(start = 16.dp, top = 64.dp)
                ) {
                    if (sharingImage) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                            color = Color.White
                        )
                    } else {
                        Icon(Icons.Outlined.Share, contentDescription = stringResource(R.string.chat_share), tint = Color.White, modifier = Modifier.size(28.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun CommentsDialog(
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
    AlertDialog(
        onDismissRequest = onDismiss,
        // 1.145：标题显示已加载评论数
        title = { Text(stringResource(R.string.explore_comments_count, comments.size)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (isLoading && comments.isEmpty()) {
                    Box(Modifier.fillMaxWidth().height(96.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else if (comments.isEmpty()) {
                    Text(stringResource(R.string.explore_no_comments), color = TextSecondary)
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
                        Text(stringResource(R.string.explore_comment_search_empty), color = TextSecondary)
                    } else {
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
                            // 1.78：父评论作者索引（避免逐条 O(n²) 扫描）
                            val commentAuthorById = remember(comments) { comments.associateBy { it.id } }
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
                                                color = OnSurface,
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
                                                        .background(Primary.copy(alpha = 0.12f))
                                                        .padding(horizontal = 4.dp, vertical = 1.dp)
                                                ) {
                                                    Text(
                                                        stringResource(R.string.explore_comment_author_badge),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = Primary
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
                                                    color = Primary,
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
                                            color = OnSurface,
                                            modifier = Modifier.combinedClickable(
                                                onClick = {},
                                                onDoubleClick = { onToggleLike(comment) }
                                            )
                                        )
                                        Text(relativeTime(comment.createdAt), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                                    }
                                    // 1.00：删除自己的评论 + 1.52：评论点赞
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        if (comment.author.id == currentUserId) {
                                            IconButton(onClick = { onDeleteComment(comment) }, modifier = Modifier.size(28.dp)) {
                                                Icon(
                                                    Icons.Outlined.Delete,
                                                    contentDescription = stringResource(R.string.explore_delete_comment),
                                                    tint = TextSecondary,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                        IconButton(onClick = { onToggleLike(comment) }, modifier = Modifier.size(28.dp)) {
                                            Icon(
                                                if (comment.likedByMe) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                                                contentDescription = stringResource(R.string.explore_comment_like),
                                                tint = if (comment.likedByMe) Color(0xFFE91E63) else TextSecondary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                        if (comment.likeCount > 0) {
                                            Text(
                                                comment.likeCount.toString(),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (comment.likedByMe) Color(0xFFE91E63) else TextSecondary
                                            )
                                        }
                                        // 1.246：举报评论（他人评论）
                                        if (comment.author.id != currentUserId) {
                                            IconButton(onClick = { onReportComment(comment) }, modifier = Modifier.size(28.dp)) {
                                                Icon(
                                                    Icons.Outlined.Flag,
                                                    contentDescription = stringResource(R.string.explore_report_comment),
                                                    tint = TextSecondary,
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
                                                color = Primary
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
                                                color = TextSecondary
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
                                color = Primary
                            )
                            Text(
                                replyToComment.content,
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        TextButton(onClick = onClearReply) {
                            Text(stringResource(R.string.common_cancel), color = TextSecondary)
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
                        else Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = stringResource(R.string.explore_send), tint = Primary)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.explore_close)) } }
    )
}

// 1.93：动态点赞者弹窗（Explore 与 PostDetail 共用）
@Composable
 internal fun LikersDialog(
    likers: List<com.maodouchat.network.UserDto>,
    isLoading: Boolean,
    onDismiss: () -> Unit,
    // 1.184：点击点赞者打开作者主页
    onOpenUser: (String) -> Unit = {}
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.explore_likers_title)) },
        text = {
            Box(Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 360.dp)) {
                when {
                    isLoading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    likers.isEmpty() -> Text(stringResource(R.string.explore_likers_empty), color = TextSecondary)
                    else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(likers, key = { it.id }) { user ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp)).clickable { onOpenUser(user.id) }.padding(vertical = 2.dp)
                            ) {
                                // 1.190：点赞者在线绿点
                                Avatar(name = user.name, avatarUrl = user.avatar, size = AvatarSize.SM, isOnline = user.isOnline)
                                Spacer(Modifier.width(8.dp))
                                Text(user.name, style = MaterialTheme.typography.bodyMedium, color = OnSurface)
                                Spacer(Modifier.width(8.dp))
                                if (user.isOnline) {
                                    Text(stringResource(R.string.chat_online), style = MaterialTheme.typography.labelSmall, color = Primary)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.explore_close)) } }
    )
}

@Composable
private fun LoadingMoreBlock() {
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
private fun visibilityOptionLabel(value: String): String = when (value) {
    "CONTACTS" -> stringResource(R.string.explore_visibility_contacts)
    "PRIVATE" -> stringResource(R.string.explore_visibility_private)
    else -> stringResource(R.string.explore_visibility_public)
}

private fun relativeTime(timestamp: Long): String {
    return DateUtils.getRelativeTimeSpanString(timestamp, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
}

// 1.270：搜索关键词高亮（与 ChatList/收藏页一致）
@Composable
private fun highlightedText(text: String, query: String): androidx.compose.ui.text.AnnotatedString = buildAnnotatedString {
    val snippet = remember(text, query) {
        com.maodouchat.ui.screen.chatlist.GlobalSearchTextHighlight.buildSnippet(text, query)
    }
    if (snippet.highlights.isEmpty()) {
        append(snippet.text)
        return@buildAnnotatedString
    }
    var cursor = 0
    snippet.highlights.forEach { span ->
        if (span.start > cursor) append(snippet.text.substring(cursor, span.start))
        pushStyle(SpanStyle(color = Primary, fontWeight = FontWeight.SemiBold, background = Primary.copy(alpha = 0.12f)))
        append(snippet.text.substring(span.start, span.end))
        pop()
        cursor = span.end
    }
    if (cursor < snippet.text.length) append(snippet.text.substring(cursor))
}

private data class EntryItem(val id: String, val title: String, val subtitle: String, val icon: ImageVector)

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun ExploreScreenPreview() {
    MaodouchatTheme { ExploreScreen() }
}
