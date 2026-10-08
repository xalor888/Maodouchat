package com.maodouchat.ui.screen.explore

import android.app.Application
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
    private var feedGeneration = 0L
    private var refreshJob: Job? = null
    private var postDetailGeneration = 0L
    private var postDetailJob: Job? = null
    private var privacyDefaultsGeneration = 0L
    private var privacyDefaultsJob: Job? = null
    private val likeJobs = ConcurrentHashMap<String, Job>()
    private val commentController = ExploreCommentController(
        application = application,
        scope = scope,
        commentPostUseCase = commentPostUseCase,
        toggleLikeUseCase = toggleLikeUseCase,
        state = { _uiState.value },
        updateState = { transform -> _uiState.update(transform) },
        textFn = { id, args -> text(id, *args) },
    )

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
        com.maodouchat.session.CurrentSession.snapshot().userId == expectedUserId && expectedUserId.isNotBlank()

    private fun draftOwnerId(): String = com.maodouchat.session.CurrentSession.ownerUserId()

    fun loadPrivacyDefaults() {
        val ownerUserId = com.maodouchat.session.CurrentSession.ownerUserId()
        if (!com.maodouchat.session.CurrentSession.hasSession() || ownerUserId.isBlank()) {
            _uiState.update { it.copy(isVisibilityReady = true) }
            return
        }
        val generation = ++privacyDefaultsGeneration
        privacyDefaultsJob?.cancel()
        val job = scope.launch {
            try {
                if (!BackgroundSessionGate.mayContinue(ownerUserId)) return@launch
                AccountSecurityNetworkRepository().privacy().fold(
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
        val ownerUserId = com.maodouchat.session.CurrentSession.ownerUserId()
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
        val ownerUserId = com.maodouchat.session.CurrentSession.ownerUserId()
        if (!com.maodouchat.session.CurrentSession.hasSession() || ownerUserId.isBlank()) {
            _uiState.update { it.copy(errorMessage = text(R.string.explore_login_required)) }
            return
        }
        val currentJob = likeJobs[post.id]
        if (currentJob != null && currentJob.isActive) return

        val job = scope.launch {
            toggleLikeUseCase.togglePostLike(
                ownerUserId = ownerUserId,
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

    fun openComments(postId: String) = commentController.openComments(postId)
    fun closeComments() = commentController.closeComments()
    fun sendComment() = commentController.sendComment()
    fun loadOlderComments() = commentController.loadOlderComments()
    fun toggleCommentLike(comment: PostCommentDto) = commentController.toggleCommentLike(comment)
    fun deleteComment(comment: PostCommentDto) = commentController.deleteComment(comment)
    fun saveCommentEdit(comment: PostCommentDto, newText: String) = commentController.saveCommentEdit(comment, newText)
    fun reportComment(comment: PostCommentDto) = commentController.reportComment(comment)
    fun copyComment(comment: PostCommentDto) = commentController.copyComment(comment)

    fun publishPost() {
        val state = _uiState.value
        if (!state.canPublish) return
        val session = feedController.currentSession()
        if (session == null || !com.maodouchat.session.CurrentSession.hasSession()) {
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

    fun onCommentTextChange(text: String) = commentController.onCommentTextChange(text)
    fun setReplyToComment(comment: PostCommentDto) = commentController.setReplyToComment(comment)
    fun clearReplyToComment() = commentController.clearReplyToComment()

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

    @Suppress("Recycle") private fun persistPickedImage(uri: Uri): Uri? { // 资源由 `?.use` 关闭；lint 的 Recycle 检测不识别安全调用形态（人工核实，与 #124 同式）
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
        val ownerUserId = draftOwnerId()
        if (!com.maodouchat.session.CurrentSession.hasSession() || ownerUserId.isBlank()) return
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
                val job = uploadQueue.startUpload(draft.id)
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
            val ownerUserId = draftOwnerId()
            if (com.maodouchat.session.CurrentSession.hasSession() && ownerUserId.isNotBlank()) {
                scope.launch {
                    uploadQueue.discardUploaded(ownerUserId = ownerUserId, url = draft.uploadUrl)
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
        val ownerUserId = com.maodouchat.session.CurrentSession.ownerUserId()
        if (!com.maodouchat.session.CurrentSession.hasSession() || ownerUserId.isBlank()) return
        val newContent = state.editPostText.trim()
        val visibility = PostVisibility.fromString(state.editPostVisibility)
        _uiState.update { it.copy(isEditingPost = false, postPendingEditId = null) }
        scope.launch {
            publishPostUseCase.edit(ownerUserId = ownerUserId, postId = postId, content = newContent, visibility = visibility).fold(
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
        val ownerUserId = com.maodouchat.session.CurrentSession.ownerUserId()
        if (!com.maodouchat.session.CurrentSession.hasSession() || ownerUserId.isBlank()) return
        val originalIndex = _uiState.value.posts.indexOfFirst { it.id == postId }
        _uiState.update { state ->
            state.copy(posts = state.posts.filterNot { it.id == postId })
        }
        scope.launch {
            publishPostUseCase.delete(ownerUserId = ownerUserId, post = post, originalIndex = originalIndex).fold(
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
        val ownerUserId = com.maodouchat.session.CurrentSession.ownerUserId()
        if (!com.maodouchat.session.CurrentSession.hasSession() || ownerUserId.isBlank()) return
        scope.launch {
            publishPostUseCase.report(ownerUserId = ownerUserId, postId = post.id, reason = "INAPPROPRIATE").fold(
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
        val ownerUserId = com.maodouchat.session.CurrentSession.ownerUserId()
        if (!com.maodouchat.session.CurrentSession.hasSession() || ownerUserId.isBlank()) return
        _uiState.update { state ->
            state.copy(posts = state.posts.filterNot { it.author.id == userId })
        }
        scope.launch {
            SocialApiClient.blockUser(com.maodouchat.session.CurrentSession.snapshot().token.orEmpty(), userId).fold(
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
        if (!com.maodouchat.session.CurrentSession.hasSession()) return
        val generation = ++postDetailGeneration
        postDetailJob?.cancel()
        _uiState.update { it.copy(detailPost = null, isPostDetailLoading = true, postDetailError = null) }
        val job = scope.launch {
            loadFeedUseCase.loadPostDetail(postId = postId).fold(
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
        if (!com.maodouchat.session.CurrentSession.hasSession()) {
            _uiState.update { it.copy(isLikersLoading = false) }
            return
        }
        scope.launch {
            loadFeedUseCase.loadLikers(postId = postId).fold(
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
        if (uploadedUrls.isNotEmpty() && com.maodouchat.session.CurrentSession.hasSession() && ownerUserId.isNotBlank()) {
            uploadedUrls.forEach { url ->
                uploadQueue.cancel(url)
            }
        }
        uploadQueue.clear()
    }
}
