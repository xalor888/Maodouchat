package com.maodouchat.ui.screen.explore

import android.app.Application
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
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.maodouchat.ui.component.OwnerScopedImageKeys
import com.maodouchat.R
import com.maodouchat.network.ApiService
import com.maodouchat.network.PostDto
import com.maodouchat.network.UserDto
import com.maodouchat.network.TokenManager
import com.maodouchat.ui.component.Avatar
import com.maodouchat.ui.component.AvatarSize
import com.maodouchat.ui.theme.Background
import com.maodouchat.ui.theme.MaodouchatTheme
import com.maodouchat.ui.theme.OnSurface
import com.maodouchat.ui.theme.Primary
import com.maodouchat.ui.theme.Surface
import com.maodouchat.ui.theme.TextSecondary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.maodouchat.ui.theme.LocalChatPalette

data class AuthorProfileUiState(
    val currentUserId: String = "",
    val author: UserDto? = null,
    val posts: List<PostDto> = emptyList(),
    val updatingPostIds: Set<String> = emptySet(),
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = true,
    val errorMessage: String? = null,
    // 1.287：拉黑/解除拉黑
    val isBlocked: Boolean = false,
    val isBlocking: Boolean = false,
    val infoMessage: String? = null
)

class AuthorProfileViewModel(application: Application) : AndroidViewModel(application) {
    private val tokenManager = TokenManager.getInstance(application)
    private var loadGeneration = 0L
    private var loadJob: kotlinx.coroutines.Job? = null
    private val loadMoreMutex = Mutex()
    private val _uiState = MutableStateFlow(AuthorProfileUiState())
    val uiState: StateFlow<AuthorProfileUiState> = _uiState.asStateFlow()

    private fun text(id: Int): String = getApplication<Application>().getString(id)
    private fun text(id: Int, vararg args: Any): String = getApplication<Application>().getString(id, *args)

