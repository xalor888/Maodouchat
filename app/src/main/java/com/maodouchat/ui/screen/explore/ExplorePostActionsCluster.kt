package com.maodouchat.ui.screen.explore

import com.maodouchat.R
import com.maodouchat.explore.policy.PostVisibility
import com.maodouchat.network.PostDto
import com.maodouchat.network.api.SocialApiClient
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// 帖子操作一族：点赞/编辑/删除/举报/拉黑。纯搬移，调用点与协程语义不变。
    fun ExploreOrchestrator.toggleLike(post: PostDto) {
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

    fun ExploreOrchestrator.resetPostState() {
        _uiState.update {
            it.copy(
                isPublishing = false,
                isEditingPost = false,
                postPendingEditId = null,
                postPendingDeleteId = null
            )
        }
    }

    fun ExploreOrchestrator.requestEditPost(postId: String) {
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

    fun ExploreOrchestrator.onEditPostTextChange(text: String) {
        _uiState.update { it.copy(editPostText = text) }
    }

    fun ExploreOrchestrator.onEditPostVisibilitySelected(visibility: String) {
        _uiState.update { it.copy(editPostVisibility = visibility) }
    }

    fun ExploreOrchestrator.cancelEditPost() {
        _uiState.update { it.copy(isEditingPost = false, postPendingEditId = null) }
    }

    fun ExploreOrchestrator.confirmEditPost() {
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

    fun ExploreOrchestrator.requestDeletePost(postId: String) {
        _uiState.update { it.copy(postPendingDeleteId = postId) }
    }

    fun ExploreOrchestrator.cancelDeletePost() {
        _uiState.update { it.copy(postPendingDeleteId = null) }
    }

    fun ExploreOrchestrator.confirmDeletePost() {
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

    fun ExploreOrchestrator.reportPost(post: PostDto) {
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

    fun ExploreOrchestrator.blockPostAuthor(userId: String) {
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

    private fun ExploreOrchestrator.updatePost(post: PostDto) {
        _uiState.update { state ->
            state.copy(
                posts = state.posts.map { if (it.id == post.id) post else it },
                detailPost = state.detailPost?.takeIf { it.id == post.id }?.let { post }
            )
        }
    }
