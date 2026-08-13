package com.maodouchat.ui.screen.explore

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.maodouchat.R
import com.maodouchat.network.ApiService
import com.maodouchat.network.PostDto
import com.maodouchat.network.PostCommentDto
import com.maodouchat.network.TokenManager
import com.maodouchat.network.UserDto
import com.maodouchat.util.ImagePicker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

data class PostImageDraft(
    val id: String = UUID.randomUUID().toString(),
    val uri: Uri,
    val uploadUrl: String? = null,
    val isUploading: Boolean = true,
    val errorMessage: String? = null
)

data class VisibilityOption(val value: String, val label: String, val description: String)

data class ExploreUiState(
    val posts: List<PostDto> = emptyList(),
    val detailPost: PostDto? = null,
    val comments: List<PostCommentDto> = emptyList(),
    val selectedPostId: String? = null,
    val postPendingDeleteId: String? = null,
    val postPendingEditId: String? = null,
    val editPostText: String = "",
    val editPostVisibility: String = "PUBLIC",
    val isEditingPost: Boolean = false,
    val composerText: String = "",
    /** 1.177：发布框已从本地恢复草稿（用户编辑或发布后清除）。 */
    val composerDraftRestored: Boolean = false,
    /** 1.196：发布成功计数器（UI 据此滚动流到顶部）。 */
    val publishRevision: Int = 0,
    val commentText: String = "",
    /** 1.76：正在回复的评论（null=顶级评论）。 */
    val replyToComment: PostCommentDto? = null,
    val selectedVisibility: String = "PRIVATE",
    val defaultPostVisibility: String? = null,
    val useDefaultPostVisibility: Boolean = true,
    val isVisibilityReady: Boolean = false,
    val imageDrafts: List<PostImageDraft> = emptyList(),
    val isLoading: Boolean = false,
    val isPostDetailLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val isPublishing: Boolean = false,
    val isCommentsLoading: Boolean = false,
    val isLoadingOlderComments: Boolean = false,
    val isSendingComment: Boolean = false,
    val isSavingCommentEdit: Boolean = false,
    val hasMoreComments: Boolean = true,
    val hasMore: Boolean = true,
    val errorMessage: String? = null,
    val postDetailError: String? = null,
    val infoMessage: String? = null,
    /** 1.93：动态点赞者弹窗。 */
    val likersPostId: String? = null,
    val likers: List<UserDto> = emptyList(),
    val isLikersLoading: Boolean = false
) {
    val readyImageUrls: List<String>
        get() = imageDrafts.mapNotNull { it.uploadUrl }

    val isUploadingImage: Boolean
        get() = imageDrafts.any { it.isUploading }

    val canPublish: Boolean
        get() = isVisibilityReady && !isPublishing && !isUploadingImage &&
            (composerText.isNotBlank() || readyImageUrls.isNotEmpty())
}

class ExploreViewModel(application: Application) : AndroidViewModel(application) {
    private val loadMoreMutex = Mutex()
    private val commentsLoadMutex = Mutex()
    private var feedGeneration = 0L
    private var refreshJob: kotlinx.coroutines.Job? = null
    private var commentsGeneration = 0L
    private var commentsJob: kotlinx.coroutines.Job? = null
    private var postDetailGeneration = 0L
    private var postDetailJob: kotlinx.coroutines.Job? = null
    private var privacyDefaultsGeneration = 0L
    private var privacyDefaultsJob: kotlinx.coroutines.Job? = null
    private val likeJobs = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    private val tokenManager = TokenManager.getInstance(application)
    private fun text(id: Int, vararg args: Any): String = getApplication<Application>().getString(id, *args)

    private fun isCurrentOwner(expectedUserId: String): Boolean =
        expectedUserId.isNotBlank() && tokenManager.getUserId() == expectedUserId

    private fun canResetUploadState(expectedUserId: String): Boolean {
        val currentUserId = tokenManager.getUserId()
        return currentUserId.isNullOrBlank() || currentUserId == expectedUserId
    }

    private fun Throwable.rethrowIfCancellation() {
        if (this is CancellationException) throw this
    }

    private val draftPrefs = application.getSharedPreferences("explore_draft", android.content.Context.MODE_PRIVATE)

    private fun draftOwnerId(): String = tokenManager.getUserId().orEmpty()

    // 8.58：legacy 一次性迁移标记（按键独立）——旧版本无作用域草稿只允许迁移一次，
    // 避免「先登录账号继承上一位用户未发送文本」的多账号泄漏；
    // 且 composer/visibility 用各自标记，防止初始化顺序（visibility 先读）吞掉 composer 旧草稿
    private fun legacyDraftMigrationPending(key: String): Boolean =
        !draftPrefs.getBoolean(key, false)

    private fun markLegacyDraftMigrated(key: String) {
        draftPrefs.edit().putBoolean(key, true).apply()
    }

    private fun readDraftComposer(): String {
        val owner = draftOwnerId()
        val scoped = ExploreDraftPolicy.scopedKey(ExploreDraftPolicy.KEY_COMPOSER_TEXT, owner)
        if (scoped != null) {
            draftPrefs.getString(scoped, null)?.let { return it }
        }
        // One-shot migrate unscoped legacy draft into the current owner bucket.
        val migrationKey = "composer_migrated_v1"
        val legacy = draftPrefs.getString(ExploreDraftPolicy.KEY_COMPOSER_TEXT, null)
        if (!legacy.isNullOrEmpty() && scoped != null && legacyDraftMigrationPending(migrationKey)) {
            markLegacyDraftMigrated(migrationKey)
            draftPrefs.edit()
                .putString(scoped, legacy)
                .remove(ExploreDraftPolicy.KEY_COMPOSER_TEXT)
                .apply()
            return legacy
        }
        return ""
    }

    private fun readDraftVisibility(): String? {
        val owner = draftOwnerId()
        val scoped = ExploreDraftPolicy.scopedKey(ExploreDraftPolicy.KEY_VISIBILITY, owner)
        if (scoped != null) {
            val stored = draftPrefs.getString(scoped, null)
            if (stored != null && stored in ExploreDraftPolicy.VISIBILITIES) return stored
            if (stored != null) draftPrefs.edit().remove(scoped).apply()
        }
        val migrationKey = "visibility_migrated_v1"
        val legacy = draftPrefs.getString(ExploreDraftPolicy.KEY_VISIBILITY, null)
        if (!legacy.isNullOrBlank() && scoped != null && legacyDraftMigrationPending(migrationKey)) {
            if (legacy !in ExploreDraftPolicy.VISIBILITIES) {
                draftPrefs.edit().remove(ExploreDraftPolicy.KEY_VISIBILITY).apply()
                return null
            }
            markLegacyDraftMigrated(migrationKey)
            draftPrefs.edit()
                .putString(scoped, legacy)
                .remove(ExploreDraftPolicy.KEY_VISIBILITY)
                .apply()
            return legacy
        }
        return null
    }

    private fun persistDraftComposer(text: String) {
        val scoped = ExploreDraftPolicy.scopedKey(ExploreDraftPolicy.KEY_COMPOSER_TEXT, draftOwnerId())
            ?: return
        draftPrefs.edit()
            .putString(scoped, text)
            .remove(ExploreDraftPolicy.KEY_COMPOSER_TEXT)
            .apply()
    }

    private fun persistDraftVisibility(visibility: String) {
        val scoped = ExploreDraftPolicy.scopedKey(ExploreDraftPolicy.KEY_VISIBILITY, draftOwnerId())
            ?: return
        draftPrefs.edit()
            .putString(scoped, visibility)
            .remove(ExploreDraftPolicy.KEY_VISIBILITY)
            .apply()
    }

