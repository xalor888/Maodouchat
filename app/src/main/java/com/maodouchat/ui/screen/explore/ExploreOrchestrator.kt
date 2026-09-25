package com.maodouchat.ui.screen.explore

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import com.maodouchat.data.repository.AccountSecurityNetworkRepository
import com.maodouchat.R
import com.maodouchat.explore.policy.PostVisibility
import com.maodouchat.explore.repository.DefaultMediaUploadQueue
import com.maodouchat.explore.repository.DefaultPostMutationRepository
import com.maodouchat.explore.repository.DraftRepository
import com.maodouchat.explore.repository.MediaUploadQueue
import com.maodouchat.explore.repository.PostMutationRepository
import com.maodouchat.explore.repository.SharedPrefsDraftRepository
import com.maodouchat.explore.repository.UploadStatus
import com.maodouchat.explore.usecase.CommentPostUseCase
import com.maodouchat.explore.usecase.LoadFeedUseCase
import com.maodouchat.explore.usecase.PublishPostUseCase
import com.maodouchat.explore.usecase.ResolveNearbyUseCase
import com.maodouchat.explore.usecase.ToggleLikeUseCase
import com.maodouchat.network.PostCommentDto
import com.maodouchat.network.PostDto
import com.maodouchat.network.TokenManager
import com.maodouchat.network.api.SocialApiClient
import com.maodouchat.security.BackgroundSessionGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import com.maodouchat.explore.policy.ExploreDraftPolicy
import com.maodouchat.explore.policy.ExploreFeedPolicy
import com.maodouchat.explore.repository.AndroidFeedRepository
import com.maodouchat.explore.repository.FeedController
import com.maodouchat.explore.policy.ExplorePaging

