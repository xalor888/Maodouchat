@file:Suppress("DEPRECATION")

package com.maodouchat.ui.screen.explore

import com.maodouchat.util.RuntimeFlags
import android.annotation.SuppressLint
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
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
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
import com.maodouchat.ui.component.FloatingBottomBarContentPadding
import com.maodouchat.ui.component.SearchBar
import com.maodouchat.ui.component.ShimmerPostCard
import com.maodouchat.ui.theme.Error
import com.maodouchat.ui.theme.LocalMotionSettings
import com.maodouchat.ui.theme.MaodouchatTheme
import com.maodouchat.ui.theme.Secondary
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.maodouchat.ui.theme.LocalChatPalette

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
// 资源字符串均在回调/协程内读取，非组合作用域
@SuppressLint("LocalContextGetResourceValueCall")
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
    val feedSearchFocus = remember { FocusRequester() }
    var requestFeedSearchFocus by remember { mutableStateOf(0) }
    // 1.109：只看我发布的动态
    var showOnlyMine by rememberSaveable { mutableStateOf(false) }
    // 1.192：只看带图片的动态
    var showOnlyMedia by rememberSaveable { mutableStateOf(false) }
    var composerExpanded by rememberSaveable { mutableStateOf(false) }
    val currentUserId = com.maodouchat.session.CurrentSession.ownerUserId()
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
            composerExpanded = false
            listState.animateScrollToItem(0)
        }
    }
    LaunchedEffect(uiState.composerText, uiState.imageDrafts, uiState.composerDraftRestored) {
        if (uiState.composerText.isNotBlank() || uiState.imageDrafts.isNotEmpty() || uiState.composerDraftRestored) {
            composerExpanded = true
        }
    }

    // 1.178：滚动超过若干项后显示「回到最新」
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }.distinctUntilChanged().collect { index ->
            showScrollToTop = index > 4
        }
    }
    LaunchedEffect(requestFeedSearchFocus) {
        if (requestFeedSearchFocus <= 0) return@LaunchedEffect
        listState.scrollToItem(0)
        kotlinx.coroutines.delay(50)
        runCatching { feedSearchFocus.requestFocus() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.nav_explore), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface) },
                actions = {
                    IconButton(onClick = { requestFeedSearchFocus++ }) {
                        Icon(Icons.Outlined.Search, contentDescription = stringResource(R.string.contacts_search))
                    }
                    IconButton(onClick = { viewModel.onEntryClick("scan") }) {
                        Icon(Icons.Outlined.QrCodeScanner, contentDescription = stringResource(R.string.explore_scan))
                    }
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.explore_refresh))
                    }
                },
                colors = com.maodouchat.ui.theme.liquidGlassTopAppBarColors()
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = Color.Transparent,
        // 1.178：回到最新
        floatingActionButton = {
            if (showScrollToTop) {
                SmallFloatingActionButton(
                    onClick = { scope.launch { listState.animateScrollToItem(0) } },
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = FloatingBottomBarContentPadding)
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
            contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = FloatingBottomBarContentPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            stickyHeader(key = "feed_header") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SearchBar(
                        value = feedSearch,
                        onValueChange = { feedSearch = it.take(160) },
                        placeholder = stringResource(R.string.explore_feed_search_hint),
                        modifier = Modifier.fillMaxWidth(),
                        focusRequester = feedSearchFocus
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = showOnlyMine,
                            onClick = { showOnlyMine = !showOnlyMine },
                            label = { Text(stringResource(R.string.explore_feed_only_mine)) }
                        )
                        FilterChip(
                            selected = showOnlyMedia,
                            onClick = { showOnlyMedia = !showOnlyMedia },
                            label = { Text(stringResource(R.string.explore_feed_only_media)) }
                        )
                    }
                }
            }
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
                            color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { viewModel.dismissComposerDraftHint() }) {
                            Text(stringResource(R.string.common_close), color = LocalChatPalette.current.textSecondary, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
            item(key = "composer", contentType = "composer") {
                CompactComposer(
                    expanded = composerExpanded,
                    onExpand = { composerExpanded = true },
                    onCollapse = { composerExpanded = false },
                    text = uiState.composerText,
                    imageDrafts = uiState.imageDrafts,
                    visibilityOptions = viewModel.visibilityOptions,
                    selectedVisibility = uiState.selectedVisibility,
                    isPublishing = uiState.isPublishing,
                    canPublish = uiState.canPublish,
                    visibilityReady = uiState.isVisibilityReady,
                    onTextChange = viewModel::onComposerTextChange,
                    onPickImages = {
                        imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    onPasteImages = {
                        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        val clip = runCatching { cm.primaryClip }.getOrNull()
                        val item = clip?.takeIf { it.itemCount > 0 }?.let { runCatching { it.getItemAt(0) }.getOrNull() }
                        val directUri = item?.uri?.takeIf { uri ->
                            runCatching { context.contentResolver.getType(uri)?.startsWith("image/") == true }.getOrDefault(false)
                        }
                        if (directUri != null) {
                            viewModel.addImages(listOf(directUri))
                        } else if (item != null) {
                            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                val pasted = runCatching {
                                    val bmp = item.uri?.let { uri ->
                                        context.contentResolver.openInputStream(uri)?.use { stream ->
                                            android.graphics.BitmapFactory.decodeStream(stream)
                                        }
                                    } ?: return@runCatching null
                                    val dir = java.io.File(context.cacheDir, "attachment-sources").apply { mkdirs() }
                                    val file = java.io.File(dir, "explore_paste_${System.currentTimeMillis()}.png")
                                    file.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, it) }
                                    bmp.recycle()
                                    android.net.Uri.fromFile(file)
                                }.getOrNull()
                                if (pasted != null) viewModel.addImages(listOf(pasted))
                                else withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    android.widget.Toast.makeText(context, context.getString(R.string.explore_clipboard_no_image), android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                        } else {
                            android.widget.Toast.makeText(context, context.getString(R.string.explore_clipboard_no_image), android.widget.Toast.LENGTH_SHORT).show()
                        }
                    },
                    onRemoveImage = viewModel::removeImage,
                    onRetryImage = viewModel::retryDraftImage,
                    onVisibilitySelected = viewModel::onVisibilitySelected,
                    onClear = {
                        viewModel.clearComposer()
                        composerExpanded = false
                    },
                    onPublish = viewModel::publishPost
                )
            }

            when {
                uiState.isLoading -> item(key = "loading", contentType = "loading") { 
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        ShimmerPostCard()
                        ShimmerPostCard()
                    }
                }
                uiState.posts.isEmpty() && !uiState.feedErrorMessage.isNullOrBlank() ->
                    item(key = "feed_error", contentType = "empty") {
                        EmptyState(
                            title = stringResource(R.string.explore_posts_load_failed),
                            subtitle = uiState.feedErrorMessage,
                            type = EmptyStateType.NETWORK_ERROR,
                            actionText = stringResource(R.string.empty_state_retry),
                            onAction = { viewModel.refresh() }
                        )
                    }
                uiState.posts.isEmpty() -> item(key = "empty", contentType = "empty") {
                    EmptyState(
                        title = stringResource(R.string.explore_empty_title),
                        subtitle = stringResource(R.string.explore_empty_subtitle),
                        type = EmptyStateType.MOMENTS,
                        actionText = stringResource(R.string.explore_empty_action),
                        onAction = {
                            composerExpanded = true
                            scope.launch { listState.animateScrollToItem(1) }
                        }
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
                onComment = { onOpenPost(post.id) },
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
                            // 9.289：跟随当前服务器地址，自建部署不再分享无效的官服域名
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
            currentUserId = com.maodouchat.session.CurrentSession.ownerUserId(),
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
                    likers.isEmpty() -> Text(stringResource(R.string.explore_likers_empty), color = LocalChatPalette.current.textSecondary)
                    else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(likers, key = { it.id }) { user ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp)).clickable { onOpenUser(user.id) }.padding(vertical = 2.dp)
                            ) {
                                // 1.190：点赞者在线绿点
                                Avatar(name = user.name, avatarUrl = user.avatar, size = AvatarSize.SM, isOnline = user.isOnline)
                                Spacer(Modifier.width(8.dp))
                                Text(user.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                                Spacer(Modifier.width(8.dp))
                                if (user.isOnline) {
                                    Text(stringResource(R.string.chat_online), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
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






@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun ExploreScreenPreview() {
    MaodouchatTheme { ExploreScreen() }
}