    private fun clearDraftPrefsForOwner() {
        val owner = draftOwnerId()
        val editor = draftPrefs.edit()
            .remove(ExploreDraftPolicy.KEY_COMPOSER_TEXT)
            .remove(ExploreDraftPolicy.KEY_VISIBILITY)
        ExploreDraftPolicy.scopedKey(ExploreDraftPolicy.KEY_COMPOSER_TEXT, owner)?.let { editor.remove(it) }
        ExploreDraftPolicy.scopedKey(ExploreDraftPolicy.KEY_VISIBILITY, owner)?.let { editor.remove(it) }
        editor.apply()
    }

    private val persistedDraftVisibility = readDraftVisibility()
    private val persistedComposerDraft = readDraftComposer()
    private val _uiState = MutableStateFlow(ExploreUiState(
        composerText = persistedComposerDraft,
        composerDraftRestored = persistedComposerDraft.isNotBlank(),
        selectedVisibility = persistedDraftVisibility ?: "PRIVATE",
        useDefaultPostVisibility = persistedDraftVisibility == null,
        isVisibilityReady = persistedDraftVisibility != null
    ))
    val uiState: StateFlow<ExploreUiState> = _uiState.asStateFlow()

    val visibilityOptions = listOf(
        VisibilityOption("PUBLIC", text(R.string.explore_visibility_public), text(R.string.explore_visibility_public_subtitle)),
        VisibilityOption("CONTACTS", text(R.string.explore_visibility_contacts), text(R.string.explore_visibility_contacts_subtitle)),
        VisibilityOption("PRIVATE", text(R.string.explore_visibility_private), text(R.string.explore_visibility_private_subtitle))
    )

    init {
        refresh()
    }