class ExploreOrchestrator(
    private val application: Application,
    private val scope: CoroutineScope,
    private val feedController: FeedController = FeedController(AndroidFeedRepository(application)),
    private val postMutationRepository: PostMutationRepository = DefaultPostMutationRepository(),
    private val draftRepository: DraftRepository = SharedPrefsDraftRepository(
        application.getSharedPreferences("explore_drafts", Context.MODE_PRIVATE)
    ),
    private val uploadQueue: MediaUploadQueue = DefaultMediaUploadQueue(),
    private val loadFeedUseCase: LoadFeedUseCase = LoadFeedUseCase(AndroidFeedRepository(application)),
    private val publishPostUseCase: PublishPostUseCase = PublishPostUseCase(postMutationRepository, draftRepository, uploadQueue),
    private val toggleLikeUseCase: ToggleLikeUseCase = ToggleLikeUseCase(postMutationRepository),
    private val commentPostUseCase: CommentPostUseCase = CommentPostUseCase(postMutationRepository),
    private val resolveNearbyUseCase: ResolveNearbyUseCase = ResolveNearbyUseCase()
) {
    private val tokenManager = TokenManager.getInstance(application)

    private val _uiState = MutableStateFlow(
        ExploreUiState(
            composerText = "",
            selectedVisibility = "PRIVATE"
        )
    )
    val uiState: StateFlow<ExploreUiState> = _uiState.asStateFlow()

    private val _entryNavigation = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val entryNavigation: SharedFlow<String> = _entryNavigation.asSharedFlow()

    private val loadMoreMutex = Mutex()
    private val commentsLoadMutex = Mutex()
    private var feedGeneration = 0L
    private var refreshJob: Job? = null
    private var commentsGeneration = 0L
    private var commentsJob: Job? = null
    private var postDetailGeneration = 0L
    private var postDetailJob: Job? = null
    private var privacyDefaultsGeneration = 0L
    private var privacyDefaultsJob: Job? = null
    private val likeJobs = ConcurrentHashMap<String, Job>()

    init {
        val ownerId = draftOwnerId()
        val draft = draftRepository.getDraft(ownerId, "PRIVATE")
        val restored = draft.composerText.isNotBlank()
        _uiState.update {
            it.copy(
                composerText = draft.composerText,
                selectedVisibility = draft.selectedVisibility,
                composerDraftRestored = restored
            )
        }
        loadPrivacyDefaults()
        refresh()
    }

    private fun text(id: Int, vararg args: Any): String = application.getString(id, *args)

    val visibilityOptions = listOf(
        VisibilityOption("PUBLIC", text(R.string.explore_visibility_public), text(R.string.explore_visibility_public_subtitle)),
        VisibilityOption("CONTACTS", text(R.string.explore_visibility_contacts), text(R.string.explore_visibility_contacts_subtitle)),
        VisibilityOption("PRIVATE", text(R.string.explore_visibility_private), text(R.string.explore_visibility_private_subtitle))
    )

    private fun isCurrentOwner(expectedUserId: String): Boolean =
        tokenManager.getUserId() == expectedUserId && expectedUserId.isNotBlank()

    private fun draftOwnerId(): String = tokenManager.getUserId().orEmpty()

    fun loadPrivacyDefaults() {
        val token = tokenManager.getToken()
        val ownerUserId = tokenManager.getUserId().orEmpty()
        if (token.isNullOrBlank() || ownerUserId.isBlank()) {
            _uiState.update { it.copy(isVisibilityReady = true) }
            return
        }
        val generation = ++privacyDefaultsGeneration
        privacyDefaultsJob?.cancel()
        val job = scope.launch {
            try {
                if (!BackgroundSessionGate.mayContinue(ownerUserId, tokenManager.getToken(), tokenManager.getUserId())) return@launch
                val liveToken = tokenManager.getToken() ?: token
                AccountSecurityNetworkRepository().privacy(liveToken).fold(
                    onSuccess = { privacy ->
                        if (privacyDefaultsGeneration == generation && isCurrentOwner(ownerUserId)) {
                            val defaultVisibility = ExploreDraftPolicy.normalizeVisibility(privacy.defaultPostVisibility)
                            _uiState.update { current ->
                                val selected = if (current.useDefaultPostVisibility) defaultVisibility else current.selectedVisibility
                                current.copy(
                                    defaultPostVisibility = defaultVisibility,
                                    selectedVisibility = selected,
                                    isVisibilityReady = true
                                )
                            }
                        }
                    },
                    onFailure = {
                        if (privacyDefaultsGeneration == generation && isCurrentOwner(ownerUserId)) {
                            _uiState.update { it.copy(isVisibilityReady = true) }
                        }
                    }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (privacyDefaultsGeneration == generation && isCurrentOwner(ownerUserId)) {
                    _uiState.update { it.copy(isVisibilityReady = true) }
                }
            }
        }
        privacyDefaultsJob = job
    }

    fun refresh() {
        val ownerUserId = tokenManager.getUserId().orEmpty()
        val session = feedController.currentSession()
        if (session == null) {
            _uiState.update {
                it.copy(
                    isLoading = false,
                    isLoadingMore = false,
                    errorMessage = text(R.string.explore_login_required)
                )
            }
            return
        }
        val generation = ++feedGeneration
        refreshJob?.cancel()
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        val job = scope.launch {
            try {
                val result = feedController.load(session, cursor = null)
                if (feedGeneration != generation || !feedController.isCurrent(session)) return@launch
                result.fold(
                    onSuccess = { posts ->
                        _uiState.update {
                            it.copy(
                                posts = posts,
                                isLoading = false,
                                hasMore = posts.size >= ExplorePaging.FEED_PAGE_SIZE,
                                errorMessage = null,
                                feedErrorMessage = null
                            )
                        }
                    },
                    onFailure = { error ->
                        val msg = error.message ?: text(R.string.explore_posts_load_failed)
                        _uiState.update { current ->
                            current.copy(
                                isLoading = false,
                                errorMessage = msg,
                                feedErrorMessage = if (current.posts.isEmpty()) msg else current.feedErrorMessage
                            )
                        }
                    }
                )
            } catch (e: CancellationException) {
                if (feedGeneration == generation && feedController.isCurrent(session)) {
                    _uiState.update { it.copy(isLoading = false) }
                }
                throw e
            }
        }
        refreshJob = job
    }

    fun loadMore() {
        val snapshot = _uiState.value
        if (snapshot.isLoading || snapshot.isLoadingMore || !snapshot.hasMore) return
        val lastPost = snapshot.posts.lastOrNull() ?: return
        val session = feedController.currentSession() ?: return
        val generation = feedGeneration
        val cursor = ExploreFeedPolicy.Cursor(createdAt = lastPost.createdAt, postId = lastPost.id)
        scope.launch {
            loadMoreMutex.withLock {
                if (feedGeneration != generation || !feedController.isCurrent(session)) return@withLock
                _uiState.update { it.copy(isLoadingMore = true, errorMessage = null) }
                try {
                    val result = feedController.load(session, cursor = cursor)
                    if (feedGeneration != generation || !feedController.isCurrent(session)) return@withLock
                    result.fold(
                        onSuccess = { newPosts ->
                            _uiState.update { current ->
                                val combined = (current.posts + newPosts).distinctBy { it.id }
                                current.copy(
                                    posts = combined,
                                    isLoadingMore = false,
                                    hasMore = newPosts.size >= ExplorePaging.FEED_PAGE_SIZE
                                )
                            }
                        },
                        onFailure = { error ->
                            _uiState.update {
                                it.copy(
                                    isLoadingMore = false,
                                    errorMessage = error.message ?: text(R.string.explore_load_more_failed)
                                )
                            }
                        }
                    )
                } catch (e: CancellationException) {
                    if (feedGeneration == generation && feedController.isCurrent(session)) {
                        _uiState.update { it.copy(isLoadingMore = false) }
                    }
                    throw e
                }
            }
        }
    }

    fun toggleLike(post: PostDto) {
        val token = tokenManager.getToken()
        val ownerUserId = tokenManager.getUserId().orEmpty()
        if (token.isNullOrBlank() || ownerUserId.isBlank()) {
            _uiState.update { it.copy(errorMessage = text(R.string.explore_login_required)) }
            return
        }
        val currentJob = likeJobs[post.id]
        if (currentJob != null && currentJob.isActive) return

        val job = scope.launch {
            toggleLikeUseCase.togglePostLike(
                ownerUserId = ownerUserId,
                token = token,
                post = post,
                onOptimisticUpdate = { optimisticPost ->
                    updatePost(optimisticPost)
                }
            ).fold(
                onSuccess = { serverPost ->
                    updatePost(serverPost)
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(errorMessage = error.message ?: text(R.string.error_operation_failed))
                    }
                }
            )
        }
        likeJobs[post.id] = job
        job.invokeOnCompletion { likeJobs.remove(post.id) }
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
                isSendingComment = false
            )
        }
        val job = scope.launch {
            try {
                if (!BackgroundSessionGate.mayContinue(commentsOwnerUserId, tokenManager.getToken(), tokenManager.getUserId())) {
                    if (commentsGeneration == generation && isCurrentOwner(commentsOwnerUserId)) {
                        _uiState.update { it.copy(isCommentsLoading = false) }
                    }
                    return@launch
                }
                val liveToken = tokenManager.getToken() ?: token
                commentPostUseCase.loadComments(liveToken, postId).fold(
                    onSuccess = { comments ->
                        if (commentsGeneration == generation && isCurrentOwner(commentsOwnerUserId)) {
                            _uiState.update { state ->
                                if (state.selectedPostId == postId) {
                                    state.copy(
                                        comments = comments,
                                        isCommentsLoading = false,
                                        hasMoreComments = comments.size >= ExplorePaging.COMMENTS_PAGE_SIZE
                                    )
                                } else state
                            }
                        }
                    },
                    onFailure = { error ->
                        if (commentsGeneration == generation && isCurrentOwner(commentsOwnerUserId)) {
                            _uiState.update { state ->
                                if (state.selectedPostId == postId) {
                                    state.copy(
                                        isCommentsLoading = false,
                                        errorMessage = error.message ?: text(R.string.explore_comments_load_failed)
                                    )
                                } else state
                            }
                        }
                    }
                )
            } catch (e: CancellationException) {
                if (commentsGeneration == generation && isCurrentOwner(commentsOwnerUserId)) {
                    _uiState.update { it.copy(isCommentsLoading = false) }
                }
                throw e
            }
        }
        commentsJob = job
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
        scope.launch {
            try {
                val liveToken = tokenManager.getToken() ?: token
                commentPostUseCase.sendComment(
                    ownerUserId = commentOwnerUserId,
                    token = liveToken,
                    postId = postId,
                    content = content,
                    replyToCommentId = replyTarget?.id
                ).fold(
                    onSuccess = { comment ->
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
                        _uiState.update { state ->
                            if (state.selectedPostId == postId) {
                                state.copy(
                                    isSendingComment = false,
                                    errorMessage = error.message ?: text(R.string.explore_comment_failed)
                                )
                            } else state
                        }
                    }
                )
            } catch (e: CancellationException) {
                if (isCurrentOwner(commentOwnerUserId)) {
                    _uiState.update { state ->
                        if (state.selectedPostId == postId) state.copy(isSendingComment = false) else state
                    }
                }
                throw e
            }
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
        scope.launch {
            commentsLoadMutex.withLock {
                val state = _uiState.value
                if (commentsGeneration != generation || state.selectedPostId != postId || state.isLoadingOlderComments) return@withLock
                val token = tokenManager.getToken()?.takeIf(String::isNotBlank) ?: return@withLock
                _uiState.update { it.copy(isLoadingOlderComments = true, errorMessage = null) }
                try {
                    commentPostUseCase.loadComments(
                        token = token,
                        postId = postId,
                        limit = ExplorePaging.COMMENTS_PAGE_SIZE,
                        before = oldest.createdAt,
                        beforeId = oldest.id
                    ).fold(
                        onSuccess = { older ->
                            if (commentsGeneration == generation && tokenManager.getUserId() == ownerUserId) {
                                _uiState.update { current ->
                                    if (current.selectedPostId != postId) current else current.copy(
                                        comments = (older + current.comments).distinctBy { it.id },
                                        isLoadingOlderComments = false,
                                        hasMoreComments = older.size >= ExplorePaging.COMMENTS_PAGE_SIZE
                                    )
                                }
                            }
                        },
                        onFailure = { error ->
                            if (commentsGeneration == generation && _uiState.value.selectedPostId == postId) {
                                _uiState.update {
                                    it.copy(
                                        isLoadingOlderComments = false,
                                        errorMessage = error.message ?: text(R.string.explore_comments_load_failed)
                                    )
                                }
                            }
                        }
                    )
                } catch (e: CancellationException) {
                    if (commentsGeneration == generation && isCurrentOwner(ownerUserId) && _uiState.value.selectedPostId == postId) {
                        _uiState.update { it.copy(isLoadingOlderComments = false) }
                    }
                    throw e
                }
            }
        }
    }

    fun toggleCommentLike(comment: PostCommentDto) {
        val token = tokenManager.getToken()
        val ownerUserId = tokenManager.getUserId().orEmpty()
        val postId = _uiState.value.selectedPostId ?: comment.postId
        if (token.isNullOrBlank() || ownerUserId.isBlank()) {
            _uiState.update { it.copy(errorMessage = text(R.string.explore_login_required)) }
            return
        }
        scope.launch {
            toggleLikeUseCase.toggleCommentLike(
                ownerUserId = ownerUserId,
                token = token,
                postId = postId,
                comment = comment,
                onOptimisticUpdate = { optimisticComment ->
                    _uiState.update { state ->
                        state.copy(comments = state.comments.map { if (it.id == comment.id) optimisticComment else it })
                    }
                }
            ).fold(
                onSuccess = { updated ->
                    _uiState.update { state ->
                        state.copy(comments = state.comments.map { if (it.id == comment.id) updated else it })
                    }
                },
                onFailure = { error ->
                    _uiState.update { it.copy(errorMessage = error.message ?: text(R.string.error_operation_failed)) }
                }
            )
        }
    }

    fun deleteComment(comment: PostCommentDto) {
        val token = tokenManager.getToken()
        val ownerUserId = tokenManager.getUserId().orEmpty()
        val postId = _uiState.value.selectedPostId ?: comment.postId
        if (token.isNullOrBlank() || ownerUserId.isBlank()) {
            _uiState.update { it.copy(errorMessage = text(R.string.explore_login_required)) }
            return
        }
        val originalIndex = _uiState.value.comments.indexOfFirst { it.id == comment.id }
        _uiState.update { state ->
            state.copy(
                comments = state.comments.filterNot { it.id == comment.id },
                posts = ExploreFeedPolicy.decrementCommentCount(state.posts, postId)
            )
        }
        scope.launch {
            commentPostUseCase.deleteComment(ownerUserId, token, postId, comment, originalIndex).fold(
                onSuccess = {},
                onFailure = { error ->
                    _uiState.update { state ->
                        val restored = state.comments.toMutableList().apply {
                            val idx = originalIndex.coerceIn(0, size)
                            add(idx, comment)
                        }
                        state.copy(
                            comments = restored,
                            posts = ExploreFeedPolicy.incrementCommentCount(state.posts, postId),
                            errorMessage = error.message ?: text(R.string.error_operation_failed)
                        )
                    }
                }
            )
        }
    }

    fun saveCommentEdit(comment: PostCommentDto, newText: String) {
        val token = tokenManager.getToken()
        val ownerUserId = tokenManager.getUserId().orEmpty()
        val postId = _uiState.value.selectedPostId ?: comment.postId
        if (token.isNullOrBlank() || ownerUserId.isBlank()) {
            _uiState.update { it.copy(errorMessage = text(R.string.explore_login_required)) }
            return
        }
        _uiState.update { it.copy(isSavingCommentEdit = true) }
        scope.launch {
            commentPostUseCase.editComment(ownerUserId, token, postId, comment.id, newText).fold(
                onSuccess = { updated ->
                    _uiState.update { state ->
                        state.copy(
                            isSavingCommentEdit = false,
                            comments = state.comments.map { if (it.id == comment.id) updated else it }
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isSavingCommentEdit = false,
                            errorMessage = error.message ?: text(R.string.explore_comment_edit_failed)
                        )
                    }
                }
            )
        }
    }

    fun reportComment(comment: PostCommentDto) {
        val token = tokenManager.getToken()
        val ownerUserId = tokenManager.getUserId().orEmpty()
        if (token.isNullOrBlank() || ownerUserId.isBlank()) {
            _uiState.update { it.copy(errorMessage = text(R.string.explore_login_required)) }
            return
        }
        scope.launch {
            commentPostUseCase.reportComment(ownerUserId, token, comment.id, "INAPPROPRIATE", null).fold(
                onSuccess = {
                    _uiState.update { it.copy(infoMessage = text(R.string.explore_report_sent)) }
                },
                onFailure = { error ->
                    _uiState.update { it.copy(errorMessage = error.message ?: text(R.string.explore_report_failed)) }
                }
            )
        }
    }

    fun copyComment(comment: PostCommentDto) {
        val clipboard = application.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("Comment", comment.content))
        _uiState.update { it.copy(infoMessage = text(R.string.explore_comment_copied)) }
    }

    fun publishPost() {
        val state = _uiState.value
        if (!state.canPublish) return
        val session = feedController.currentSession()
        val token = tokenManager.getToken()
        if (session == null || token.isNullOrBlank()) {
            _uiState.update { it.copy(errorMessage = text(R.string.explore_login_required)) }
            return
        }
        val content = state.composerText.trim()
        val imageUrls = state.readyImageUrls
        val visibility = PostVisibility.fromString(state.selectedVisibility)

        _uiState.update { it.copy(isPublishing = true, errorMessage = null) }
        scope.launch {
            publishPostUseCase.publish(
                ownerUserId = session.ownerUserId,
                token = token,
                content = content,
                imageUrls = imageUrls,
                visibility = visibility
            ).fold(
                onSuccess = { post ->
                    _uiState.update { current ->
                        current.copy(
                            posts = listOf(post) + current.posts.filterNot { it.id == post.id },
                            isPublishing = false,
                            composerText = "",
                            imageDrafts = emptyList(),
                            composerDraftRestored = false,
                            publishRevision = current.publishRevision + 1,
                            infoMessage = text(R.string.explore_publish_success)
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isPublishing = false,
                            errorMessage = error.message ?: text(R.string.explore_publish_failed)
                        )
                    }
                }
            )
        }
    }

    fun onComposerTextChange(text: String) {
        val ownerUserId = draftOwnerId()
        draftRepository.saveComposerText(ownerUserId, text)
        _uiState.update { it.copy(composerText = text) }
    }

    fun dismissComposerDraftHint() {
        _uiState.update { it.copy(composerDraftRestored = false) }
    }

    fun clearComposer() {
        val ownerUserId = draftOwnerId()
        draftRepository.clearAllDrafts(ownerUserId)
        _uiState.update {
            it.copy(
                composerText = "",
                imageDrafts = emptyList(),
                composerDraftRestored = false
            )
        }
    }

    fun onCommentTextChange(text: String) {
        _uiState.update { it.copy(commentText = text) }
    }

    fun setReplyToComment(comment: PostCommentDto) {
        _uiState.update { it.copy(replyToComment = comment) }
    }

    fun clearReplyToComment() {
        _uiState.update { it.copy(replyToComment = null) }
    }

    fun onVisibilitySelected(visibility: String) {
        val normalized = ExploreDraftPolicy.normalizeVisibility(visibility)
        val ownerUserId = draftOwnerId()
        draftRepository.saveVisibility(ownerUserId, normalized)
        _uiState.update {
            it.copy(selectedVisibility = normalized, useDefaultPostVisibility = false)
        }
    }

    fun addImages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val currentDrafts = _uiState.value.imageDrafts
        val maxImages = 9
        val available = maxImages - currentDrafts.size
        if (available <= 0) return
        val toAdd = uris.take(available)

        val newDrafts = toAdd.mapNotNull { uri ->
            val persistedUri = persistPickedImage(uri) ?: return@mapNotNull null
            PostImageDraft(uri = persistedUri, isUploading = false)
        }
        val updated = currentDrafts + newDrafts
        _uiState.update { it.copy(imageDrafts = updated) }
        newDrafts.forEach { draft ->
            uploadDraftImage(draft)
        }
    }

    private fun persistPickedImage(uri: Uri): Uri? {
        return try {
            val dir = File(application.filesDir, "draft_images").apply { mkdirs() }
            val file = File(dir, "img_${System.currentTimeMillis()}_${java.util.UUID.randomUUID().toString().take(8)}.jpg")
            application.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(file).use { output ->
                    input.copyTo(output)
                }
            }
            Uri.fromFile(file)
        } catch (_: Exception) {
            null
        }
    }

    fun retryDraftImage(draftId: String) {
        val draft = _uiState.value.imageDrafts.firstOrNull { it.id == draftId } ?: return
        uploadDraftImage(draft)
    }

    private fun uploadDraftImage(draft: PostImageDraft) {
        val token = tokenManager.getToken()
        val ownerUserId = draftOwnerId()
        if (token.isNullOrBlank() || ownerUserId.isBlank()) return
        updateDraft(draft.id) { it.copy(isUploading = true, errorMessage = null) }
        scope.launch(Dispatchers.IO) {
            try {
                val bytes = application.contentResolver.openInputStream(draft.uri)?.use { it.readBytes() }
                if (bytes == null || bytes.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        updateDraft(draft.id) { it.copy(isUploading = false, errorMessage = text(R.string.explore_upload_failed)) }
                    }
                    return@launch
                }
                val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                uploadQueue.enqueue(draft.id, ownerUserId, base64)
                val job = uploadQueue.startUpload(draft.id, token)
                job?.join()
                val item = uploadQueue.itemsFlow.value[draft.id]
                withContext(Dispatchers.Main) {
                    if (item?.status == UploadStatus.SUCCESS && item.uploadUrl != null) {
                        updateDraft(draft.id) { it.copy(uploadUrl = item.uploadUrl, isUploading = false, errorMessage = null) }
                    } else {
                        updateDraft(draft.id) { it.copy(isUploading = false, errorMessage = item?.errorMessage ?: text(R.string.explore_upload_failed)) }
                    }
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    updateDraft(draft.id) { it.copy(isUploading = false, errorMessage = text(R.string.explore_upload_failed)) }
                }
            }
        }
    }

    fun removeImage(id: String) {
        val draft = _uiState.value.imageDrafts.firstOrNull { it.id == id }
        uploadQueue.cancel(id)
        if (draft?.uploadUrl != null) {
            val token = tokenManager.getToken()
            val ownerUserId = draftOwnerId()
            if (!token.isNullOrBlank() && ownerUserId.isNotBlank()) {
                scope.launch {
                    uploadQueue.discardUploaded(token, ownerUserId, draft.uploadUrl)
                }
            }
        }
        _uiState.update { state ->
            state.copy(imageDrafts = state.imageDrafts.filterNot { it.id == id })
        }
    }

    fun resetPostState() {
        _uiState.update {
            it.copy(
                isPublishing = false,
                isEditingPost = false,
                postPendingEditId = null,
                postPendingDeleteId = null
            )
        }
    }

    fun requestEditPost(postId: String) {
        val post = _uiState.value.posts.firstOrNull { it.id == postId } ?: return
        _uiState.update {
            it.copy(
                postPendingEditId = postId,
                editPostText = post.content,
                editPostVisibility = post.visibility,
                isEditingPost = true
            )
        }
    }

    fun onEditPostTextChange(text: String) {
        _uiState.update { it.copy(editPostText = text) }
    }

    fun onEditPostVisibilitySelected(visibility: String) {
        _uiState.update { it.copy(editPostVisibility = visibility) }
    }

    fun cancelEditPost() {
        _uiState.update { it.copy(isEditingPost = false, postPendingEditId = null) }
    }

    fun confirmEditPost() {
        val state = _uiState.value
        val postId = state.postPendingEditId ?: return
        val token = tokenManager.getToken()
        val ownerUserId = tokenManager.getUserId().orEmpty()
        if (token.isNullOrBlank() || ownerUserId.isBlank()) return
        val newContent = state.editPostText.trim()
        val visibility = PostVisibility.fromString(state.editPostVisibility)
        _uiState.update { it.copy(isEditingPost = false, postPendingEditId = null) }
        scope.launch {
            publishPostUseCase.edit(ownerUserId, token, postId, newContent, visibility).fold(
                onSuccess = { updated ->
                    updatePost(updated)
                },
                onFailure = { error ->
                    _uiState.update { it.copy(errorMessage = error.message ?: text(R.string.explore_edit_failed)) }
                }
            )
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
        val post = _uiState.value.posts.firstOrNull { it.id == postId } ?: return
        _uiState.update { it.copy(postPendingDeleteId = null) }
        val token = tokenManager.getToken()
        val ownerUserId = tokenManager.getUserId().orEmpty()
        if (token.isNullOrBlank() || ownerUserId.isBlank()) return
        val originalIndex = _uiState.value.posts.indexOfFirst { it.id == postId }
        _uiState.update { state ->
            state.copy(posts = state.posts.filterNot { it.id == postId })
        }
        scope.launch {
            publishPostUseCase.delete(ownerUserId, token, post, originalIndex).fold(
                onSuccess = {},
                onFailure = { error ->
                    _uiState.update { state ->
                        val restored = state.posts.toMutableList().apply {
                            val idx = originalIndex.coerceIn(0, size)
                            add(idx, post)
                        }
                        state.copy(posts = restored, errorMessage = error.message ?: text(R.string.explore_delete_failed))
                    }
                }
            )
        }
    }

    fun reportPost(post: PostDto) {
        val token = tokenManager.getToken()
        val ownerUserId = tokenManager.getUserId().orEmpty()
        if (token.isNullOrBlank() || ownerUserId.isBlank()) return
        scope.launch {
            publishPostUseCase.report(ownerUserId, token, post.id, "INAPPROPRIATE", null).fold(
                onSuccess = {
                    _uiState.update { it.copy(infoMessage = text(R.string.explore_report_sent)) }
                },
                onFailure = { error ->
                    _uiState.update { it.copy(errorMessage = error.message ?: text(R.string.explore_report_failed)) }
                }
            )
        }
    }

    fun blockPostAuthor(userId: String) {
        val token = tokenManager.getToken()
        val ownerUserId = tokenManager.getUserId().orEmpty()
        if (token.isNullOrBlank() || ownerUserId.isBlank()) return
        _uiState.update { state ->
            state.copy(posts = state.posts.filterNot { it.author.id == userId })
        }
        scope.launch {
            SocialApiClient.blockUser(token, userId).fold(
                onSuccess = {
                    _uiState.update { it.copy(infoMessage = text(R.string.explore_block_done)) }
                },
                onFailure = { error ->
                    refresh()
                    _uiState.update { it.copy(errorMessage = error.message ?: text(R.string.explore_block_failed)) }
                }
            )
        }
    }

    fun openPostDetail(postId: String) {
        if (postId.isBlank()) return
        openComments(postId)
        loadPostDetail(postId)
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
        if (token.isNullOrBlank()) return
        val generation = ++postDetailGeneration
        postDetailJob?.cancel()
        _uiState.update { it.copy(detailPost = null, isPostDetailLoading = true, postDetailError = null) }
        val job = scope.launch {
            loadFeedUseCase.loadPostDetail(token, postId).fold(
                onSuccess = { post ->
                    if (postDetailGeneration == generation) {
                        _uiState.update { it.copy(detailPost = post, isPostDetailLoading = false) }
                    }
                },
                onFailure = { error ->
                    if (postDetailGeneration == generation) {
                        _uiState.update {
                            it.copy(
                                isPostDetailLoading = false,
                                postDetailError = error.message ?: text(R.string.explore_posts_load_failed)
                            )
                        }
                    }
                }
            )
        }
        postDetailJob = job
    }

    fun openLikers(postId: String) {
        if (postId.isBlank()) return
        _uiState.update { it.copy(likersPostId = postId, likers = emptyList(), isLikersLoading = true) }
        val token = tokenManager.getToken()
        if (token.isNullOrBlank()) {
            _uiState.update { it.copy(isLikersLoading = false) }
            return
        }
        scope.launch {
            loadFeedUseCase.loadLikers(token, postId).fold(
                onSuccess = { users ->
                    if (_uiState.value.likersPostId == postId) {
                        _uiState.update { it.copy(likers = users, isLikersLoading = false) }
                    }
                },
                onFailure = {
                    if (_uiState.value.likersPostId == postId) {
                        _uiState.update { it.copy(likers = emptyList(), isLikersLoading = false) }
                    }
                }
            )
        }
    }

    fun closeLikers() {
        _uiState.update { it.copy(likersPostId = null, likers = emptyList(), isLikersLoading = false) }
    }

    fun showEntryPrompt(title: String) {
        _uiState.update { it.copy(infoMessage = text(R.string.explore_entry_unavailable, title)) }
    }

    fun onEntryClick(entryId: String) {
        when (entryId) {
            "scan", "moments", "my_qr_code" -> _entryNavigation.tryEmit(entryId)
            "nearby" -> showEntryPrompt(entryId)
            else -> showEntryPrompt(entryId)
        }
    }

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

    fun onCleared() {
        val uploadedUrls = _uiState.value.readyImageUrls
        val ownerUserId = draftOwnerId()
        val token = tokenManager.getToken()
        if (uploadedUrls.isNotEmpty() && !token.isNullOrBlank() && ownerUserId.isNotBlank()) {
            uploadedUrls.forEach { url ->
                uploadQueue.cancel(url)
            }
        }
        uploadQueue.clear()
    }
}
