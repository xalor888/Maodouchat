package com.maodouchat.ui.screen.explore

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.maodouchat.R
import com.maodouchat.network.PostDto
import com.maodouchat.network.UserDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.maodouchat.explore.policy.ExploreFeedPolicy
import com.maodouchat.data.repository.UserNetworkRepository
import com.maodouchat.data.repository.ModerationNetworkRepository
import com.maodouchat.data.repository.PostNetworkRepository
import com.maodouchat.data.repository.AccountSecurityNetworkRepository

private const val AUTHOR_PAGE_SIZE = 40
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
        val loadOwnerUserId = com.maodouchat.session.CurrentSession.ownerUserId()
        if (!com.maodouchat.session.CurrentSession.hasSession() || loadOwnerUserId.isBlank()) {
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
                )
                ) {
                    if (loadGeneration == generation) {
                        _uiState.update { it.copy(isLoading = false) }
                    }
                    return@launch
                }
                UserNetworkRepository().user(userId = authorId).onSuccess { author ->
                    if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = loadOwnerUserId,
                    )
                    ) {
                        return@onSuccess
                    }
                    if (loadGeneration == generation) {
                        _uiState.update { it.copy(author = author) }
                    }
                }.onFailure { error ->
                    if (loadGeneration == generation && com.maodouchat.session.CurrentSession.snapshot().userId == loadOwnerUserId) {
                        _uiState.update { it.copy(errorMessage = error.message ?: text(R.string.explore_author_load_failed)) }
                    }
                }
                // 1.287：加载拉黑状态（决定操作按钮显示「拉黑」还是「解除拉黑」）
                ModerationNetworkRepository().blockedUserIds().onSuccess { blockedIds ->
                    if (loadGeneration == generation && com.maodouchat.session.CurrentSession.snapshot().userId == loadOwnerUserId) {
                        _uiState.update { it.copy(isBlocked = authorId in blockedIds) }
                    }
                }
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = loadOwnerUserId,
                )
                ) {
                    if (loadGeneration == generation) {
                        _uiState.update { it.copy(isLoading = false) }
                    }
                    return@launch
                }
                // 拉作者全部动态
                PostNetworkRepository().posts(limit = AUTHOR_PAGE_SIZE, authorId = authorId).fold(
                    onSuccess = { posts ->
                        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                            expectedUserId = loadOwnerUserId,
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
                        if (loadGeneration == generation && com.maodouchat.session.CurrentSession.snapshot().userId == loadOwnerUserId) {
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
        val ownerUserId = com.maodouchat.session.CurrentSession.ownerUserId()
        val generation = loadGeneration
        viewModelScope.launch {
            loadMoreMutex.withLock {
                val state = _uiState.value
                if (loadGeneration != generation || state.isLoadingMore || !state.hasMore) return@withLock
                if (!com.maodouchat.session.CurrentSession.hasSession()) return@withLock
                _uiState.update { it.copy(isLoadingMore = true, errorMessage = null) }
                try {
                    if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = ownerUserId,
                    )
                    ) {
                        _uiState.update { it.copy(isLoadingMore = false) }
                        return@withLock
                    }
                    PostNetworkRepository().posts(
                        limit = AUTHOR_PAGE_SIZE,
                        before = cursor.createdAt,
                        beforeId = cursor.postId,
                        authorId = authorId
                    ).fold(
                        onSuccess = { posts ->
                            if (loadGeneration == generation && com.maodouchat.session.CurrentSession.snapshot().userId == ownerUserId) {
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
                            if (loadGeneration == generation && com.maodouchat.session.CurrentSession.snapshot().userId == ownerUserId) {
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
        val ownerUserId = com.maodouchat.session.CurrentSession.ownerUserId()
        if (authorId.isBlank() || authorId == ownerUserId) return
        val target = _uiState.value.author ?: return
        if (_uiState.value.isBlocking) return
        val wantBlock = !_uiState.value.isBlocked
        if (wantBlock && !com.maodouchat.util.RuntimeFlags.isEnabled(getApplication(), com.maodouchat.util.RuntimeFlags.BLOCK_REPORT)) {
            _uiState.update { it.copy(infoMessage = text(R.string.feature_disabled_by_admin)) }
            return
        }
        if (!com.maodouchat.session.CurrentSession.hasSession() || ownerUserId.isBlank()) {
            _uiState.update { it.copy(errorMessage = text(R.string.error_session_expired)) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isBlocking = true, infoMessage = null) }
            try {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = ownerUserId,
                )
                ) {
                    _uiState.update { it.copy(isBlocking = false) }
                    return@launch
                }
                val request = if (wantBlock) ModerationNetworkRepository().blockUser(userId = authorId) else AccountSecurityNetworkRepository().unblock(userId = authorId)
                request.fold(
                    onSuccess = {
                        if (com.maodouchat.session.CurrentSession.snapshot().userId != ownerUserId) return@fold
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
        val likeOwnerUserId = com.maodouchat.session.CurrentSession.ownerUserId()
        if (!com.maodouchat.session.CurrentSession.hasSession() || likeOwnerUserId.isBlank()) {
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

                val result = if (currentPost.likedByMe) PostNetworkRepository().unlike(postId = post.id) else PostNetworkRepository().like(postId = post.id)
                result.fold(
                    onSuccess = { updated ->
                        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                            expectedUserId = likeOwnerUserId,
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
                        if (loadGeneration == generation && com.maodouchat.session.CurrentSession.snapshot().userId == likeOwnerUserId) {
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