    private fun loadPrivacyDefaults() {
        val token = tokenManager.getToken() ?: return
        val privacyOwnerUserId = tokenManager.getUserId().orEmpty()
        if (privacyOwnerUserId.isBlank()) return
        val generation = ++privacyDefaultsGeneration
        privacyDefaultsJob?.cancel()
        privacyDefaultsJob = viewModelScope.launch {
            if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = privacyOwnerUserId,
                    liveToken = tokenManager.getToken(),
                    liveUserId = tokenManager.getUserId(),
                )
            ) {
                return@launch
            }
            val liveToken = tokenManager.getToken() ?: token
            ApiService.getPrivacy(liveToken).onSuccess { privacy ->
                if (privacyDefaultsGeneration != generation ||
                    !com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = privacyOwnerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    return@onSuccess
                }
                val accountDefault = normalizeVisibility(privacy.defaultPostVisibility)
                _uiState.update { state ->
                    if (state.useDefaultPostVisibility) {
                        state.copy(
                            selectedVisibility = accountDefault,
                            defaultPostVisibility = accountDefault,
                            isVisibilityReady = true
                        )
                    } else {
                        state.copy(defaultPostVisibility = accountDefault, isVisibilityReady = true)
                    }
                }
            }.onFailure { error ->
                error.rethrowIfCancellation()
                if (privacyDefaultsGeneration != generation ||
                    !com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = privacyOwnerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    return@onFailure
                }
                _uiState.update { state ->
                    if (state.useDefaultPostVisibility && !state.isVisibilityReady) {
                        state.copy(errorMessage = state.errorMessage ?: text(R.string.explore_visibility_load_failed))
                    } else {
                        state
                    }
                }
            }
        }
    }

    fun refresh() {
        loadPrivacyDefaults()
        val token = tokenManager.getToken()
        val refreshOwnerUserId = tokenManager.getUserId().orEmpty()
        if (token.isNullOrBlank() || refreshOwnerUserId.isBlank()) {
            _uiState.update { it.copy(isLoading = false, errorMessage = text(R.string.explore_login_required_page)) }
            return
        }
        val generation = ++feedGeneration
        refreshJob?.cancel()
        _uiState.update {
            it.copy(isLoading = true, isLoadingMore = false, errorMessage = null, hasMore = true)
        }
        val job = viewModelScope.launch {
            try {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = refreshOwnerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    if (feedGeneration == generation && isCurrentOwner(refreshOwnerUserId)) {
                        _uiState.update { it.copy(isLoading = false) }
                    }
                    return@launch
                }
                val liveToken = tokenManager.getToken() ?: token
                ApiService.getPosts(liveToken).fold(
                    onSuccess = { posts ->
                        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                expectedUserId = refreshOwnerUserId,
                                liveToken = tokenManager.getToken(),
                                liveUserId = tokenManager.getUserId(),
                            )
                        ) {
                            return@fold
                        }
                        if (feedGeneration == generation) {
                            _uiState.update { it.copy(posts = posts, isLoading = false, hasMore = posts.size >= FEED_PAGE_SIZE) }
                        }
                    },
                    onFailure = { error ->
                        error.rethrowIfCancellation()
                        if (feedGeneration == generation && com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                expectedUserId = refreshOwnerUserId,
                                liveToken = tokenManager.getToken(),
                                liveUserId = tokenManager.getUserId(),
                            )
                        ) {
                            _uiState.update { it.copy(isLoading = false, errorMessage = error.message ?: text(R.string.explore_posts_load_failed)) }
                        }
                    }
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                if (feedGeneration == generation && isCurrentOwner(refreshOwnerUserId)) {
                    _uiState.update { it.copy(isLoading = false) }
                }
                throw error
            }
        }
        refreshJob = job
        job.invokeOnCompletion {
            if (refreshJob === job) refreshJob = null
        }
    }

    fun loadMore() {
        val snapshot = _uiState.value
        if (!ExploreFeedPolicy.canStartLoadMore(
                isLoading = snapshot.isLoading,
                isLoadingMore = snapshot.isLoadingMore,
                hasMore = snapshot.hasMore,
                postCount = snapshot.posts.size
            )
        ) {
            return
        }
        viewModelScope.launch {
            loadMoreMutex.withLock {
                val guardState = _uiState.value
                if (!ExploreFeedPolicy.canStartLoadMore(
                        isLoading = guardState.isLoading,
                        isLoadingMore = guardState.isLoadingMore,
                        hasMore = guardState.hasMore,
                        postCount = guardState.posts.size
                    )
                ) {
                    return@withLock
                }
                val generation = feedGeneration
                val cursor = ExploreFeedPolicy.oldestCursor(guardState.posts) ?: return@withLock
                val token = tokenManager.getToken()
                val loadMoreOwnerUserId = tokenManager.getUserId().orEmpty()
                if (ExploreFeedPolicy.missingSessionForLoadMore(token) || token == null || loadMoreOwnerUserId.isBlank()) {
                    // Never leave isLoadingMore stuck: null token used to early-return after setting true.
                    _uiState.update {
                        it.copy(isLoadingMore = false, errorMessage = text(R.string.explore_login_required_page))
                    }
                    return@withLock
                }
                _uiState.update { it.copy(isLoadingMore = true, errorMessage = null) }
                try {
                    if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                            expectedUserId = loadMoreOwnerUserId,
                            liveToken = tokenManager.getToken(),
                            liveUserId = tokenManager.getUserId(),
                        )
                    ) {
                        if (isCurrentOwner(loadMoreOwnerUserId)) {
                            _uiState.update { it.copy(isLoadingMore = false) }
                        }
                        return@withLock
                    }
                    val liveToken = tokenManager.getToken() ?: token
                    ApiService.getPosts(
                        liveToken,
                        before = cursor.createdAt,
                        beforeId = cursor.postId
                    ).fold(
                        onSuccess = { posts ->
                            if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                    expectedUserId = loadMoreOwnerUserId,
                                    liveToken = tokenManager.getToken(),
                                    liveUserId = tokenManager.getUserId(),
                                )
                            ) {
                                return@fold
                            }
                            if (feedGeneration == generation) {
                                _uiState.update {
                                    it.copy(
                                        posts = (it.posts + posts).distinctBy { p -> p.id },
                                        isLoadingMore = false,
                                        hasMore = posts.size >= FEED_PAGE_SIZE
                                    )
                                }
                            }
                        },
                        onFailure = { error ->
                            error.rethrowIfCancellation()
                            if (feedGeneration == generation && com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                    expectedUserId = loadMoreOwnerUserId,
                                    liveToken = tokenManager.getToken(),
                                    liveUserId = tokenManager.getUserId(),
                                )
                            ) {
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
                    if (feedGeneration == generation && isCurrentOwner(loadMoreOwnerUserId)) {
                        _uiState.update { it.copy(isLoadingMore = false) }
                    }
                    throw error
                }
            }
        }
    }

    /** 1.00：删除自己的评论。 */
    /** 1.06：举报动态。 */
    fun reportPost(post: com.maodouchat.network.PostDto) {
        val token = tokenManager.getToken()
        if (token.isNullOrBlank()) return
        viewModelScope.launch {
            try {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = tokenManager.getUserId().orEmpty(),
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    return@launch
                }
                val liveToken = tokenManager.getToken() ?: token
                ApiService.createReport(
                    token = liveToken,
                    targetType = "POST",
                    targetId = post.id,
                    reason = "举报动态",
                    description = post.content.take(200).takeIf { it.isNotBlank() }
                ).onSuccess {
                    _uiState.update { it.copy(infoMessage = text(R.string.explore_report_sent)) }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                _uiState.update { it.copy(infoMessage = text(R.string.explore_report_failed)) }
            }
        }
    }

    /** 1.172：屏蔽动态作者（防骚扰；本地从流与详情移除其动态）。 */
    fun blockPostAuthor(userId: String) {
        val token = tokenManager.getToken()
        val ownerUserId = tokenManager.getUserId().orEmpty()
        if (token.isNullOrBlank() || ownerUserId.isBlank()) return
        if (userId.isBlank() || userId == ownerUserId) {
            _uiState.update { it.copy(infoMessage = text(R.string.explore_block_self)) }
            return
        }
        viewModelScope.launch {
            try {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = ownerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    return@launch
                }
                val liveToken = tokenManager.getToken() ?: token
                ApiService.blockUser(liveToken, userId).fold(
                    onSuccess = {
                        _uiState.update { st ->
                            st.copy(
                                posts = st.posts.filterNot { it.author.id == userId },
                                detailPost = st.detailPost?.takeIf { it.author.id != userId },
                                infoMessage = text(R.string.explore_block_done)
                            )
                        }
                    },
                    onFailure = {
                        _uiState.update { it.copy(infoMessage = text(R.string.explore_block_failed)) }
                    }
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                _uiState.update { it.copy(infoMessage = text(R.string.explore_block_failed)) }
            }
        }
    }

    /** 1.245：举报评论。 */
    fun reportComment(comment: com.maodouchat.network.PostCommentDto) {
        val token = tokenManager.getToken()
        val ownerUserId = tokenManager.getUserId().orEmpty()
        if (token.isNullOrBlank() || ownerUserId.isBlank()) return
        viewModelScope.launch {
            try {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = ownerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    return@launch
                }
                val liveToken = tokenManager.getToken() ?: token
                ApiService.createReport(
                    token = liveToken,
                    targetType = "COMMENT",
                    targetId = comment.id,
                    reason = "举报评论",
                    description = comment.content.take(200).takeIf { it.isNotBlank() }
                ).onSuccess {
                    _uiState.update { it.copy(infoMessage = text(R.string.explore_report_sent)) }
                }.onFailure {
                    _uiState.update { it.copy(infoMessage = text(R.string.explore_report_failed)) }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                _uiState.update { it.copy(infoMessage = text(R.string.explore_report_failed)) }
            }
        }
    }

    fun deleteComment(comment: com.maodouchat.network.PostCommentDto) {
        val token = tokenManager.getToken()
        val ownerUserId = tokenManager.getUserId().orEmpty()
        if (token.isNullOrBlank() || ownerUserId.isBlank()) return
        val postId = _uiState.value.selectedPostId ?: return
        viewModelScope.launch {
            try {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = ownerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    return@launch
                }
                val liveToken = tokenManager.getToken() ?: token
                ApiService.deleteComment(liveToken, postId, comment.id)
                    .onSuccess {
                        _uiState.update { state ->
                            state.copy(
                                comments = state.comments.filter { it.id != comment.id },
                                // 1.117：删除评论同步递减评论计数（posts + detailPost）
                                posts = ExploreFeedPolicy.decrementCommentCount(state.posts, postId),
                                detailPost = state.detailPost?.let { post ->
                                    if (post.id == postId && post.commentCount > 0) post.copy(commentCount = post.commentCount - 1) else post
                                }
                            )
                        }
                    }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                // 失败静默：下次重新加载会收敛
            }
        }
    }

    /** 编辑自己的评论。 */
    fun saveCommentEdit(comment: com.maodouchat.network.PostCommentDto, newText: String) {
        val token = tokenManager.getToken()
        val ownerUserId = tokenManager.getUserId().orEmpty()
        val content = newText.trim()
        if (token.isNullOrBlank() || ownerUserId.isBlank()) return
        if (content.isBlank() || content.length > 1_000) {
            _uiState.update { it.copy(infoMessage = text(R.string.explore_comment_edit_invalid)) }
            return
        }
        if (_uiState.value.isSavingCommentEdit) return
        _uiState.update { it.copy(isSavingCommentEdit = true, infoMessage = null) }
        viewModelScope.launch {
            try {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = ownerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    _uiState.update { it.copy(isSavingCommentEdit = false) }
                    return@launch
                }
                val liveToken = tokenManager.getToken() ?: token
                ApiService.editPostComment(liveToken, comment.postId, comment.id, content).fold(
                    onSuccess = { updated ->
                        if (com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                expectedUserId = ownerUserId,
                                liveToken = tokenManager.getToken(),
                                liveUserId = tokenManager.getUserId(),
                            )
                        ) {
                            _uiState.update { state ->
                                state.copy(
                                    comments = state.comments.map { if (it.id == comment.id) updated else it },
                                    isSavingCommentEdit = false,
                                    infoMessage = text(R.string.explore_comment_edit_success)
                                )
                            }
                        }
                    },
                    onFailure = {
                        if (com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                expectedUserId = ownerUserId,
                                liveToken = tokenManager.getToken(),
                                liveUserId = tokenManager.getUserId(),
                            )
                        ) {
                            _uiState.update {
                                it.copy(
                                    isSavingCommentEdit = false,
                                    infoMessage = text(R.string.explore_comment_edit_failed)
                                )
                            }
                        }
                    }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _uiState.update { it.copy(isSavingCommentEdit = false, infoMessage = text(R.string.explore_comment_edit_failed)) }
            }
        }
    }

    // 1.92：复制评论文本（纯本地操作，不依赖网络）
    fun copyComment(comment: com.maodouchat.network.PostCommentDto) {
        if (comment.content.isBlank()) return
        val context = getApplication<Application>()
        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        if (clipboard == null) return
        val text = if (comment.parentId.isNullOrBlank()) {
            comment.content
        } else {
            "@${comment.author.name}: ${comment.content}"
        }
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("comment", text))
        android.widget.Toast.makeText(context, text(R.string.explore_comment_copied), android.widget.Toast.LENGTH_SHORT).show()
    }

    /** 1.52：点赞/取消点赞评论（乐观更新 + 失败回滚）。 */
    fun toggleCommentLike(comment: com.maodouchat.network.PostCommentDto) {        val token = tokenManager.getToken()
        val ownerUserId = tokenManager.getUserId().orEmpty()
        if (token.isNullOrBlank() || ownerUserId.isBlank()) return
        val postId = _uiState.value.selectedPostId ?: return
        val targetLiked = !comment.likedByMe
        // 乐观更新
        _uiState.update { state ->
            state.copy(
                comments = state.comments.map {
                    if (it.id == comment.id) {
                        it.copy(
                            likedByMe = targetLiked,
                            likeCount = (it.likeCount + if (targetLiked) 1 else -1).coerceAtLeast(0)
                        )
                    } else it
                }
            )
        }
        viewModelScope.launch {
            try {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = ownerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    return@launch
                }
                val liveToken = tokenManager.getToken() ?: token
                val result = if (targetLiked) {
                    ApiService.likeComment(liveToken, postId, comment.id)
                } else {
                    ApiService.unlikeComment(liveToken, postId, comment.id)
                }
                result.onSuccess { resp ->
                    _uiState.update { state ->
                        state.copy(
                            comments = state.comments.map {
                                if (it.id == comment.id) it.copy(likedByMe = targetLiked, likeCount = resp.likeCount.coerceAtLeast(0)) else it
                            }
                        )
                    }
                }.onFailure {
                    // 失败回滚
                    _uiState.update { state ->
                        state.copy(
                            comments = state.comments.map {
                                if (it.id == comment.id) it.copy(likedByMe = comment.likedByMe, likeCount = comment.likeCount) else it
                            }
                        )
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                // 失败回滚
                _uiState.update { state ->
                    state.copy(
                        comments = state.comments.map {
                            if (it.id == comment.id) it.copy(likedByMe = comment.likedByMe, likeCount = comment.likeCount) else it
                        }
                    )
                }
            }
        }
    }

    fun onComposerTextChange(text: String) {
        val trimmed = text.take(2_000)
        persistDraftComposer(trimmed)
        _uiState.update { it.copy(composerText = trimmed, composerDraftRestored = false) }
    }

    /** 1.177：关闭「已恢复草稿」提示（保留草稿文本）。 */
    fun dismissComposerDraftHint() {
        _uiState.update { it.copy(composerDraftRestored = false) }
    }

    /** 1.202：清空发布框（文本 + 已选图片 + 持久化草稿）；1.272：同时恢复默认可见范围。 */
    fun clearComposer() {
        persistDraftComposer("")
        _uiState.update {
            it.copy(
                composerText = "",
                composerDraftRestored = false,
                imageDrafts = emptyList(),
                selectedVisibility = it.defaultPostVisibility ?: it.selectedVisibility,
                useDefaultPostVisibility = true,
                isVisibilityReady = false
            )
        }
    }

    fun onCommentTextChange(text: String) {
        _uiState.update { it.copy(commentText = text.take(800)) }
    }

    /** 1.76：设置回复目标（并预填 @目标 到输入框，未包含时）。 */
    fun setReplyToComment(comment: PostCommentDto) {
        val current = _uiState.value.commentText
        val mention = "@${comment.author.name} "
        val next = if (current.isBlank()) mention else if (current.contains(mention)) current else current + mention
        _uiState.update { it.copy(replyToComment = comment, commentText = next.take(800)) }
    }

    /** 1.76：取消回复目标（保留已输入内容，仅清回复上下文）。 */
    fun clearReplyToComment() {
        _uiState.update { it.copy(replyToComment = null) }
    }

    fun onVisibilitySelected(visibility: String) {
        val normalized = normalizeVisibility(visibility)
        persistDraftVisibility(normalized)
        _uiState.update {
            it.copy(
                selectedVisibility = normalized,
                useDefaultPostVisibility = false,
                isVisibilityReady = true
            )
        }
    }

    fun addImages(uris: List<Uri>) {
        val token = tokenManager.getToken()
        if (token.isNullOrBlank()) {
            _uiState.update { it.copy(errorMessage = text(R.string.explore_login_required)) }
            return
        }
        val available = 9 - _uiState.value.imageDrafts.size
        val picked = uris.take(available)
        if (picked.isEmpty()) {
            _uiState.update { it.copy(infoMessage = text(R.string.explore_max_images)) }
            return
        }
        val drafts = picked.map { PostImageDraft(uri = it) }
        _uiState.update { it.copy(imageDrafts = it.imageDrafts + drafts, errorMessage = null) }
        drafts.forEach(::uploadDraftImage)
    }

    /** 1.211：重试上传失败/中断的发布图片（复用原 uri 重新压缩上传）。 */
    fun retryDraftImage(draftId: String) {
        val draft = _uiState.value.imageDrafts.firstOrNull { it.id == draftId } ?: return
        if (draft.isUploading) return
        if (draft.errorMessage == null && draft.uploadUrl != null) return
        updateDraft(draftId) { it.copy(isUploading = true, errorMessage = null) }
        uploadDraftImage(draft.copy(isUploading = true, errorMessage = null))
    }

    private fun uploadDraftImage(draft: PostImageDraft) {
        val uploadOwnerUserId = tokenManager.getUserId().orEmpty()
        val uploadToken = tokenManager.getToken()?.takeIf(String::isNotBlank)
        if (uploadOwnerUserId.isBlank() || uploadToken == null) {
            updateDraft(draft.id) {
                it.copy(isUploading = false, errorMessage = text(R.string.explore_login_required))
            }
            return
        }
        viewModelScope.launch {
            try {
                val base64Data = withContext(Dispatchers.IO) {
                    ImagePicker.uriToBase64(getApplication(), draft.uri, maxWidth = 1280, quality = 78)
                }
                if (!isCurrentOwner(uploadOwnerUserId)) {
                    if (canResetUploadState(uploadOwnerUserId)) {
                        updateDraft(draft.id) { it.copy(isUploading = false) }
                    }
                    return@launch
                }
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = uploadOwnerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    if (canResetUploadState(uploadOwnerUserId)) {
                        updateDraft(draft.id) { it.copy(isUploading = false) }
                    }
                    return@launch
                }
                if (base64Data.isNullOrBlank()) {
                    // 读取失败的可能原因：文件不可读、超过 15MB 限制、解码失败。
                    updateDraft(draft.id) { it.copy(isUploading = false, errorMessage = text(R.string.explore_image_read_failed)) }
                    _uiState.update { it.copy(errorMessage = text(R.string.explore_some_images_read_failed)) }
                    return@launch
                }
                ApiService.uploadPostImage(uploadToken, base64Data).fold(
                    onSuccess = { url ->
                        if (com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                expectedUserId = uploadOwnerUserId,
                                liveToken = tokenManager.getToken(),
                                liveUserId = tokenManager.getUserId(),
                            )
                        ) {
                            if (_uiState.value.imageDrafts.any { it.id == draft.id }) {
                                updateDraft(draft.id) { it.copy(uploadUrl = url, isUploading = false, errorMessage = null) }
                            } else {
                                discardUploadedImage(url, uploadOwnerUserId)
                            }
                        } else if (canResetUploadState(uploadOwnerUserId)) {
                            updateDraft(draft.id) { it.copy(isUploading = false) }
                        }
                    },
                    onFailure = { error ->
                        error.rethrowIfCancellation()
                        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                expectedUserId = uploadOwnerUserId,
                                liveToken = tokenManager.getToken(),
                                liveUserId = tokenManager.getUserId(),
                            )
                        ) {
                            if (canResetUploadState(uploadOwnerUserId)) {
                                updateDraft(draft.id) { it.copy(isUploading = false) }
                            }
                            return@fold
                        }
                        val kind = ExplorePublishErrorPolicy.classify(error)
                        val fallback = when (kind) {
                            ExplorePublishErrorPolicy.Kind.POST_RESTRICTED ->
                                text(R.string.explore_post_restricted)
                            ExplorePublishErrorPolicy.Kind.AUTH ->
                                text(R.string.explore_login_required)
                            else -> text(R.string.explore_upload_failed)
                        }
                        val draftMsg = ExplorePublishErrorPolicy.displayMessage(error, fallback)
                        val banner = when (kind) {
                            ExplorePublishErrorPolicy.Kind.POST_RESTRICTED,
                            ExplorePublishErrorPolicy.Kind.SUSPENDED,
                            ExplorePublishErrorPolicy.Kind.AUTH -> draftMsg
                            else -> text(R.string.explore_some_images_upload_failed)
                        }
                        updateDraft(draft.id) { it.copy(isUploading = false, errorMessage = draftMsg) }
                        _uiState.update { it.copy(errorMessage = banner) }
                    }
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                if (canResetUploadState(uploadOwnerUserId)) {
                    updateDraft(draft.id) { it.copy(isUploading = false) }
                }
                throw error
            } catch (_: Exception) {
                if (canResetUploadState(uploadOwnerUserId)) {
                    updateDraft(draft.id) {
                        it.copy(isUploading = false, errorMessage = text(R.string.explore_upload_failed))
                    }
                    _uiState.update { it.copy(errorMessage = text(R.string.explore_some_images_upload_failed)) }
                }
            }
        }
    }

    fun removeImage(id: String) {
        val draft = _uiState.value.imageDrafts.firstOrNull { it.id == id }
        _uiState.update { state ->
            state.copy(imageDrafts = state.imageDrafts.filterNot { it.id == id })
        }
        draft?.uploadUrl?.let { discardUploadedImage(it, draftOwnerId()) }
    }

    fun resetPostState() {
        val uploadedUrls = _uiState.value.readyImageUrls
        val ownerUserId = draftOwnerId()
        clearDraftPrefsForOwner()
        _uiState.update { state ->
            val accountDefault = state.defaultPostVisibility
            state.copy(
                composerText = "",
                imageDrafts = emptyList(),
                selectedVisibility = accountDefault ?: "PRIVATE",
                useDefaultPostVisibility = true,
                isVisibilityReady = accountDefault != null
            )
        }
        if (_uiState.value.defaultPostVisibility == null) loadPrivacyDefaults()
        uploadedUrls.forEach { discardUploadedImage(it, ownerUserId) }
    }

    private fun discardUploadedImage(url: String, ownerUserId: String) {
        if (ownerUserId.isBlank()) return
        viewModelScope.launch {
            if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = ownerUserId,
                    liveToken = tokenManager.getToken(),
                    liveUserId = tokenManager.getUserId(),
                )
            ) return@launch
            val liveToken = tokenManager.getToken()?.takeIf(String::isNotBlank) ?: return@launch
            ApiService.discardPostImage(liveToken, url)
        }
    }

    fun publishPost() {
        val token = tokenManager.getToken()
        val publishOwnerUserId = tokenManager.getUserId().orEmpty()
        val state = _uiState.value
        if (token.isNullOrBlank() || publishOwnerUserId.isBlank()) {
            _uiState.update { it.copy(errorMessage = text(R.string.explore_login_required)) }
            return
        }
        if (!state.canPublish) return
        _uiState.update { it.copy(isPublishing = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = publishOwnerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    if (isCurrentOwner(publishOwnerUserId)) {
                        _uiState.update { it.copy(isPublishing = false, errorMessage = text(R.string.explore_login_required)) }
                    }
                    return@launch
                }
                val liveToken = tokenManager.getToken() ?: token
                val visibilityOverride = state.selectedVisibility.takeUnless { state.useDefaultPostVisibility }
                ApiService.createPost(liveToken, state.composerText.trim(), state.readyImageUrls, visibilityOverride).fold(
                    onSuccess = { post ->
                        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                expectedUserId = publishOwnerUserId,
                                liveToken = tokenManager.getToken(),
                                liveUserId = tokenManager.getUserId(),
                            )
                        ) {
                            return@fold
                        }
                        val current = _uiState.value
                        val draftUnchanged = current.composerText == state.composerText &&
                            current.imageDrafts == state.imageDrafts &&
                            current.useDefaultPostVisibility == state.useDefaultPostVisibility &&
                            (state.useDefaultPostVisibility || current.selectedVisibility == state.selectedVisibility)
                        _uiState.update {
                            if (draftUnchanged) {
                                val resolvedDefault = if (state.useDefaultPostVisibility) {
                                    normalizeVisibility(post.visibility)
                                } else {
                                    it.defaultPostVisibility
                                }
                                it.copy(
                                    posts = listOf(post) + it.posts,
                                    composerText = "",
                                    composerDraftRestored = false,
                                    imageDrafts = emptyList(),
                                    isPublishing = false,
                                    publishRevision = it.publishRevision + 1,
                                    infoMessage = text(R.string.explore_publish_success),
                                    selectedVisibility = resolvedDefault ?: "PRIVATE",
                                    defaultPostVisibility = resolvedDefault,
                                    useDefaultPostVisibility = true,
                                    isVisibilityReady = resolvedDefault != null
                                )
                            } else {
                                it.copy(
                                    posts = listOf(post) + it.posts,
                                    isPublishing = false,
                                    infoMessage = text(R.string.explore_publish_success)
                                )
                            }
                        }
                        if (draftUnchanged) {
                            clearDraftPrefsForOwner()
                            loadPrivacyDefaults()
                        }
                    },
                    onFailure = { error ->
                        error.rethrowIfCancellation()
                        if (!isCurrentOwner(publishOwnerUserId)) return@fold
                        val fallback = when (ExplorePublishErrorPolicy.classify(error)) {
                            ExplorePublishErrorPolicy.Kind.POST_RESTRICTED ->
                                text(R.string.explore_post_restricted)
                            ExplorePublishErrorPolicy.Kind.AUTH ->
                                text(R.string.explore_login_required)
                            else -> text(R.string.explore_publish_failed)
                        }
                        val msg = ExplorePublishErrorPolicy.displayMessage(error, fallback)
                        _uiState.update { it.copy(isPublishing = false, errorMessage = msg) }
                    }
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                if (isCurrentOwner(publishOwnerUserId)) {
                    _uiState.update { it.copy(isPublishing = false) }
                }
                throw error
            }
        }
    }

    fun toggleLike(post: PostDto) {
        val token = tokenManager.getToken()
        val likeOwnerUserId = tokenManager.getUserId().orEmpty()
        if (token.isNullOrBlank() || likeOwnerUserId.isBlank()) {
            _uiState.update { it.copy(errorMessage = text(R.string.explore_login_required)) }
            return
        }
        // 1.137：不能给自己的动态点赞（与服务器校验一致，避免乐观回滚抖动）
        if (post.isMine || post.author.id == likeOwnerUserId) {
            _uiState.update { it.copy(infoMessage = text(R.string.explore_self_like_denied)) }
            return
        }
        val currentPost = _uiState.value.posts.firstOrNull { it.id == post.id }
            ?: _uiState.value.detailPost?.takeIf { it.id == post.id }
            ?: return
        val toggle = ExploreFeedPolicy.toggleLike(currentPost)
        val generation = feedGeneration
        val jobKey = "$likeOwnerUserId:${post.id}"
        val job = viewModelScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = likeOwnerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    if (feedGeneration == generation && isCurrentOwner(likeOwnerUserId)) {
                        _uiState.update { state ->
                            state.copy(
                                posts = ExploreFeedPolicy.rollbackPost(state.posts, toggle.previous),
                                detailPost = state.detailPost?.takeIf { it.id == toggle.previous.id }?.let { toggle.previous }
                            )
                        }
                    }
                    return@launch
                }
                val liveToken = tokenManager.getToken() ?: token
                val result = if (toggle.willLike) {
                    ApiService.likePost(liveToken, post.id)
                } else {
                    ApiService.unlikePost(liveToken, post.id)
                }
                result.fold(
                    onSuccess = { server ->
                        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                expectedUserId = likeOwnerUserId,
                                liveToken = tokenManager.getToken(),
                                liveUserId = tokenManager.getUserId(),
                            )
                        ) {
                            return@fold
                        }
                        if (feedGeneration == generation) {
                            _uiState.update { state ->
                                state.copy(
                                    posts = ExploreFeedPolicy.applyServerPost(state.posts, server),
                                    detailPost = state.detailPost?.takeIf { it.id == server.id }?.let { server }
                                )
                            }
                        }
                    },
                    onFailure = { error ->
                        error.rethrowIfCancellation()
                        if (feedGeneration == generation && com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                expectedUserId = likeOwnerUserId,
                                liveToken = tokenManager.getToken(),
                                liveUserId = tokenManager.getUserId(),
                            )
                        ) {
                            // 8.58：404 = 动态已被删除（版主/作者删除）——本地移除 + 明确提示
                            val is404 = error is com.maodouchat.network.ApiException &&
                                error.kind == com.maodouchat.network.ApiFailureKind.HTTP &&
                                error.statusCode == 404
                            if (is404) {
                                _uiState.update { state ->
                                    state.copy(
                                        posts = ExploreFeedPolicy.removePost(state.posts, post.id),
                                        infoMessage = text(R.string.explore_post_deleted)
                                    )
                                }
                            } else {
                                _uiState.update { state ->
                                    state.copy(
                                        posts = ExploreFeedPolicy.rollbackPost(state.posts, toggle.previous),
                                        detailPost = state.detailPost?.takeIf { it.id == toggle.previous.id }?.let { toggle.previous },
                                        errorMessage = error.message ?: text(R.string.error_operation_failed)
                                    )
                                }
                            }
                        }
                    }
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                if (feedGeneration == generation && isCurrentOwner(likeOwnerUserId)) {
                    _uiState.update { state ->
                        state.copy(
                            posts = ExploreFeedPolicy.rollbackPost(state.posts, toggle.previous),
                            detailPost = state.detailPost?.takeIf { it.id == toggle.previous.id }?.let { toggle.previous }
                        )
                    }
                }
                throw error
            }
        }
        if (likeJobs.putIfAbsent(jobKey, job) != null) {
            job.cancel()
            return
        }
        updatePost(toggle.optimistic)
        job.invokeOnCompletion { likeJobs.remove(jobKey, job) }
        job.start()
    }

    fun requestEditPost(postId: String) {
        val post = _uiState.value.posts.find { it.id == postId } ?: return
        _uiState.update { it.copy(postPendingEditId = postId, editPostText = post.content, editPostVisibility = post.visibility) }
    }

    fun onEditPostTextChange(text: String) {
        _uiState.update { it.copy(editPostText = text.take(2_000)) }
    }

    fun onEditPostVisibilitySelected(visibility: String) {
        _uiState.update { it.copy(editPostVisibility = normalizeVisibility(visibility)) }
    }

    fun cancelEditPost() {
        _uiState.update { it.copy(postPendingEditId = null, editPostText = "", editPostVisibility = "PUBLIC", isEditingPost = false) }
    }

    fun confirmEditPost() {
        val token = tokenManager.getToken()
        val editOwnerUserId = tokenManager.getUserId().orEmpty()
        if (token.isNullOrBlank() || editOwnerUserId.isBlank()) {
            _uiState.update { it.copy(errorMessage = text(R.string.explore_login_required)) }
            return
        }
        val postId = _uiState.value.postPendingEditId ?: return
        val content = _uiState.value.editPostText.trim()
        val visibility = _uiState.value.editPostVisibility
        if (content.isBlank() || _uiState.value.isEditingPost) return
        _uiState.update { it.copy(isEditingPost = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = editOwnerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    if (isCurrentOwner(editOwnerUserId)) {
                        _uiState.update { it.copy(isEditingPost = false, errorMessage = text(R.string.explore_login_required)) }
                    }
                    return@launch
                }
                val liveToken = tokenManager.getToken() ?: token
                ApiService.editPost(liveToken, postId, content, visibility).fold(
                    onSuccess = { updated ->
                        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                expectedUserId = editOwnerUserId,
                                liveToken = tokenManager.getToken(),
                                liveUserId = tokenManager.getUserId(),
                            )
                        ) {
                            return@fold
                        }
                        _uiState.update {
                            it.copy(
                                posts = it.posts.map { post -> if (post.id == postId) updated else post },
                                postPendingEditId = null,
                                editPostText = "",
                                editPostVisibility = "PUBLIC",
                                isEditingPost = false,
                                infoMessage = text(R.string.explore_edit_success)
                            )
                        }
                    },
                    onFailure = { error ->
                        error.rethrowIfCancellation()
                        if (com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                expectedUserId = editOwnerUserId,
                                liveToken = tokenManager.getToken(),
                                liveUserId = tokenManager.getUserId(),
                            )
                        ) {
                            _uiState.update { it.copy(isEditingPost = false, errorMessage = error.message ?: text(R.string.explore_edit_failed)) }
                        }
                    }
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                if (isCurrentOwner(editOwnerUserId)) {
                    _uiState.update { it.copy(isEditingPost = false) }
                }
                throw error
            }
        }
    }

    fun requestDeletePost(postId: String) {
        _uiState.update { it.copy(postPendingDeleteId = postId) }
    }

    fun cancelDeletePost() {
        _uiState.update { it.copy(postPendingDeleteId = null) }
    }

    fun confirmDeletePost() {
        val postId = _uiState.value.postPendingDeleteId ?: return
        deletePost(postId)
    }

    private fun deletePost(postId: String) {
        val token = tokenManager.getToken()
        val deleteOwnerUserId = tokenManager.getUserId().orEmpty()
        if (token.isNullOrBlank() || deleteOwnerUserId.isBlank()) {
            // Do not optimistically remove the post when session is already gone.
            _uiState.update {
                it.copy(
                    postPendingDeleteId = null,
                    errorMessage = text(R.string.explore_login_required)
                )
            }
            return
        }
        val previousPosts = _uiState.value.posts
        val deletedPost = previousPosts.firstOrNull { it.id == postId } ?: return
        val deletedIndex = previousPosts.indexOfFirst { it.id == postId }
        _uiState.update {
            it.copy(
                posts = ExploreFeedPolicy.removePost(it.posts, postId),
                postPendingDeleteId = null,
                errorMessage = null
            )
        }
        viewModelScope.launch {
            try {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = deleteOwnerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    return@launch
                }
                val liveToken = tokenManager.getToken() ?: token
                ApiService.deletePost(liveToken, postId).fold(
                    onSuccess = {
                        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                expectedUserId = deleteOwnerUserId,
                                liveToken = tokenManager.getToken(),
                                liveUserId = tokenManager.getUserId(),
                            )
                        ) {
                            return@fold
                        }
                        _uiState.update { it.copy(infoMessage = text(R.string.explore_deleted)) }
                    },
                    onFailure = { error ->
                        error.rethrowIfCancellation()
                        if (com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                expectedUserId = deleteOwnerUserId,
                                liveToken = tokenManager.getToken(),
                                liveUserId = tokenManager.getUserId(),
                            )
                        ) {
                            _uiState.update {
                                it.copy(
                                    posts = ExploreFeedPolicy.restorePost(it.posts, deletedPost, deletedIndex),
                                    errorMessage = error.message ?: text(R.string.explore_delete_failed)
                                )
                            }
                        }
                    }
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                // Ambiguous whether delete landed — keep optimistic removal; user can refresh.
                throw error
            }
        }
    }

    fun openComments(postId: String) {
        val token = tokenManager.getToken()
        val commentsOwnerUserId = tokenManager.getUserId().orEmpty()
        if (token.isNullOrBlank() || commentsOwnerUserId.isBlank()) {
            _uiState.update { it.copy(errorMessage = text(R.string.explore_login_required)) }
            return
        }
        val generation = ++commentsGeneration
        commentsJob?.cancel()
        _uiState.update {
            it.copy(
                selectedPostId = postId,
                comments = emptyList(),
                commentText = "",
                isCommentsLoading = true,
                isLoadingOlderComments = false,
                hasMoreComments = true,
                // 8.58：切换帖子时复位发送中——避免绕过 closeComments 直接 openComments(新帖) 时按钮永久禁用
                isSendingComment = false
            )
        }
        val job = viewModelScope.launch {
            try {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = commentsOwnerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    if (commentsGeneration == generation && isCurrentOwner(commentsOwnerUserId) &&
                        _uiState.value.selectedPostId == postId
                    ) {
                        _uiState.update { it.copy(isCommentsLoading = false) }
                    }
                    return@launch
                }
                val liveToken = tokenManager.getToken() ?: token
                ApiService.getPostComments(liveToken, postId).fold(
                    onSuccess = { comments ->
                        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                expectedUserId = commentsOwnerUserId,
                                liveToken = tokenManager.getToken(),
                                liveUserId = tokenManager.getUserId(),
                            )
                        ) {
                            return@fold
                        }
                        if (commentsGeneration == generation) {
                            _uiState.update { state ->
                                if (state.selectedPostId == postId) {
                                    state.copy(
                                        comments = comments,
                                        isCommentsLoading = false,
                                        hasMoreComments = comments.size >= COMMENTS_PAGE_SIZE
                                    )
                                } else state
                            }
                        }
                    },
                    onFailure = { error ->
                        error.rethrowIfCancellation()
                        if (commentsGeneration == generation && isCurrentOwner(commentsOwnerUserId)) {
                            // 8.58：404 = 动态已被删除（评论也不存在）——明确提示
                            val is404 = error is com.maodouchat.network.ApiException &&
                                error.kind == com.maodouchat.network.ApiFailureKind.HTTP &&
                                error.statusCode == 404
                            _uiState.update { state ->
                                if (state.selectedPostId == postId) {
                                    state.copy(
                                        isCommentsLoading = false,
                                        errorMessage = if (is404) text(R.string.explore_post_deleted)
                                        else error.message ?: text(R.string.explore_comments_load_failed)
                                    )
                                } else state
                            }
                        }
                    }
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                if (commentsGeneration == generation && isCurrentOwner(commentsOwnerUserId)) {
                    _uiState.update { state ->
                        if (state.selectedPostId == postId) state.copy(isCommentsLoading = false) else state
                    }
                }
                throw error
            }
        }
        commentsJob = job
        job.invokeOnCompletion {
            if (commentsJob === job) commentsJob = null
        }
    }

    fun openPostDetail(postId: String) {
        if (postId.isBlank()) return
        openComments(postId)
        loadPostDetail(postId)
    }

    // 1.93：打开/关闭动态点赞者弹窗
    fun openLikers(postId: String) {
        if (postId.isBlank()) return
        _uiState.update { it.copy(likersPostId = postId, likers = emptyList(), isLikersLoading = true) }
        val token = tokenManager.getToken()
        val ownerUserId = tokenManager.getUserId().orEmpty()
        if (token.isNullOrBlank() || ownerUserId.isBlank()) {
            _uiState.update { it.copy(isLikersLoading = false) }
            return
        }
        viewModelScope.launch {
            try {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = ownerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    return@launch
                }
                val liveToken = tokenManager.getToken() ?: token
                ApiService.getPostLikers(liveToken, postId).fold(
                    onSuccess = { resp ->
                        if (_uiState.value.likersPostId == postId) {
                            _uiState.update { it.copy(likers = resp.likers, isLikersLoading = false) }
                        }
                    },
                    onFailure = { _ ->
                        if (_uiState.value.likersPostId == postId) {
                            _uiState.update { it.copy(likers = emptyList(), isLikersLoading = false) }
                        }
                    }
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                if (_uiState.value.likersPostId == postId) {
                    _uiState.update { it.copy(likers = emptyList(), isLikersLoading = false) }
                }
            }
        }
    }

    fun closeLikers() {
        _uiState.update { it.copy(likersPostId = null, likers = emptyList(), isLikersLoading = false) }
    }


    fun loadPostDetail(postId: String) {
        if (postId.isBlank()) return
        val cached = _uiState.value.posts.firstOrNull { it.id == postId }
        if (cached != null) {
            _uiState.update {
                it.copy(detailPost = cached, isPostDetailLoading = false, postDetailError = null)
            }
            return
        }
        val token = tokenManager.getToken()
        val ownerUserId = tokenManager.getUserId().orEmpty()
        if (token.isNullOrBlank() || ownerUserId.isBlank()) {
            _uiState.update {
                it.copy(isPostDetailLoading = false, postDetailError = text(R.string.explore_login_required_page))
            }
            return
        }
        val generation = ++postDetailGeneration
        postDetailJob?.cancel()
        _uiState.update {
            it.copy(detailPost = null, isPostDetailLoading = true, postDetailError = null)
        }
        val job = viewModelScope.launch {
            try {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = ownerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    if (postDetailGeneration == generation && isCurrentOwner(ownerUserId)) {
                        _uiState.update { it.copy(isPostDetailLoading = false) }
                    }
                    return@launch
                }
                val liveToken = tokenManager.getToken() ?: token
                ApiService.getPost(liveToken, postId).fold(
                    onSuccess = { post ->
                        if (postDetailGeneration == generation && isCurrentOwner(ownerUserId)) {
                            _uiState.update {
                                it.copy(detailPost = post, isPostDetailLoading = false, postDetailError = null)
                            }
                        }
                    },
                    onFailure = { error ->
                        error.rethrowIfCancellation()
                        if (postDetailGeneration == generation && isCurrentOwner(ownerUserId)) {
                            // 8.58：404 = 动态已被删除——给明确提示而非通用失败
                            val is404 = error is com.maodouchat.network.ApiException &&
                                error.kind == com.maodouchat.network.ApiFailureKind.HTTP &&
                                error.statusCode == 404
                            _uiState.update {
                                it.copy(
                                    isPostDetailLoading = false,
                                    postDetailError = if (is404) text(R.string.explore_post_deleted)
                                    else error.message ?: text(R.string.explore_posts_load_failed)
                                )
                            }
                        }
                    }
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                if (postDetailGeneration == generation && isCurrentOwner(ownerUserId)) {
                    _uiState.update { it.copy(isPostDetailLoading = false) }
                }
                throw error
            }
        }
        postDetailJob = job
        job.invokeOnCompletion {
            if (postDetailJob === job) postDetailJob = null
        }
    }

    fun loadOlderComments() {
        val snapshot = _uiState.value
        val postId = snapshot.selectedPostId ?: return
        if (snapshot.isCommentsLoading || snapshot.isLoadingOlderComments || !snapshot.hasMoreComments) return
        val oldest = snapshot.comments.minWithOrNull(
            compareBy<PostCommentDto> { it.createdAt }.thenBy { it.id }
        ) ?: return
        val ownerUserId = tokenManager.getUserId().orEmpty()
        val generation = commentsGeneration
        viewModelScope.launch {
            commentsLoadMutex.withLock {
                val state = _uiState.value
                if (commentsGeneration != generation || state.selectedPostId != postId || state.isLoadingOlderComments) {
                    return@withLock
                }
                if (!isCurrentOwner(ownerUserId)) return@withLock
                val token = tokenManager.getToken()?.takeIf(String::isNotBlank) ?: return@withLock
                _uiState.update { it.copy(isLoadingOlderComments = true, errorMessage = null) }
                try {
                    if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                            expectedUserId = ownerUserId,
                            liveToken = tokenManager.getToken(),
                            liveUserId = tokenManager.getUserId(),
                        )
                    ) {
                        if (isCurrentOwner(ownerUserId)) {
                            _uiState.update { it.copy(isLoadingOlderComments = false) }
                        }
                        return@withLock
                    }
                    ApiService.getPostComments(
                        token = token,
                        postId = postId,
                        limit = COMMENTS_PAGE_SIZE,
                        before = oldest.createdAt,
                        beforeId = oldest.id
                    ).fold(
                        onSuccess = { older ->
                            if (commentsGeneration == generation && tokenManager.getUserId() == ownerUserId) {
                                _uiState.update { current ->
                                    if (current.selectedPostId != postId) current else current.copy(
                                        comments = (older + current.comments).distinctBy { it.id },
                                        isLoadingOlderComments = false,
                                        hasMoreComments = older.size >= COMMENTS_PAGE_SIZE
                                    )
                                }
                            }
                        },
                        onFailure = { error ->
                            error.rethrowIfCancellation()
                            if (commentsGeneration == generation &&
                                _uiState.value.selectedPostId == postId &&
                                tokenManager.getUserId() == ownerUserId
                            ) {
                                _uiState.update {
                                    it.copy(
                                        isLoadingOlderComments = false,
                                        errorMessage = error.message ?: text(R.string.explore_comments_load_failed)
                                    )
                                }
                            }
                        }
                    )
                } catch (error: kotlinx.coroutines.CancellationException) {
                    if (commentsGeneration == generation && isCurrentOwner(ownerUserId) &&
                        _uiState.value.selectedPostId == postId
                    ) {
                        _uiState.update { it.copy(isLoadingOlderComments = false) }
                    }
                    throw error
                }
            }
        }
    }

    fun closeComments() {
        commentsGeneration++
        commentsJob?.cancel()
        commentsJob = null
        _uiState.update {
            it.copy(
                selectedPostId = null,
                comments = emptyList(),
                commentText = "",
                isSendingComment = false,
                isCommentsLoading = false,
                isLoadingOlderComments = false,
                hasMoreComments = true
            )
        }
    }

    fun sendComment() {
        val token = tokenManager.getToken()
        val commentOwnerUserId = tokenManager.getUserId().orEmpty()
        if (token.isNullOrBlank() || commentOwnerUserId.isBlank()) {
            _uiState.update { it.copy(errorMessage = text(R.string.explore_login_required)) }
            return
        }
        val postId = _uiState.value.selectedPostId ?: return
        val content = _uiState.value.commentText.trim()
        if (content.isBlank() || _uiState.value.isSendingComment) return
        val replyTarget = _uiState.value.replyToComment
        _uiState.update { it.copy(isSendingComment = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = commentOwnerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    if (isCurrentOwner(commentOwnerUserId)) {
                        _uiState.update { it.copy(isSendingComment = false, errorMessage = text(R.string.explore_login_required)) }
                    }
                    return@launch
                }
                val liveToken = tokenManager.getToken() ?: token
                ApiService.createPostComment(liveToken, postId, content, replyTarget?.id).fold(
                    onSuccess = { comment ->
                        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                expectedUserId = commentOwnerUserId,
                                liveToken = tokenManager.getToken(),
                                liveUserId = tokenManager.getUserId(),
                            )
                        ) {
                            return@fold
                        }
                        _uiState.update { state ->
                            val posts = ExploreFeedPolicy.incrementCommentCount(state.posts, postId)
                            val detailPost = state.detailPost?.let { post ->
                                if (post.id == postId) post.copy(commentCount = post.commentCount + 1) else post
                            }
                            if (state.selectedPostId == postId) {
                                state.copy(
                                    comments = state.comments + comment,
                                    commentText = "",
                                    replyToComment = null,
                                    isSendingComment = false,
                                    posts = posts,
                                    detailPost = detailPost
                                )
                            } else {
                                state.copy(posts = posts, detailPost = detailPost)
                            }
                        }
                    },
                    onFailure = { error ->
                        error.rethrowIfCancellation()
                        if (com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                expectedUserId = commentOwnerUserId,
                                liveToken = tokenManager.getToken(),
                                liveUserId = tokenManager.getUserId(),
                            )
                        ) {
                            _uiState.update { state ->
                                if (state.selectedPostId == postId) {
                                    state.copy(isSendingComment = false, errorMessage = error.message ?: text(R.string.explore_comment_failed))
                                } else state
                            }
                        }
                    }
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                if (isCurrentOwner(commentOwnerUserId)) {
                    _uiState.update { state ->
                        if (state.selectedPostId == postId) state.copy(isSendingComment = false) else state
                    }
                }
                throw error
            }
        }
    }

    fun showEntryPrompt(title: String) {
        _uiState.update { it.copy(infoMessage = text(R.string.explore_entry_unavailable, title)) }
    }
    fun onEntryClick(entryId: String) {
        when (entryId) {
            "scan", "nearby", "moments", "my_qr_code" -> showEntryNavigation(entryId)
            else -> showEntryPrompt(entryId)
        }
    }

    private val _entryNavigation = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 1)
    val entryNavigation: kotlinx.coroutines.flow.SharedFlow<String> = _entryNavigation.asSharedFlow()
    private fun showEntryNavigation(target: String) { _entryNavigation.tryEmit(target) }

    fun consumeMessage() {
        _uiState.update { it.copy(errorMessage = null, infoMessage = null) }
    }

    private fun updatePost(post: PostDto) {
        _uiState.update { state ->
            state.copy(
                posts = state.posts.map { if (it.id == post.id) post else it },
                detailPost = state.detailPost?.takeIf { it.id == post.id }?.let { post }
            )
        }
    }

    private fun updateDraft(id: String, transform: (PostImageDraft) -> PostImageDraft) {
        _uiState.update { state ->
            state.copy(imageDrafts = state.imageDrafts.map { if (it.id == id) transform(it) else it })
        }
    }

    private fun normalizeVisibility(value: String): String {
        return ExploreDraftPolicy.normalizeVisibility(value)
    }

    override fun onCleared() {
        val uploadedUrls = _uiState.value.readyImageUrls
        val ownerUserId = draftOwnerId()
        val manager = tokenManager
        if (uploadedUrls.isNotEmpty() && ownerUserId.isNotBlank()) {
            com.maodouchat.MaodouchatApp.instance.applicationScope.launch {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = ownerUserId,
                        liveToken = manager.getToken(),
                        liveUserId = manager.getUserId(),
                    )
                ) return@launch
                val liveToken = manager.getToken()?.takeIf(String::isNotBlank) ?: return@launch
                uploadedUrls.forEach { ApiService.discardPostImage(liveToken, it) }
            }
        }
        super.onCleared()
    }

    private companion object {
        const val COMMENTS_PAGE_SIZE = 50
        /** Feed 页大小：与服务端 getPosts 默认 limit 对齐（此前 30 vs 40 不一致导致多一次空请求）。 */
        const val FEED_PAGE_SIZE = 40
    }
}
