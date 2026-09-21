package com.maodouchat.ui.screen.explore

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.maodouchat.R
import com.maodouchat.ui.component.Avatar
import com.maodouchat.ui.component.AvatarSize
import com.maodouchat.ui.component.PullToRefreshLayout
import kotlinx.coroutines.flow.distinctUntilChanged
import com.maodouchat.ui.theme.LocalChatPalette
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * 「朋友圈」子页（G116 从 `ExploreSubScreens.kt` 拆出，原 209 行）。
 *
 * 与 `ExploreScreen` 的动态流一致，但默认过滤 PUBLIC + 只显示「近 30 天」，支持下拉刷新。
 * 含 `relativeTime`（时间本地化）与 `highlightedText`（搜索关键词高亮）——
 * 两者在 `ExplorePostDetailScreen.kt` 与 `ExploreScreen.kt` 各有一份同名的 private 副本，
 * 这是本代码库的既有模式（跨文件重复的小助手），搬移时保持一致。
 *
 * **拆解约束**：不抓任何全局单例、不读数据库、不 import `MaodouchatApp`。
 * 纯搬移，不改判断。
 */

/**
 * 「朋友圈」子页：与 ExploreScreen 的动态流一致，但默认过滤 PUBLIC + 只显示"近 30 天"
 *  - 支持下拉刷新
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MomentsScreen(
    onBack: () -> Unit = {},
    onOpenAuthor: (String) -> Unit = {},
    onOpenPost: (String) -> Unit = {},
    viewModel: ExploreViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.explore_moments), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh) { Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.common_refresh), tint = MaterialTheme.colorScheme.primary) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f))
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        var momentsSearch by rememberSaveable { mutableStateOf("") }
        // 1.330：只看带图片的动态（与 Explore 动态流 1.192 一致）
        var momentsOnlyMedia by rememberSaveable { mutableStateOf(false) }
        val thirtyDaysAgo = System.currentTimeMillis() - 30L * 24 * 3600 * 1000
        val publicPosts = remember(state.posts) { state.posts.filter { it.visibility == "PUBLIC" && it.createdAt >= thirtyDaysAgo } }
        val filteredMoments = remember(publicPosts, momentsSearch, momentsOnlyMedia) {
            var base = publicPosts
            if (momentsOnlyMedia) base = base.filter { it.imageUrls.isNotEmpty() }
            val query = momentsSearch.trim()
            if (query.isBlank()) {
                base
            } else {
                base.filter {
                    it.content.contains(query, ignoreCase = true) ||
                        it.author.name.contains(query, ignoreCase = true) ||
                        it.author.id.contains(query, ignoreCase = true)
                }
            }
        }
        // 8.59：朋友圈接分页——滚动到底触发 loadMore（与 ExploreScreen 一致）；
        // 搜索激活或已到底暂停，避免过滤视图不增长时持续拉取
        val momentsListState = androidx.compose.foundation.lazy.rememberLazyListState()
        LaunchedEffect(momentsListState) {
            androidx.compose.runtime.snapshotFlow {
                val layoutInfo = momentsListState.layoutInfo
                val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                lastVisible >= layoutInfo.totalItemsCount - 3 && layoutInfo.totalItemsCount > 0
            }.distinctUntilChanged().collect { shouldLoadMore ->
                if (shouldLoadMore && momentsSearch.isBlank() && state.hasMore) viewModel.loadMore()
            }
        }
        if (publicPosts.isEmpty() && state.isLoading) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        if (publicPosts.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.explore_moments_empty), color = LocalChatPalette.current.textHint)
            }
            return@Scaffold
        }
        // 1.111：朋友圈下拉刷新（复用动态流/会话列表组件）
        com.maodouchat.ui.component.PullToRefreshLayout(
            isRefreshing = state.isLoading,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier.fillMaxSize()
        ) {
         LazyColumn(state = momentsListState, modifier = Modifier.fillMaxSize().padding(padding), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "moments_filters", contentType = "filters") {
                // 1.330：只看带图片的动态
                FilterChip(
                    selected = momentsOnlyMedia,
                    onClick = { momentsOnlyMedia = !momentsOnlyMedia },
                    label = { Text(stringResource(R.string.explore_feed_only_media)) }
                )
            }
            item(key = "moments_search", contentType = "search") {
                OutlinedTextField(
                    value = momentsSearch,
                    onValueChange = { momentsSearch = it.take(160) },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    placeholder = { Text(stringResource(R.string.explore_feed_search_hint)) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (filteredMoments.isEmpty()) {
                item(key = "moments_search_empty", contentType = "empty") {
                    Text(
                        stringResource(R.string.explore_feed_search_empty),
                        color = LocalChatPalette.current.textHint,
                        modifier = Modifier.padding(vertical = 24.dp)
                    )
                }
            } else {
            items(filteredMoments, key = { it.id }, contentType = { "public_post" }) { post ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().clickable { onOpenPost(post.id) }
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Avatar(name = post.author.name, avatarUrl = post.author.avatar, size = AvatarSize.SM, isOnline = post.author.isOnline)
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    // 1.307：朋友圈搜索时高亮匹配作者名
                                    if (momentsSearch.isBlank()) androidx.compose.ui.text.AnnotatedString(post.author.name)
                                    else highlightedText(post.author.name, momentsSearch),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(relativeTime(post.createdAt), style = MaterialTheme.typography.bodySmall, color = LocalChatPalette.current.textSecondary)
                            }
                            Icon(Icons.Outlined.Public, contentDescription = stringResource(R.string.explore_visibility_public), tint = LocalChatPalette.current.textHint, modifier = Modifier.size(16.dp))
                        }
                        if (post.content.isNotBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                // 1.307：朋友圈搜索时高亮匹配正文
                                if (momentsSearch.isBlank()) androidx.compose.ui.text.AnnotatedString(post.content)
                                else highlightedText(post.content, momentsSearch),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { viewModel.toggleLike(post) }, modifier = Modifier.size(28.dp)) {
                                Icon(
                                    if (post.likedByMe) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                                    contentDescription = stringResource(R.string.explore_like),
                                    tint = if (post.likedByMe) androidx.compose.ui.graphics.Color(0xFFE91E63) else LocalChatPalette.current.textSecondary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(post.likeCount.toString(), style = MaterialTheme.typography.bodySmall, color = LocalChatPalette.current.textSecondary)
                            Spacer(modifier = Modifier.width(12.dp))
                            Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = stringResource(R.string.explore_comment), tint = LocalChatPalette.current.textSecondary, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(post.commentCount.toString(), style = MaterialTheme.typography.bodySmall, color = LocalChatPalette.current.textSecondary)
                            Spacer(modifier = Modifier.weight(1f))
                            TextButton(onClick = { onOpenAuthor(post.author.id) }) { Text(stringResource(R.string.explore_author_home)) }
                        }
                    }
                }
            }
            } // filtered moments
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