    fun load(authorId: String) {
        if (authorId.isBlank()) {
            _uiState.update {
                it.copy(isLoading = false, errorMessage = text(R.string.explore_author_load_failed))
            }
            return
        }
        val token = tokenManager.getToken().orEmpty()
        val loadOwnerUserId = tokenManager.getUserId().orEmpty()
        if (token.isBlank() || loadOwnerUserId.isBlank()) {
            _uiState.update { it.copy(isLoading = false, errorMessage = text(R.string.error_session_expired)) }
            return
        }
        val generation = ++loadGeneration
        loadJob?.cancel()
        _uiState.update {
            it.copy(
                currentUserId = loadOwnerUserId,
                author = null,
                posts = emptyList(),
                updatingPostIds = emptySet(),
                isLoading = true,
                isLoadingMore = false,
                hasMore = true,
                errorMessage = null
            )
        }
        val job = viewModelScope.launch {
            try {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = loadOwnerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    if (loadGeneration == generation) {
                        _uiState.update { it.copy(isLoading = false) }
                    }
                    return@launch
                }
                val liveToken = tokenManager.getToken() ?: token
                ApiService.getUser(liveToken, authorId).onSuccess { author ->
                    if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                            expectedUserId = loadOwnerUserId,
                            liveToken = tokenManager.getToken(),
                            liveUserId = tokenManager.getUserId(),
                        )
                    ) {
                        return@onSuccess
                    }
                    if (loadGeneration == generation) {
                        _uiState.update { it.copy(author = author) }
                    }
                }.onFailure { error ->
                    if (loadGeneration == generation && tokenManager.getUserId() == loadOwnerUserId) {
                        _uiState.update { it.copy(errorMessage = error.message ?: text(R.string.explore_author_load_failed)) }
                    }
                }
                // 1.287：加载拉黑状态（决定操作按钮显示「拉黑」还是「解除拉黑」）
                ApiService.getBlockedUsers(tokenManager.getToken() ?: liveToken).onSuccess { blockedIds ->
                    if (loadGeneration == generation && tokenManager.getUserId() == loadOwnerUserId) {
                        _uiState.update { it.copy(isBlocked = authorId in blockedIds) }
                    }
                }
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = loadOwnerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    if (loadGeneration == generation) {
                        _uiState.update { it.copy(isLoading = false) }
                    }
                    return@launch
                }
                // 拉作者全部动态
                ApiService.getPosts(tokenManager.getToken() ?: liveToken, limit = AUTHOR_PAGE_SIZE, authorId = authorId).fold(
                    onSuccess = { posts ->
                        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                expectedUserId = loadOwnerUserId,
                                liveToken = tokenManager.getToken(),
                                liveUserId = tokenManager.getUserId(),
                            )
                        ) {
                            return@fold
                        }
                        if (loadGeneration == generation) {
                            _uiState.update {
                                it.copy(
                                    author = it.author ?: posts.firstOrNull()?.author,
                                    posts = posts,
                                    isLoading = false,
                                    hasMore = posts.size >= AUTHOR_PAGE_SIZE,
                                    errorMessage = if (it.author == null && posts.isEmpty()) it.errorMessage else null
                                )
                            }
                        }
                    },
                    onFailure = { error ->
                        if (loadGeneration == generation && tokenManager.getUserId() == loadOwnerUserId) {
                            _uiState.update {
                                it.copy(isLoading = false, errorMessage = error.message ?: text(R.string.explore_author_load_failed))
                            }
                        }
                    }
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                if (loadGeneration == generation) {
                    _uiState.update { it.copy(isLoading = false) }
                }
                throw error
            }
        }
        loadJob = job
        job.invokeOnCompletion {
            if (loadJob === job) loadJob = null
        }
    }

    fun loadMore(authorId: String) {
        val snapshot = _uiState.value
        if (snapshot.isLoading || snapshot.isLoadingMore || !snapshot.hasMore || snapshot.posts.isEmpty()) return
        val cursor = ExploreFeedPolicy.oldestCursor(snapshot.posts) ?: return
        val ownerUserId = tokenManager.getUserId().orEmpty()
        val generation = loadGeneration
        viewModelScope.launch {
            loadMoreMutex.withLock {
                val state = _uiState.value
                if (loadGeneration != generation || state.isLoadingMore || !state.hasMore) return@withLock
                val token = tokenManager.getToken()?.takeIf(String::isNotBlank) ?: return@withLock
                _uiState.update { it.copy(isLoadingMore = true, errorMessage = null) }
                try {
                    if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                            expectedUserId = ownerUserId,
                            liveToken = tokenManager.getToken(),
                            liveUserId = tokenManager.getUserId(),
                        )
                    ) {
                        _uiState.update { it.copy(isLoadingMore = false) }
                        return@withLock
                    }
                    ApiService.getPosts(
                        token = token,
                        limit = AUTHOR_PAGE_SIZE,
                        before = cursor.createdAt,
                        beforeId = cursor.postId,
                        authorId = authorId
                    ).fold(
                        onSuccess = { posts ->
                            if (loadGeneration == generation && tokenManager.getUserId() == ownerUserId) {
                                _uiState.update {
                                    it.copy(
                                        posts = (it.posts + posts).distinctBy(PostDto::id),
                                        isLoadingMore = false,
                                        hasMore = posts.size >= AUTHOR_PAGE_SIZE
                                    )
                                }
                            }
                        },
                        onFailure = { error ->
                            if (loadGeneration == generation && tokenManager.getUserId() == ownerUserId) {
                                _uiState.update {
                                    it.copy(
                                        isLoadingMore = false,
                                        errorMessage = error.message ?: text(R.string.explore_load_more_failed)
                                    )
                                }
                            }
                        }
                    )
                } catch (error: kotlinx.coroutines.CancellationException) {
                    if (loadGeneration == generation) {
                        _uiState.update { it.copy(isLoadingMore = false) }
                    }
                    throw error
                }
            }
        }
    }

    /** 1.287：拉黑/解除拉黑（与 ChatDetail.blockContact/unblockContact 同模式）。 */
    fun toggleBlock(authorId: String) {
        val ownerUserId = tokenManager.getUserId().orEmpty()
        if (authorId.isBlank() || authorId == ownerUserId) return
        val target = _uiState.value.author ?: return
        if (_uiState.value.isBlocking) return
        val wantBlock = !_uiState.value.isBlocked
        if (wantBlock && !com.maodouchat.util.RuntimeFlags.isEnabled(getApplication(), com.maodouchat.util.RuntimeFlags.BLOCK_REPORT)) {
            _uiState.update { it.copy(infoMessage = text(R.string.feature_disabled_by_admin)) }
            return
        }
        val token = tokenManager.getToken().orEmpty()
        if (token.isBlank() || ownerUserId.isBlank()) {
            _uiState.update { it.copy(errorMessage = text(R.string.error_session_expired)) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isBlocking = true, infoMessage = null) }
            try {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = ownerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    _uiState.update { it.copy(isBlocking = false) }
                    return@launch
                }
                val liveToken = tokenManager.getToken() ?: token
                val request = if (wantBlock) ApiService.blockUser(liveToken, authorId) else ApiService.unblockUser(liveToken, authorId)
                request.fold(
                    onSuccess = {
                        if (tokenManager.getUserId() != ownerUserId) return@fold
                        val message = if (wantBlock) {
                            text(R.string.explore_author_blocked_done, target.name)
                        } else {
                            text(R.string.explore_author_unblocked_done, target.name)
                        }
                        _uiState.update { st ->
                            st.copy(
                                isBlocked = wantBlock,
                                isBlocking = false,
                                infoMessage = message,
                                // 拉黑后本地移除该作者动态（服务端同频过滤）
                                posts = if (wantBlock) emptyList() else st.posts
                            )
                        }
                    },
                    onFailure = { error ->
                        _uiState.update { it.copy(isBlocking = false, infoMessage = text(if (wantBlock) R.string.explore_author_block_failed else R.string.explore_author_unblock_failed)) }
                    }
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                _uiState.update { it.copy(isBlocking = false) }
                throw error
            } catch (_: Exception) {
                _uiState.update { it.copy(isBlocking = false, infoMessage = text(if (wantBlock) R.string.explore_author_block_failed else R.string.explore_author_unblock_failed)) }
            }
        }
    }

    fun toggleLike(post: PostDto) {
        val token = tokenManager.getToken().orEmpty()
        val likeOwnerUserId = tokenManager.getUserId().orEmpty()
        if (token.isBlank() || likeOwnerUserId.isBlank()) {
            _uiState.update { it.copy(errorMessage = text(R.string.error_session_expired)) }
            return
        }
        val currentPost = _uiState.value.posts.firstOrNull { it.id == post.id } ?: return
        if (post.id in _uiState.value.updatingPostIds) return
        val generation = loadGeneration
        val optimistic = currentPost.copy(
            likedByMe = !currentPost.likedByMe,
            likeCount = (currentPost.likeCount + if (currentPost.likedByMe) -1 else 1).coerceAtLeast(0)
        )
        _uiState.update { state ->
            state.copy(
                posts = state.posts.map { if (it.id == post.id) optimistic else it },
                updatingPostIds = state.updatingPostIds + post.id,
                errorMessage = null
            )
        }
        viewModelScope.launch {
            try {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = likeOwnerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    _uiState.update { state ->
                        state.copy(
                            posts = state.posts.map { if (it.id == post.id) currentPost else it },
                            updatingPostIds = state.updatingPostIds - post.id
                        )
                    }
                    return@launch
                }
                val liveToken = tokenManager.getToken() ?: token
                val result = if (currentPost.likedByMe) ApiService.unlikePost(liveToken, post.id) else ApiService.likePost(liveToken, post.id)
                result.fold(
                    onSuccess = { updated ->
                        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                expectedUserId = likeOwnerUserId,
                                liveToken = tokenManager.getToken(),
                                liveUserId = tokenManager.getUserId(),
                            )
                        ) {
                            return@fold
                        }
                        if (loadGeneration == generation) {
                            _uiState.update { state ->
                                state.copy(
                                    posts = state.posts.map { if (it.id == updated.id) updated else it },
                                    updatingPostIds = state.updatingPostIds - post.id
                                )
                            }
                        }
                    },
                    onFailure = { error ->
                        if (loadGeneration == generation && tokenManager.getUserId() == likeOwnerUserId) {
                            _uiState.update { state ->
                                state.copy(
                                    posts = state.posts.map { if (it.id == post.id) currentPost else it },
                                    updatingPostIds = state.updatingPostIds - post.id,
                                    errorMessage = error.message ?: text(R.string.explore_like_failed)
                                )
                            }
                        }
                    }
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                if (loadGeneration == generation) {
                    _uiState.update { state ->
                        state.copy(
                            posts = state.posts.map { if (it.id == post.id) currentPost else it },
                            updatingPostIds = state.updatingPostIds - post.id
                        )
                    }
                }
                throw error
            }
        }
    }
}

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
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f))
            )
        },
        containerColor = Background
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
                        color = if (state.isBlocked) Primary else TextSecondary,
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
                                Text(author.id, style = MaterialTheme.typography.bodySmall, color = LocalChatPalette.current.textSecondary)
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
                                            color = if (state.isBlocked) Primary else com.maodouchat.ui.theme.UnreadRed
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
                                    Text(relativeTimeFmt(post.createdAt), style = MaterialTheme.typography.bodySmall, color = LocalChatPalette.current.textSecondary)
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
                                            tint = if (post.likedByMe) androidx.compose.ui.graphics.Color(0xFFE91E63) else TextSecondary,
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

private const val AUTHOR_PAGE_SIZE = 40

@Composable
private fun relativeTimeFmt(ts: Long): String {
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

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun PreviewAuthor() {
    MaodouchatTheme { AuthorProfileScreen(authorId = "u1") }
}
