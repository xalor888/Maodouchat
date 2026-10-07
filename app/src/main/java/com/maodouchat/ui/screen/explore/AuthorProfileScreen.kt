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
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.maodouchat.ui.component.OwnerScopedImageKeys
import com.maodouchat.R
import com.maodouchat.ui.component.Avatar
import com.maodouchat.ui.component.AvatarSize
import com.maodouchat.ui.theme.MaodouchatTheme
import com.maodouchat.ui.theme.LocalChatPalette

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthorProfileScreen(
    authorId: String,
    onBack: () -> Unit = {},
    onOpenChat: (String) -> Unit = {},
    onOpenPost: (String) -> Unit = {},
    viewModel: AuthorProfileViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var authorPostSearch by rememberSaveable { mutableStateOf("") }
    val filteredAuthorPosts = remember(state.posts, authorPostSearch) {
        val query = authorPostSearch.trim()
        if (query.isBlank()) {
            state.posts
        } else {
            state.posts.filter { it.content.contains(query, ignoreCase = true) }
        }
    }
    androidx.compose.runtime.LaunchedEffect(authorId) { viewModel.load(authorId) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.author?.name ?: stringResource(R.string.explore_author_home), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(28.dp))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f))
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        if (state.isLoading) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            state.errorMessage?.let { error ->
                item(key = "error", contentType = "error") {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            error,
                            color = com.maodouchat.ui.theme.UnreadRed,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp)
                        )
                        if (state.author == null && state.posts.isEmpty()) {
                            TextButton(onClick = { viewModel.load(authorId) }) {
                                Text(stringResource(R.string.empty_state_retry))
                            }
                        }
                    }
                }
            }
            // 1.287：拉黑/解除拉黑结果提示（成功或失败）
            state.infoMessage?.let { info ->
                item(key = "info", contentType = "info") {
                    Text(
                        info,
                        color = if (state.isBlocked) MaterialTheme.colorScheme.primary else LocalChatPalette.current.textSecondary,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)
                    )
                }
            }
            state.author?.let { author ->
                item(key = "author_header", contentType = "author_header") {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(20.dp).fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Avatar(name = author.name, avatarUrl = author.avatar, size = AvatarSize.LG, isOnline = author.isOnline)
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(author.name, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    author.username?.takeIf { it.isNotBlank() }?.let { "@$it" }
                                        ?: if (author.id.length > 16) author.id.take(8) + "…" + author.id.takeLast(4) else author.id,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = LocalChatPalette.current.textSecondary
                                )
                                if (author.status.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(author.status, style = MaterialTheme.typography.bodyMedium, color = LocalChatPalette.current.textSecondary)
                                }
                            }
                            if (author.id != state.currentUserId) {
                                Column(horizontalAlignment = Alignment.End) {
                                    TextButton(onClick = { onOpenChat(author.id) }) {
                                        Text(stringResource(R.string.explore_author_chat), color = MaterialTheme.colorScheme.primary)
                                    }
                                    // 1.287：拉黑/解除拉黑（信息气泡复用 infoMessage 显示）
                                    TextButton(
                                        onClick = { viewModel.toggleBlock(author.id) },
                                        enabled = !state.isBlocking
                                    ) {
                                        Text(
                                            if (state.isBlocked) stringResource(R.string.explore_author_unblock)
                                            else stringResource(R.string.explore_author_block),
                                            color = if (state.isBlocked) MaterialTheme.colorScheme.primary else com.maodouchat.ui.theme.UnreadRed
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (state.posts.isEmpty()) {
                item(key = "empty", contentType = "empty") {
                    Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.explore_author_empty), color = LocalChatPalette.current.textSecondary)
                    }
                }
            } else {
                // 1.204：动态总数
                item(key = "author_post_count", contentType = "count") {
                    Text(
                        pluralStringResource(R.plurals.explore_author_post_count, state.posts.size, state.posts.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = LocalChatPalette.current.textSecondary,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
                item(key = "author_post_search", contentType = "search") {
                    OutlinedTextField(
                        value = authorPostSearch,
                        onValueChange = { authorPostSearch = it.take(160) },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                        placeholder = { Text(stringResource(R.string.explore_author_search_hint)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (filteredAuthorPosts.isEmpty()) {
                    item(key = "author_post_search_empty", contentType = "empty") {
                        Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            Text(stringResource(R.string.explore_author_search_empty), color = LocalChatPalette.current.textSecondary)
                        }
                    }
                } else {
                    items(filteredAuthorPosts, key = { it.id }, contentType = { "author_post" }) { post ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth().clickable { onOpenPost(post.id) }
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(state.author?.name ?: stringResource(R.string.explore_other_person), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Icon(Icons.Outlined.Public, contentDescription = stringResource(R.string.explore_visibility_public), tint = LocalChatPalette.current.textSecondary, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.weight(1f))
                                    Text(relativeTime(post.createdAt), style = MaterialTheme.typography.bodySmall, color = LocalChatPalette.current.textSecondary)
                                }
                                if (post.content.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(post.content, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                                }
                                if (post.imageUrls.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    AsyncImage(
                                        model = OwnerScopedImageKeys.request(
                                            context = androidx.compose.ui.platform.LocalContext.current,
                                            data = post.imageUrls.first(),
                                        ),
                                        contentDescription = stringResource(R.string.explore_post_image),
                                        contentScale = ContentScale.FillWidth,
                                        modifier = Modifier.fillMaxWidth().height(180.dp).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    TextButton(onClick = { viewModel.toggleLike(post) }, enabled = post.id !in state.updatingPostIds) {
                                        Icon(
                                            imageVector = if (post.likedByMe) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
                                            contentDescription = stringResource(R.string.explore_like),
                                            tint = if (post.likedByMe) androidx.compose.ui.graphics.Color(0xFFE91E63) else LocalChatPalette.current.textSecondary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(post.likeCount.toString())
                                    }
                                    TextButton(onClick = { onOpenPost(post.id) }) {
                                        Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = stringResource(R.string.explore_comment), tint = LocalChatPalette.current.textSecondary, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(post.commentCount.toString())
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (state.hasMore || state.isLoadingMore) {
                item(key = "author_load_more", contentType = "loading") {
                    TextButton(
                        onClick = { viewModel.loadMore(authorId) },
                        enabled = state.hasMore && !state.isLoadingMore,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (state.isLoadingMore) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Text(stringResource(R.string.explore_load_older_posts))
                        }
                    }
                }
            }
        }
    }
}



@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun PreviewAuthor() {
    MaodouchatTheme { AuthorProfileScreen(authorId = "u1") }
}
