package com.maodouchat.ui.screen.explore

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.maodouchat.R
import com.maodouchat.explore.policy.ExploreFeedPolicy
import com.maodouchat.explore.policy.ExplorePaging
import com.maodouchat.explore.usecase.CommentPostUseCase
import com.maodouchat.explore.usecase.ToggleLikeUseCase
import com.maodouchat.network.PostCommentDto
import com.maodouchat.security.BackgroundSessionGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class ExploreCommentController(
    private val application: Application,
    private val scope: CoroutineScope,
    private val commentPostUseCase: CommentPostUseCase,
    private val toggleLikeUseCase: ToggleLikeUseCase,
    private val state: () -> ExploreUiState,
    private val updateState: ((ExploreUiState) -> ExploreUiState) -> Unit,
    private val textFn: (Int, Array<out Any>) -> String,
) {
    private val commentsLoadMutex = Mutex()
    private var commentsGeneration = 0L
    private var commentsJob: Job? = null

    private fun text(id: Int, vararg args: Any): String = textFn(id, args)

    private fun isCurrentOwner(expectedUserId: String): Boolean =
        com.maodouchat.session.CurrentSession.snapshot().userId == expectedUserId && expectedUserId.isNotBlank()

    fun openComments(postId: String) {
        val commentsOwnerUserId = com.maodouchat.session.CurrentSession.ownerUserId()
        if (!com.maodouchat.session.CurrentSession.hasSession() || commentsOwnerUserId.isBlank()) {
            updateState { it.copy(errorMessage = text(R.string.explore_login_required)) }
            return
        }
        val generation = ++commentsGeneration
        commentsJob?.cancel()
        updateState {
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
                if (!BackgroundSessionGate.mayContinue(commentsOwnerUserId)) {
                    if (commentsGeneration == generation && isCurrentOwner(commentsOwnerUserId)) {
                        updateState { it.copy(isCommentsLoading = false) }
                    }
                    return@launch
                }
                commentPostUseCase.loadComments(postId = postId).fold(
                    onSuccess = { comments ->
                        if (commentsGeneration == generation && isCurrentOwner(commentsOwnerUserId)) {
                            updateState { state ->
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
                            updateState { state ->
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
                    updateState { it.copy(isCommentsLoading = false) }
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
        updateState {
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
        val commentOwnerUserId = com.maodouchat.session.CurrentSession.ownerUserId()
        if (!com.maodouchat.session.CurrentSession.hasSession() || commentOwnerUserId.isBlank()) {
            updateState { it.copy(errorMessage = text(R.string.explore_login_required)) }
            return
        }
        val postId = state().selectedPostId ?: return
        val content = state().commentText.trim()
        if (content.isBlank() || state().isSendingComment) return
        val replyTarget = state().replyToComment
        updateState { it.copy(isSendingComment = true, errorMessage = null) }
        scope.launch {
            try {
                commentPostUseCase.sendComment(
                    ownerUserId = commentOwnerUserId,
                    postId = postId,
                    content = content,
                    replyToCommentId = replyTarget?.id
                ).fold(
                    onSuccess = { comment ->
                        updateState { state ->
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
                        updateState { state ->
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
                    updateState { state ->
                        if (state.selectedPostId == postId) state.copy(isSendingComment = false) else state
                    }
                }
                throw e
            }
        }
    }

    fun loadOlderComments() {
        val snapshot = state()
        val postId = snapshot.selectedPostId ?: return
        if (snapshot.isCommentsLoading || snapshot.isLoadingOlderComments || !snapshot.hasMoreComments) return
        val oldest = snapshot.comments.minWithOrNull(
            compareBy<PostCommentDto> { it.createdAt }.thenBy { it.id }
        ) ?: return
        val ownerUserId = com.maodouchat.session.CurrentSession.ownerUserId()
        val generation = commentsGeneration
        scope.launch {
            commentsLoadMutex.withLock {
                val state = state()
                if (commentsGeneration != generation || state.selectedPostId != postId || state.isLoadingOlderComments) return@withLock
                if (!com.maodouchat.session.CurrentSession.hasSession()) return@withLock
                updateState { it.copy(isLoadingOlderComments = true, errorMessage = null) }
                try {
                    commentPostUseCase.loadComments(
                        postId = postId,
                        limit = ExplorePaging.COMMENTS_PAGE_SIZE,
                        before = oldest.createdAt,
                        beforeId = oldest.id
                    ).fold(
                        onSuccess = { older ->
                            if (commentsGeneration == generation && com.maodouchat.session.CurrentSession.snapshot().userId == ownerUserId) {
                                updateState { current ->
                                    if (current.selectedPostId != postId) current else current.copy(
                                        comments = (older + current.comments).distinctBy { it.id },
                                        isLoadingOlderComments = false,
                                        hasMoreComments = older.size >= ExplorePaging.COMMENTS_PAGE_SIZE
                                    )
                                }
                            }
                        },
                        onFailure = { error ->
                            if (commentsGeneration == generation && state().selectedPostId == postId) {
                                updateState {
                                    it.copy(
                                        isLoadingOlderComments = false,
                                        errorMessage = error.message ?: text(R.string.explore_comments_load_failed)
                                    )
                                }
                            }
                        }
                    )
                } catch (e: CancellationException) {
                    if (commentsGeneration == generation && isCurrentOwner(ownerUserId) && state().selectedPostId == postId) {
                        updateState { it.copy(isLoadingOlderComments = false) }
                    }
                    throw e
                }
            }
        }
    }

    fun toggleCommentLike(comment: PostCommentDto) {
        val ownerUserId = com.maodouchat.session.CurrentSession.ownerUserId()
        val postId = state().selectedPostId ?: comment.postId
        if (!com.maodouchat.session.CurrentSession.hasSession() || ownerUserId.isBlank()) {
            updateState { it.copy(errorMessage = text(R.string.explore_login_required)) }
            return
        }
        scope.launch {
            toggleLikeUseCase.toggleCommentLike(
                ownerUserId = ownerUserId,
                postId = postId,
                comment = comment,
                onOptimisticUpdate = { optimisticComment ->
                    updateState { state ->
                        state.copy(comments = state.comments.map { if (it.id == comment.id) optimisticComment else it })
                    }
                }
            ).fold(
                onSuccess = { updated ->
                    updateState { state ->
                        state.copy(comments = state.comments.map { if (it.id == comment.id) updated else it })
                    }
                },
                onFailure = { error ->
                    updateState { it.copy(errorMessage = error.message ?: text(R.string.error_operation_failed)) }
                }
            )
        }
    }

    fun deleteComment(comment: PostCommentDto) {
        val ownerUserId = com.maodouchat.session.CurrentSession.ownerUserId()
        val postId = state().selectedPostId ?: comment.postId
        if (!com.maodouchat.session.CurrentSession.hasSession() || ownerUserId.isBlank()) {
            updateState { it.copy(errorMessage = text(R.string.explore_login_required)) }
            return
        }
        val originalIndex = state().comments.indexOfFirst { it.id == comment.id }
        updateState { state ->
            state.copy(
                comments = state.comments.filterNot { it.id == comment.id },
                posts = ExploreFeedPolicy.decrementCommentCount(state.posts, postId)
            )
        }
        scope.launch {
            commentPostUseCase.deleteComment(ownerUserId = ownerUserId, postId = postId, comment = comment, originalIndex = originalIndex).fold(
                onSuccess = {},
                onFailure = { error ->
                    updateState { state ->
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
        val ownerUserId = com.maodouchat.session.CurrentSession.ownerUserId()
        val postId = state().selectedPostId ?: comment.postId
        if (!com.maodouchat.session.CurrentSession.hasSession() || ownerUserId.isBlank()) {
            updateState { it.copy(errorMessage = text(R.string.explore_login_required)) }
            return
        }
        updateState { it.copy(isSavingCommentEdit = true) }
        scope.launch {
            commentPostUseCase.editComment(ownerUserId = ownerUserId, postId = postId, commentId = comment.id, newContent = newText).fold(
                onSuccess = { updated ->
                    updateState { state ->
                        state.copy(
                            isSavingCommentEdit = false,
                            comments = state.comments.map { if (it.id == comment.id) updated else it }
                        )
                    }
                },
                onFailure = { error ->
                    updateState {
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
        val ownerUserId = com.maodouchat.session.CurrentSession.ownerUserId()
        if (!com.maodouchat.session.CurrentSession.hasSession() || ownerUserId.isBlank()) {
            updateState { it.copy(errorMessage = text(R.string.explore_login_required)) }
            return
        }
        scope.launch {
            commentPostUseCase.reportComment(ownerUserId = ownerUserId, commentId = comment.id, reason = "INAPPROPRIATE").fold(
                onSuccess = {
                    updateState { it.copy(infoMessage = text(R.string.explore_report_sent)) }
                },
                onFailure = { error ->
                    updateState { it.copy(errorMessage = error.message ?: text(R.string.explore_report_failed)) }
                }
            )
        }
    }

    fun copyComment(comment: PostCommentDto) {
        val clipboard = application.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("Comment", comment.content))
        updateState { it.copy(infoMessage = text(R.string.explore_comment_copied)) }
    }

    fun onCommentTextChange(text: String) {
        updateState { it.copy(commentText = text) }
    }

    fun setReplyToComment(comment: PostCommentDto) {
        updateState { it.copy(replyToComment = comment) }
    }

    fun clearReplyToComment() {
        updateState { it.copy(replyToComment = null) }
    }
}
