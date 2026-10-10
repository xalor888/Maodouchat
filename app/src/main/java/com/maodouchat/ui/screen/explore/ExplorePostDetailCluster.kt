package com.maodouchat.ui.screen.explore

import com.maodouchat.R
import com.maodouchat.network.PostCommentDto
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// 详情与入口一族：帖子详情/点赞者/入口跳转/评论透传。纯搬移，调用点与协程语义不变。
    fun ExploreOrchestrator.openComments(postId: String) = commentController.openComments(postId)
    fun ExploreOrchestrator.closeComments() = commentController.closeComments()
    fun ExploreOrchestrator.sendComment() = commentController.sendComment()
    fun ExploreOrchestrator.loadOlderComments() = commentController.loadOlderComments()
    fun ExploreOrchestrator.toggleCommentLike(comment: PostCommentDto) = commentController.toggleCommentLike(comment)
    fun ExploreOrchestrator.deleteComment(comment: PostCommentDto) = commentController.deleteComment(comment)
    fun ExploreOrchestrator.saveCommentEdit(comment: PostCommentDto, newText: String) = commentController.saveCommentEdit(comment, newText)
    fun ExploreOrchestrator.reportComment(comment: PostCommentDto) = commentController.reportComment(comment)
    fun ExploreOrchestrator.copyComment(comment: PostCommentDto) = commentController.copyComment(comment)

    fun ExploreOrchestrator.onCommentTextChange(text: String) = commentController.onCommentTextChange(text)
    fun ExploreOrchestrator.setReplyToComment(comment: PostCommentDto) = commentController.setReplyToComment(comment)
    fun ExploreOrchestrator.clearReplyToComment() = commentController.clearReplyToComment()

    fun ExploreOrchestrator.openPostDetail(postId: String) {
        if (postId.isBlank()) return
        openComments(postId)
        loadPostDetail(postId)
    }

    fun ExploreOrchestrator.loadPostDetail(postId: String) {
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

    fun ExploreOrchestrator.openLikers(postId: String) {
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

    fun ExploreOrchestrator.closeLikers() {
        _uiState.update { it.copy(likersPostId = null, likers = emptyList(), isLikersLoading = false) }
    }

    fun ExploreOrchestrator.showEntryPrompt(title: String) {
        _uiState.update { it.copy(infoMessage = text(R.string.explore_entry_unavailable, title)) }
    }

    fun ExploreOrchestrator.onEntryClick(entryId: String) {
        when (entryId) {
            "scan", "moments", "my_qr_code" -> _entryNavigation.tryEmit(entryId)
            "nearby" -> showEntryPrompt(entryId)
            else -> showEntryPrompt(entryId)
        }
    }

    fun ExploreOrchestrator.consumeMessage() {
        _uiState.update { it.copy(errorMessage = null, infoMessage = null) }
    }
